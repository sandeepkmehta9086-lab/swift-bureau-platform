package com.swiftbureau.correspondent;

import com.swiftbureau.domain.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CorrespondentAccountRepository extends JpaRepository<CorrespondentAccount, UUID> {
    List<CorrespondentAccount> findByTenantId(UUID tenantId);

    Optional<CorrespondentAccount> findFirstByTenantIdAndTypeAndCurrencyAndCorrespondentBic(
            UUID tenantId, AccountType type, String currency, String correspondentBic);

    Optional<CorrespondentAccount> findByIdAndTenantId(UUID id, UUID tenantId);
}
