package com.swiftbureau.tenant;

import com.swiftbureau.audit.AuditService;
import com.swiftbureau.common.ApiException;
import com.swiftbureau.domain.Role;
import com.swiftbureau.domain.TenantStatus;
import com.swiftbureau.mx.MxMessageFactory;
import com.swiftbureau.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TenantService {

    private final BankTenantRepository tenants;
    private final BankBicRepository bics;
    private final AuditService auditService;

    public TenantService(BankTenantRepository tenants, BankBicRepository bics, AuditService auditService) {
        this.tenants = tenants;
        this.bics = bics;
        this.auditService = auditService;
    }

    @Transactional
    public BankTenant create(CurrentUser actor, String legalName, String country, String expectedVolumes) {
        requireBureau(actor);
        BankTenant tenant = new BankTenant();
        tenant.setLegalName(legalName);
        tenant.setCountry(country.toUpperCase());
        tenant.setExpectedVolumes(expectedVolumes);
        tenant.setStatus(TenantStatus.DRAFT);
        tenants.save(tenant);
        auditService.record(actor, "TENANT_CREATED", "BankTenant", tenant.getId().toString(), legalName);
        return tenant;
    }

    @Transactional
    public BankTenant advanceKyc(CurrentUser actor, UUID id, TenantStatus status) {
        requireBureau(actor);
        BankTenant tenant = tenants.findById(id).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Tenant not found"));
        tenant.setStatus(status);
        auditService.record(actor, "TENANT_STATUS", "BankTenant", id.toString(), status.name());
        return tenant;
    }

    @Transactional
    public BankBic addBic(CurrentUser actor, UUID tenantId, String bic, String dn, boolean primary) {
        requireBureau(actor);
        MxMessageFactory.validateBic(bic.toUpperCase());
        BankTenant tenant = tenants.findById(tenantId).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Tenant not found"));
        BankBic row = new BankBic();
        row.setTenantId(tenant.getId());
        row.setBic(bic.toUpperCase());
        row.setDistinguishedName(dn);
        row.setPrimaryBic(primary);
        bics.save(row);
        auditService.record(actor, "BIC_MAPPED", "BankBic", row.getId().toString(), row.getBic());
        return row;
    }

    public List<BankTenant> list(CurrentUser actor) {
        if (actor.bureau()) {
            return tenants.findAll();
        }
        return List.of(tenants.findById(actor.requireTenantId()).orElseThrow());
    }

    public Map<String, Object> detail(CurrentUser actor, UUID tenantId) {
        UUID id = actor.bureau() ? tenantId : actor.requireTenantId();
        if (!actor.bureau() && !id.equals(tenantId) && tenantId != null && !tenantId.equals(actor.tenantId())) {
            throw new ApiException(403, "TENANT_ISOLATION", "Cannot view another bank");
        }
        BankTenant tenant = tenants.findById(id).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Tenant not found"));
        return Map.of("tenant", tenant, "bics", bics.findByTenantId(tenant.getId()));
    }

    public static void requireBureau(CurrentUser actor) {
        if (actor.role() != Role.BUREAU_OPERATOR) {
            throw new ApiException(403, "FORBIDDEN", "Bureau operator role required");
        }
    }
}
