# CSCF v2026 gap assessment (bureau)

This register is the working copy of controls the attestation UI reads from `cscf-v2026-controls.json`. Update evidence as assessments close. **Control 2.4 (back-office data-flow security) is mandatory in v2026.**

## How to use

1. Independent assessor scores each control: `OPEN`, `PARTIAL`, `IMPLEMENTED`, `WAIVED`
2. Bureau security officers record evidence in the product (`/csp` page) — dual control required to freeze a pack
3. Customer confirmation is a separate artefact generated from `/api/csp/pack`

## Priority gaps for a greenfield bureau

- **1.1 Secure zone** — Alliance connector, HSM, jump hosts only; no bank portal in the zone
- **2.4 Back-office data flows** — mTLS between portal/API and secure-zone adapter; no cleartext MX
- **4.1 Password policy** — 12+ mixed charset, no shared logins, history, lockout
- **4.2 MFA** — TOTP/WebAuthn for all operators and bank users; step-up on payment release; MFA for firewall and LSO/RSO-style roles
- **5.1 / 6.4 Logging and integrity** — immutable audit log, warehouse never updates a sent MX (new version row)
- **2.9 Operator connections** — bureau operators cannot release a customer payment

## Explicit non-goals in software

- Replacing the customer’s own CSP attestation for *their* SWIFT footprint
- Homegrown sanctions lists as the production control (vendor required)
