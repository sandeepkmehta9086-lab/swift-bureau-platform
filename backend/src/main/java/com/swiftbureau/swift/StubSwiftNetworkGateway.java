package com.swiftbureau.swift;

import com.swiftbureau.mx.MxMessageFactory;
import com.swiftbureau.payment.PaymentMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.swift.mode", havingValue = "STUB", matchIfMissing = true)
public class StubSwiftNetworkGateway implements SwiftNetworkGateway {

    @Override
    public SwiftSendResult send(PaymentMessage message) {
        String ack = MxMessageFactory.pacs002(message, "ACSC");
        String notify = MxMessageFactory.camt054(message, "DBIT");
        return new SwiftSendResult(
                "STUB-" + UUID.randomUUID(),
                List.of(
                        new SwiftSendResult.InboundMx("pacs.002", ack),
                        new SwiftSendResult.InboundMx("camt.054", notify)
                )
        );
    }
}
