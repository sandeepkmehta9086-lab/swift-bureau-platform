package com.swiftbureau.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtil {

    private SecurityUtil() {
    }

    public static CurrentUser current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CurrentUser user)) {
            throw new com.swiftbureau.common.ApiException(401, "UNAUTHENTICATED", "Login required");
        }
        return user;
    }
}
