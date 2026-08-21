package com.swiftbureau.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, UUID> {
    List<MessageTemplate> findByTenantIdOrderByNameAsc(UUID tenantId);

    List<MessageTemplate> findByTenantIdAndActiveTrueOrderByNameAsc(UUID tenantId);

    Optional<MessageTemplate> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

    boolean existsByTenantIdAndNameIgnoreCaseAndIdNot(UUID tenantId, String name, UUID id);
}
