# Alliance Cloud connectivity checklist

Alliance Lite2 is closed to new customers. New bureaux should contract **Alliance Cloud** and the Messaging API.

## Contract and identity

- [ ] Alliance Cloud subscription (test/training + production)
- [ ] Bureau legal name matching SWIFT relationship
- [ ] Distinguished names for the bureau endpoint
- [ ] Customer BIC hosting model agreed (shared infrastructure vs dedicated DN)

## Technical

- [ ] Alliance Connect Virtual or approved connectivity
- [ ] mTLS client certificate in HSM
- [ ] Messaging API credentials in vault (not in git)
- [ ] Clock sync (NTP) for non-repudiation timestamps
- [ ] Inbound listener: route by receiver BIC to `tenant_id`
- [ ] Retry / poison-message queue (no silent drop)
- [ ] Configuration report endpoint enabled for assessors

## Cutover

1. `app.swift.mode=STUB` — software UAT, no SWIFT
2. `app.swift.mode=ALLIANCE_CLOUD` + `live-enabled=false` — test BIC only
3. Independent assessment
4. `live-enabled=true` after first customer confirmation

The `AllianceCloudGateway` class in this repo is the swap point for the stub. Do not call live SWIFT from developer laptops.
