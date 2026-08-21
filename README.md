# SWIFT Service Bureau and Correspondent Operations Platform

Multi-tenant portal for banks that are onboarded (not self-registered) to send ISO 20022 CBPR+ payments through a **Service Bureau**. Default runtime uses a **stub SWIFT adapter**. It is a simulator until SIP, Alliance Cloud, and vendor screening are complete.

## What is in this repo

| Area | Location |
| --- | --- |
| Phase 0 eligibility pack (SIP, CSCF, secure zone, playbooks) | [docs/phase-0](docs/phase-0) |
| API (Java 21, Spring Boot) | [backend](backend) |
| Bank portal (React) | [frontend](frontend) |

SWIFT is messaging, not settlement. Nostro/vostro screens are **each tenant bank’s shadow books**, not this bureau’s money.

## Run locally

Terminal 1:

```bash
cd backend
mvn test
mvn spring-boot:run
```

Terminal 2:

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173

### Seeded users (dev seed on)

Authenticator secret for all seeded users: `JBSWY3DPEHPK3PXP`

| Login | Password | Role |
| --- | --- | --- |
| `bureau.ops` | `ChangeMe_Bureau1!` | Bureau operator |
| `bureau.ops2` | `ChangeMe_Bureau1!` | Second operator (CSP dual control) |
| `meridian.so` | `ChangeMe_Bank1!` | Bank security officer |
| `meridian.maker` | `ChangeMe_Bank1!` | Payment maker |
| `meridian.checker` | `ChangeMe_Bank1!` | Payment checker |
| `meridian.liq` | `ChangeMe_Bank1!` | Liquidity |

Golden path: maker creates `pacs.008` (structured address) → submit (RMA + screening) → checker approves with step-up TOTP → stub sends MX, inbound `pacs.002`, gpi hops, shadow ledger.

To force a screening hit, use debtor name `SANCTIONED PERSON`.

## Live SWIFT (blocked on purpose)

Production requires all of [docs/phase-0](docs/phase-0). Then:

```yaml
app.swift.mode: ALLIANCE_CLOUD
app.swift.live-enabled: true
app.swift.alliance-base-url: https://your-alliance-host
app.screening.provider: VENDOR
```

Startup refuses live mode with the stub gateway or stub screening.

## Architecture notes

- No public signup. Invitations issue a unique `login_id`; MFA is mandatory.
- Maker cannot approve their own payment. Bureau operators cannot release customer payments.
- Sent MX is frozen (`xmlFrozen`). UETR is generated once and never rewritten.
- `SwiftNetworkGateway` is the swap point: `StubSwiftNetworkGateway` vs `AllianceCloudGateway`.
- Configuration report: `GET /api/csp/configuration-report`
