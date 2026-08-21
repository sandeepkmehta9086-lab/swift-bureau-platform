package com.swiftbureau.charge;

import com.swiftbureau.domain.ChargeBearer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "charge_schedule")
public class ChargeSchedule {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(length = 11)
    private String fromBic;

    @Column(length = 11)
    private String toBic;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal feeAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChargeBearer defaultBearer = ChargeBearer.SHAR;

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

    public String getFromBic() {
        return fromBic;
    }

    public void setFromBic(String fromBic) {
        this.fromBic = fromBic;
    }

    public String getToBic() {
        return toBic;
    }

    public void setToBic(String toBic) {
        this.toBic = toBic;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getFeeAmount() {
        return feeAmount;
    }

    public void setFeeAmount(BigDecimal feeAmount) {
        this.feeAmount = feeAmount;
    }

    public ChargeBearer getDefaultBearer() {
        return defaultBearer;
    }

    public void setDefaultBearer(ChargeBearer defaultBearer) {
        this.defaultBearer = defaultBearer;
    }
}
