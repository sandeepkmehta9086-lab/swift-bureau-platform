package com.swiftbureau.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Security security = new Security();
    private final Swift swift = new Swift();
    private final Screening screening = new Screening();
    private final Csp csp = new Csp();
    private final Seed seed = new Seed();

    public Jwt getJwt() {
        return jwt;
    }

    public Security getSecurity() {
        return security;
    }

    public Swift getSwift() {
        return swift;
    }

    public Screening getScreening() {
        return screening;
    }

    public Csp getCsp() {
        return csp;
    }

    public Seed getSeed() {
        return seed;
    }

    public static class Jwt {
        private String secret = "change-me";
        private long ttlMinutes = 30;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public long getTtlMinutes() {
            return ttlMinutes;
        }

        public void setTtlMinutes(long ttlMinutes) {
            this.ttlMinutes = ttlMinutes;
        }
    }

    public static class Security {
        private int lockoutThreshold = 5;
        private String demoTotpSecret = "JBSWY3DPEHPK3PXP";

        public int getLockoutThreshold() {
            return lockoutThreshold;
        }

        public void setLockoutThreshold(int lockoutThreshold) {
            this.lockoutThreshold = lockoutThreshold;
        }

        public String getDemoTotpSecret() {
            return demoTotpSecret;
        }

        public void setDemoTotpSecret(String demoTotpSecret) {
            this.demoTotpSecret = demoTotpSecret;
        }
    }

    public static class Swift {
        private String mode = "STUB";
        private boolean liveEnabled = false;
        private String allianceBaseUrl = "";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public boolean isLiveEnabled() {
            return liveEnabled;
        }

        public void setLiveEnabled(boolean liveEnabled) {
            this.liveEnabled = liveEnabled;
        }

        public String getAllianceBaseUrl() {
            return allianceBaseUrl;
        }

        public void setAllianceBaseUrl(String allianceBaseUrl) {
            this.allianceBaseUrl = allianceBaseUrl;
        }

        public boolean stub() {
            return !"ALLIANCE_CLOUD".equalsIgnoreCase(mode);
        }
    }

    public static class Screening {
        private String provider = "STUB";
        private boolean failClosed = true;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public boolean isFailClosed() {
            return failClosed;
        }

        public void setFailClosed(boolean failClosed) {
            this.failClosed = failClosed;
        }

        public boolean stub() {
            return !"VENDOR".equalsIgnoreCase(provider);
        }
    }

    public static class Csp {
        private String controlsPath = "classpath:csp/cscf-v2026-controls.json";

        public String getControlsPath() {
            return controlsPath;
        }

        public void setControlsPath(String controlsPath) {
            this.controlsPath = controlsPath;
        }
    }

    public static class Seed {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
