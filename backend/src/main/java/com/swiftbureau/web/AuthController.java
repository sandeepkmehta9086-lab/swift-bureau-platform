package com.swiftbureau.web;

import com.swiftbureau.domain.Role;
import com.swiftbureau.identity.AuthService;
import com.swiftbureau.identity.UserAccount;
import com.swiftbureau.security.CurrentUser;
import com.swiftbureau.security.SecurityUtil;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        return authService.login(request.loginId(), request.password(), request.totp());
    }

    @PostMapping("/auth/accept-invite")
    public Map<String, Object> accept(@RequestBody AcceptInviteRequest request) {
        return authService.acceptInvite(request.token(), request.password());
    }

    @PostMapping("/auth/mfa/enroll")
    public Map<String, Object> enroll() {
        return authService.startEnroll(SecurityUtil.current());
    }

    @PostMapping("/auth/mfa/confirm")
    public Map<String, Object> confirm(@RequestBody TotpRequest request) {
        return authService.confirmEnroll(SecurityUtil.current(), request.code());
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me() {
        CurrentUser user = SecurityUtil.current();
        return Map.of(
                "id", user.id(),
                "loginId", user.loginId(),
                "role", user.role().name(),
                "tenantId", user.tenantId() == null ? "" : user.tenantId().toString(),
                "mfaEnabled", user.mfaEnabled()
        );
    }

    @PostMapping("/users/invite")
    public Map<String, Object> invite(@RequestBody InviteRequest request) {
        return authService.invite(SecurityUtil.current(), request.tenantId(), request.loginId(), request.email(), request.role());
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        return authService.list(SecurityUtil.current()).stream().map(AuthService::publicUser).toList();
    }

    public record LoginRequest(@NotBlank String loginId, @NotBlank String password, String totp) {
    }

    public record AcceptInviteRequest(@NotBlank String token, @NotBlank String password) {
    }

    public record TotpRequest(@NotBlank String code) {
    }

    public record InviteRequest(UUID tenantId, @NotBlank String loginId, @Email String email, Role role) {
    }
}
