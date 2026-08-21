# Operating playbooks

## 1. Bank tenant onboarding

1. Bureau operator creates tenant (legal name, country, expected volumes) — status `DRAFT`
2. Compliance KYC/KYB → `KYC_PENDING` then `CONTRACTED`
3. Map BIC(s) and Distinguished Name
4. Invite **bank security officer** only (unique login ID, out-of-band token)
5. Officer sets password, enrolls MFA, then invites maker / checker / liquidity / auditor
6. Record RMA counterparties before the first payment
7. Customer confirms CSP interface attestation (pack download)

No public registration. Shared logins are a CSP finding.

## 2. Payment release (dual control)

1. Maker creates `pacs.008` / `pacs.009` with structured postal address
2. Engine validates CBPR+ fields, UETR (generated once, never rewritten), RMA
3. Screening vendor called (fail-closed)
4. Distinct checker approves with step-up TOTP
5. Secure-zone adapter sends MX; warehouse stores sent copy
6. Stub/Alliance returns `pacs.002`; gpi tracker updated; shadow ledger posts on send/ack

Bureau operators cannot complete step 4 for a customer tenant.

## 3. Incident notification clock

| Time | Action |
| --- | --- |
| T+0 | Contain: freeze tenant or stop adapter if SWIFT-impacting |
| T+15m | Bridge: CISO, ops, SWIFT consultant |
| T+60m | Notify affected customers through recorded channel |
| Per SWIFT CSP | Notify SWIFT as required by SIP terms |
| T+24h | Written timeline, warehouse hash export, preserve logs |

## 4. RMA change

RMA create/activate requires a bank security officer. Revoke immediately if a counterparty relationship ends. Outbound payments to BICs without `ACTIVE` RMA are rejected.

## 5. Liquidity and statements

Nostro/vostro registers are **reference data + shadow books**. They are not the bureau’s money. Intraday `camt.052` and end-of-day `camt.053` update expected vs statement balances. Investigations use `camt.056` / `camt.029` in the warehouse.
