package com.swiftbureau.swift;

import java.util.List;

public record SwiftSendResult(String networkReference, List<InboundMx> inbound) {

    public record InboundMx(String messageType, String xml) {
    }
}
