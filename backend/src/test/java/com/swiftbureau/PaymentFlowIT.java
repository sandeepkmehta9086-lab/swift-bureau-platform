package com.swiftbureau;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftbureau.domain.AccountType;
import com.swiftbureau.domain.ChargeBearer;
import com.swiftbureau.domain.Role;
import com.swiftbureau.domain.RmaStatus;
import com.swiftbureau.domain.TenantStatus;
import com.swiftbureau.domain.UserStatus;
import com.swiftbureau.identity.UserAccount;
import com.swiftbureau.identity.UserAccountRepository;
import com.swiftbureau.ledger.LedgerService;
import com.swiftbureau.rma.RmaRelationship;
import com.swiftbureau.rma.RmaRelationshipRepository;
import com.swiftbureau.correspondent.CorrespondentAccount;
import com.swiftbureau.correspondent.CorrespondentAccountRepository;
import com.swiftbureau.screening.WatchlistEntry;
import com.swiftbureau.screening.WatchlistEntryRepository;
import com.swiftbureau.tenant.BankBic;
import com.swiftbureau.tenant.BankBicRepository;
import com.swiftbureau.tenant.BankTenant;
import com.swiftbureau.tenant.BankTenantRepository;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentFlowIT {

    private static final String SECRET = "JBSWY3DPEHPK3PXP";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    UserAccountRepository users;
    @Autowired
    BankTenantRepository tenants;
    @Autowired
    BankBicRepository bics;
    @Autowired
    RmaRelationshipRepository rma;
    @Autowired
    CorrespondentAccountRepository accounts;
    @Autowired
    WatchlistEntryRepository watchlist;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    LedgerService ledger;

    private UUID tenantA;
    private UUID tenantB;
    private String makerToken;
    private String checkerToken;
    private String makerBToken;
    private String soToken;
    private int suffix;

    @BeforeEach
    void setup() throws Exception {
        int n = SEQ.incrementAndGet();
        tenantA = seedBank("Alpha Bank " + n, "ALPHGB2L", "CHASUS33", n + "a");
        tenantB = seedBank("Beta Bank " + n, "BETAGB2L", "CHASUS33", n + "b");
        makerToken = login("maker." + n + "a");
        checkerToken = login("checker." + n + "a");
        makerBToken = login("maker." + n + "b");
        soToken = login("so." + n + "a");
        suffix = n;
        if (watchlist.findAll().stream().noneMatch(w -> "SANCTIONED PERSON".equalsIgnoreCase(w.getNamePattern()))) {
            WatchlistEntry entry = new WatchlistEntry();
            entry.setNamePattern("SANCTIONED PERSON");
            entry.setReason("test");
            watchlist.save(entry);
        }
    }

    @Test
    void goldenPathCreateSubmitApproveAckAndGpi() throws Exception {
        String body = paymentJson("Acme Ltd", "Widget LLC");
        MvcResult created = mvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + makerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uetr").exists())
                .andExpect(jsonPath("$.mxXml", containsString("UETR")))
                .andReturn();
        JsonNode payment = mapper.readTree(created.getResponse().getContentAsString());
        String id = payment.get("id").asText();
        String uetr = payment.get("uetr").asText();

        mvc.perform(post("/api/payments/" + id + "/submit").header("Authorization", "Bearer " + makerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CHECK"));

        mvc.perform(post("/api/payments/" + id + "/approve")
                        .header("Authorization", "Bearer " + makerToken)
                        .header("X-Step-Up-Totp", totp()))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/payments/" + id + "/approve")
                        .header("Authorization", "Bearer " + checkerToken)
                        .header("X-Step-Up-Totp", totp()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKED"))
                .andExpect(jsonPath("$.xmlFrozen").value(true))
                .andExpect(jsonPath("$.uetr").value(uetr));

        mvc.perform(get("/api/inbox").header("Authorization", "Bearer " + makerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].messageType").value("pacs.002"));

        mvc.perform(get("/api/gpi/" + uetr).header("Authorization", "Bearer " + makerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hopStatus").value("INITIATED"));
    }

    @Test
    void scenario1CustomerInstructionAcceptedRunsThroughClearingAndReporting() throws Exception {
        // Stage 1: pain.001 in, pain.002 ACCP out, pacs.008 drafted on the same UETR.
        MvcResult initiated = mvc.perform(post("/api/payments/initiate")
                        .header("Authorization", "Bearer " + makerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(instructionJson("Acme Ltd", "London")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.reasonCode").value("ACCP"))
                .andExpect(jsonPath("$.instruction.messageType").value("pain.001"))
                .andExpect(jsonPath("$.instruction.direction").value("IN"))
                .andExpect(jsonPath("$.instruction.status").value("ACKED"))
                .andExpect(jsonPath("$.instruction.mxXml", containsString("pain.001.001.09")))
                .andExpect(jsonPath("$.statusReport.messageType").value("pain.002"))
                .andExpect(jsonPath("$.statusReport.direction").value("OUT"))
                .andExpect(jsonPath("$.statusReport.mxXml", containsString("<GrpSts>ACCP</GrpSts>")))
                .andExpect(jsonPath("$.payment.messageType").value("pacs.008"))
                .andExpect(jsonPath("$.payment.status").value("DRAFT"))
                .andReturn();
        JsonNode result = mapper.readTree(initiated.getResponse().getContentAsString());
        String uetr = result.get("instruction").get("uetr").asText();
        assertEquals(uetr, result.get("statusReport").get("uetr").asText());
        assertEquals(uetr, result.get("payment").get("uetr").asText());
        String paymentId = result.get("payment").get("id").asText();

        // Stage 2: clear via maker submit + checker release; stub answers pacs.002.
        mvc.perform(post("/api/payments/" + paymentId + "/submit").header("Authorization", "Bearer " + makerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CHECK"));
        mvc.perform(post("/api/payments/" + paymentId + "/approve")
                        .header("Authorization", "Bearer " + checkerToken)
                        .header("X-Step-Up-Totp", totp()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKED"))
                .andExpect(jsonPath("$.uetr").value(uetr));

        // Stage 3: camt.054 debit notification sits on the shadow nostro.
        MvcResult statements = mvc.perform(get("/api/statements").header("Authorization", "Bearer " + makerToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode stmts = mapper.readTree(statements.getResponse().getContentAsString());
        assertTrue(stream(stmts).anyMatch(s -> "camt.054".equals(s.path("messageType").asText())
                && uetr.equals(s.path("uetr").asText())
                && s.path("mxXml").asText().contains("<CdtDbtInd>DBIT</CdtDbtInd>")));

        // Every message of the journey shares the UETR and the tracker shows the whole story in order.
        assertEquals(List.of("CUSTOMER_INSTRUCTED", "CUSTOMER_ACCEPTED", "INITIATED", "SENT_TO_SWIFT",
                "RECEIVED_BY_CORRESPONDENT", "CREDITED"), hops(uetr, makerToken));
        List<String> types = messageTypes(uetr, makerToken);
        assertTrue(types.containsAll(List.of("pain.001", "pain.002", "pacs.008", "pacs.002")), types.toString());
    }

    @Test
    void scenario1RejectedInstructionGetsPain002RjctAndNoPacs008() throws Exception {
        MvcResult initiated = mvc.perform(post("/api/payments/initiate")
                        .header("Authorization", "Bearer " + makerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(instructionJson("Acme Ltd", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.reasonCode").value("BE04"))
                .andExpect(jsonPath("$.payment").doesNotExist())
                .andExpect(jsonPath("$.instruction.status").value("REJECTED"))
                .andExpect(jsonPath("$.statusReport.reasonCode").value("BE04"))
                .andExpect(jsonPath("$.statusReport.mxXml", containsString("<TxSts>RJCT</TxSts>")))
                .andExpect(jsonPath("$.statusReport.mxXml", containsString("<Cd>BE04</Cd>")))
                .andReturn();
        String uetr = mapper.readTree(initiated.getResponse().getContentAsString()).get("instruction").get("uetr").asText();

        assertEquals(List.of("CUSTOMER_INSTRUCTED", "CUSTOMER_REJECTED"), hops(uetr, makerToken));
        List<String> types = messageTypes(uetr, makerToken);
        assertEquals(List.of("pain.001", "pain.002"), types.stream().sorted().toList());

        // Malformed instructions (not storable) are a channel error, not a pain.002.
        mvc.perform(post("/api/payments/initiate")
                        .header("Authorization", "Bearer " + makerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(instructionJson("Acme Ltd", "London").replace("1500.00", "null")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/payments/initiate")
                        .header("Authorization", "Bearer " + checkerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(instructionJson("Acme Ltd", "London")))
                .andExpect(status().isForbidden());
    }

    @Test
    void scenario1RecallRejectedByCorrespondentLeavesFundsWithBeneficiary() throws Exception {
        String id = releasedPayment();
        String uetr = uetrOf(id);
        UUID nostro = accounts.findByTenantId(tenantA).get(0).getId();
        BigDecimal afterSend = ledger.bookedBalance(tenantA, nostro);
        assertEquals(0, new BigDecimal("-1500.00").compareTo(afterSend));

        mvc.perform(post("/api/payments/" + id + "/recall").header("Authorization", "Bearer " + makerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageType").value("camt.056"))
                .andExpect(jsonPath("$.reasonCode").value("CUST"))
                .andExpect(jsonPath("$.status").value("ACKED"))
                .andExpect(jsonPath("$.uetr").value(uetr))
                .andExpect(jsonPath("$.mxXml", containsString("<Cd>CUST</Cd>")));

        mvc.perform(get("/api/payments/" + id).header("Authorization", "Bearer " + makerToken))
                .andExpect(jsonPath("$.status").value("ACKED"));
        List<String> types = messageTypes(uetr, makerToken);
        assertTrue(types.contains("camt.029"), types.toString());
        assertTrue(!types.contains("pacs.004"), types.toString());
        assertEquals("RJCR", inbound(uetr, "camt.029", makerToken).get("reasonCode").asText());
        assertEquals(List.of("INITIATED", "SENT_TO_SWIFT", "RECEIVED_BY_CORRESPONDENT", "CREDITED",
                "RECALL_REQUESTED", "RECALL_REJECTED"), hops(uetr, makerToken));
        assertEquals(0, afterSend.compareTo(ledger.bookedBalance(tenantA, nostro)));
    }

    @Test
    void scenario1RecallAcceptedReturnsFundsWithPacs004() throws Exception {
        String id = releasedPayment();
        String uetr = uetrOf(id);
        UUID nostro = accounts.findByTenantId(tenantA).get(0).getId();

        mvc.perform(post("/api/payments/" + id + "/recall")
                        .header("Authorization", "Bearer " + makerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"dupl\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageType").value("camt.056"))
                .andExpect(jsonPath("$.reasonCode").value("DUPL"))
                .andExpect(jsonPath("$.mxXml", containsString("<Cd>DUPL</Cd>")));

        mvc.perform(get("/api/payments/" + id).header("Authorization", "Bearer " + makerToken))
                .andExpect(jsonPath("$.status").value("RETURNED"))
                .andExpect(jsonPath("$.uetr").value(uetr));
        assertEquals("CNCL", inbound(uetr, "camt.029", makerToken).get("reasonCode").asText());
        JsonNode pacs004 = inbound(uetr, "pacs.004", makerToken);
        assertEquals("FOCR", pacs004.get("reasonCode").asText());
        assertEquals("CHASUS33", pacs004.get("instructingAgentBic").asText());
        assertTrue(pacs004.get("mxXml").asText().contains("<RtrdIntrBkSttlmAmt Ccy=\"USD\">1500.00</RtrdIntrBkSttlmAmt>"));

        // Shadow ledger debit reversed and a camt.054 credit notification stored.
        assertEquals(0, BigDecimal.ZERO.compareTo(ledger.bookedBalance(tenantA, nostro)));
        JsonNode stmts = mapper.readTree(mvc.perform(get("/api/statements").header("Authorization", "Bearer " + makerToken))
                .andReturn().getResponse().getContentAsString());
        assertTrue(stream(stmts).anyMatch(s -> uetr.equals(s.path("uetr").asText())
                && s.path("mxXml").asText().contains("<CdtDbtInd>CRDT</CdtDbtInd>")));

        assertEquals(List.of("INITIATED", "SENT_TO_SWIFT", "RECEIVED_BY_CORRESPONDENT", "CREDITED",
                "RECALL_REQUESTED", "RECALL_ACCEPTED", "RETURNED"), hops(uetr, makerToken));

        // A returned payment cannot be recalled twice.
        mvc.perform(post("/api/payments/" + id + "/recall").header("Authorization", "Bearer " + makerToken))
                .andExpect(status().isConflict());
    }

    @Test
    void recallNeedsASentPaymentAndCustomerUser() throws Exception {
        MvcResult created = mvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + makerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("Acme Ltd", "Widget LLC")))
                .andExpect(status().isOk())
                .andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/payments/" + id + "/recall").header("Authorization", "Bearer " + makerToken))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/payments/" + id + "/recall").header("Authorization", "Bearer " + makerBToken))
                .andExpect(status().isNotFound());
        String released = releasedPayment();
        mvc.perform(post("/api/payments/" + released + "/recall")
                        .header("Authorization", "Bearer " + makerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"not a code\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tenantIsolation() throws Exception {
        MvcResult created = mvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + makerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("Acme Ltd", "Widget LLC")))
                .andExpect(status().isOk())
                .andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/payments/" + id).header("Authorization", "Bearer " + makerBToken))
                .andExpect(status().isNotFound());
        assertNotEquals(tenantA, tenantB);
    }

    @Test
    void screeningHitBlocksRelease() throws Exception {
        MvcResult created = mvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + makerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("SANCTIONED PERSON", "Widget LLC")))
                .andExpect(status().isOk())
                .andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/payments/" + id + "/submit").header("Authorization", "Bearer " + makerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCREENING_HIT"));
        mvc.perform(post("/api/payments/" + id + "/approve")
                        .header("Authorization", "Bearer " + checkerToken)
                        .header("X-Step-Up-Totp", totp()))
                .andExpect(status().isConflict());
    }

    @Test
    void bureauCannotReleaseCustomerPayment() throws Exception {
        UserAccount ops = new UserAccount();
        ops.setLoginId("ops." + SEQ.incrementAndGet());
        ops.setEmail("ops@test.local");
        ops.setRole(Role.BUREAU_OPERATOR);
        ops.setStatus(UserStatus.ACTIVE);
        ops.setPasswordHash(encoder.encode("ChangeMe_Bureau1!"));
        ops.setMfaSecret(SECRET);
        ops.setMfaEnabled(true);
        users.save(ops);
        String opsToken = login(ops.getLoginId(), "ChangeMe_Bureau1!");
        MvcResult created = mvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + makerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("Acme Ltd", "Widget LLC")))
                .andExpect(status().isOk())
                .andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/payments/" + id + "/submit").header("Authorization", "Bearer " + makerToken))
                .andExpect(status().isOk());
        mvc.perform(post("/api/payments/" + id + "/approve")
                        .header("Authorization", "Bearer " + opsToken)
                        .header("X-Step-Up-Totp", totp()))
                .andExpect(status().isForbidden());
        assertEquals(Role.BUREAU_OPERATOR, ops.getRole());
    }

    @Test
    void templateApplyCreatesDraftWithNewUetr() throws Exception {
        String name = "USD-CHAS-WIDGET-" + suffix;
        MvcResult createdTpl = mvc.perform(post("/api/templates")
                        .header("Authorization", "Bearer " + soToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson(name, "ALPHGB2L")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name))
                .andReturn();
        String templateId = mapper.readTree(createdTpl.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(post("/api/templates")
                        .header("Authorization", "Bearer " + makerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(templateJson(name + "-maker", "ALPHGB2L")))
                .andExpect(status().isForbidden());

        MvcResult draft = mvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + makerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateId":"%s","amount":2500.00}
                                """.formatted(templateId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.instructedAgentBic").value("CHASUS33"))
                .andExpect(jsonPath("$.uetr").exists())
                .andExpect(jsonPath("$.mxXml", containsString("UETR")))
                .andReturn();
        String uetr = mapper.readTree(draft.getResponse().getContentAsString()).get("uetr").asText();

        MvcResult second = mvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + makerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateId":"%s","amount":100.00}
                                """.formatted(templateId)))
                .andExpect(status().isOk())
                .andReturn();
        String uetr2 = mapper.readTree(second.getResponse().getContentAsString()).get("uetr").asText();
        assertNotEquals(uetr, uetr2);

        MvcResult listed = mvc.perform(get("/api/templates").header("Authorization", "Bearer " + makerBToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode templates = mapper.readTree(listed.getResponse().getContentAsString());
        for (JsonNode node : templates) {
            assertNotEquals(templateId, node.path("id").asText());
        }
    }

    private UUID seedBank(String name, String bic, String corr, String suffix) {
        BankTenant tenant = new BankTenant();
        tenant.setLegalName(name);
        tenant.setCountry("GB");
        tenant.setStatus(TenantStatus.ACTIVE);
        tenants.save(tenant);
        BankBic row = new BankBic();
        row.setTenantId(tenant.getId());
        row.setBic(bic);
        row.setPrimaryBic(true);
        bics.save(row);
        RmaRelationship rel = new RmaRelationship();
        rel.setTenantId(tenant.getId());
        rel.setOurBic(bic);
        rel.setCounterpartyBic(corr);
        rel.setStatus(RmaStatus.ACTIVE);
        rma.save(rel);
        CorrespondentAccount nostro = new CorrespondentAccount();
        nostro.setTenantId(tenant.getId());
        nostro.setType(AccountType.NOSTRO);
        nostro.setCurrency("USD");
        nostro.setAccountRef("N-" + suffix);
        nostro.setCorrespondentBic(corr);
        nostro.setLabel("USD nostro");
        nostro.setStatementBalance(new BigDecimal("1000000"));
        accounts.save(nostro);
        seedUser(tenant.getId(), "maker." + suffix, Role.PAYMENT_MAKER);
        seedUser(tenant.getId(), "checker." + suffix, Role.PAYMENT_CHECKER);
        seedUser(tenant.getId(), "so." + suffix, Role.BANK_SECURITY_OFFICER);
        return tenant.getId();
    }

    private void seedUser(UUID tenantId, String login, Role role) {
        UserAccount user = new UserAccount();
        user.setTenantId(tenantId);
        user.setLoginId(login);
        user.setEmail(login + "@test.local");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash(encoder.encode("ChangeMe_Bank1!"));
        user.setMfaSecret(SECRET);
        user.setMfaEnabled(true);
        users.save(user);
    }

    private String login(String loginId) throws Exception {
        return login(loginId, "ChangeMe_Bank1!");
    }

    private String login(String loginId, String password) throws Exception {
        String payload = """
                {"loginId":"%s","password":"%s","totp":"%s"}
                """.formatted(loginId, password, totp());
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String totp() throws Exception {
        // DefaultCodeVerifier checks generate(secret, floor(now / period)), so the counter is the 30s bucket, not raw seconds.
        long bucket = Math.floorDiv(new SystemTimeProvider().getTime(), 30);
        return new DefaultCodeGenerator().generate(SECRET, bucket);
    }

    private String templateJson(String name, String instructingBic) {
        return """
                {
                  "name":"%s",
                  "description":"test template",
                  "messageType":"pacs.008",
                  "instructingAgentBic":"%s",
                  "instructedAgentBic":"CHASUS33",
                  "currency":"USD",
                  "debtorName":"Acme Ltd",
                  "debtorStreet":"1 King",
                  "debtorTown":"London",
                  "debtorCountry":"GB",
                  "creditorName":"Widget LLC",
                  "creditorStreet":"200 West",
                  "creditorTown":"New York",
                  "creditorCountry":"US",
                  "chargeBearer":"SHAR"
                }
                """.formatted(name, instructingBic);
    }

    /** Runs the golden path (create → submit → approve) and returns the released pacs.008 id. */
    private String releasedPayment() throws Exception {
        MvcResult created = mvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + makerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("Acme Ltd", "Widget LLC")))
                .andExpect(status().isOk())
                .andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/payments/" + id + "/submit").header("Authorization", "Bearer " + makerToken))
                .andExpect(status().isOk());
        mvc.perform(post("/api/payments/" + id + "/approve")
                        .header("Authorization", "Bearer " + checkerToken)
                        .header("X-Step-Up-Totp", totp()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKED"));
        return id;
    }

    private String uetrOf(String id) throws Exception {
        MvcResult res = mvc.perform(get("/api/payments/" + id).header("Authorization", "Bearer " + makerToken))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(res.getResponse().getContentAsString()).get("uetr").asText();
    }

    private List<String> hops(String uetr, String token) throws Exception {
        MvcResult res = mvc.perform(get("/api/gpi/" + uetr).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return stream(mapper.readTree(res.getResponse().getContentAsString()))
                .map(h -> h.get("hopStatus").asText())
                .toList();
    }

    /** Message types of every stored message (any direction) sharing the UETR. */
    private List<String> messageTypes(String uetr, String token) throws Exception {
        MvcResult res = mvc.perform(get("/api/payments").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return stream(mapper.readTree(res.getResponse().getContentAsString()))
                .filter(p -> uetr.equals(p.get("uetr").asText()))
                .map(p -> p.get("messageType").asText())
                .toList();
    }

    private JsonNode inbound(String uetr, String messageType, String token) throws Exception {
        MvcResult res = mvc.perform(get("/api/inbox").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return stream(mapper.readTree(res.getResponse().getContentAsString()))
                .filter(p -> uetr.equals(p.get("uetr").asText()) && messageType.equals(p.get("messageType").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No inbound " + messageType + " for " + uetr));
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }

    private String instructionJson(String debtor, String debtorTown) {
        return """
                {
                  "debtorAgentBic":"ALPHGB2L",
                  "creditorAgentBic":"CHASUS33",
                  "amount":1500.00,
                  "currency":"USD",
                  "debtorName":"%s",
                  "debtorStreet":"1 King",
                  "debtorTown":"%s",
                  "debtorCountry":"GB",
                  "creditorName":"Widget LLC",
                  "creditorStreet":"200 West",
                  "creditorTown":"New York",
                  "creditorCountry":"US",
                  "chargeBearer":"SHAR"
                }
                """.formatted(debtor, debtorTown);
    }

    private String paymentJson(String debtor, String creditor) {
        return """
                {
                  "messageType":"pacs.008",
                  "instructingAgentBic":"ALPHGB2L",
                  "instructedAgentBic":"CHASUS33",
                  "amount":1500.00,
                  "currency":"USD",
                  "debtorName":"%s",
                  "debtorStreet":"1 King",
                  "debtorTown":"London",
                  "debtorCountry":"GB",
                  "creditorName":"%s",
                  "creditorStreet":"200 West",
                  "creditorTown":"New York",
                  "creditorCountry":"US",
                  "chargeBearer":"SHAR"
                }
                """.formatted(debtor, creditor);
    }
}
