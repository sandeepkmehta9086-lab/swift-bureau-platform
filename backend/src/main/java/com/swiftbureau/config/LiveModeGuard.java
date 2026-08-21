package com.swiftbureau.config;

import com.swiftbureau.common.ApiException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class LiveModeGuard implements ApplicationRunner {

    private final AppProperties properties;

    public LiveModeGuard(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getSwift().isLiveEnabled()) {
            return;
        }
        if (properties.getSwift().stub()) {
            throw new ApiException(500, "LIVE_GUARD", "Live SWIFT cannot run with app.swift.mode=STUB");
        }
        if (properties.getScreening().stub()) {
            throw new ApiException(500, "LIVE_GUARD", "Live SWIFT cannot run with stub screening");
        }
    }
}
