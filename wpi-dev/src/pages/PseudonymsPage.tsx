import { useCallback, useState } from "react";
import {
  createPseudonym,
  deletePseudonym,
  fetchPseudonymRegistrationOptions,
  finishPseudonymRegistration,
  listPseudonyms,
} from "../api/pseudonym";
import { AuthGate } from "../components/AuthGate";
import { AuthenticatingBanner } from "../components/AuthenticatingBanner";
import { JsonPanel } from "../components/JsonPanel";
import { runPseudonymRegistration } from "../features/pseudonym/webauthn";
import { useAuthedApi } from "../hooks/useAuthedApi";
import type { PseudonymView } from "../types/pseudonym";
import { formatApiError } from "../utils/apiError";
import { formatWebAuthnError } from "../auth/webauthn";

export function PseudonymsPage() {
  const { session, withProtectedAction, busy, clearError } = useAuthedApi();
  const holderId = session?.holderId.trim() ?? "";

  const [items, setItems] = useState<PseudonymView[] | null>(null);
  const [rpId, setRpId] = useState("localhost");
  const [alias, setAlias] = useState("Demo RP passkey");
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [lastRaw, setLastRaw] = useState<unknown>(null);

  const runAction = useCallback(
    async (action: () => Promise<void>) => {
      clearError();
      setError(null);
      setSuccessMessage(null);
      try {
        await action();
      } catch (err) {
        setError(formatApiError(err));
      }
    },
    [clearError],
  );

  async function loadPseudonyms() {
    await withProtectedAction(async (headers) => {
      const list = await listPseudonyms(holderId, headers);
      setItems(list);
      setLastRaw(list);
    });
  }

  async function handleCreate() {
    const trimmedRp = rpId.trim().toLowerCase();
    if (!trimmedRp) {
      setError("RP id (WebAuthn rpId / domain) is required.");
      return;
    }
    await runAction(async () => {
      await withProtectedAction(async (headers) => {
        const created = await createPseudonym(
          {
            holderId,
            rpId: trimmedRp,
            alias: alias.trim() || undefined,
          },
          headers,
        );
        setSuccessMessage(
          `Pseudonym slot created for rpId ${created.rpId}. Register the passkey next.`,
        );
        setLastRaw(created);
        const list = await listPseudonyms(holderId, headers);
        setItems(list);
      });
    });
  }

  async function handleRegister(item: PseudonymView) {
    const origin = window.location.origin;
    await runAction(async () => {
      const options = await withProtectedAction((headers) =>
        fetchPseudonymRegistrationOptions(item.id, holderId, origin, headers),
      );
      let clientDataJSON: string;
      try {
        clientDataJSON = await runPseudonymRegistration(options, item.alias ?? item.rpId);
      } catch (err) {
        throw new Error(formatWebAuthnError(err));
      }
      const result = await withProtectedAction((headers) =>
        finishPseudonymRegistration(item.id, holderId, origin, clientDataJSON, headers),
      );
      setSuccessMessage(`Pseudonym registered for ${item.rpId}.`);
      setLastRaw(result);
      await loadPseudonyms();
    });
  }

  async function handleDelete(item: PseudonymView) {
    if (!window.confirm(`Delete pseudonym for ${item.rpId}? HSM key material will be removed.`)) {
      return;
    }
    await runAction(async () => {
      await withProtectedAction(async (headers) => {
        await deletePseudonym(item.id, holderId, headers);
        setSuccessMessage(`Pseudonym for ${item.rpId} deleted.`);
        const list = await listPseudonyms(holderId, headers);
        setItems(list);
      });
    });
  }

  return (
    <AuthGate>
      <section className="page">
        <header className="page__header">
          <h1>Pseudonyms</h1>
          <p className="page__lead">
            Per-RP WebAuthn passkeys (Topic 11). Use <code>localhost</code> as rpId when testing from
            this UI on <code>http://localhost:5173</code>.
          </p>
        </header>

        {busy ? <AuthenticatingBanner /> : null}
        {error ? <div className="alert alert--error">{error}</div> : null}
        {successMessage ? (
          <div className="alert alert--success" role="status">
            {successMessage}
          </div>
        ) : null}

        <div className="card">
          <h2 className="card__title">Create pseudonym slot</h2>
          <p className="hint">
            Requires <code>wpb.pseudonym.enabled=true</code> on WPB. Registration runs a separate WebAuthn
            ceremony bound to the RP domain.
          </p>
          <div className="form">
            <label className="form__field">
              <span className="form__label">RP id (domain)</span>
              <input value={rpId} onChange={(event) => setRpId(event.target.value)} disabled={busy} />
            </label>
            <label className="form__field">
              <span className="form__label">Alias (optional)</span>
              <input value={alias} onChange={(event) => setAlias(event.target.value)} disabled={busy} />
            </label>
            <div className="toolbar toolbar--compact">
              <button type="button" disabled={busy || !holderId} onClick={() => void handleCreate()}>
                Create slot
              </button>
              <button
                type="button"
                className="button button--secondary"
                disabled={busy || !holderId}
                onClick={() => void runAction(loadPseudonyms)}
              >
                Unlock &amp; list
              </button>
            </div>
          </div>
        </div>

        <div className="card">
          <h2 className="card__title">Your pseudonyms</h2>
          {items == null ? (
            <p className="hint">Unlock to load pseudonyms for this holder.</p>
          ) : items.length === 0 ? (
            <p className="hint">No pseudonyms yet.</p>
          ) : (
            <ul className="privacy-list">
              {items.map((item) => (
                <li key={item.id} className="privacy-list__item">
                  <div>
                    <strong>{item.alias ?? item.rpId}</strong>
                    <div className="hint">
                      rpId <code>{item.rpId}</code>
                    </div>
                    <div className="hint mono-sm">{item.id}</div>
                    <span
                      className={`status-badge status-badge--${item.status === "REGISTERED" ? "up" : "unknown"}`}
                    >
                      {item.status}
                    </span>
                  </div>
                  <div className="toolbar toolbar--compact">
                    {item.status === "PENDING" ? (
                      <button
                        type="button"
                        className="button button--sm"
                        disabled={busy}
                        onClick={() => void handleRegister(item)}
                      >
                        Register passkey
                      </button>
                    ) : null}
                    <button
                      type="button"
                      className="button button--danger button--sm"
                      disabled={busy}
                      onClick={() => void handleDelete(item)}
                    >
                      Delete
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>

        {lastRaw ? <JsonPanel title="Last API response" data={lastRaw} /> : null}
      </section>
    </AuthGate>
  );
}
