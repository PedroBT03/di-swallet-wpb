# Demo trust material (lab only)

Verifier access certificate and signing key used by the Flask emulator. WPB validates the same chain via `classpath:trust/demo-lote.json` and `classpath:trust/demo-anchor.pem`.

## Chain

```
WPB Demo Trust CA (demo-anchor.pem)
  └── Demo Verifier Access (demo-verifier-access.pem)
        └── embedded in authorization requests as verifier_info.x5c
```

- **client_id:** `verifier-demo-client`
- **cert SHA-256 (LoTE binding):** `A20F7A1C0E99AF7BF512955DAB6C80273E78BC87AD81B4BC85EB9AFBFED4527D`
- **SAN:** `verifier-demo.local`

## Files

| File | Purpose |
|------|---------|
| `demo-anchor.pem` | Trust anchor (also in WPB `app/src/main/resources/trust/`) |
| `demo-ca.key` | CA private key (lab only; used to re-issue access certs) |
| `demo-verifier-access.pem` | Access certificate served in `verifier_info.x5c` |
| `demo-verifier.key` | ES256 key for signing authorization request JWTs |

Do not use these keys outside local thesis or lab environments.

## Regenerating (optional)

```bash
cd verifier-emulator/trust
openssl ecparam -name prime256v1 -genkey -noout -out demo-ca.key
```

Use `ca.ext` / `verifier.ext` for OpenSSL extensions. After regeneration, update `app/src/main/resources/trust/demo-lote.json` (`certSha256` and `trustAnchorsPem`) and WPB `demo-anchor.pem` to match.
