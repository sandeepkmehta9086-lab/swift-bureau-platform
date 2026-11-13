package com.swiftbureau.swift;

import java.util.List;

public record SwiftSendResult(String networkReference, List<InboundMx> inbound) {

    /**
     * An MX received back from the network. {@code statusCode} is the ISO code the message carries
     * (pacs.002 TxSts, camt.029 Conf, pacs.004 return reason, camt.054 CdtDbtInd); null when not applicable.
     */
    public record InboundMx(String messageType, String xml, String statusCode) {

        public InboundMx(String messageType, String xml) {
            this(messageType, xml, null);
        }
    }
}
