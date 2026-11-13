package com.swiftbureau.swift;

import com.swiftbureau.common.ApiException;
import com.swiftbureau.payment.PaymentMessage;

public interface SwiftNetworkGateway {

    SwiftSendResult send(PaymentMessage message);

    /**
     * Sends a camt.056 cancellation request for {@code original} and returns what the network answered:
     * a camt.029 resolution, and on acceptance a pacs.004 return plus its camt.054 credit notification.
     */
    default SwiftSendResult recall(PaymentMessage original, PaymentMessage recallRequest) {
        throw new ApiException(501, "RECALL_UNSUPPORTED", "This SWIFT adapter does not support camt.056 recalls yet");
    }
}
