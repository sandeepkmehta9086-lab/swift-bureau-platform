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
