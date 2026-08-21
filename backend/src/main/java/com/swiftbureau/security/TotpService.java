package com.swiftbureau.security;

import com.swiftbureau.common.ApiException;
import com.swiftbureau.config.AppProperties;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.springframework.stereotype.Service;

@Service
public class TotpService {

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeVerifier verifier;
    private final AppProperties properties;

    public TotpService(AppProperties properties) {
        this.properties = properties;
        CodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        this.verifier = new DefaultCodeVerifier(generator, new SystemTimeProvider());
    }

    public String newSecret() {
        return secretGenerator.generate();
    }

    public String otpauth(String loginId, String secret) {
        return "otpauth://totp/SWIFT-Bureau:" + loginId + "?secret=" + secret + "&issuer=SWIFT-Bureau&digits=6&period=30";
    }

    public void verifyOrThrow(String secret, String code) {
        if (secret == null || code == null || !verifier.isValidCode(secret, code.replace(" ", ""))) {
            throw new ApiException(401, "MFA_INVALID", "Invalid authenticator code");
        }
    }

    public String demoSecret() {
        return properties.getSecurity().getDemoTotpSecret();
    }
}
