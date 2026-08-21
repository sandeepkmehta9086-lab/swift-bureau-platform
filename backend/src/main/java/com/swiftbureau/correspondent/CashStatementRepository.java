package com.swiftbureau.correspondent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CashStatementRepository extends JpaRepository<CashStatement, UUID> {
    List<CashStatement> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
