package com.swiftbureau.screening;

import com.swiftbureau.domain.ScreeningStatus;
import com.swiftbureau.payment.PaymentMessage;

public interface ScreeningClient {
    ScreeningResult screen(PaymentMessage message);

    record ScreeningResult(ScreeningStatus status, String detail) {
    }
}
