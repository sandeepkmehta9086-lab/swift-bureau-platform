package com.swiftbureau.bootstrap;

import com.swiftbureau.charge.ChargeSchedule;
import com.swiftbureau.charge.ChargeScheduleRepository;
import com.swiftbureau.config.AppProperties;
import com.swiftbureau.correspondent.CorrespondentAccount;
import com.swiftbureau.correspondent.CorrespondentAccountRepository;
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
import com.swiftbureau.screening.WatchlistEntry;
import com.swiftbureau.screening.WatchlistEntryRepository;
import com.swiftbureau.tenant.BankBic;
import com.swiftbureau.tenant.BankBicRepository;
import com.swiftbureau.tenant.BankTenant;
import com.swiftbureau.tenant.BankTenantRepository;
import com.swiftbureau.template.MessageTemplate;
import com.swiftbureau.template.MessageTemplateRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Order(1)
public class DevDataSeeder implements ApplicationRunner {

    private final AppProperties properties;
    private final UserAccountRepository users;
    private final BankTenantRepository tenants;
    private final BankBicRepository bics;
    private final RmaRelationshipRepository rma;
    private final CorrespondentAccountRepository accounts;
    private final ChargeScheduleRepository charges;
    private final WatchlistEntryRepository watchlist;
    private final MessageTemplateRepository templates;
    private final PasswordEncoder encoder;

    public DevDataSeeder(
            AppProperties properties,
            UserAccountRepository users,
            BankTenantRepository tenants,
            BankBicRepository bics,
            RmaRelationshipRepository rma,
            CorrespondentAccountRepository accounts,
            ChargeScheduleRepository charges,
            WatchlistEntryRepository watchlist,
            MessageTemplateRepository templates,
            PasswordEncoder encoder
    ) {
        this.properties = properties;
        this.users = users;
        this.tenants = tenants;
        this.bics = bics;
        this.rma = rma;
        this.accounts = accounts;
        this.charges = charges;
        this.watchlist = watchlist;
        this.templates = templates;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getSeed().isEnabled() || users.countByRole(Role.BUREAU_OPERATOR) > 0) {
            return;
        }
        String totp = properties.getSecurity().getDemoTotpSecret();
        String bureauHash = encoder.encode("ChangeMe_Bureau1!");
        String bankHash = encoder.encode("ChangeMe_Bank1!");

        seedUser(null, "bureau.ops", "ops@bureau.local", Role.BUREAU_OPERATOR, bureauHash, totp);
        seedUser(null, "bureau.ops2", "ops2@bureau.local", Role.BUREAU_OPERATOR, bureauHash, totp);

        BankTenant tenant = new BankTenant();
        tenant.setLegalName("Meridian Correspondent Bank");
        tenant.setCountry("GB");
        tenant.setExpectedVolumes("pilot");
        tenant.setStatus(TenantStatus.ACTIVE);
        tenants.save(tenant);

        BankBic bic = new BankBic();
        bic.setTenantId(tenant.getId());
        bic.setBic("MIDNGB2L");
        bic.setDistinguishedName("ou=midn,o=swift");
        bic.setPrimaryBic(true);
        bics.save(bic);

        seedUser(tenant.getId(), "meridian.so", "so@meridian.test", Role.BANK_SECURITY_OFFICER, bankHash, totp);
        seedUser(tenant.getId(), "meridian.so2", "so2@meridian.test", Role.BANK_SECURITY_OFFICER, bankHash, totp);
        seedUser(tenant.getId(), "meridian.maker", "maker@meridian.test", Role.PAYMENT_MAKER, bankHash, totp);
        seedUser(tenant.getId(), "meridian.checker", "checker@meridian.test", Role.PAYMENT_CHECKER, bankHash, totp);
        seedUser(tenant.getId(), "meridian.liq", "liq@meridian.test", Role.LIQUIDITY, bankHash, totp);
        seedUser(tenant.getId(), "meridian.auditor", "auditor@meridian.test", Role.AUDITOR, bankHash, totp);

        RmaRelationship rel = new RmaRelationship();
        rel.setTenantId(tenant.getId());
        rel.setOurBic("MIDNGB2L");
        rel.setCounterpartyBic("CHASUS33");
        rel.setStatus(RmaStatus.ACTIVE);
        rma.save(rel);

        CorrespondentAccount nostro = new CorrespondentAccount();
        nostro.setTenantId(tenant.getId());
        nostro.setType(AccountType.NOSTRO);
        nostro.setCurrency("USD");
        nostro.setAccountRef("NOSTRO-USD-CHAS");
        nostro.setCorrespondentBic("CHASUS33");
        nostro.setLabel("USD nostro at JPMorgan");
        nostro.setStatementBalance(new BigDecimal("2500000.00"));
        accounts.save(nostro);

        CorrespondentAccount vostro = new CorrespondentAccount();
        vostro.setTenantId(tenant.getId());
        vostro.setType(AccountType.VOSTRO);
        vostro.setCurrency("GBP");
        vostro.setAccountRef("VOSTRO-GBP-RESP");
        vostro.setCorrespondentBic("RESPGB2L");
        vostro.setLabel("GBP vostro for respondent");
        vostro.setStatementBalance(new BigDecimal("800000.00"));
        accounts.save(vostro);

        ChargeSchedule schedule = new ChargeSchedule();
        schedule.setTenantId(tenant.getId());
        schedule.setFromBic("MIDNGB2L");
        schedule.setToBic("CHASUS33");
        schedule.setCurrency("USD");
        schedule.setFeeAmount(new BigDecimal("25.00"));
        schedule.setDefaultBearer(ChargeBearer.SHAR);
        charges.save(schedule);

        WatchlistEntry hit = new WatchlistEntry();
        hit.setNamePattern("SANCTIONED PERSON");
        hit.setReason("Simulator hit — UAT only");
        watchlist.save(hit);

        MessageTemplate template = new MessageTemplate();
        template.setTenantId(tenant.getId());
        template.setName("USD-CHAS-WIDGET");
        template.setDescription("Standing USD customer credit to Widget LLC via CHASUS33");
        template.setActive(true);
        template.setMessageType("pacs.008");
        template.setInstructingAgentBic("MIDNGB2L");
        template.setInstructedAgentBic("CHASUS33");
        template.setCurrency("USD");
        template.setDebtorName("Acme Ltd");
        template.setDebtorStreet("1 King Street");
        template.setDebtorTown("London");
        template.setDebtorCountry("GB");
        template.setCreditorName("Widget LLC");
        template.setCreditorStreet("200 West Street");
        template.setCreditorTown("New York");
        template.setCreditorCountry("US");
        template.setChargeBearer(ChargeBearer.SHAR);
        templates.save(template);
    }

    private void seedUser(java.util.UUID tenantId, String login, String email, Role role, String hash, String totp) {
        UserAccount user = new UserAccount();
        user.setTenantId(tenantId);
        user.setLoginId(login);
        user.setEmail(email);
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash(hash);
        user.setMfaSecret(totp);
        user.setMfaEnabled(true);
        users.save(user);
    }
}
