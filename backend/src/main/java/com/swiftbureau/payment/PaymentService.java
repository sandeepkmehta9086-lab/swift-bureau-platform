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
            CashStatementRepository statements
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
    }

    @Transactional
    public PaymentMessage create(CurrentUser actor, CreatePaymentRequest req) {
        assertRole(actor, Role.PAYMENT_MAKER, Role.BANK_SECURITY_OFFICER);
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
        msg.setTenantId(tenantId);
        msg.setMessageType(req.messageType());
        msg.setUetr(MxMessageFactory.newUetr());
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
        payments.save(msg);
        gpiService.append(tenantId, msg.getUetr(), "INITIATED", msg.getInstructingAgentBic(), "Created by maker");
        auditService.record(actor, "PAYMENT_CREATED", "PaymentMessage", msg.getId().toString(), msg.getUetr());
        return msg;
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
                CorrespondentAccount account = accounts.findFirstByTenantIdAndTypeAndCurrencyAndCorrespondentBic(
                                outbound.getTenantId(), AccountType.NOSTRO, outbound.getCurrency(), outbound.getInstructedAgentBic())
                        .or(() -> accounts.findByTenantId(outbound.getTenantId()).stream().findFirst())
                        .orElse(null);
                if (account != null) {
                    CashStatement statement = new CashStatement();
                    statement.setTenantId(outbound.getTenantId());
                    statement.setMessageType(inbound.messageType());
                    statement.setAccountId(account.getId());
                    statement.setMxXml(inbound.xml());
                    statement.setRelatedPaymentId(outbound.getId());
                    statement.setUetr(outbound.getUetr());
                    statements.save(statement);
                }
            }
        }
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

    @Transactional
    public PaymentMessage recall(CurrentUser actor, UUID id) {
        assertRole(actor, Role.PAYMENT_MAKER, Role.PAYMENT_CHECKER, Role.BANK_SECURITY_OFFICER);
        PaymentMessage original = requireOwned(actor, id);
        if (original.getDirection() != MessageDirection.OUT) {
            throw new ApiException(400, "DIRECTION", "Recall applies to outbound payments");
        }
        PaymentMessage recall = new PaymentMessage();
        recall.setTenantId(original.getTenantId());
        recall.setMessageType("camt.056");
        recall.setUetr(original.getUetr());
        recall.setDirection(MessageDirection.OUT);
        recall.setStatus(MessageStatus.SENT);
        recall.setInstructingAgentBic(original.getInstructingAgentBic());
        recall.setInstructedAgentBic(original.getInstructedAgentBic());
        recall.setAmount(original.getAmount());
        recall.setCurrency(original.getCurrency());
        recall.setDebtorName(original.getDebtorName());
        recall.setCreditorName(original.getCreditorName());
        recall.setDebtorTown(original.getDebtorTown());
        recall.setDebtorCountry(original.getDebtorCountry());
        recall.setCreditorTown(original.getCreditorTown());
        recall.setCreditorCountry(original.getCreditorCountry());
        recall.setMxXml(MxMessageFactory.camt056(original));
        recall.setXmlFrozen(true);
        recall.setMakerId(actor.id());
        payments.save(recall);
        PaymentMessage resolution = new PaymentMessage();
        resolution.setTenantId(original.getTenantId());
        resolution.setMessageType("camt.029");
        resolution.setUetr(original.getUetr());
        resolution.setDirection(MessageDirection.IN);
        resolution.setStatus(MessageStatus.ACKED);
        resolution.setInstructingAgentBic(original.getInstructedAgentBic());
        resolution.setInstructedAgentBic(original.getInstructingAgentBic());
        resolution.setAmount(original.getAmount());
        resolution.setCurrency(original.getCurrency());
        resolution.setDebtorName(original.getDebtorName());
        resolution.setCreditorName(original.getCreditorName());
        resolution.setDebtorTown(original.getDebtorTown());
        resolution.setDebtorCountry(original.getDebtorCountry());
        resolution.setCreditorTown(original.getCreditorTown());
        resolution.setCreditorCountry(original.getCreditorCountry());
        resolution.setMxXml(MxMessageFactory.camt029(original, "RJCR"));
        resolution.setXmlFrozen(true);
        payments.save(resolution);
        auditService.record(actor, "INVESTIGATION_RECALL", "PaymentMessage", original.getId().toString(), original.getUetr());
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
            ChargeBearer chargeBearer
    ) {
    }
}
