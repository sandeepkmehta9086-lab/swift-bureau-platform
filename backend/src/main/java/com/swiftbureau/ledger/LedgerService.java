package com.swiftbureau.ledger;

import com.swiftbureau.correspondent.CorrespondentAccount;
import com.swiftbureau.correspondent.CorrespondentAccountRepository;
import com.swiftbureau.domain.AccountType;
import com.swiftbureau.payment.PaymentMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class LedgerService {

    private final LedgerEntryRepository entries;
    private final CorrespondentAccountRepository accounts;

    public LedgerService(LedgerEntryRepository entries, CorrespondentAccountRepository accounts) {
        this.entries = entries;
        this.accounts = accounts;
    }

    @Transactional
    public void postOutbound(PaymentMessage msg) {
        CorrespondentAccount nostro = nostroFor(msg);
        if (nostro == null) {
            return;
        }
        LedgerEntry debit = new LedgerEntry();
        debit.setTenantId(msg.getTenantId());
        debit.setAccountId(nostro.getId());
        debit.setDebit(msg.getAmount());
        debit.setCredit(BigDecimal.ZERO);
        debit.setCurrency(msg.getCurrency());
        debit.setValueDate(LocalDate.now());
        debit.setPaymentMessageId(msg.getId());
        debit.setUetr(msg.getUetr());
        debit.setNarrative("Outbound " + msg.getMessageType());
        entries.save(debit);
    }

    /** Reverses the outbound debit when the correspondent returns the funds with a pacs.004. */
    @Transactional
    public void postReturn(PaymentMessage original, PaymentMessage paymentReturn) {
        CorrespondentAccount nostro = nostroFor(original);
        if (nostro == null) {
            return;
        }
        LedgerEntry credit = new LedgerEntry();
        credit.setTenantId(original.getTenantId());
        credit.setAccountId(nostro.getId());
        credit.setDebit(BigDecimal.ZERO);
        credit.setCredit(original.getAmount());
        credit.setCurrency(original.getCurrency());
        credit.setValueDate(LocalDate.now());
        credit.setPaymentMessageId(paymentReturn.getId());
        credit.setUetr(original.getUetr());
        credit.setNarrative("Return " + paymentReturn.getMessageType() + " of " + original.getMessageType());
        entries.save(credit);
    }

    private CorrespondentAccount nostroFor(PaymentMessage msg) {
        return accounts.findFirstByTenantIdAndTypeAndCurrencyAndCorrespondentBic(
                        msg.getTenantId(), AccountType.NOSTRO, msg.getCurrency(), msg.getInstructedAgentBic())
                .or(() -> accounts.findByTenantId(msg.getTenantId()).stream()
                        .filter(a -> a.getType() == AccountType.NOSTRO && a.getCurrency().equals(msg.getCurrency()))
                        .findFirst())
                .orElse(null);
    }

    public BigDecimal bookedBalance(UUID tenantId, UUID accountId) {
        return entries.findByTenantIdAndAccountId(tenantId, accountId).stream()
                .map(e -> e.getCredit().subtract(e.getDebit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<LedgerEntry> forTenant(UUID tenantId) {
        return entries.findByTenantId(tenantId);
    }
}
