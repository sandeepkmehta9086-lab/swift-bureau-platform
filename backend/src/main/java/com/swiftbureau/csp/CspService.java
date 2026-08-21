package com.swiftbureau.csp;

import com.swiftbureau.audit.AuditService;
import com.swiftbureau.common.ApiException;
import com.swiftbureau.config.AppProperties;
import com.swiftbureau.domain.AttestationStatus;
import com.swiftbureau.domain.Role;
import com.swiftbureau.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CspService {

    private final CspControlAttestationRepository attestations;
    private final ResourceLoader resourceLoader;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public CspService(
            CspControlAttestationRepository attestations,
            ResourceLoader resourceLoader,
            AppProperties properties,
            ObjectMapper objectMapper,
            AuditService auditService
    ) {
        this.attestations = attestations;
        this.resourceLoader = resourceLoader;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    public JsonNode framework() {
        try {
            Resource resource = resourceLoader.getResource(properties.getCsp().getControlsPath());
            try (InputStream in = resource.getInputStream()) {
                return objectMapper.readTree(in);
            }
        } catch (Exception e) {
            throw new ApiException(500, "CSP_PACK", "Unable to load CSCF control catalogue");
        }
    }

    public List<Map<String, Object>> controls() {
        JsonNode root = framework();
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode control : root.path("controls")) {
            String id = control.path("id").asText();
            CspControlAttestation att = attestations.findByControlId(id).orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("title", control.path("title").asText());
            row.put("mandatory", control.path("mandatory").asBoolean());
            row.put("objective", control.path("objective").asText());
            row.put("evidenceHint", control.path("evidenceHint").asText());
            row.put("status", att == null ? AttestationStatus.OPEN.name() : att.getStatus().name());
            row.put("evidenceNotes", att == null ? "" : att.getEvidenceNotes());
            row.put("frozen", att != null && att.isFrozen());
            row.put("attestedBy", att == null || att.getAttestedBy() == null ? "" : att.getAttestedBy().toString());
            row.put("confirmedBy", att == null || att.getConfirmedBy() == null ? "" : att.getConfirmedBy().toString());
            out.add(row);
        }
        return out;
    }

    @Transactional
    public CspControlAttestation attest(CurrentUser actor, String controlId, AttestationStatus status, String notes) {
        requireBureau(actor);
        CspControlAttestation att = attestations.findByControlId(controlId).orElseGet(() -> {
            CspControlAttestation created = new CspControlAttestation();
            created.setControlId(controlId);
            return created;
        });
        if (att.isFrozen()) {
            throw new ApiException(409, "FROZEN", "Control pack is frozen");
        }
        att.setStatus(status);
        att.setEvidenceNotes(notes);
        att.setAttestedBy(actor.id());
        att.setAttestedAt(Instant.now());
        attestations.save(att);
        auditService.record(actor, "CSP_ATTEST", "CspControl", controlId, status.name());
        return att;
    }

    @Transactional
    public CspControlAttestation confirm(CurrentUser actor, String controlId) {
        requireBureau(actor);
        CspControlAttestation att = attestations.findByControlId(controlId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Control not attested"));
        if (actor.id().equals(att.getAttestedBy())) {
            throw new ApiException(403, "DUAL_CONTROL", "A second bureau operator must confirm the pack");
        }
        att.setConfirmedBy(actor.id());
        att.setConfirmedAt(Instant.now());
        att.setFrozen(true);
        auditService.record(actor, "CSP_CONFIRM", "CspControl", controlId, "frozen");
        return att;
    }

    public Map<String, Object> pack() {
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("framework", framework());
        pack.put("attestations", controls());
        pack.put("generatedAt", Instant.now().toString());
        pack.put("disclaimer", "Customer confirmation of interface security is required before live BIC traffic.");
        return pack;
    }

    public Map<String, Object> configurationReport() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("interface", "swift-bureau-platform");
        report.put("secureZoneAdapter", "AllianceCloudGateway");
        report.put("defaultGateway", properties.getSwift().getMode());
        report.put("liveEnabled", properties.getSwift().isLiveEnabled());
        report.put("screeningProvider", properties.getScreening().getProvider());
        report.put("mfa", "TOTP AAL2-class, step-up on payment release");
        report.put("passwordPolicy", "12+ mixed charset, lockout after failed attempts");
        report.put("warehouse", "Immutable sent MX (xmlFrozen); repairs require new version");
        return report;
    }

    private void requireBureau(CurrentUser actor) {
        if (actor.role() != Role.BUREAU_OPERATOR) {
            throw new ApiException(403, "FORBIDDEN", "Bureau operator required");
        }
    }
}
