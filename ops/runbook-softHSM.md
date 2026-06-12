# Runbook — SoftHSM2 (development / CI)

This runbook covers the virtual HSM used in development and automated tests. Production should use a certified HSM; the probe semantics are the same (PKCS#11 session, no signing in health checks).

## Initialize token (once per machine)

```bash
mkdir -p ~/softhsm/tokens
export SOFTHSM2_CONF="$HOME/.softhsm2.conf"
printf '%s\n' \
  "directories.tokendir = $HOME/softhsm/tokens" \
  "objectstore.backend = file" \
  > "$SOFTHSM2_CONF"
softhsm2-util --init-token --free --label "DI-Swallet-WSCD" --pin 1234 --so-pin 123456
```

## Verify token

```bash
export SOFTHSM2_CONF=$HOME/.softhsm2.conf
softhsm2-util --show-slots
```

## Application configuration

```properties
wpb.hsm.library=/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so
wpb.hsm.pin=<pin>
```

Gradle `bootRun` and tests set `SOFTHSM2_CONF` automatically when using the project build scripts.

## Health check

`/actuator/health` component `hsm` reports:

- **UP** — PKCS#11 session opened, key entry count available
- **DOWN** — library missing, wrong PIN, or token unavailable

The health probe does **not** perform signing operations.

## Common failures

| Symptom | Action |
|---------|--------|
| `CKR_PIN_INCORRECT` | Verify `wpb.hsm.pin` matches token PIN |
| Library not found | Install `softhsm2`; fix `wpb.hsm.library` path |
| Empty slot | Re-run token init or restore token directory backup |
| CI failures | Ensure workflow initializes SoftHSM before `./gradlew test` |

## Production note

Do not use SoftHSM2 PIN `1234` in production. `ProductionReadinessValidator` rejects known weak defaults when `prod` profile is active.
