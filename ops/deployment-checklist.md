# WPB Production Deployment Checklist

Use this checklist before exposing the Wallet Provider Backend to holders or ecosystem partners.

## Secrets and configuration

- [ ] `SPRING_PROFILES_ACTIVE=prod`
- [ ] `spring.datasource.password` - not the dev default (`tese2026`)
- [ ] `wpb.hsm.pin` - not the dev default (`1234`); HSM token initialized on target host
- [ ] `wallet.disclosures.encryption-key` - unique 32-byte Base64 key
- [ ] `wpb.transaction-log.encryption-key` and `wpb.transaction-log.integrity-key` - unique 32-byte Base64 keys
- [ ] `wpb.status-list.signing-key-pem-path` - production signing key (auto-generate disabled in prod)
- [ ] `wpb.openid4vp.demo-mode=false` and `wpb.openid4vci.demo-mode=false`
- [ ] `wallet.allow-untrusted-attestation=false`
- [ ] `wallet.rp.id` and `wallet.origins` - production HTTPS domain only
- [ ] `wpb.dpa-reporting.provider-fallback-dpa.*` - at least one of `email`, `phone`, or `form-uri` (enforced by `ProductionReadinessValidator`)

## Trust and registry

- [ ] LoTE / trust snapshot source configured (`wpb.openid4vp.trust.*`)
- [ ] RP registry URL and verification keys when registry is enabled
- [ ] Remote host allow-lists reviewed (`remote-allowed-hosts`)

## Network and edge

- [ ] TLS termination at reverse proxy or ingress
- [ ] Rate limiting / WAF for public status list paths (`/api/v1/wallet/status-lists/*`) at the edge - not in WPB
- [ ] Actuator (`/actuator/*`) reachable only from monitoring network or via authenticated proxy
- [ ] `wpb.swagger.enabled=false` (default in `application-prod.properties`)

## Observability

- [ ] Prometheus scrape of `/actuator/prometheus` when `wpb.ops.prometheus-enabled=true`
- [ ] Alert on `trustSnapshot` health `DOWN` or sustained `DEGRADED`
- [ ] Alert on `hsm` health `DOWN`

## Smoke tests after deploy

```bash
curl -fsS https://<host>/actuator/health
curl -fsS https://<host>/actuator/info
curl -fsS "https://<host>/api/v1/wallet/status-lists/<LIST_ID>?format=json"
```

## Startup validation

With `prod` profile, `ProductionReadinessValidator` fails fast on weak secrets, demo flags, bundled signing keys, or missing DPA fallback contact. Fix violations before traffic is routed.
