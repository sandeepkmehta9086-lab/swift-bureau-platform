package com.swiftbureau.charge;

import com.swiftbureau.domain.ChargeBearer;
import com.swiftbureau.ledger.LedgerEntry;
import com.swiftbureau.ledger.LedgerEntryRepository;
import com.swiftbureau.payment.PaymentMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ChargeService {

    private final ChargeScheduleRepository schedules;
    private final LedgerEntryRepository ledger;

    public ChargeService(ChargeScheduleRepository schedules, LedgerEntryRepository ledger) {
        this.schedules = schedules;
        this.ledger = ledger;
    }

    public List<ChargeSchedule> list(UUID tenantId) {
        return schedules.findByTenantId(tenantId);
    }

    @Transactional
    public ChargeSchedule create(UUID tenantId, String fromBic, String toBic, String currency, BigDecimal fee, ChargeBearer bearer) {
        ChargeSchedule schedule = new ChargeSchedule();
        schedule.setTenantId(tenantId);
        schedule.setFromBic(fromBic);
        schedule.setToBic(toBic);
        schedule.setCurrency(currency.toUpperCase());
        schedule.setFeeAmount(fee);
        schedule.setDefaultBearer(bearer == null ? ChargeBearer.SHAR : bearer);
        return schedules.save(schedule);
    }

    public Map<String, Object> quote(UUID tenantId, String fromBic, String toBic, String currency, ChargeBearer bearer, BigDecimal amount) {
        ChargeSchedule match = schedules.findByTenantId(tenantId).stream()
                .filter(s -> s.getCurrency().equalsIgnoreCase(currency))
                .filter(s -> s.getToBic() == null || s.getToBic().isBlank() || s.getToBic().equalsIgnoreCase(toBic))
                .filter(s -> s.getFromBic() == null || s.getFromBic().isBlank() || s.getFromBic().equalsIgnoreCase(fromBic))
                .max(Comparator.comparingInt(s -> score(s, fromBic, toBic)))
                .orElse(null);
        BigDecimal fee = match == null ? BigDecimal.ZERO : match.getFeeAmount();
        ChargeBearer used = bearer != null ? bearer : (match == null ? ChargeBearer.SHAR : match.getDefaultBearer());
        BigDecimal instructed = amount;
        if (used == ChargeBearer.CRED) {
            instructed = amount.subtract(fee).max(BigDecimal.ZERO);
        }
        return Map.of(
                "fee", fee,
                "bearer", used.name(),
                "instructedAmount", instructed,
                "legacyCode", used == ChargeBearer.DEBT ? "OUR" : used == ChargeBearer.CRED ? "BEN" : "SHA"
        );
    }

    @Transactional
    public void applyFee(PaymentMessage msg) {
        Map<String, Object> quote = quote(msg.getTenantId(), msg.getInstructingAgentBic(), msg.getInstructedAgentBic(),
                msg.getCurrency(), msg.getChargeBearer(), msg.getAmount());
        BigDecimal fee = (BigDecimal) quote.get("fee");
        if (fee.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        LedgerEntry entry = new LedgerEntry();
        entry.setTenantId(msg.getTenantId());
        entry.setAccountId(msg.getId());
        entry.setDebit(msg.getChargeBearer() == ChargeBearer.CRED ? BigDecimal.ZERO : fee);
        entry.setCredit(BigDecimal.ZERO);
        entry.setCurrency(msg.getCurrency());
        entry.setValueDate(LocalDate.now());
        entry.setPaymentMessageId(msg.getId());
        entry.setUetr(msg.getUetr());
        entry.setNarrative("Correspondent fee " + quote.get("legacyCode"));
        ledger.save(entry);
    }

    private int score(ChargeSchedule schedule, String fromBic, String toBic) {
        int score = 0;
        if (fromBic != null && fromBic.equalsIgnoreCase(schedule.getFromBic())) {
            score += 2;
        }
        if (toBic != null && toBic.equalsIgnoreCase(schedule.getToBic())) {
            score += 2;
        }
        return score;
    }
}
