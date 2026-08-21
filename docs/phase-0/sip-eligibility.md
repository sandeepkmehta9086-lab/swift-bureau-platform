# SWIFT Shared Infrastructure Programme (SIP) eligibility

A Service Bureau connects many banks to SWIFT without holding customer funds. SIP certification is a SWIFT programme, not a software feature.

## What we are applying as

- **Role:** Shared infrastructure / Service Bureau (messaging and operations portal)
- **Not in scope:** nostro settlement on the bureau balance sheet, payment principal, FX principal
- **Customers:** supervised financial institutions with their own BICs
- **Interface:** Alliance Cloud Messaging API from a CSP secure zone; this application is the back-office portal outside that zone

## Application evidence to collect

1. Legal entity extract, ownership, directors, insurance (cyber + professional indemnity)
2. ISO 27001 certificate (or SoA + audit calendar if certification is in progress)
3. Architecture pack: [secure-zone-design.md](secure-zone-design.md), data-flow diagram, encryption, key custody (HSM)
4. Provider Security Controls Framework mapping to CSCF v2026
5. 24/7 operating model, RACI, escalation tree
6. Customer due-diligence procedure (KYC/KYB before any login is issued)
7. Incident notification procedure (SWIFT + affected customers), including clocks
8. Configuration reporting capability (implemented in this product under `/api/csp/configuration-report`)
9. Data residency and subcontractors list (cloud region, screening vendor, SOC)

## Customer onboarding terms (must be in the contract)

- Customer remains a SWIFT user; bureau is a processor of their messages
- No public self-registration; users are unique and MFA-enrolled (CSP D2)
- Customer confirms interface security attestation after install/demo
- RMA remains the customer’s responsibility; bureau enforces recorded relationships
- Right to suspend a tenant on sanctions, CSP breach, or unpaid fees
- Audit rights and log retention (minimum 7 years for payment messages)

## Sequence (cannot be skipped)

1. Contract SWIFT / Alliance Cloud (test & training first)
2. Build secure zone, mTLS, jump host, HSM
3. Independent SIP/CSP assessment
4. Test BIC traffic only
5. First live customer BIC after customer confirmation of attestation
6. Incident drills before second customer

Reference: [SIP programme description](https://www.swift.com/about-us/partner-programme/shared-infrastructure-programme/programme-description)
