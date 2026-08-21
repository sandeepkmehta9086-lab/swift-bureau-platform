package com.swiftbureau.rma;

import com.swiftbureau.domain.RmaStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RmaRelationshipRepository extends JpaRepository<RmaRelationship, UUID> {
    List<RmaRelationship> findByTenantId(UUID tenantId);

    Optional<RmaRelationship> findByTenantIdAndOurBicAndCounterpartyBic(UUID tenantId, String ourBic, String counterpartyBic);

    boolean existsByTenantIdAndOurBicAndCounterpartyBicAndStatus(UUID tenantId, String ourBic, String counterpartyBic, RmaStatus status);
}
