package com.swiftbureau.swift;

import com.swiftbureau.mx.MxMessageFactory;
import com.swiftbureau.payment.PaymentMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.swift.mode", havingValue = "STUB", matchIfMissing = true)
public class StubSwiftNetworkGateway implements SwiftNetworkGateway {

    /**
     * Cancellation reasons the simulated correspondent honours with a pacs.004 return.
     * Anything else (including the default CUST "customer request") is refused with camt.029 RJCR,
     * which mirrors a beneficiary bank that will not claw back funds already credited.
     */
    public static final Set<String> RETURNABLE_REASONS = Set.of("DUPL", "FRAD", "TECH", "AM09", "AC03");

    /** pacs.004 return reason used when a recall is honoured: "following cancellation request". */
    public static final String RETURN_REASON = "FOCR";

    @Override
    public SwiftSendResult send(PaymentMessage message) {
        String ack = MxMessageFactory.pacs002(message, "ACSC");
        String notify = MxMessageFactory.camt054(message, "DBIT");
        return new SwiftSendResult(
                "STUB-" + UUID.randomUUID(),
                List.of(
                        new SwiftSendResult.InboundMx("pacs.002", ack, "ACSC"),
                        new SwiftSendResult.InboundMx("camt.054", notify, "DBIT")
                )
        );
    }

    @Override
    public SwiftSendResult recall(PaymentMessage original, PaymentMessage recallRequest) {
        String reason = recallRequest.getReasonCode();
        boolean accepted = reason != null && RETURNABLE_REASONS.contains(reason);
        String reference = "STUB-RECALL-" + UUID.randomUUID();
        if (!accepted) {
            return new SwiftSendResult(reference, List.of(
                    new SwiftSendResult.InboundMx("camt.029", MxMessageFactory.camt029(original, "RJCR"), "RJCR")
            ));
        }
        return new SwiftSendResult(reference, List.of(
                new SwiftSendResult.InboundMx("camt.029", MxMessageFactory.camt029(original, "CNCL"), "CNCL"),
                new SwiftSendResult.InboundMx("pacs.004", MxMessageFactory.pacs004(original, RETURN_REASON), RETURN_REASON),
                new SwiftSendResult.InboundMx("camt.054", MxMessageFactory.camt054(original, "CRDT"), "CRDT")
        ));
    }
}
