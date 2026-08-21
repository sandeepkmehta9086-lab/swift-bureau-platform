package com.swiftbureau.reporting;

import com.swiftbureau.domain.MessageStatus;
import com.swiftbureau.payment.PaymentMessageRepository;
import com.swiftbureau.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ReportingService {

    private final PaymentMessageRepository payments;

    public ReportingService(PaymentMessageRepository payments) {
        this.payments = payments;
    }

    public Map<String, Object> stp(CurrentUser actor) {
        var tenantId = actor.requireTenantId();
        long total = payments.countByTenantId(tenantId);
        long acked = payments.countByTenantIdAndStatus(tenantId, MessageStatus.ACKED);
        long hits = payments.countByTenantIdAndStatus(tenantId, MessageStatus.SCREENING_HIT);
        long pending = payments.countByTenantIdAndStatus(tenantId, MessageStatus.PENDING_CHECK);
        double stpRate = total == 0 ? 0 : (acked * 100.0 / total);
        return Map.of(
                "total", total,
                "acked", acked,
                "screeningHits", hits,
                "pendingCheck", pending,
                "stpRatePercent", Math.round(stpRate * 100.0) / 100.0
        );
    }
}
