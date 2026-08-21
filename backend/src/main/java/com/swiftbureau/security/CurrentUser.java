package com.swiftbureau.security;

import com.swiftbureau.domain.Role;

import java.util.UUID;

public record CurrentUser(
        UUID id,
        String loginId,
        Role role,
        UUID tenantId,
        boolean mfaEnabled
) {
    public boolean bureau() {
        return role == Role.BUREAU_OPERATOR;
    }

    public void requireTenant() {
        if (tenantId == null && !bureau()) {
            throw new com.swiftbureau.common.ApiException(403, "NO_TENANT", "User is not assigned to a bank tenant");
        }
    }

    public UUID requireTenantId() {
        requireTenant();
        if (tenantId == null) {
            throw new com.swiftbureau.common.ApiException(400, "TENANT_REQUIRED", "Bureau operator must pass tenant context");
        }
        return tenantId;
    }
}
