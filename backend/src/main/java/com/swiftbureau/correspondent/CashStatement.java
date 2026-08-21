package com.swiftbureau.correspondent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cash_statement")
public class CashStatement {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String messageType;

    @Column(nullable = false)
    private UUID accountId;

    @Lob
    private String mxXml;

    private UUID relatedPaymentId;

    @Column(length = 36)
    private String uetr;

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

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public String getMxXml() {
        return mxXml;
    }

    public void setMxXml(String mxXml) {
        this.mxXml = mxXml;
    }

    public UUID getRelatedPaymentId() {
        return relatedPaymentId;
    }

    public void setRelatedPaymentId(UUID relatedPaymentId) {
        this.relatedPaymentId = relatedPaymentId;
    }

    public String getUetr() {
        return uetr;
    }

    public void setUetr(String uetr) {
        this.uetr = uetr;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
