# CSP secure-zone design

## Zones

```
[Bank browsers] --> [WAF / TLS] --> [Application zone: portal + API + PostgreSQL]
                                         |
                                         | mTLS, allowlisted, MX only
                                         v
                              [Secure zone: SWIFT adapter]
                                         |
                                         | Alliance Cloud Messaging API
                                         v
                                    SWIFTNet
```

## Secure zone contains

- Alliance Cloud / SWIFTNet connectivity components
- HSM (or cloud HSM) for SWIFT-related keys
- Jump host for operators (MFA, recorded sessions)
- Adapter process (`AllianceCloudGateway`) — no React UI, no tenant administration

## Application zone contains

- Bank portal, REST API, Keycloak-or-local identity, message warehouse metadata
- MX XML stored encrypted at rest (database + object lock/WORM in production)
- Screening vendor integration (API egress allowlist)

## Hard rules

1. Bank portal is never deployed in the secure zone
2. Adapter accepts connections only from the API subnet
3. Sent MX is immutable; repairs create `version + 1`
4. Tenant routing is by receiver BIC / DN, never by client-supplied tenant id on inbound
5. Production profile refuses `STUB` gateway when `app.swift.live-enabled=true`
