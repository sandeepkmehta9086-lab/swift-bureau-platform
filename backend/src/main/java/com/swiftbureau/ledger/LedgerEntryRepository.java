package com.swiftbureau.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    List<LedgerEntry> findByTenantIdAndAccountId(UUID tenantId, UUID accountId);

    List<LedgerEntry> findByTenantId(UUID tenantId);
}
