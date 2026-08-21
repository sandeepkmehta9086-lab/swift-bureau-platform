package com.swiftbureau.swift;

import com.swiftbureau.common.ApiException;
import com.swiftbureau.config.AppProperties;
import com.swiftbureau.payment.PaymentMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Secure-zone swap point for Alliance Cloud Messaging API.
 * Refuses to send unless live mode is explicitly enabled and a real base URL is configured.
 * Does not call SWIFT from developer defaults.
 */
@Component
@ConditionalOnProperty(name = "app.swift.mode", havingValue = "ALLIANCE_CLOUD")
public class AllianceCloudGateway implements SwiftNetworkGateway {

    private final AppProperties properties;

    public AllianceCloudGateway(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public SwiftSendResult send(PaymentMessage message) {
        if (!properties.getSwift().isLiveEnabled()) {
            throw new ApiException(503, "SWIFT_NOT_LIVE",
                    "Alliance Cloud adapter is selected but live sending is disabled (test BIC / SIP gate)");
        }
        String base = properties.getSwift().getAllianceBaseUrl();
        if (base == null || base.isBlank() || base.contains("example.swift")) {
            throw new ApiException(503, "ALLIANCE_NOT_CONFIGURED",
                    "Alliance Cloud base URL and mTLS material are not configured. See docs/phase-0/alliance-cloud-checklist.md");
        }
        RestClient client = RestClient.builder().baseUrl(base).build();
        client.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_XML)
                .body(message.getMxXml())
                .retrieve()
                .toBodilessEntity();
        return new SwiftSendResult("ALLIANCE-" + message.getUetr(), java.util.List.of());
    }
}
