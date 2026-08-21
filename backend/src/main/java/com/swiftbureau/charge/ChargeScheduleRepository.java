package com.swiftbureau.charge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChargeScheduleRepository extends JpaRepository<ChargeSchedule, UUID> {
    List<ChargeSchedule> findByTenantId(UUID tenantId);
}
