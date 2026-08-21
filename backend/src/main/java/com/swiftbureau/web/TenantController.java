package com.swiftbureau.web;

import com.swiftbureau.domain.TenantStatus;
import com.swiftbureau.security.SecurityUtil;
import com.swiftbureau.tenant.BankBic;
import com.swiftbureau.tenant.BankTenant;
import com.swiftbureau.tenant.TenantService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping
    public List<BankTenant> list() {
        return tenantService.list(SecurityUtil.current());
    }

    @PostMapping
    public BankTenant create(@RequestBody CreateTenantRequest request) {
        return tenantService.create(SecurityUtil.current(), request.legalName(), request.country(), request.expectedVolumes());
    }

    @PostMapping("/{id}/status")
    public BankTenant status(@PathVariable UUID id, @RequestBody StatusRequest request) {
        return tenantService.advanceKyc(SecurityUtil.current(), id, request.status());
    }

    @PostMapping("/{id}/bics")
    public BankBic bic(@PathVariable UUID id, @RequestBody BicRequest request) {
        return tenantService.addBic(SecurityUtil.current(), id, request.bic(), request.distinguishedName(), request.primaryBic());
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        return tenantService.detail(SecurityUtil.current(), id);
    }

    public record CreateTenantRequest(@NotBlank String legalName, @NotBlank String country, String expectedVolumes) {
    }

    public record StatusRequest(TenantStatus status) {
    }

    public record BicRequest(@NotBlank String bic, String distinguishedName, boolean primaryBic) {
    }
}
