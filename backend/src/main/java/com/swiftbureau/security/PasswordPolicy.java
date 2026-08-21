package com.swiftbureau.security;

import com.swiftbureau.common.ApiException;

public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < 12) {
            throw new ApiException(400, "PASSWORD_POLICY", "Password must be at least 12 characters");
        }
        if (!password.chars().anyMatch(Character::isUpperCase)
                || !password.chars().anyMatch(Character::isLowerCase)
                || !password.chars().anyMatch(Character::isDigit)
                || password.chars().allMatch(Character::isLetterOrDigit)) {
            throw new ApiException(400, "PASSWORD_POLICY",
                    "Password must include upper, lower, digit, and special character");
        }
    }
}
