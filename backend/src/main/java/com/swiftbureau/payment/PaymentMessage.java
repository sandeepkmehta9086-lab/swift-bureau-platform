package com.swiftbureau.payment;

import com.swiftbureau.domain.ChargeBearer;
import com.swiftbureau.domain.MessageDirection;
import com.swiftbureau.domain.MessageStatus;
import com.swiftbureau.domain.ScreeningStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_message")
public class PaymentMessage {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String messageType;

    @Column(nullable = false, length = 36)
    private String uetr;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageStatus status = MessageStatus.DRAFT;

    @Column(nullable = false)
    private int version = 1;

    @Lob
    private String mxXml;

    private boolean xmlFrozen;

    @Column(nullable = false, length = 11)
    private String instructingAgentBic;

    @Column(nullable = false, length = 11)
    private String instructedAgentBic;

    @Column(nullable = false, precision = 18, scale = 2)
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

    private UUID makerId;
    private UUID checkerId;

    @Enumerated(EnumType.STRING)
    private ScreeningStatus screeningStatus = ScreeningStatus.NOT_RUN;

    private String screeningResult;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant sentAt;

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

    public void setId(UUID id) {
        this.id = id;
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

    public String getUetr() {
        return uetr;
    }

    public void setUetr(String uetr) {
        this.uetr = uetr;
    }

    public MessageDirection getDirection() {
        return direction;
    }

    public void setDirection(MessageDirection direction) {
        this.direction = direction;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getMxXml() {
        return mxXml;
    }

    public void setMxXml(String mxXml) {
        this.mxXml = mxXml;
    }

    public boolean isXmlFrozen() {
        return xmlFrozen;
    }

    public void setXmlFrozen(boolean xmlFrozen) {
        this.xmlFrozen = xmlFrozen;
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

    public UUID getMakerId() {
        return makerId;
    }

    public void setMakerId(UUID makerId) {
        this.makerId = makerId;
    }

    public UUID getCheckerId() {
        return checkerId;
    }

    public void setCheckerId(UUID checkerId) {
        this.checkerId = checkerId;
    }

    public ScreeningStatus getScreeningStatus() {
        return screeningStatus;
    }

    public void setScreeningStatus(ScreeningStatus screeningStatus) {
        this.screeningStatus = screeningStatus;
    }

    public String getScreeningResult() {
        return screeningResult;
    }

    public void setScreeningResult(String screeningResult) {
        this.screeningResult = screeningResult;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }
}
