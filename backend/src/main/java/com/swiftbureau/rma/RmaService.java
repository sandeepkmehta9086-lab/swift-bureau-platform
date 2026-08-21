package com.swiftbureau.rma;

import com.swiftbureau.audit.AuditService;
import com.swiftbureau.common.ApiException;
import com.swiftbureau.domain.Role;
import com.swiftbureau.domain.RmaStatus;
import com.swiftbureau.mx.MxMessageFactory;
import com.swiftbureau.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RmaService {

    private final RmaRelationshipRepository repository;
    private final AuditService auditService;

    public RmaService(RmaRelationshipRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional
    public RmaRelationship create(CurrentUser actor, String ourBic, String counterpartyBic) {
        requireOfficer(actor);
        MxMessageFactory.validateBic(ourBic);
        MxMessageFactory.validateBic(counterpartyBic);
        UUID tenantId = actor.requireTenantId();
        repository.findByTenantIdAndOurBicAndCounterpartyBic(tenantId, ourBic.toUpperCase(), counterpartyBic.toUpperCase())
                .ifPresent(existing -> {
                    throw new ApiException(409, "RMA_EXISTS", "RMA already recorded");
                });
        RmaRelationship rel = new RmaRelationship();
        rel.setTenantId(tenantId);
        rel.setOurBic(ourBic.toUpperCase());
        rel.setCounterpartyBic(counterpartyBic.toUpperCase());
        rel.setStatus(RmaStatus.PENDING);
        rel.setCreatedBy(actor.id());
        repository.save(rel);
        auditService.record(actor, "RMA_CREATED", "RmaRelationship", rel.getId().toString(), counterpartyBic);
        return rel;
    }

    @Transactional
    public RmaRelationship activate(CurrentUser actor, UUID id) {
        requireOfficer(actor);
        RmaRelationship rel = repository.findById(id).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "RMA not found"));
        if (!rel.getTenantId().equals(actor.requireTenantId())) {
            throw new ApiException(403, "TENANT_ISOLATION", "Wrong tenant");
        }
        if (actor.id().equals(rel.getCreatedBy())) {
            throw new ApiException(403, "DUAL_CONTROL", "A second security officer must activate RMA");
        }
        rel.setStatus(RmaStatus.ACTIVE);
        rel.setActivatedBy(actor.id());
        rel.setValidFrom(Instant.now());
        auditService.record(actor, "RMA_ACTIVATED", "RmaRelationship", rel.getId().toString(), rel.getCounterpartyBic());
        return rel;
    }

    @Transactional
    public RmaRelationship revoke(CurrentUser actor, UUID id) {
        requireOfficer(actor);
        RmaRelationship rel = repository.findById(id).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "RMA not found"));
        if (!rel.getTenantId().equals(actor.requireTenantId())) {
            throw new ApiException(403, "TENANT_ISOLATION", "Wrong tenant");
        }
        rel.setStatus(RmaStatus.REVOKED);
        rel.setValidTo(Instant.now());
        auditService.record(actor, "RMA_REVOKED", "RmaRelationship", rel.getId().toString(), rel.getCounterpartyBic());
        return rel;
    }

    public List<RmaRelationship> list(CurrentUser actor) {
        return repository.findByTenantId(actor.requireTenantId());
    }

    private void requireOfficer(CurrentUser actor) {
        if (actor.role() != Role.BANK_SECURITY_OFFICER) {
            throw new ApiException(403, "FORBIDDEN", "Bank security officer required");
        }
    }
}
