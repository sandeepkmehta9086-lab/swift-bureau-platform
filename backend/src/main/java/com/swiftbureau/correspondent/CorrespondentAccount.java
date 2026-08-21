package com.swiftbureau.correspondent;

import com.swiftbureau.domain.AccountType;
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
@Table(name = "correspondent_account")
public class CorrespondentAccount {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String accountRef;

    @Column(nullable = false, length = 11)
    private String correspondentBic;

    private String label;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal statementBalance = BigDecimal.ZERO;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (statementBalance == null) {
            statementBalance = BigDecimal.ZERO;
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

    public AccountType getType() {
        return type;
    }

    public void setType(AccountType type) {
        this.type = type;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getAccountRef() {
        return accountRef;
    }

    public void setAccountRef(String accountRef) {
        this.accountRef = accountRef;
    }

    public String getCorrespondentBic() {
        return correspondentBic;
    }

    public void setCorrespondentBic(String correspondentBic) {
        this.correspondentBic = correspondentBic;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public BigDecimal getStatementBalance() {
        return statementBalance;
    }

    public void setStatementBalance(BigDecimal statementBalance) {
        this.statementBalance = statementBalance;
    }
}
