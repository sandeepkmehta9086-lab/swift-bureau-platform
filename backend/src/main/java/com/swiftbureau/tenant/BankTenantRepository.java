package com.swiftbureau.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BankTenantRepository extends JpaRepository<BankTenant, UUID> {
}
