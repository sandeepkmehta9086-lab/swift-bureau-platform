package com.swiftbureau.rma;

import com.swiftbureau.domain.RmaStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rma_relationship")
public class RmaRelationship {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 11)
    private String ourBic;

    @Column(nullable = false, length = 11)
    private String counterpartyBic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RmaStatus status = RmaStatus.PENDING;

    private Instant validFrom;
    private Instant validTo;
    private UUID createdBy;
    private UUID activatedBy;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getOurBic() {
        return ourBic;
    }

    public void setOurBic(String ourBic) {
        this.ourBic = ourBic;
    }

    public String getCounterpartyBic() {
        return counterpartyBic;
    }

    public void setCounterpartyBic(String counterpartyBic) {
        this.counterpartyBic = counterpartyBic;
    }

    public RmaStatus getStatus() {
        return status;
    }

    public void setStatus(RmaStatus status) {
        this.status = status;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Instant validFrom) {
        this.validFrom = validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public void setValidTo(Instant validTo) {
        this.validTo = validTo;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public UUID getActivatedBy() {
        return activatedBy;
    }

    public void setActivatedBy(UUID activatedBy) {
        this.activatedBy = activatedBy;
    }
}
