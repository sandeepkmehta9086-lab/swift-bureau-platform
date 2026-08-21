package com.swiftbureau.template;

import com.swiftbureau.audit.AuditService;
import com.swiftbureau.common.ApiException;
import com.swiftbureau.domain.ChargeBearer;
import com.swiftbureau.domain.Role;
import com.swiftbureau.mx.MxMessageFactory;
import com.swiftbureau.security.CurrentUser;
import com.swiftbureau.tenant.BankBicRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class TemplateService {

    private final MessageTemplateRepository templates;
    private final BankBicRepository bics;
    private final AuditService auditService;

    public TemplateService(
            MessageTemplateRepository templates,
            BankBicRepository bics,
            AuditService auditService
    ) {
        this.templates = templates;
        this.bics = bics;
        this.auditService = auditService;
    }

    public List<MessageTemplate> list(CurrentUser actor, boolean includeInactive) {
        UUID tenantId = actor.requireTenantId();
        if (includeInactive) {
            requireOfficer(actor);
            return templates.findByTenantIdOrderByNameAsc(tenantId);
        }
        return templates.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId);
    }

    public MessageTemplate get(CurrentUser actor, UUID id) {
        return requireOwned(actor.requireTenantId(), id);
    }

    @Transactional
    public MessageTemplate create(CurrentUser actor, TemplateRequest request) {
        requireOfficer(actor);
        UUID tenantId = actor.requireTenantId();
        validatePayload(tenantId, request);
        if (templates.existsByTenantIdAndNameIgnoreCase(tenantId, request.name().trim())) {
            throw new ApiException(409, "TEMPLATE_NAME", "A template with this name already exists");
        }
        MessageTemplate template = new MessageTemplate();
        template.setTenantId(tenantId);
        template.setCreatedBy(actor.id());
        apply(template, request);
        templates.save(template);
        auditService.record(actor, "TEMPLATE_CREATED", "MessageTemplate", template.getId().toString(), template.getName());
        return template;
    }

    @Transactional
    public MessageTemplate update(CurrentUser actor, UUID id, TemplateRequest request) {
        requireOfficer(actor);
        UUID tenantId = actor.requireTenantId();
        MessageTemplate template = requireOwned(tenantId, id);
        validatePayload(tenantId, request);
        if (templates.existsByTenantIdAndNameIgnoreCaseAndIdNot(tenantId, request.name().trim(), id)) {
            throw new ApiException(409, "TEMPLATE_NAME", "A template with this name already exists");
        }
        apply(template, request);
        auditService.record(actor, "TEMPLATE_UPDATED", "MessageTemplate", template.getId().toString(), template.getName());
        return template;
    }

    public MessageTemplate requireActive(UUID tenantId, UUID templateId) {
        MessageTemplate template = requireOwned(tenantId, templateId);
        if (!template.isActive()) {
            throw new ApiException(409, "TEMPLATE_INACTIVE", "Template is inactive");
        }
        return template;
    }

    private MessageTemplate requireOwned(UUID tenantId, UUID id) {
        return templates.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Template not found"));
    }

    private void apply(MessageTemplate template, TemplateRequest request) {
        template.setName(request.name().trim());
        template.setDescription(request.description());
        template.setActive(request.active() == null || request.active());
        template.setMessageType(request.messageType());
        template.setInstructingAgentBic(request.instructingAgentBic().toUpperCase());
        template.setInstructedAgentBic(request.instructedAgentBic().toUpperCase());
        template.setAmount(request.amount());
        template.setCurrency(request.currency().toUpperCase());
        template.setDebtorName(request.debtorName());
        template.setDebtorStreet(request.debtorStreet());
        template.setDebtorTown(request.debtorTown());
        template.setDebtorCountry(upper(request.debtorCountry()));
        template.setCreditorName(request.creditorName());
        template.setCreditorStreet(request.creditorStreet());
        template.setCreditorTown(request.creditorTown());
        template.setCreditorCountry(upper(request.creditorCountry()));
        template.setChargeBearer(request.chargeBearer() == null ? ChargeBearer.SHAR : request.chargeBearer());
    }

    private void validatePayload(UUID tenantId, TemplateRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ApiException(400, "NAME", "Template name is required");
        }
        if (!"pacs.008".equals(request.messageType()) && !"pacs.009".equals(request.messageType())) {
            throw new ApiException(400, "MSG_TYPE", "Use pacs.008 or pacs.009");
        }
        MxMessageFactory.validateBic(request.instructingAgentBic());
        MxMessageFactory.validateBic(request.instructedAgentBic());
        if (bics.findByTenantId(tenantId).stream()
                .noneMatch(b -> b.getBic().equalsIgnoreCase(request.instructingAgentBic()))) {
            throw new ApiException(400, "BIC_NOT_OWNED", "Instructing BIC is not mapped to this tenant");
        }
        if (request.currency() == null || request.currency().length() != 3) {
            throw new ApiException(400, "CURRENCY", "Currency must be ISO 4217");
        }
        if (filled(request.debtorTown()) || filled(request.debtorCountry())
                || filled(request.creditorTown()) || filled(request.creditorCountry())) {
            if (!filled(request.debtorTown()) || !filled(request.debtorCountry())
                    || !filled(request.creditorTown()) || !filled(request.creditorCountry())) {
                throw new ApiException(400, "CBPR_ADDRESS", "Town and country are required on both parties when an address is provided");
            }
        }
    }

    private void requireOfficer(CurrentUser actor) {
        if (actor.role() != Role.BANK_SECURITY_OFFICER) {
            throw new ApiException(403, "FORBIDDEN", "Bank security officer required");
        }
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase();
    }

    public record TemplateRequest(
            String name,
            String description,
            Boolean active,
            String messageType,
            String instructingAgentBic,
            String instructedAgentBic,
            BigDecimal amount,
            String currency,
            String debtorName,
            String debtorStreet,
            String debtorTown,
            String debtorCountry,
            String creditorName,
            String creditorStreet,
            String creditorTown,
            String creditorCountry,
            ChargeBearer chargeBearer
    ) {
    }
}
