# Payment Flow Agent: Scenarios

This file lists the SWIFT ISO 20022 (CBPR+) payment scenarios the platform should capture end to end. Each scenario names the message sequence, who sends what, and how the bureau platform handles it.

---

## Scenario 1: Customer credit transfer, start to finish (with recall)

### Summary

The life of one cross-border payment in four stages: **Initiate → Clear → Report → Recall** (recall only when something goes wrong).

### Message families

| Family | Direction | Purpose |
| --- | --- | --- |
| `pain` | Customer → Bank | Payment initiation |
| `pacs` | Bank → Bank | Clearing and settlement |
| `camt` | Bank → Customer / Bank ↔ Bank | Reporting and exceptions |

### Actors

| Actor | Role |
| --- | --- |
| Debtor | Customer who pays |
| Debtor bank | Tenant bank onboarded to the Service Bureau |
| Creditor bank | Correspondent / beneficiary bank on the SWIFT network |
| Creditor | Customer who gets paid |

### Sequence

```text
Debtor          Debtor bank            Creditor bank          Creditor
  |  pain.001  ---->|                        |                     |
  |<---- pain.002   |                        |                     |
  |                 |  pacs.008  ----------->|                     |
  |                 |<----------- pacs.002   |                     |
  |<---- camt.054   |                        |  camt.054  -------->|
  |<---- camt.053   |                        |  camt.053  -------->|
  |                 |                        |                     |
  |      (exception path)                    |                     |
  |                 |  camt.056  ----------->|                     |
  |                 |<----------- camt.029   |                     |
  |                 |<----------- pacs.004   |                     |
```

### Stage 1: Initiate

| Step | Message | From → To | Meaning |
| --- | --- | --- | --- |
| 1.1 | `pain.001` Customer Credit Transfer Initiation | Debtor → Debtor bank | Customer asks their bank to make the payment. |
| 1.2 | `pain.002` Customer Payment Status Report | Debtor bank → Debtor | Bank says whether the instruction is accepted or rejected. |

**Acceptance criteria**
- A `pain.001` is validated (schema, mandatory fields, structured address) before a payment is created.
- Each `pain.001` gets exactly one `pain.002` reply: accepted (`ACCP`/`ACTC`) or rejected (`RJCT`) with a reason code.
- A rejected `pain.001` never produces a `pacs.008`.

### Stage 2: Clear

| Step | Message | From → To | Meaning |
| --- | --- | --- | --- |
| 2.1 | `pacs.008` FI to FI Customer Credit Transfer | Debtor bank → Creditor bank | The interbank payment instruction. |
| 2.2 | `pacs.002` FI to FI Payment Status Report | Creditor bank → Debtor bank | Interbank status: accepted, pending, or rejected. |

**Acceptance criteria**
- `pacs.008` goes through maker → submit (RMA check + sanctions screening) → checker approval with step-up TOTP before it is sent.
- Maker cannot approve their own payment. Bureau operators cannot release customer payments.
- The UETR is generated once and carried unchanged on every later message (`pacs.002`, `camt.054`, `camt.056`, `camt.029`, `pacs.004`).
- Sent MX is frozen (`xmlFrozen`) and is never edited.
- Inbound `pacs.002` updates payment status and gpi tracking.
- A screening hit (e.g. debtor `SANCTIONED PERSON`) stops the payment before it is sent.

### Stage 3: Report

| Step | Message | From → To | Meaning |
| --- | --- | --- | --- |
| 3.1 | `camt.054` Bank to Customer Debit/Credit Notification | Debtor bank → Debtor; Creditor bank → Creditor | Account was debited or credited. |
| 3.2 | `camt.053` Bank to Customer Statement | Each bank → its customer | End-of-day statement. |

**Acceptance criteria**
- A `camt.054` is produced for the debit once the payment is accepted, and posts to the tenant bank's shadow nostro/vostro ledger.
- `camt.053` end-of-day balance matches the shadow ledger for the account and currency.
- Shadow books belong to each tenant bank, not to the bureau.

### Stage 4: Recall (exception path)

