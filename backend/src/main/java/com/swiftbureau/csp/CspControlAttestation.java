package com.swiftbureau.csp;

import com.swiftbureau.domain.AttestationStatus;
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
@Table(name = "csp_control_attestation")
public class CspControlAttestation {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String controlId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttestationStatus status = AttestationStatus.OPEN;

    @Column(length = 4000)
    private String evidenceNotes;

    private UUID attestedBy;
    private Instant attestedAt;
    private UUID confirmedBy;
    private Instant confirmedAt;
    private boolean frozen;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public UUID getId() {
        return id;
    }

    public String getControlId() {
        return controlId;
    }

    public void setControlId(String controlId) {
        this.controlId = controlId;
    }

    public AttestationStatus getStatus() {
        return status;
    }

    public void setStatus(AttestationStatus status) {
        this.status = status;
    }

    public String getEvidenceNotes() {
        return evidenceNotes;
    }

    public void setEvidenceNotes(String evidenceNotes) {
        this.evidenceNotes = evidenceNotes;
    }

    public UUID getAttestedBy() {
        return attestedBy;
    }

    public void setAttestedBy(UUID attestedBy) {
        this.attestedBy = attestedBy;
    }

    public Instant getAttestedAt() {
        return attestedAt;
    }

    public void setAttestedAt(Instant attestedAt) {
        this.attestedAt = attestedAt;
    }

    public UUID getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(UUID confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }
}
