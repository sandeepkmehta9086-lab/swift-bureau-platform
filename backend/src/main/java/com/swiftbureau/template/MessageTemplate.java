package com.swiftbureau.template;

import com.swiftbureau.domain.ChargeBearer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "message_template", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "name"}))
public class MessageTemplate {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private String messageType;

    @Column(nullable = false, length = 11)
    private String instructingAgentBic;

    @Column(nullable = false, length = 11)
    private String instructedAgentBic;

    @Column(precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    private String debtorName;
    private String debtorStreet;
    private String debtorTown;
    private String debtorCountry;
    private String creditorName;
    private String creditorStreet;
    private String creditorTown;
    private String creditorCountry;

    @Enumerated(EnumType.STRING)
    private ChargeBearer chargeBearer = ChargeBearer.SHAR;

    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getInstructingAgentBic() {
        return instructingAgentBic;
    }

    public void setInstructingAgentBic(String instructingAgentBic) {
        this.instructingAgentBic = instructingAgentBic;
    }

    public String getInstructedAgentBic() {
        return instructedAgentBic;
    }

    public void setInstructedAgentBic(String instructedAgentBic) {
        this.instructedAgentBic = instructedAgentBic;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getDebtorName() {
        return debtorName;
    }

    public void setDebtorName(String debtorName) {
        this.debtorName = debtorName;
    }

    public String getDebtorStreet() {
        return debtorStreet;
    }

    public void setDebtorStreet(String debtorStreet) {
        this.debtorStreet = debtorStreet;
    }

    public String getDebtorTown() {
        return debtorTown;
    }

    public void setDebtorTown(String debtorTown) {
        this.debtorTown = debtorTown;
    }

    public String getDebtorCountry() {
        return debtorCountry;
    }

    public void setDebtorCountry(String debtorCountry) {
        this.debtorCountry = debtorCountry;
    }

    public String getCreditorName() {
        return creditorName;
    }

    public void setCreditorName(String creditorName) {
        this.creditorName = creditorName;
    }

    public String getCreditorStreet() {
        return creditorStreet;
    }

    public void setCreditorStreet(String creditorStreet) {
        this.creditorStreet = creditorStreet;
    }

    public String getCreditorTown() {
        return creditorTown;
    }

    public void setCreditorTown(String creditorTown) {
        this.creditorTown = creditorTown;
    }

    public String getCreditorCountry() {
        return creditorCountry;
    }

    public void setCreditorCountry(String creditorCountry) {
        this.creditorCountry = creditorCountry;
    }

    public ChargeBearer getChargeBearer() {
        return chargeBearer;
    }

    public void setChargeBearer(ChargeBearer chargeBearer) {
        this.chargeBearer = chargeBearer;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
