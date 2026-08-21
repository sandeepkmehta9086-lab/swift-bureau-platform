package com.swiftbureau.csp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CspControlAttestationRepository extends JpaRepository<CspControlAttestation, UUID> {
    Optional<CspControlAttestation> findByControlId(String controlId);
}
