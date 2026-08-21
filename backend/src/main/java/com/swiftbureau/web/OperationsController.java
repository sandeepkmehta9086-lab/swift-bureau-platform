package com.swiftbureau.web;

import com.swiftbureau.audit.AuditEvent;
import com.swiftbureau.audit.AuditEventRepository;
import com.swiftbureau.charge.ChargeSchedule;
import com.swiftbureau.charge.ChargeService;
import com.swiftbureau.correspondent.CashStatement;
import com.swiftbureau.correspondent.CorrespondentAccount;
import com.swiftbureau.correspondent.CorrespondentService;
import com.swiftbureau.csp.CspControlAttestation;
import com.swiftbureau.csp.CspService;
import com.swiftbureau.domain.AccountType;
import com.swiftbureau.domain.AttestationStatus;
import com.swiftbureau.domain.ChargeBearer;
import com.swiftbureau.gpi.GpiEvent;
import com.swiftbureau.gpi.GpiService;
import com.swiftbureau.reporting.ReportingService;
import com.swiftbureau.rma.RmaRelationship;
import com.swiftbureau.rma.RmaService;
import com.swiftbureau.screening.WatchlistEntry;
import com.swiftbureau.screening.WatchlistEntryRepository;
import com.swiftbureau.security.CurrentUser;
import com.swiftbureau.security.SecurityUtil;
import com.swiftbureau.tenant.TenantService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class OperationsController {

    private final RmaService rmaService;
    private final CorrespondentService correspondentService;
    private final ChargeService chargeService;
    private final GpiService gpiService;
    private final CspService cspService;
    private final ReportingService reportingService;
    private final AuditEventRepository auditEvents;
    private final WatchlistEntryRepository watchlist;

    public OperationsController(
            RmaService rmaService,
            CorrespondentService correspondentService,
            ChargeService chargeService,
            GpiService gpiService,
            CspService cspService,
            ReportingService reportingService,
            AuditEventRepository auditEvents,
            WatchlistEntryRepository watchlist
    ) {
        this.rmaService = rmaService;
        this.correspondentService = correspondentService;
        this.chargeService = chargeService;
        this.gpiService = gpiService;
        this.cspService = cspService;
        this.reportingService = reportingService;
        this.auditEvents = auditEvents;
        this.watchlist = watchlist;
    }

    @GetMapping("/rma")
    public List<RmaRelationship> rma() {
        return rmaService.list(SecurityUtil.current());
    }

    @PostMapping("/rma")
    public RmaRelationship createRma(@RequestBody RmaRequest request) {
        return rmaService.create(SecurityUtil.current(), request.ourBic(), request.counterpartyBic());
    }

    @PostMapping("/rma/{id}/activate")
    public RmaRelationship activateRma(@PathVariable UUID id) {
        return rmaService.activate(SecurityUtil.current(), id);
    }

    @PostMapping("/rma/{id}/revoke")
    public RmaRelationship revokeRma(@PathVariable UUID id) {
        return rmaService.revoke(SecurityUtil.current(), id);
    }

    @GetMapping("/accounts")
    public List<CorrespondentAccount> accounts() {
        return correspondentService.list(SecurityUtil.current());
    }

    @PostMapping("/accounts")
    public CorrespondentAccount createAccount(@RequestBody AccountRequest request) {
        return correspondentService.create(SecurityUtil.current(), request.type(), request.currency(),
                request.accountRef(), request.correspondentBic(), request.label());
    }

    @PostMapping("/accounts/{id}/statements")
    public CashStatement ingest(@PathVariable UUID id, @RequestBody StatementRequest request) {
        return correspondentService.ingestStatement(SecurityUtil.current(), id, request.messageType(), request.statementBalance());
    }

    @GetMapping("/statements")
    public List<CashStatement> statements() {
        return correspondentService.statements(SecurityUtil.current());
    }

    @GetMapping("/liquidity")
    public List<Map<String, Object>> liquidity() {
        return correspondentService.liquidity(SecurityUtil.current());
    }

    @GetMapping("/charges")
    public List<ChargeSchedule> charges() {
        return chargeService.list(SecurityUtil.current().requireTenantId());
    }

    @PostMapping("/charges")
    public ChargeSchedule createCharge(@RequestBody ChargeRequest request) {
        return chargeService.create(SecurityUtil.current().requireTenantId(), request.fromBic(), request.toBic(),
                request.currency(), request.feeAmount(), request.defaultBearer());
    }

    @PostMapping("/charges/quote")
    public Map<String, Object> quote(@RequestBody QuoteRequest request) {
        return chargeService.quote(SecurityUtil.current().requireTenantId(), request.fromBic(), request.toBic(),
                request.currency(), request.bearer(), request.amount());
    }

    @GetMapping("/gpi/{uetr}")
    public List<GpiEvent> gpi(@PathVariable String uetr) {
        return gpiService.track(SecurityUtil.current().requireTenantId(), uetr);
    }

    @GetMapping("/audit")
    public List<AuditEvent> audit() {
        CurrentUser actor = SecurityUtil.current();
        if (actor.bureau()) {
            return auditEvents.findAllByOrderByCreatedAtDesc();
        }
        return auditEvents.findByTenantIdOrderByCreatedAtDesc(actor.requireTenantId());
    }

    @GetMapping("/reports/stp")
    public Map<String, Object> stp() {
        return reportingService.stp(SecurityUtil.current());
    }

    @GetMapping("/csp/controls")
    public List<Map<String, Object>> cspControls() {
        TenantService.requireBureau(SecurityUtil.current());
        return cspService.controls();
    }

    @PostMapping("/csp/attest")
    public CspControlAttestation attest(@RequestBody AttestRequest request) {
        return cspService.attest(SecurityUtil.current(), request.controlId(), request.status(), request.evidenceNotes());
    }

    @PostMapping("/csp/confirm/{controlId}")
    public CspControlAttestation confirm(@PathVariable String controlId) {
        return cspService.confirm(SecurityUtil.current(), controlId);
    }

    @GetMapping("/csp/pack")
    public Map<String, Object> pack() {
        TenantService.requireBureau(SecurityUtil.current());
        return cspService.pack();
    }

    @GetMapping("/csp/configuration-report")
    public Map<String, Object> configReport() {
        TenantService.requireBureau(SecurityUtil.current());
        return cspService.configurationReport();
    }

    @GetMapping("/screening/watchlist")
    public List<WatchlistEntry> watchlist() {
        TenantService.requireBureau(SecurityUtil.current());
        return watchlist.findAll();
    }

    @PostMapping("/screening/watchlist")
    public WatchlistEntry addWatch(@RequestBody WatchRequest request) {
        TenantService.requireBureau(SecurityUtil.current());
        WatchlistEntry entry = new WatchlistEntry();
        entry.setNamePattern(request.namePattern());
        entry.setReason(request.reason());
        return watchlist.save(entry);
    }

    public record RmaRequest(String ourBic, String counterpartyBic) {
    }

    public record AccountRequest(AccountType type, String currency, String accountRef, String correspondentBic, String label) {
    }

    public record StatementRequest(String messageType, BigDecimal statementBalance) {
    }

    public record ChargeRequest(String fromBic, String toBic, String currency, BigDecimal feeAmount, ChargeBearer defaultBearer) {
    }

    public record QuoteRequest(String fromBic, String toBic, String currency, ChargeBearer bearer, BigDecimal amount) {
    }

    public record AttestRequest(String controlId, AttestationStatus status, String evidenceNotes) {
    }

    public record WatchRequest(String namePattern, String reason) {
    }
}
