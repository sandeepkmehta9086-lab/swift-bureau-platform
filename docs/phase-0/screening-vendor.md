# Screening vendor (buy, do not build)

DIY list screening is not an acceptable production control. This product ships a **vendor adapter interface** plus a stub that reads an internal watchlist **for simulator/UAT only**.

## Production choice

Short-list SWIFT Screening or an equivalent specialist (name/address/payment-message screening, audit export, 24/7 feed updates). Record DPA, data residency, and fail-closed behaviour in the SIP pack.

## Adapter contract

```
ScreeningClient.screen(PaymentScreeningRequest) -> PaymentScreeningResult
  CLEAR | HIT | REVIEW | UNAVAILABLE
```

Release path:

- `UNAVAILABLE` + fail-closed → do not send
- `HIT` → status `SCREENING_HIT`, maker-checker cannot override without dual compliance role (not implemented as silent bypass)
- `CLEAR` → checker may release with step-up TOTP

## Stub behaviour (this repo)

- Matches debtor/creditor names against `watchlist_entry`
- Used when `app.screening.provider=STUB`
- Must not be used with `app.swift.live-enabled=true` (application refuses to start)
