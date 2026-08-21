package com.swiftbureau.swift;

import com.swiftbureau.payment.PaymentMessage;

import java.util.List;

public interface SwiftNetworkGateway {

    SwiftSendResult send(PaymentMessage message);
}
