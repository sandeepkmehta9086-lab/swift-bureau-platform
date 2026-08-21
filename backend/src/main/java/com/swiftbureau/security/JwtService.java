package com.swiftbureau.security;

import com.swiftbureau.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final AppProperties properties;

    public JwtService(AppProperties properties) {
        this.properties = properties;
    }

    public String issue(CurrentUser user) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(properties.getJwt().getTtlMinutes() * 60);
        return Jwts.builder()
                .subject(user.id().toString())
                .claim("loginId", user.loginId())
                .claim("role", user.role().name())
                .claim("tenantId", user.tenantId() == null ? "" : user.tenantId().toString())
                .claim("mfa", user.mfaEnabled())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key())
                .compact();
    }

    public CurrentUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String tenant = claims.get("tenantId", String.class);
        UUID tenantId = (tenant == null || tenant.isBlank()) ? null : UUID.fromString(tenant);
        return new CurrentUser(
                UUID.fromString(claims.getSubject()),
                claims.get("loginId", String.class),
                com.swiftbureau.domain.Role.valueOf(claims.get("role", String.class)),
                tenantId,
                Boolean.TRUE.equals(claims.get("mfa", Boolean.class))
        );
    }

    private SecretKey key() {
        byte[] bytes = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            bytes = java.util.Arrays.copyOf(bytes, 32);
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
