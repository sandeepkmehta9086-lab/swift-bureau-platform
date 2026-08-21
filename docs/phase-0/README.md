# Phase 0 — Bureau eligibility (blocks live SWIFT)

This folder is the operating pack for becoming a SWIFT Service Bureau. Software in this repository can run as a **simulator** against the stub adapter. It must not be marketed as SWIFT-connected until every item here is complete.

## Status model

| Gate | Blocks | Owner |
| --- | --- | --- |
| Legal entity, cyber insurance, ISO 27001 (SOC 2 recommended) | Customer contracts | Legal / GRC |
| SWIFT Shared Infrastructure Programme (SIP) application | Live BICs | SWIFT relationship |
| CSCF v2026 attestation + independent assessment | Production messaging | CISO |
| Alliance Cloud contract + mTLS / HSM | Outbound FIN/InterAct | Infrastructure |
| Screening vendor in the release path | Payment release | Compliance |
| Incident notification to SWIFT and customers | Operations go-live | 24/7 ops |

## Documents in this pack

- [sip-eligibility.md](sip-eligibility.md) — SIP application checklist and customer terms outline
- [cscf-v2026-gap-assessment.md](cscf-v2026-gap-assessment.md) — control-by-control gap register
- [cscf-v2026-controls.json](cscf-v2026-controls.json) — machine-readable controls loaded by the attestation API
- [secure-zone-design.md](secure-zone-design.md) — CSP secure zone vs application zone
- [alliance-cloud-checklist.md](alliance-cloud-checklist.md) — connectivity, APIs, test BIC, cutover
- [screening-vendor.md](screening-vendor.md) — buy-don’t-build screening, adapter contract
- [operating-playbooks.md](operating-playbooks.md) — onboarding, release, incident, RMA

Live traffic requires `app.swift.mode=ALLIANCE_CLOUD` **and** `app.swift.live-enabled=true` **and** SIP attestation recorded in the CSP pack. The default runtime is `STUB`.
