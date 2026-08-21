package com.swiftbureau.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByLoginIdIgnoreCase(String loginId);

    Optional<UserAccount> findByInviteTokenHash(String inviteTokenHash);

    List<UserAccount> findByTenantId(UUID tenantId);

    long countByRole(com.swiftbureau.domain.Role role);
}
