# WPI Dev: feature traceability

Maps WPB capabilities (see repo `README.md`) to **wpi-dev** screens and API clients.

| WPB capability | API / path | wpi-dev screen | Client module |
|----------------|------------|----------------|---------------|
| Health / actuator | `GET /actuator/health` | Health (`/`) | `api/health.ts` |
| FIDO2 register | `POST /auth/register/{userId}` | Onboarding (`/onboarding`) | `api/auth.ts` |
| FIDO2 challenge | `GET /auth/challenge/{userId}` | Login, protected actions | `api/auth.ts` |
| Wallet init | `POST /wallet/init` | Wallet | `api/wallet.ts` |
| Wallet summary | `GET /wallet/summary/{holderId}` | Wallet (Sync from server) | `api/wallet.ts` |
| HSM keys | `POST/GET /wallet/keys/{holderId}` | Wallet | `api/wallet.ts` |
| Revoke HSM key | `POST /wallet/keys/{holderId}/revoke` | Wallet | `api/wallet.ts` |
| List credentials | `GET /wallet/credentials/{holderId}` | Wallet | `api/wallet.ts` |
| Issue demo SD-JWT | `POST /wallet/credentials/issue-sd/{holderId}` | Wallet | `api/wallet.ts` |
| Delete credential | `DELETE /wallet/credentials/{id}` | Wallet | `api/wallet.ts` |
| Revoke credential | `POST /wallet/credentials/{id}/revoke` | Wallet | `api/wallet.ts` |
| Revoke wallet unit | `POST /wallet/units/{walletId}/revoke` | Wallet | `api/wallet.ts` |
| Manual SD-JWT presentation | `POST /wallet/credentials/{id}/presentation` | Wallet | `api/wallet.ts` |
| Remote sign | `POST /wallet/sign/{holderId}` | Wallet | `api/wallet.ts` |
| OID4VP authorize | `POST /openid4vp/authorize` | Present | `api/openid4vp.ts` |
| OID4VP consent | `GET/POST /openid4vp/...` | Present | `api/openid4vp.ts` |
| OID4VCI issuance | `/openid4vci/*` | Issue | `api/openid4vci.ts` |
| Deferred issuance poll | `POST /openid4vci/deferred/query` | Issue (Continue) | `api/openid4vci.ts` |
| Issuer notification | `POST /openid4vci/notify` | Issue (Continue) | `api/openid4vci.ts` |
| Transaction log | `/api/v1/wallet/transactions/*` | Log | `api/transactionLog.ts` |
| Data deletion | `/api/v1/wallet/deletion-requests/*` | Privacy → Deletion | `api/privacy.ts` |
| DPA reports | `/api/v1/wallet/dpa-reports/*` | Privacy → DPA | `api/privacy.ts` |
| Trust mark | `GET /wallet/trust-mark` | Ops → Trust mark | `api/trustMark.ts` |
| Trust mark refresh | `POST /wallet/trust-mark/refresh` | Ops | `api/trustMark.ts` |
| Status lists | `GET /wallet/status-lists/*` | Ops → Status lists | `api/statusList.ts` |
| Pseudonyms | `/wallet/pseudonyms/*` | Pseudonyms | `api/pseudonym.ts` |
| Operational flags | `GET /api/v1/wallet/ops` | Health, Issue, Settings | `api/ops.ts` |

## Not covered in wpi-dev (by design)

| Capability | Notes |
|------------|-------|
| mdoc issuance / presentation | Backend throws or policy-gated; no lab UI |
| Real external issuer / verifier | Use WPB demo-mode + emulator instead |
| WIA / mobile attestation | Web-only lab harness |
| RP dashboard / migration import | Out of scope per WPB README |

## Dev profile toggles

| Feature | Property | Default in `application-dev.properties` |
|---------|----------|----------------------------------------|
| OID4VP demo | `wpb.openid4vp.demo-mode` | `true` |
| OID4VCI demo | `wpb.openid4vci.demo-mode` | `true` (via `dev` profile) |
| Deferred issuance | `wpb.openid4vci.simulator.always-defer` | `false` (uncomment to test) |
| Trust mark | `wpb.trust-mark.enabled` | `true` (placeholder URLs) |
| Pseudonyms | `wpb.pseudonym.enabled` | `true` (`localhost` rpId) |
| DPA fallback | `wpb.dpa-reporting.provider-fallback-dpa.*` | dummy CNPD contact |
| Deletion fallback | `wpb.data-deletion.provider-fallback-rp.*` | demo verifier contact |
