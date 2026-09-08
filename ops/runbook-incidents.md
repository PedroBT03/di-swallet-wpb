# Runbook - Operational Incidents

## HSM unavailable (`hsm` health DOWN)

1. Check PKCS#11 library path and `SOFTHSM2_CONF` (dev) or HSM appliance connectivity (prod).
2. Verify PIN / slot status without restarting holders' sessions unnecessarily.
3. If prolonged outage: block new key generation and credential issuance; presentation may fail on signing steps.
4. Escalate to HSM operator; restore from backup token only per key-management policy.

## Trust snapshot degraded or down (`trustSnapshot` health)

| Status | Meaning | Action |
|--------|---------|--------|
| **UP** | Fresh LoTE snapshot loaded | None |
| **DEGRADED** | Cached snapshot past max age | Check remote LoTE URL and allow-list; fix network |
| **DOWN** | No snapshot or repeated refresh failures | Presentation trust may fail-closed; restore LoTE source |

Inspect `consecutiveRefreshFailures` in health details. Threshold: `wpb.ops.trust-snapshot-failure-threshold` (default 3).

## High FIDO2 failure rate (`wpb.security.fido2.failures`)

1. Correlate with WPI client version and clock skew.
2. Check for scanning / missing `X-Wallet-Authorization` (reason `missing_or_invalid`).
3. Apply rate limiting at reverse proxy if abuse is suspected - WPB does not implement rate limits on status lists or auth.

## Status list latency (`wpb.statuslist.get`)

1. Check database load and status list capacity.
2. Verify signing key PEM readable.
3. Scale read replicas or cache at CDN for JWT responses (`Cache-Control` is set).

## Production readiness startup failure

`ProductionReadinessValidator` throws on weak secrets or demo flags. Update environment variables per `ops/deployment-checklist.md` and redeploy.

## Evidence collection

```bash
curl -sS https://<host>/actuator/health | jq .
curl -sS https://<host>/actuator/info | jq .
curl -sS https://<host>/actuator/metrics/wpb.trust.validation
```

Attach logs with `event=trust.snapshot.*` and `SecurityPolicy:` lines for security incidents.
