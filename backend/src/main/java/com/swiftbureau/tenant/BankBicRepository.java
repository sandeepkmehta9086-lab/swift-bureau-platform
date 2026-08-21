package com.swiftbureau.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankBicRepository extends JpaRepository<BankBic, UUID> {
    List<BankBic> findByTenantId(UUID tenantId);

    Optional<BankBic> findFirstByTenantIdAndPrimaryBicTrue(UUID tenantId);

    Optional<BankBic> findByBic(String bic);
}
