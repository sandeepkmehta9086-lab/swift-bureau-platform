package com.swiftbureau.mx;

import com.swiftbureau.domain.ChargeBearer;
import com.swiftbureau.domain.MessageDirection;
import com.swiftbureau.payment.PaymentMessage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MxMessageFactoryTest {

    @Test
    void pacs008ContainsUetrAndStructuredAddress() {
        PaymentMessage msg = sample();
        String xml = MxMessageFactory.pacs008(msg);
        assertTrue(xml.contains("<UETR>" + msg.getUetr() + "</UETR>"));
        assertTrue(xml.contains("<TwnNm>London</TwnNm>"));
        assertTrue(xml.contains("<Ctry>GB</Ctry>"));
        assertTrue(xml.contains("<TwnNm>New York</TwnNm>"));
        assertTrue(xml.contains("pacs.008.001.08"));
    }

    @Test
    void pain001CarriesTheUetrThePacs008WillUse() {
        PaymentMessage msg = sample();
        String xml = MxMessageFactory.pain001(msg);
        assertTrue(xml.contains("pain.001.001.09"));
        assertTrue(xml.contains("<UETR>" + msg.getUetr() + "</UETR>"));
        assertTrue(xml.contains("<DbtrAgt><FinInstnId><BICFI>MIDNGB2L</BICFI>"));
        assertTrue(xml.contains("<CdtrAgt><FinInstnId><BICFI>CHASUS33</BICFI>"));
        assertTrue(xml.contains("<InstdAmt Ccy=\"USD\">1000.00</InstdAmt>"));
    }

    @Test
    void pain002ReportsAcceptanceWithoutReasonAndRejectionWithReason() {
        PaymentMessage msg = sample();
        String accepted = MxMessageFactory.pain002(msg, "ACCP", null);
        assertTrue(accepted.contains("<GrpSts>ACCP</GrpSts>"));
        assertTrue(accepted.contains("<OrgnlUETR>" + msg.getUetr() + "</OrgnlUETR>"));
        assertTrue(!accepted.contains("StsRsnInf"));

        String rejected = MxMessageFactory.pain002(msg, "RJCT", "BE04");
        assertTrue(rejected.contains("<TxSts>RJCT</TxSts>"));
        assertTrue(rejected.contains("<StsRsnInf><Rsn><Cd>BE04</Cd></Rsn></StsRsnInf>"));
    }

    @Test
    void pacs004ReturnsTheOriginalAmountWithAgentsSwapped() {
        PaymentMessage msg = sample();
        String xml = MxMessageFactory.pacs004(msg, "FOCR");
        assertTrue(xml.contains("pacs.004.001.09"));
        assertTrue(xml.contains("<OrgnlUETR>" + msg.getUetr() + "</OrgnlUETR>"));
        assertTrue(xml.contains("<OrgnlMsgId>" + msg.getId() + "</OrgnlMsgId>"));
        assertTrue(xml.contains("<RtrdIntrBkSttlmAmt Ccy=\"USD\">1000.00</RtrdIntrBkSttlmAmt>"));
        assertTrue(xml.contains("<InstgAgt><FinInstnId><BICFI>CHASUS33</BICFI>"));
        assertTrue(xml.contains("<InstdAgt><FinInstnId><BICFI>MIDNGB2L</BICFI>"));
        assertTrue(xml.contains("<RtrRsnInf><Rsn><Cd>FOCR</Cd></Rsn></RtrRsnInf>"));
    }

    @Test
    void camt056ReferencesOriginalMessageUetrAmountAndReason() {
        PaymentMessage msg = sample();
        msg.setMessageType("pacs.008");
        String xml = MxMessageFactory.camt056(msg, "DUPL");
        assertTrue(xml.contains("<OrgnlMsgId>" + msg.getId() + "</OrgnlMsgId>"));
        assertTrue(xml.contains("<OrgnlMsgNmId>pacs.008</OrgnlMsgNmId>"));
        assertTrue(xml.contains("<OrgnlUETR>" + msg.getUetr() + "</OrgnlUETR>"));
        assertTrue(xml.contains("<OrgnlIntrBkSttlmAmt Ccy=\"USD\">1000.00</OrgnlIntrBkSttlmAmt>"));
        assertTrue(xml.contains("<Cd>DUPL</Cd>"));
        assertTrue(MxMessageFactory.camt056(msg).contains("<Cd>CUST</Cd>"));
    }

    @Test
    void rejectsMissingTown() {
        PaymentMessage msg = sample();
        msg.setDebtorTown("");
        assertThrows(com.swiftbureau.common.ApiException.class, () -> MxMessageFactory.validateStructuredAddress(msg));
    }

    private PaymentMessage sample() {
        PaymentMessage msg = new PaymentMessage();
        msg.setId(UUID.randomUUID());
        msg.setUetr(MxMessageFactory.newUetr());
        msg.setDirection(MessageDirection.OUT);
        msg.setInstructingAgentBic("MIDNGB2L");
        msg.setInstructedAgentBic("CHASUS33");
        msg.setAmount(new BigDecimal("1000.00"));
        msg.setCurrency("USD");
        msg.setChargeBearer(ChargeBearer.SHAR);
        msg.setDebtorName("Acme Ltd");
        msg.setDebtorStreet("1 Threadneedle");
        msg.setDebtorTown("London");
        msg.setDebtorCountry("GB");
        msg.setCreditorName("Widget LLC");
        msg.setCreditorStreet("200 West");
        msg.setCreditorTown("New York");
        msg.setCreditorCountry("US");
        return msg;
    }
}