| Step | Message | From → To | Meaning |
| --- | --- | --- | --- |
| 4.1 | `camt.056` FI to FI Payment Cancellation Request | Debtor bank → Creditor bank | Asks for the payment to be recalled. |
| 4.2 | `camt.029` Resolution of Investigation | Creditor bank → Debtor bank | Answer to the recall: accepted or rejected. |
| 4.3 | `pacs.004` Payment Return | Creditor bank → Debtor bank | Returns the funds (only if the recall is accepted). |

**Acceptance criteria**
- A recall can only be raised on a payment that was sent.
- `camt.056` references the original `pacs.008` (message ID, UETR, amount).
- `camt.029` with a rejection closes the case with no funds movement.
- `camt.029` with acceptance is followed by `pacs.004`, which reverses the shadow ledger entry and generates a credit `camt.054`.

**Tests for this stage**
- Backend (`cd backend && mvn test`): `PaymentFlowIT.scenario1RecallRejectedByCorrespondentLeavesFundsWithBeneficiary`, `scenario1RecallAcceptedReturnsFundsWithPacs004`, `recallNeedsASentPaymentAndCustomerUser`; `MxMessageFactoryTest.pacs004…`, `camt056…`.
- Frontend (`cd frontend && npm test`): the recall tests under "payment detail" and "payments list" in `App.integration.test.tsx`, and "an accepted recall reads as returned" / "a rejected recall keeps the credited outcome" in `gpi.test.ts`.
- Full list per stage: [Tests covering Scenario 1](#tests-covering-scenario-1-by-stage). Manual steps: [Try Stage 4 by hand](#try-stage-4-by-hand-dev-seed-stub-gateway).

### Current coverage in this repo

| Message | Status | Where |
| --- | --- | --- |
| `pain.001` / `pain.002` | Implemented: `POST /api/payments/initiate` (maker or security officer) | `PaymentService.initiate`, `MxMessageFactory.pain001/pain002` |
| `pacs.008` | Implemented | `backend/src/main/java/com/swiftbureau/mx/MxMessageFactory.java` |
| `pacs.002` | Implemented (inbound from stub) | `StubSwiftNetworkGateway` |
| `camt.054` | Implemented (DBIT on send, CRDT on return) | `StubSwiftNetworkGateway`, `MxMessageFactory` |
| `camt.053` | Implemented | `MxMessageFactory`, `CorrespondentService.ingestStatement` |
| `camt.056` | Implemented: `POST /api/payments/{id}/recall` with optional `{"reasonCode": "..."}` | `PaymentService.recall` |
| `camt.029` | Implemented (CNCL or RJCR from stub) | `StubSwiftNetworkGateway.recall` |
| `pacs.004` | Implemented after an accepted `camt.029`, reverses the shadow ledger debit | `PaymentService.recall`, `LedgerService.postReturn` |

### How the gpi tracker captures the scenario

Every message shares the UETR minted on the `pain.001`, so `GET /api/gpi/{uetr}` returns the whole story.
Hops are strictly ordered (`GpiService` nudges colliding timestamps), and the portal timeline labels them:

| Stage | Hop codes (in order) |
| --- | --- |
| 1 Initiate | `CUSTOMER_INSTRUCTED` → `CUSTOMER_ACCEPTED` (or `CUSTOMER_REJECTED`, then nothing else) → `INITIATED` |
| 2 Clear | `SENT_TO_SWIFT` → `RECEIVED_BY_CORRESPONDENT` → `CREDITED` |
| 3 Report | `camt.054` / `camt.053` are stored as cash statements on the shadow nostro (no hop) |
| 4 Recall | `RECALL_REQUESTED` → `RECALL_ACCEPTED` → `RETURNED` (or `RECALL_REQUESTED` → `RECALL_REJECTED`) |

### Stub behaviour to know when testing

- `pain.001` is rejected with `pain.002 RJCT` when business validation fails: `BE04` missing structured address, `AM02` bad amount, `RC01` BIC not owned by the tenant. Malformed instructions (missing amount/currency/debtor, bad BIC format) get an HTTP 400 instead, because there is nothing valid to store.
- The stub correspondent honours a recall (`camt.029 CNCL` + `pacs.004 FOCR` + `camt.054 CRDT`) for reasons `DUPL`, `FRAD`, `TECH`, `AM09`, `AC03`. The default `CUST` and any other reason are refused with `camt.029 RJCR` and the funds stay with the beneficiary.
- A recall is only accepted on a `pacs.008`/`pacs.009` in status `SENT` or `ACKED`; a `RETURNED` payment cannot be recalled again.

### Tests covering Scenario 1 (by stage)

| Stage | Backend (`cd backend && mvn test`) | Frontend (`cd frontend && npm test`) |
| --- | --- | --- |
| 1 Initiate | `PaymentFlowIT.scenario1CustomerInstructionAcceptedRunsThroughClearingAndReporting`, `scenario1RejectedInstructionGetsPain002RjctAndNoPacs008`; `MxMessageFactoryTest.pain001…`, `pain002…` | `App.integration.test.tsx` › "scenario 1: customer instruction (pain.001 → pain.002)"; `gpi.test.ts` › "shows pain.001 and pain.002 before initiation", "a rejected pain.001 stops the journey" |
| 2 Clear | `PaymentFlowIT.goldenPathCreateSubmitApproveAckAndGpi`, `screeningHitBlocksRelease`, `bureauCannotReleaseCustomerPayment` | `App.integration.test.tsx` › "golden path: maker → checker → gpi credited" |
| 3 Report | `scenario1CustomerInstructionAccepted…` asserts the `camt.054 DBIT` statement; `scenario1RecallAcceptedReturnsFundsWithPacs004` asserts the `camt.054 CRDT` statement | — (statements screen only) |
| 4 Recall | `PaymentFlowIT.scenario1RecallRejectedByCorrespondentLeavesFundsWithBeneficiary` (CUST → `camt.029 RJCR`, no `pacs.004`, ledger unchanged), `scenario1RecallAcceptedReturnsFundsWithPacs004` (DUPL → `camt.029 CNCL` + `pacs.004 FOCR`, status `RETURNED`, ledger reversed, second recall → 409), `recallNeedsASentPaymentAndCustomerUser` (draft → 409, other tenant → 404, bad reason → 400); `MxMessageFactoryTest.pacs004…`, `camt056…` | `App.integration.test.tsx` › "sends a camt.056 recall and keeps the page usable" (CUST, rejected), "recalls with a reason the correspondent honours and shows the pacs.004 return" (DUPL), "offers the recall only for sent interbank payments", "explains a refused recall and lets the user retry with a reason that is honoured", "lets a checker recall but not a role the backend would refuse", "a camt.056 is not a payment: no actions on it, and it links back to the payment it recalls", "lists payments only, with their recall state; pain.002 / camt.056 rows are opt-in", "opens a recallable payment from the list and recalls it"; `gpi.test.ts` › "an accepted recall reads as returned", "a rejected recall keeps the credited outcome" |

### Try Stage 4 by hand (dev seed, stub gateway)

1. Sign in as `meridian.maker`, create a `pacs.008` and **Submit for screening** (debtor must not be `SANCTIONED PERSON`).
2. Sign in as `meridian.checker`, enter the step-up TOTP and **Approve and release**. The status becomes `ACKED`.
3. On **Payments**, open the `pacs.008` row whose **Recall** column says *Can be recalled*: the **Recall reason** dropdown and **camt.056 recall** button appear **only now**. A `DRAFT`, `PENDING_CHECK` or `SCREENING_HIT` payment was never sent, so it has nothing to recall and shows no recall form; the line under the buttons says why.
   - The list shows payments only. `pain.002` and `camt.056` rows are messages about a payment and cannot be recalled; tick **Show related messages** to see them. Opening one shows a link back to the original `pacs.008`.
   - Maker, checker and security officer can recall; the liquidity role cannot.
4. Keep `CUST` and recall → tracker shows *Recall requested → Recall rejected*, headline "recall rejected (camt.029 RJCR)", status stays `ACKED`; the Inbox has a `camt.029 RJCR`.
5. Pick `DUPL` (or `FRAD`, `TECH`, `AM09`, `AC03`) and recall → tracker shows *Recall requested → Recall accepted → Funds returned*, status `RETURNED`; the Inbox has `camt.029 CNCL` and `pacs.004 FOCR`, and the USD nostro on the Liquidity screen is back to its pre-payment balance.

API equivalent: `POST /api/payments/{id}/recall` with body `{"reasonCode":"DUPL"}` (omit the body for `CUST`), then `GET /api/gpi/{uetr}` and `GET /api/inbox`.
