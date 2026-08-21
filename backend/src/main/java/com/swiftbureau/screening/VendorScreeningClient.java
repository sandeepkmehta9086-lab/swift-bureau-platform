package com.swiftbureau.screening;

import com.swiftbureau.common.ApiException;
import com.swiftbureau.domain.ScreeningStatus;
import com.swiftbureau.payment.PaymentMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production screening must be a contracted vendor. This class is the integration seam.
 */
@Component
@ConditionalOnProperty(name = "app.screening.provider", havingValue = "VENDOR")
public class VendorScreeningClient implements ScreeningClient {

    @Override
    public ScreeningResult screen(PaymentMessage message) {
        throw new ApiException(503, "SCREENING_VENDOR",
                "Vendor screening endpoint is not configured. See docs/phase-0/screening-vendor.md");
    }
}
