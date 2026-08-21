package com.swiftbureau.gpi;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GpiEventRepository extends JpaRepository<GpiEvent, UUID> {
    List<GpiEvent> findByTenantIdAndUetrOrderByOccurredAtAsc(UUID tenantId, String uetr);
}
