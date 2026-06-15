# WPB Metrics Reference (Micrometer)

Exposed via `/actuator/metrics` and `/actuator/prometheus` when enabled.

## Counters

| Metric | Tags | Description |
|--------|------|-------------|
| `wpb.presentation.sessions` | `outcome` | Terminal presentation session outcomes |
| `wpb.issuance.sessions` | `outcome` | Terminal issuance session outcomes |
| `wpb.trust.validation` | `result` | OpenID4VP trust validation results |
| `wpb.security.fido2.failures` | `reason` | Auth interceptor failures (`missing_or_invalid`, `parse_error`, `verification_failed`) |

## Timers

| Metric | Description |
|--------|-------------|
| `wpb.statuslist.get` | Status list publication GET latency |
| `wpb.registry.lookup` | RP registry HTTP lookup latency |

## Health (Actuator)

| Component | Semantics |
|-----------|-----------|
| `hsm` | PKCS#11 session probe (no signing) |
| `trustSnapshot` | UP / **DEGRADED** / DOWN per cached LoTE snapshot age and refresh failures |

## Info (`/actuator/info`)

`operational` block: active profiles, demo flags, swagger enabled, registry enabled, trust source mode, build version, git commit abbrev.

## Configuration

```properties
wpb.ops.prometheus-enabled=true
management.endpoints.web.exposure.include=health,info,metrics,prometheus
```

Disable Prometheus in dev with `wpb.ops.prometheus-enabled=false` (default).
