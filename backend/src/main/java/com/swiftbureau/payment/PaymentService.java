package com.swiftbureau.payment;

import com.swiftbureau.audit.AuditService;
import com.swiftbureau.charge.ChargeService;
import com.swiftbureau.common.ApiException;
import com.swiftbureau.correspondent.CashStatement;
import com.swiftbureau.correspondent.CashStatementRepository;
import com.swiftbureau.correspondent.CorrespondentAccount;
import com.swiftbureau.correspondent.CorrespondentAccountRepository;
import com.swiftbureau.domain.AccountType;
import com.swiftbureau.domain.ChargeBearer;
import com.swiftbureau.domain.MessageDirection;
import com.swiftbureau.domain.MessageStatus;
import com.swiftbureau.domain.Role;
import com.swiftbureau.domain.RmaStatus;
import com.swiftbureau.domain.ScreeningStatus;
import com.swiftbureau.gpi.GpiService;
import com.swiftbureau.ledger.LedgerService;
import com.swiftbureau.mx.MxMessageFactory;
import com.swiftbureau.rma.RmaRelationshipRepository;
import com.swiftbureau.screening.ScreeningClient;
import com.swiftbureau.security.CurrentUser;
import com.swiftbureau.swift.SwiftNetworkGateway;
import com.swiftbureau.swift.SwiftSendResult;
import com.swiftbureau.template.MessageTemplate;
import com.swiftbureau.template.TemplateService;
import com.swiftbureau.tenant.BankBicRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentMessageRepository payments;
    private final BankBicRepository bics;
    private final RmaRelationshipRepository rma;
    private final ScreeningClient screeningClient;
    private final SwiftNetworkGateway swiftNetworkGateway;
    private final AuditService auditService;
    private final GpiService gpiService;
    private final LedgerService ledgerService;
    private final ChargeService chargeService;
    private final CorrespondentAccountRepository accounts;
    private final CashStatementRepository statements;
    private final TemplateService templateService;

    public PaymentService(
            PaymentMessageRepository payments,
            BankBicRepository bics,
            RmaRelationshipRepository rma,
            ScreeningClient screeningClient,
            SwiftNetworkGateway swiftNetworkGateway,
            AuditService auditService,
            GpiService gpiService,
            LedgerService ledgerService,
            ChargeService chargeService,
            CorrespondentAccountRepository accounts,
            CashStatementRepository statements,
            TemplateService templateService
    ) {
        this.payments = payments;
        this.bics = bics;
        this.rma = rma;
        this.screeningClient = screeningClient;
        this.swiftNetworkGateway = swiftNetworkGateway;
        this.auditService = auditService;
        this.gpiService = gpiService;
        this.ledgerService = ledgerService;
        this.chargeService = chargeService;
        this.accounts = accounts;
        this.statements = statements;
        this.templateService = templateService;
    }

    @Transactional
    public PaymentMessage create(CurrentUser actor, CreatePaymentRequest incoming) {
        assertRole(actor, Role.PAYMENT_MAKER, Role.BANK_SECURITY_OFFICER);
        UUID tenantId = actor.requireTenantId();
        CreatePaymentRequest req = overlayTemplate(tenantId, incoming);
        PaymentMessage msg = createDraft(actor, req, MxMessageFactory.newUetr(), "Created by maker");
        UUID appliedTemplate = templateUuid(incoming.templateId());
        if (appliedTemplate != null) {
            auditService.record(actor, "TEMPLATE_APPLIED", "MessageTemplate", appliedTemplate.toString(), msg.getUetr());
        }
        return msg;
    }

    /**
     * Stage 1 of the payment life cycle: a customer's pain.001 arrives at the tenant bank.
     * The bank validates it, answers with exactly one pain.002 (ACCP or RJCT), and only an accepted
     * instruction becomes a pacs.008 draft. The UETR is minted here and shared by every later message.
     * A rejection is a normal business outcome, so both messages are persisted and no exception is raised.
     */
    @Transactional
    public InitiationResult initiate(CurrentUser actor, CustomerInstructionRequest req) {
        assertRole(actor, Role.PAYMENT_MAKER, Role.BANK_SECURITY_OFFICER);
        UUID tenantId = actor.requireTenantId();
        // Fields the message store cannot hold are a malformed file, not a business rejection.
        MxMessageFactory.validateBic(req.debtorAgentBic());
        MxMessageFactory.validateBic(req.creditorAgentBic());
        if (req.amount() == null) {
            throw new ApiException(400, "AMOUNT", "Instructed amount is mandatory on pain.001");
        }
        if (req.currency() == null || req.currency().length() != 3) {
            throw new ApiException(400, "CURRENCY", "Currency must be an ISO 4217 code");
        }
        if (req.debtorName() == null || req.debtorName().isBlank()) {
            throw new ApiException(400, "DEBTOR", "Debtor name is mandatory on pain.001");
        }

        String uetr = MxMessageFactory.newUetr();
        PaymentMessage instruction = new PaymentMessage();
        instruction.setId(UUID.randomUUID());
        instruction.setTenantId(tenantId);
        instruction.setMessageType("pain.001");
        instruction.setUetr(uetr);
        instruction.setDirection(MessageDirection.IN);
        instruction.setStatus(MessageStatus.SCREENING);
        copyParties(req, instruction);
        instruction.setMakerId(actor.id());
        instruction.setMxXml(MxMessageFactory.pain001(instruction));
        instruction.setXmlFrozen(true);
        payments.save(instruction);
        gpiService.append(tenantId, uetr, "CUSTOMER_INSTRUCTED", instruction.getInstructingAgentBic(),
                "pain.001 received from " + instruction.getDebtorName());

        PaymentMessage draft = null;
        String reasonCode = null;
        String rejection = null;
        try {
            draft = buildDraft(actor, toCreateRequest(req), uetr);
        } catch (ApiException ex) {
            reasonCode = pain002Reason(ex.getCode());
            rejection = ex.getMessage();
        }

        boolean accepted = draft != null;
        instruction.setStatus(accepted ? MessageStatus.ACKED : MessageStatus.REJECTED);
        instruction.setReasonCode(accepted ? "ACCP" : reasonCode);
        instruction.setScreeningResult(rejection);

        PaymentMessage statusReport = new PaymentMessage();
        statusReport.setTenantId(tenantId);
        statusReport.setMessageType("pain.002");
        statusReport.setUetr(uetr);
        statusReport.setDirection(MessageDirection.OUT);
        statusReport.setStatus(accepted ? MessageStatus.ACKED : MessageStatus.REJECTED);
        copyParties(req, statusReport);
        statusReport.setReasonCode(accepted ? "ACCP" : reasonCode);
        statusReport.setScreeningResult(rejection);
        statusReport.setMxXml(MxMessageFactory.pain002(instruction, accepted ? "ACCP" : "RJCT", accepted ? null : reasonCode));
        statusReport.setXmlFrozen(true);
        payments.save(statusReport);

        if (accepted) {
            gpiService.append(tenantId, uetr, "CUSTOMER_ACCEPTED", instruction.getInstructingAgentBic(), "pain.002 ACCP");
            auditService.record(actor, "INSTRUCTION_ACCEPTED", "PaymentMessage", instruction.getId().toString(), uetr);
            // Only an accepted instruction becomes an interbank draft, so INITIATED follows the acceptance.
            persistDraft(actor, draft, "pacs.008 drafted from accepted pain.001");
        } else {
            gpiService.append(tenantId, uetr, "CUSTOMER_REJECTED", instruction.getInstructingAgentBic(),
                    "pain.002 RJCT " + reasonCode + ": " + rejection);
            auditService.record(actor, "INSTRUCTION_REJECTED", "PaymentMessage", instruction.getId().toString(),
                    reasonCode + " " + rejection);
        }
        return new InitiationResult(instruction, statusReport, draft, accepted, accepted ? "ACCP" : reasonCode, rejection);
    }

    /** Builds, validates and stores a pacs.008/pacs.009 draft carrying the given UETR. */
    private PaymentMessage createDraft(CurrentUser actor, CreatePaymentRequest req, String uetr, String gpiDetail) {
        return persistDraft(actor, buildDraft(actor, req, uetr), gpiDetail);
    }

    private PaymentMessage persistDraft(CurrentUser actor, PaymentMessage msg, String gpiDetail) {
        payments.save(msg);
        gpiService.append(msg.getTenantId(), msg.getUetr(), "INITIATED", msg.getInstructingAgentBic(), gpiDetail);
        auditService.record(actor, "PAYMENT_CREATED", "PaymentMessage", msg.getId().toString(), msg.getUetr());
        return msg;
    }

    /** Validates the request and builds an unsaved draft; throws ApiException on any business rule failure. */
    private PaymentMessage buildDraft(CurrentUser actor, CreatePaymentRequest req, String uetr) {
        UUID tenantId = actor.requireTenantId();
        if (!"pacs.008".equals(req.messageType()) && !"pacs.009".equals(req.messageType())) {
            throw new ApiException(400, "MSG_TYPE", "Use pacs.008 or pacs.009");
        }
        MxMessageFactory.validateBic(req.instructingAgentBic());
        MxMessageFactory.validateBic(req.instructedAgentBic());
        if (bics.findByTenantId(tenantId).stream().noneMatch(b -> b.getBic().equalsIgnoreCase(req.instructingAgentBic()))) {
            throw new ApiException(400, "BIC_NOT_OWNED", "Instructing BIC is not mapped to this tenant");
        }
        if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(400, "AMOUNT", "Amount must be positive");
        }
        PaymentMessage msg = new PaymentMessage();
        msg.setId(UUID.randomUUID());
        msg.setTenantId(tenantId);
        msg.setMessageType(req.messageType());
        msg.setUetr(uetr);
        msg.setDirection(MessageDirection.OUT);
        msg.setStatus(MessageStatus.DRAFT);
        msg.setInstructingAgentBic(req.instructingAgentBic().toUpperCase());
        msg.setInstructedAgentBic(req.instructedAgentBic().toUpperCase());
        msg.setAmount(req.amount());
        msg.setCurrency(req.currency().toUpperCase());
        msg.setDebtorName(req.debtorName());
        msg.setDebtorStreet(req.debtorStreet());
        msg.setDebtorTown(req.debtorTown());
        msg.setDebtorCountry(req.debtorCountry() == null ? null : req.debtorCountry().toUpperCase());
        msg.setCreditorName(req.creditorName());
        msg.setCreditorStreet(req.creditorStreet());
        msg.setCreditorTown(req.creditorTown());
        msg.setCreditorCountry(req.creditorCountry() == null ? null : req.creditorCountry().toUpperCase());
        msg.setChargeBearer(req.chargeBearer() == null ? ChargeBearer.SHAR : req.chargeBearer());
        msg.setMakerId(actor.id());
        MxMessageFactory.validateStructuredAddress(msg);
        msg.setMxXml("pacs.008".equals(msg.getMessageType()) ? MxMessageFactory.pacs008(msg) : MxMessageFactory.pacs009(msg));
        return msg;
    }

    private static void copyParties(CustomerInstructionRequest req, PaymentMessage msg) {
        msg.setInstructingAgentBic(req.debtorAgentBic().toUpperCase());
        msg.setInstructedAgentBic(req.creditorAgentBic().toUpperCase());
        msg.setAmount(req.amount());
        msg.setCurrency(req.currency().toUpperCase());
        msg.setDebtorName(req.debtorName());
        msg.setDebtorStreet(req.debtorStreet());
        msg.setDebtorTown(req.debtorTown());
        msg.setDebtorCountry(req.debtorCountry() == null ? null : req.debtorCountry().toUpperCase());
        msg.setCreditorName(req.creditorName());
        msg.setCreditorStreet(req.creditorStreet());
        msg.setCreditorTown(req.creditorTown());
        msg.setCreditorCountry(req.creditorCountry() == null ? null : req.creditorCountry().toUpperCase());
        msg.setChargeBearer(req.chargeBearer() == null ? ChargeBearer.SHAR : req.chargeBearer());
    }

    private static CreatePaymentRequest toCreateRequest(CustomerInstructionRequest req) {
        return new CreatePaymentRequest(
                "pacs.008",
                req.debtorAgentBic(),
                req.creditorAgentBic(),
                req.amount(),
                req.currency(),
                req.debtorName(),
                req.debtorStreet(),
                req.debtorTown(),
                req.debtorCountry(),
                req.creditorName(),
                req.creditorStreet(),
                req.creditorTown(),
                req.creditorCountry(),
                req.chargeBearer(),
                null
        );
    }

    /** Maps an internal validation code to the ISO 20022 external status reason reported on pain.002. */
    static String pain002Reason(String apiCode) {
        return switch (apiCode == null ? "" : apiCode) {
            case "CBPR_ADDRESS" -> "BE04";   // missing/invalid postal address
            case "AMOUNT" -> "AM02";         // amount not allowed
            case "BIC_NOT_OWNED", "BIC" -> "RC01"; // bank identifier incorrect
            default -> "NARR";               // narrative: see detail
        };
    }

    private CreatePaymentRequest overlayTemplate(UUID tenantId, CreatePaymentRequest req) {
        UUID templateId = templateUuid(req.templateId());
        if (templateId == null) {
            return req;
        }
        MessageTemplate template = templateService.requireActive(tenantId, templateId);
        return new CreatePaymentRequest(
                coalesce(req.messageType(), template.getMessageType()),
                coalesce(req.instructingAgentBic(), template.getInstructingAgentBic()),
                coalesce(req.instructedAgentBic(), template.getInstructedAgentBic()),
                req.amount() != null ? req.amount() : template.getAmount(),
                coalesce(req.currency(), template.getCurrency()),
                coalesce(req.debtorName(), template.getDebtorName()),
                coalesce(req.debtorStreet(), template.getDebtorStreet()),
                coalesce(req.debtorTown(), template.getDebtorTown()),
                coalesce(req.debtorCountry(), template.getDebtorCountry()),
                coalesce(req.creditorName(), template.getCreditorName()),
                coalesce(req.creditorStreet(), template.getCreditorStreet()),
                coalesce(req.creditorTown(), template.getCreditorTown()),
                coalesce(req.creditorCountry(), template.getCreditorCountry()),
                req.chargeBearer() != null ? req.chargeBearer() : template.getChargeBearer(),
                req.templateId()
        );
    }

    private static UUID templateUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, "TEMPLATE", "Invalid template id");
        }
    }

    private static String coalesce(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Transactional
    public PaymentMessage submit(CurrentUser actor, UUID id) {
        assertRole(actor, Role.PAYMENT_MAKER, Role.BANK_SECURITY_OFFICER);
        PaymentMessage msg = requireOwned(actor, id);
        if (msg.getStatus() != MessageStatus.DRAFT && msg.getStatus() != MessageStatus.SCREENING_HIT) {
            throw new ApiException(409, "STATE", "Only drafts can be submitted");
        }
        if (msg.isXmlFrozen()) {
            throw new ApiException(409, "FROZEN", "Sent MX cannot be mutated; create a new version");
        }
        if (!rma.existsByTenantIdAndOurBicAndCounterpartyBicAndStatus(
                msg.getTenantId(), msg.getInstructingAgentBic(), msg.getInstructedAgentBic(), RmaStatus.ACTIVE)) {
            throw new ApiException(400, "RMA", "No active RMA with instructed agent BIC");
        }
        msg.setStatus(MessageStatus.SCREENING);
        ScreeningClient.ScreeningResult result = screeningClient.screen(msg);
        msg.setScreeningStatus(result.status());
        msg.setScreeningResult(result.detail());
        if (result.status() == ScreeningStatus.HIT || result.status() == ScreeningStatus.UNAVAILABLE) {
            msg.setStatus(MessageStatus.SCREENING_HIT);
            auditService.record(actor, "SCREENING_HIT", "PaymentMessage", msg.getId().toString(), result.detail());
            return msg;
        }
        msg.setStatus(MessageStatus.PENDING_CHECK);
        auditService.record(actor, "PAYMENT_SUBMITTED", "PaymentMessage", msg.getId().toString(), msg.getUetr());
        return msg;
    }

    @Transactional
    public PaymentMessage approve(CurrentUser actor, UUID id) {
        if (actor.role() == Role.BUREAU_OPERATOR) {
            throw new ApiException(403, "DUAL_CONTROL", "Bureau operators cannot release a customer payment");
        }
        assertRole(actor, Role.PAYMENT_CHECKER, Role.BANK_SECURITY_OFFICER);
        PaymentMessage msg = requireOwned(actor, id);
        if (msg.getStatus() != MessageStatus.PENDING_CHECK) {
            throw new ApiException(409, "STATE", "Payment is not awaiting checker");
        }
        if (actor.id().equals(msg.getMakerId())) {
            throw new ApiException(403, "MAKER_CHECKER", "Maker cannot approve their own payment");
        }
        msg.setCheckerId(actor.id());
        msg.setStatus(MessageStatus.APPROVED);
        String originalUetr = msg.getUetr();
        String xml = "pacs.008".equals(msg.getMessageType()) ? MxMessageFactory.pacs008(msg) : MxMessageFactory.pacs009(msg);
        if (!xml.contains(originalUetr)) {
            throw new ApiException(500, "UETR", "UETR must never be rewritten");
        }
        msg.setMxXml(xml);
        msg.setXmlFrozen(true);
        msg.setUetr(originalUetr);
        SwiftSendResult sent = swiftNetworkGateway.send(msg);
        msg.setStatus(MessageStatus.SENT);
        msg.setSentAt(java.time.Instant.now());
        gpiService.append(msg.getTenantId(), msg.getUetr(), "SENT_TO_SWIFT", msg.getInstructingAgentBic(), sent.networkReference());
        ledgerService.postOutbound(msg);
        chargeService.applyFee(msg);
        persistInbound(msg, sent);
        auditService.record(actor, "PAYMENT_RELEASED", "PaymentMessage", msg.getId().toString(), msg.getUetr());
        return msg;
    }

    private void persistInbound(PaymentMessage outbound, SwiftSendResult sent) {
        for (SwiftSendResult.InboundMx inbound : sent.inbound()) {
            if ("pacs.002".equals(inbound.messageType())) {
                PaymentMessage ack = new PaymentMessage();
                ack.setTenantId(outbound.getTenantId());
                ack.setMessageType("pacs.002");
                ack.setUetr(outbound.getUetr());
                ack.setDirection(MessageDirection.IN);
                ack.setStatus(MessageStatus.ACKED);
                ack.setInstructingAgentBic(outbound.getInstructedAgentBic());
                ack.setInstructedAgentBic(outbound.getInstructingAgentBic());
                ack.setAmount(outbound.getAmount());
                ack.setCurrency(outbound.getCurrency());
                ack.setDebtorName(outbound.getDebtorName());
                ack.setCreditorName(outbound.getCreditorName());
                ack.setDebtorTown(outbound.getDebtorTown());
                ack.setDebtorCountry(outbound.getDebtorCountry());
                ack.setCreditorTown(outbound.getCreditorTown());
                ack.setCreditorCountry(outbound.getCreditorCountry());
                ack.setMxXml(inbound.xml());
                ack.setXmlFrozen(true);
                ack.setScreeningStatus(ScreeningStatus.CLEAR);
                payments.save(ack);
                outbound.setStatus(MessageStatus.ACKED);
                gpiService.append(outbound.getTenantId(), outbound.getUetr(), "RECEIVED_BY_CORRESPONDENT",
                        outbound.getInstructedAgentBic(), "pacs.002 ACSC");
                gpiService.append(outbound.getTenantId(), outbound.getUetr(), "CREDITED",
                        outbound.getInstructedAgentBic(), "Stub tracker complete");
            } else if (inbound.messageType().startsWith("camt.")) {
                storeStatement(outbound, inbound);
            }
        }
    }

    /** Stage 3 (Report): a camt.05x notification lands on the tenant's shadow nostro for that payment. */
    private void storeStatement(PaymentMessage outbound, SwiftSendResult.InboundMx inbound) {
        CorrespondentAccount account = accounts.findFirstByTenantIdAndTypeAndCurrencyAndCorrespondentBic(
                        outbound.getTenantId(), AccountType.NOSTRO, outbound.getCurrency(), outbound.getInstructedAgentBic())
                .or(() -> accounts.findByTenantId(outbound.getTenantId()).stream().findFirst())
                .orElse(null);
        if (account == null) {
            return;
        }
        CashStatement statement = new CashStatement();
        statement.setTenantId(outbound.getTenantId());
        statement.setMessageType(inbound.messageType());
        statement.setAccountId(account.getId());
        statement.setMxXml(inbound.xml());
        statement.setRelatedPaymentId(outbound.getId());
        statement.setUetr(outbound.getUetr());
        statements.save(statement);
    }

    /** Copies the parties of the original payment onto a related message, flipping agents for inbound ones. */
    private static PaymentMessage relatedMessage(PaymentMessage original, String messageType, MessageDirection direction) {
        PaymentMessage msg = new PaymentMessage();
        msg.setTenantId(original.getTenantId());
        msg.setMessageType(messageType);
        msg.setUetr(original.getUetr());
        msg.setDirection(direction);
        boolean inbound = direction == MessageDirection.IN;
        msg.setInstructingAgentBic(inbound ? original.getInstructedAgentBic() : original.getInstructingAgentBic());
        msg.setInstructedAgentBic(inbound ? original.getInstructingAgentBic() : original.getInstructedAgentBic());
        msg.setAmount(original.getAmount());
        msg.setCurrency(original.getCurrency());
        msg.setDebtorName(original.getDebtorName());
        msg.setCreditorName(original.getCreditorName());
        msg.setDebtorTown(original.getDebtorTown());
        msg.setDebtorCountry(original.getDebtorCountry());
        msg.setCreditorTown(original.getCreditorTown());
        msg.setCreditorCountry(original.getCreditorCountry());
        msg.setChargeBearer(original.getChargeBearer());
        msg.setXmlFrozen(true);
        return msg;
    }

    public List<PaymentMessage> list(CurrentUser actor, MessageDirection direction) {
        UUID tenantId = actor.requireTenantId();
        if (direction == null) {
            return payments.findByTenantIdOrderByCreatedAtDesc(tenantId);
        }
        return payments.findByTenantIdAndDirectionOrderByCreatedAtDesc(tenantId, direction);
    }

    public PaymentMessage get(CurrentUser actor, UUID id) {
        return requireOwned(actor, id);
    }

    /** Backwards-compatible recall with the default CUST reason. */
    @Transactional
    public PaymentMessage recall(CurrentUser actor, UUID id) {
        return recall(actor, id, null);
    }

    /**
     * Stage 4 of the payment life cycle: camt.056 asks the correspondent to recall a sent payment.
     * The network answers with camt.029 (CNCL or RJCR). When the recall is accepted the correspondent
     * returns the funds with pacs.004, the shadow ledger debit is reversed and a camt.054 credit lands.
     * Every step is appended to the gpi tracker under the original UETR.
     */
    @Transactional
    public PaymentMessage recall(CurrentUser actor, UUID id, String reasonCode) {
        assertRole(actor, Role.PAYMENT_MAKER, Role.PAYMENT_CHECKER, Role.BANK_SECURITY_OFFICER);
        PaymentMessage original = requireOwned(actor, id);
        if (original.getDirection() != MessageDirection.OUT) {
            throw new ApiException(400, "DIRECTION", "Recall applies to outbound payments");
        }
        if (!"pacs.008".equals(original.getMessageType()) && !"pacs.009".equals(original.getMessageType())) {
            throw new ApiException(400, "MSG_TYPE", "Only a pacs.008 or pacs.009 can be recalled");
        }
        if (original.getStatus() == MessageStatus.RETURNED) {
            throw new ApiException(409, "STATE", "Payment was already returned");
        }
        if (original.getStatus() != MessageStatus.SENT && original.getStatus() != MessageStatus.ACKED) {
            throw new ApiException(409, "STATE", "Only a sent payment can be recalled");
        }
        String reason = reasonCode == null || reasonCode.isBlank() ? "CUST" : reasonCode.trim().toUpperCase();
        if (reason.length() > 8 || !reason.chars().allMatch(Character::isLetterOrDigit)) {
            throw new ApiException(400, "REASON", "Cancellation reason must be an ISO 20022 code such as CUST, DUPL or FRAD");
        }

        PaymentMessage recall = relatedMessage(original, "camt.056", MessageDirection.OUT);
        recall.setStatus(MessageStatus.SENT);
        recall.setReasonCode(reason);
        recall.setMxXml(MxMessageFactory.camt056(original, reason));
        recall.setMakerId(actor.id());
        recall.setSentAt(java.time.Instant.now());
        payments.save(recall);
        gpiService.append(original.getTenantId(), original.getUetr(), "RECALL_REQUESTED",
                original.getInstructingAgentBic(), "camt.056 " + reason);

        SwiftSendResult answer = swiftNetworkGateway.recall(original, recall);
        for (SwiftSendResult.InboundMx inbound : answer.inbound()) {
            switch (inbound.messageType()) {
                case "camt.029" -> {
                    PaymentMessage resolution = relatedMessage(original, "camt.029", MessageDirection.IN);
                    resolution.setStatus(MessageStatus.ACKED);
                    resolution.setReasonCode(inbound.statusCode());
                    resolution.setMxXml(inbound.xml());
                    payments.save(resolution);
                    recall.setStatus(MessageStatus.ACKED);
                    boolean cancelled = "CNCL".equals(inbound.statusCode());
                    gpiService.append(original.getTenantId(), original.getUetr(),
                            cancelled ? "RECALL_ACCEPTED" : "RECALL_REJECTED",
                            original.getInstructedAgentBic(), "camt.029 " + inbound.statusCode());
                }
                case "pacs.004" -> {
                    PaymentMessage paymentReturn = relatedMessage(original, "pacs.004", MessageDirection.IN);
                    paymentReturn.setStatus(MessageStatus.ACKED);
                    paymentReturn.setReasonCode(inbound.statusCode());
                    paymentReturn.setMxXml(inbound.xml());
                    payments.save(paymentReturn);
                    original.setStatus(MessageStatus.RETURNED);
                    ledgerService.postReturn(original, paymentReturn);
                    gpiService.append(original.getTenantId(), original.getUetr(), "RETURNED",
                            original.getInstructedAgentBic(), "pacs.004 " + inbound.statusCode() + " funds returned to nostro");
                }
                default -> {
                    if (inbound.messageType().startsWith("camt.")) {
                        storeStatement(original, inbound);
                    }
                }
            }
        }
        auditService.record(actor, "INVESTIGATION_RECALL", "PaymentMessage", original.getId().toString(),
                original.getUetr() + " " + reason + " -> " + original.getStatus());
        return recall;
    }

    private PaymentMessage requireOwned(CurrentUser actor, UUID id) {
        UUID tenantId = actor.bureau() ? payments.findById(id).map(PaymentMessage::getTenantId).orElse(null) : actor.requireTenantId();
        if (actor.bureau()) {
            throw new ApiException(403, "TENANT_ISOLATION", "Use customer users to operate payments");
        }
        return payments.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Payment not found"));
    }

    private void assertRole(CurrentUser actor, Role... allowed) {
        for (Role role : allowed) {
            if (actor.role() == role) {
                return;
            }
        }
        throw new ApiException(403, "FORBIDDEN", "Insufficient role");
    }

    public record CreatePaymentRequest(
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
            ChargeBearer chargeBearer,
            String templateId
    ) {
    }

    /** A customer's pain.001 as the bank receives it: parties are named the ISO way (debtor/creditor agent). */
    public record CustomerInstructionRequest(
            String debtorAgentBic,
            String creditorAgentBic,
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

    /**
     * Outcome of stage 1: the stored pain.001, the pain.002 sent back, and the pacs.008 draft
     * (null when rejected). {@code reasonCode} is ACCP or the ISO rejection reason on the pain.002.
     */
    public record InitiationResult(
            PaymentMessage instruction,
            PaymentMessage statusReport,
            PaymentMessage payment,
            boolean accepted,
            String reasonCode,
            String rejectionDetail
    ) {
    }
}
