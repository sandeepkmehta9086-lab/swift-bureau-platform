package com.swiftbureau.correspondent;

import com.swiftbureau.common.ApiException;
import com.swiftbureau.domain.AccountType;
import com.swiftbureau.domain.Role;
import com.swiftbureau.ledger.LedgerService;
import com.swiftbureau.mx.MxMessageFactory;
import com.swiftbureau.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CorrespondentService {

    private final CorrespondentAccountRepository accounts;
    private final CashStatementRepository statements;
    private final LedgerService ledgerService;

    public CorrespondentService(
            CorrespondentAccountRepository accounts,
            CashStatementRepository statements,
            LedgerService ledgerService
    ) {
        this.accounts = accounts;
        this.statements = statements;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public CorrespondentAccount create(CurrentUser actor, AccountType type, String currency, String accountRef, String correspondentBic, String label) {
        requireOps(actor);
        MxMessageFactory.validateBic(correspondentBic);
        CorrespondentAccount account = new CorrespondentAccount();
        account.setTenantId(actor.requireTenantId());
        account.setType(type);
        account.setCurrency(currency.toUpperCase());
        account.setAccountRef(accountRef);
        account.setCorrespondentBic(correspondentBic.toUpperCase());
        account.setLabel(label);
        return accounts.save(account);
    }

    public List<CorrespondentAccount> list(CurrentUser actor) {
        return accounts.findByTenantId(actor.requireTenantId());
    }

    @Transactional
    public CashStatement ingestStatement(CurrentUser actor, UUID accountId, String messageType, BigDecimal statementBalance) {
        requireOps(actor);
        CorrespondentAccount account = accounts.findByIdAndTenantId(accountId, actor.requireTenantId())
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Account not found"));
        if (statementBalance != null) {
            account.setStatementBalance(statementBalance);
        }
        String xml = "camt.052".equals(messageType)
                ? MxMessageFactory.camt052(account.getAccountRef(), account.getCurrency(), account.getStatementBalance().toPlainString())
                : MxMessageFactory.camt053(account.getAccountRef(), account.getCurrency(), account.getStatementBalance().toPlainString(), account.getCorrespondentBic());
        CashStatement stmt = new CashStatement();
        stmt.setTenantId(actor.requireTenantId());
        stmt.setMessageType(messageType);
        stmt.setAccountId(account.getId());
        stmt.setMxXml(xml);
        return statements.save(stmt);
    }

    public List<CashStatement> statements(CurrentUser actor) {
        return statements.findByTenantIdOrderByCreatedAtDesc(actor.requireTenantId());
    }

    public List<Map<String, Object>> liquidity(CurrentUser actor) {
        UUID tenantId = actor.requireTenantId();
        return accounts.findByTenantId(tenantId).stream().map(account -> {
            Map<String, Object> row = new HashMap<>();
            BigDecimal booked = ledgerService.bookedBalance(tenantId, account.getId());
            row.put("account", account);
            row.put("bookedBalance", booked);
            row.put("statementBalance", account.getStatementBalance());
            row.put("variance", account.getStatementBalance().subtract(booked));
            return row;
        }).toList();
    }

    private void requireOps(CurrentUser actor) {
        if (actor.role() != Role.BANK_SECURITY_OFFICER && actor.role() != Role.LIQUIDITY) {
            throw new ApiException(403, "FORBIDDEN", "Liquidity or security officer required");
        }
    }
}
