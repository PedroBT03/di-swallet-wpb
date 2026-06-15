# WPB Security Review Checklist

Operational security review items for thesis evidence and pre-production sign-off.

## Authentication and sole control

- [ ] All holder-mutating endpoints require valid FIDO2 assertion (`X-Wallet-Authorization`)
- [ ] Public endpoints limited to bootstrap, status lists, and trust-mark metadata
- [ ] Consent submission enforces FIDO2 when `wpb.consent.require-fido2-on-submit=true`

## Cryptography

- [ ] Holder keys and signing operations use PKCS#11 HSM (no software fallback in prod)
- [ ] Disclosure encryption and transaction log keys are unique per environment
- [ ] Status list JWT signing key is dedicated and rotated per policy

## Data protection

- [ ] Transaction log payloads encrypted at rest; integrity HMAC verified on read
- [ ] Consent audit records exclude attribute values (OIA_10 / DASH_03a)
- [ ] Data deletion request flow documented; no full holder wipe in MVP (document limitation)

## Trust and federation

- [ ] Untrusted attestation disabled in production
- [ ] OpenID4VP trust fail-closed unless explicitly configured for controlled dev
- [ ] Registry signed-response requirements enabled for production registry use

## Exposure surface

- [ ] Swagger UI disabled in prod (`wpb.swagger.enabled=false`)
- [ ] Actuator endpoints not internet-facing without auth
- [ ] Demo mode flags verified false in `/actuator/info` operational block

## Monitoring

- [ ] `wpb.security.fido2.failures` metric reviewed for brute-force patterns
- [ ] `wpb.trust.validation` counters monitored for trust degradation
- [ ] Incident runbook (`ops/runbook-incidents.md`) accessible to operators
