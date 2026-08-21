package com.swiftbureau.screening;

import com.swiftbureau.domain.ScreeningStatus;
import com.swiftbureau.payment.PaymentMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.screening.provider", havingValue = "STUB", matchIfMissing = true)
public class StubScreeningClient implements ScreeningClient {

    private final WatchlistEntryRepository watchlist;

    public StubScreeningClient(WatchlistEntryRepository watchlist) {
        this.watchlist = watchlist;
    }

    @Override
    public ScreeningResult screen(PaymentMessage message) {
        return watchlist.findAll().stream()
                .filter(entry -> matches(message, entry.getNamePattern()))
                .findFirst()
                .map(entry -> new ScreeningResult(ScreeningStatus.HIT, "Watchlist hit: " + entry.getReason()))
                .orElseGet(() -> new ScreeningResult(ScreeningStatus.CLEAR, "Stub vendor: no hit"));
    }

    private boolean matches(PaymentMessage message, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String needle = pattern.toLowerCase();
        return contains(message.getDebtorName(), needle) || contains(message.getCreditorName(), needle);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }
}
