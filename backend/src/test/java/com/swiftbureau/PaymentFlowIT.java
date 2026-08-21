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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    private UUID tenantA;
    private UUID tenantB;
    private String makerToken;
    private String checkerToken;
    private String makerBToken;

    @BeforeEach
    void setup() throws Exception {
        int n = SEQ.incrementAndGet();
        tenantA = seedBank("Alpha Bank " + n, "ALPHGB2L", "CHASUS33", n + "a");
        tenantB = seedBank("Beta Bank " + n, "BETAGB2L", "CHASUS33", n + "b");
        makerToken = login("maker." + n + "a");
        checkerToken = login("checker." + n + "a");
        makerBToken = login("maker." + n + "b");
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
        long time = new SystemTimeProvider().getTime();
        return new DefaultCodeGenerator().generate(SECRET, time);
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
