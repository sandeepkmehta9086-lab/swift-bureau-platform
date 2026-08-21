package com.swiftbureau.identity;

import com.swiftbureau.audit.AuditService;
import com.swiftbureau.common.ApiException;
import com.swiftbureau.config.AppProperties;
import com.swiftbureau.domain.Role;
import com.swiftbureau.domain.UserStatus;
import com.swiftbureau.security.CurrentUser;
import com.swiftbureau.security.JwtService;
import com.swiftbureau.security.PasswordPolicy;
import com.swiftbureau.security.TotpService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;
    private final TotpService totpService;
    private final AuditService auditService;
    private final AppProperties properties;
    private final SecureRandom random = new SecureRandom();

    public AuthService(
            UserAccountRepository users,
            PasswordEncoder encoder,
            JwtService jwtService,
            TotpService totpService,
            AuditService auditService,
            AppProperties properties
    ) {
        this.users = users;
        this.encoder = encoder;
        this.jwtService = jwtService;
        this.totpService = totpService;
        this.auditService = auditService;
        this.properties = properties;
    }

    @Transactional
    public Map<String, Object> login(String loginId, String password, String totp) {
        UserAccount user = users.findByLoginIdIgnoreCase(loginId)
                .orElseThrow(() -> new ApiException(401, "BAD_CREDENTIALS", "Invalid login"));
        if (user.getStatus() == UserStatus.LOCKED) {
            throw new ApiException(403, "LOCKED", "Account locked");
        }
        if (user.getStatus() == UserStatus.INVITED || user.getPasswordHash() == null) {
            throw new ApiException(403, "INVITE_PENDING", "Accept the invitation before logging in");
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            user.setFailedLogins(user.getFailedLogins() + 1);
            if (user.getFailedLogins() >= properties.getSecurity().getLockoutThreshold()) {
                user.setStatus(UserStatus.LOCKED);
            }
            throw new ApiException(401, "BAD_CREDENTIALS", "Invalid login");
        }
        user.setFailedLogins(0);
        if (!user.isMfaEnabled()) {
            String enroll = jwtService.issue(toCurrent(user));
            return Map.of(
                    "status", "MFA_ENROLLMENT_REQUIRED",
                    "enrollmentToken", enroll,
                    "loginId", user.getLoginId()
            );
        }
        if (totp == null || totp.isBlank()) {
            throw new ApiException(401, "MFA_REQUIRED", "Authenticator code required");
        }
        totpService.verifyOrThrow(user.getMfaSecret(), totp);
        user.setLastLoginAt(Instant.now());
        CurrentUser current = toCurrent(user);
        auditService.record(current, "LOGIN", "UserAccount", user.getId().toString(), user.getLoginId());
        return Map.of("status", "OK", "accessToken", jwtService.issue(current), "user", publicUser(user));
    }

    @Transactional
    public Map<String, Object> startEnroll(CurrentUser actor) {
        UserAccount user = users.findById(actor.id()).orElseThrow();
        if (user.isMfaEnabled()) {
            throw new ApiException(400, "MFA_ALREADY", "MFA is already enrolled");
        }
        String secret = totpService.newSecret();
        user.setMfaSecret(secret);
        return Map.of(
                "secret", secret,
                "otpauth", totpService.otpauth(user.getLoginId(), secret)
        );
    }

    @Transactional
    public Map<String, Object> confirmEnroll(CurrentUser actor, String code) {
        UserAccount user = users.findById(actor.id()).orElseThrow();
        if (user.getMfaSecret() == null) {
            throw new ApiException(400, "MFA_NOT_STARTED", "Start MFA enrollment first");
        }
        totpService.verifyOrThrow(user.getMfaSecret(), code);
        user.setMfaEnabled(true);
        CurrentUser current = toCurrent(user);
        auditService.record(current, "MFA_ENROLLED", "UserAccount", user.getId().toString(), null);
        return Map.of("status", "OK", "accessToken", jwtService.issue(current), "user", publicUser(user));
    }

    @Transactional
    public Map<String, Object> acceptInvite(String token, String password) {
        String hash = hashToken(token);
        UserAccount user = users.findByInviteTokenHash(hash)
                .orElseThrow(() -> new ApiException(400, "INVITE_INVALID", "Invitation is invalid"));
        if (user.getInviteExpiresAt() != null && user.getInviteExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(400, "INVITE_EXPIRED", "Invitation expired");
        }
        PasswordPolicy.validate(password);
        user.setPasswordHash(encoder.encode(password));
        user.setStatus(UserStatus.ACTIVE);
        user.setInviteTokenHash(null);
        CurrentUser current = toCurrent(user);
        return Map.of(
                "status", "MFA_ENROLLMENT_REQUIRED",
                "enrollmentToken", jwtService.issue(current),
                "user", publicUser(user)
        );
    }

    @Transactional
    public Map<String, Object> invite(CurrentUser actor, UUID tenantId, String loginId, String email, Role role) {
        if (role == Role.BUREAU_OPERATOR && actor.role() != Role.BUREAU_OPERATOR) {
            throw new ApiException(403, "FORBIDDEN", "Only bureau operators can create bureau operators");
        }
        if (role != Role.BUREAU_OPERATOR && actor.role() != Role.BUREAU_OPERATOR && actor.role() != Role.BANK_SECURITY_OFFICER) {
            throw new ApiException(403, "FORBIDDEN", "Only a bank security officer can invite bank users");
        }
        if (role == Role.BUREAU_OPERATOR) {
            tenantId = null;
        } else if (actor.role() == Role.BANK_SECURITY_OFFICER) {
            tenantId = actor.tenantId();
        }
        if (role != Role.BUREAU_OPERATOR && tenantId == null) {
            throw new ApiException(400, "TENANT_REQUIRED", "Bank users require a tenant");
        }
        if (users.findByLoginIdIgnoreCase(loginId).isPresent()) {
            throw new ApiException(409, "LOGIN_TAKEN", "Login ID already exists");
        }
        UserAccount user = new UserAccount();
        user.setTenantId(tenantId);
        user.setLoginId(loginId.trim());
        user.setEmail(email.trim());
        user.setRole(role);
        user.setStatus(UserStatus.INVITED);
        String token = randomToken();
        user.setInviteTokenHash(hashToken(token));
        user.setInviteExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        users.save(user);
        auditService.record(actor, "USER_INVITED", "UserAccount", user.getId().toString(), loginId + ":" + role);
        return Map.of(
                "id", user.getId(),
                "loginId", user.getLoginId(),
                "inviteToken", token,
                "expiresAt", user.getInviteExpiresAt().toString()
        );
    }

    public List<UserAccount> list(CurrentUser actor) {
        if (actor.bureau()) {
            return users.findAll();
        }
        return users.findByTenantId(actor.requireTenantId());
    }

    public void verifyStepUp(CurrentUser actor, String totp) {
        UserAccount user = users.findById(actor.id()).orElseThrow();
        totpService.verifyOrThrow(user.getMfaSecret(), totp);
    }

    public static CurrentUser toCurrent(UserAccount user) {
        return new CurrentUser(user.getId(), user.getLoginId(), user.getRole(), user.getTenantId(), user.isMfaEnabled());
    }

    public static Map<String, Object> publicUser(UserAccount user) {
        return Map.of(
                "id", user.getId(),
                "loginId", user.getLoginId(),
                "email", user.getEmail(),
                "role", user.getRole().name(),
                "tenantId", user.getTenantId() == null ? "" : user.getTenantId().toString(),
                "mfaEnabled", user.isMfaEnabled(),
                "status", user.getStatus().name()
        );
    }

    private String randomToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
