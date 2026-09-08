import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
  createPseudonym,
  deletePseudonym,
  fetchPseudonymRegistrationOptions,
  finishPseudonymRegistration,
  listPseudonyms,
} from "../api/pseudonym";
import { AuthGate } from "../components/AuthGate";
import { AuthenticatingBanner } from "../components/AuthenticatingBanner";
import { runPseudonymRegistration } from "../features/pseudonym/webauthn";
import { useAuthedApi } from "../hooks/useAuthedApi";
import type { PseudonymView } from "../types/pseudonym";
import { formatApiError } from "../utils/apiError";
import { formatWebAuthnError } from "../auth/webauthn";

export function PseudonymsPage() {
  const { session, withApiAuth, withSoleControl, busy, clearError } = useAuthedApi();
  const holderId = session?.holderId.trim() ?? "";

  const [items, setItems] = useState<PseudonymView[] | null>(null);
  const [rpId, setRpId] = useState("localhost");
  const [alias, setAlias] = useState("Demo RP passkey");
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

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

  const loadPseudonyms = useCallback(async () => {
    await withApiAuth(async (headers) => {
      const list = await listPseudonyms(holderId, headers);
      setItems(list);
    });
  }, [holderId, withApiAuth]);

  const loadPseudonymsRef = useRef(loadPseudonyms);
  loadPseudonymsRef.current = loadPseudonyms;

  useEffect(() => {
    if (!holderId) {
      return;
    }
    let cancelled = false;
    void (async () => {
      clearError();
      setError(null);
      setRefreshing(true);
      try {
        await loadPseudonymsRef.current();
      } catch (err) {
        if (!cancelled) {
          setError(formatApiError(err));
        }
      } finally {
        if (!cancelled) {
          setRefreshing(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [holderId, clearError]);

  async function handleRefresh() {
    setRefreshing(true);
    try {
      await loadPseudonyms();
    } catch (err) {
      setError(formatApiError(err));
    } finally {
      setRefreshing(false);
    }
  }

  async function handleCreate() {
    const trimmedRp = rpId.trim().toLowerCase();
    if (!trimmedRp) {
      setError("RP id (WebAuthn rpId / domain) is required.");
      return;
    }
    await runAction(async () => {
      await withApiAuth(async (headers) => {
        const created = await createPseudonym(
          {
            holderId,
            rpId: trimmedRp,
            alias: alias.trim() || undefined,
          },
          headers,
        );
        setSuccessMessage(
          `Pseudonym slot created for ${created.rpId}. Click Register passkey in the list below.`,
        );
        const list = await listPseudonyms(holderId, headers);
        setItems(list);
      });
    });
  }

  async function handleRegister(item: PseudonymView) {
    const origin = window.location.origin;
    await runAction(async () => {
      const options = await withApiAuth((headers) =>
        fetchPseudonymRegistrationOptions(item.id, holderId, origin, headers),
      );
      let clientDataJSON: string;
      try {
        clientDataJSON = await runPseudonymRegistration(options, item.alias ?? item.rpId);
      } catch (err) {
        throw new Error(formatWebAuthnError(err));
      }
      await withSoleControl((headers) =>
        finishPseudonymRegistration(item.id, holderId, origin, clientDataJSON, headers),
      );
      setSuccessMessage(`Pseudonym registered for ${item.rpId}. Check Log for PseudonymGeneration.`);
      await loadPseudonyms();
    });
  }

  async function handleDelete(item: PseudonymView) {
    if (!window.confirm(`Delete pseudonym for ${item.rpId}? HSM key material will be removed.`)) {
      return;
    }
    await runAction(async () => {
      await withSoleControl(async (headers) => {
        await deletePseudonym(item.id, holderId, headers);
        setSuccessMessage(`Pseudonym for ${item.rpId} deleted.`);
        const list = await listPseudonyms(holderId, headers);
        setItems(list);
      });
    });
  }

  return (
    <AuthGate>
      <section className="page page--pseudonyms">
        <header className="page__header">
          <h1>Pseudonyms</h1>
          <p className="page__lead">
            Per-RP WebAuthn passkeys for external relying parties. Requires sign-in. Distinct from
            your wallet login passkey.
          </p>
        </header>

        {busy ? <AuthenticatingBanner /> : null}
        {error ? <div className="alert alert--error">{error}</div> : null}
        {successMessage ? (
          <div className="alert alert--success" role="status">
            {successMessage}
          </div>
        ) : null}

        <div className="page-stack">
          <div className="card ops-scenario-card">
            <h2 className="card__title">What to demonstrate</h2>
            <ol className="ops-scenario-card__steps">
              <li>
                Explain that the passkey on <Link to="/login">Log in</Link> authenticates you{" "}
                <strong>to this wallet</strong>. Pseudonyms are separate passkeys bound to an{" "}
                <strong>RP domain</strong> (Topic 11).
              </li>
              <li>
                Create a slot with rpId <code>localhost</code> when testing from{" "}
                <code>http://localhost:5173</code>, then click <strong>Register passkey</strong>. You
                can create <strong>several pseudonyms for the same RP</strong> (same rpId, different
                alias).
              </li>
              <li>
                Another rpId (e.g. <code>localhost2</code>) is blocked in dev unless WPB allows it, and
                WebAuthn still requires the browser origin to match the rpId - from this UI only{" "}
                <code>localhost</code> works.
              </li>
              <li>
                Show status <strong>REGISTERED</strong> and open <Link to="/log">Log</Link> for{" "}
                <strong>PseudonymGeneration</strong> (and deletion events if you remove one).
              </li>
            </ol>
          </div>

          <div className="card">
            <h2 className="card__title">Create pseudonym slot</h2>
            <p className="hint">
              Reserves a per-RP passkey slot and dedicated HSM key material. Registration is a
              second WebAuthn ceremony bound to the RP domain. From{" "}
              <code>http://localhost:5173</code> use rpId <code>localhost</code> only.
            </p>
            <div className="ops-toolbar">
              <label className="form__field ops-toolbar__field ops-toolbar__field--narrow">
                <span className="form__label">RP id (domain)</span>
                <input
                  value={rpId}
                  onChange={(event) => setRpId(event.target.value)}
                  disabled={busy}
                  autoComplete="off"
                  spellCheck={false}
                />
              </label>
              <label className="form__field ops-toolbar__field">
                <span className="form__label">Alias (optional)</span>
                <input
                  value={alias}
                  onChange={(event) => setAlias(event.target.value)}
                  disabled={busy}
                  autoComplete="off"
                />
              </label>
              <button
                type="button"
                className="ops-toolbar__btn"
                disabled={busy || !holderId}
                onClick={() => void handleCreate()}
              >
                Create slot
              </button>
            </div>
          </div>

          <div className="card">
            <h2 className="card__title">
              Your pseudonyms{items ? ` (${items.length})` : ""}
            </h2>
            <p className="hint">
              Pending slots need passkey registration. Registered pseudonyms can authenticate to the
              RP domain without revealing your wallet login identity.
            </p>
            <div className="toolbar privacy-card__toolbar">
              <button
                type="button"
                className="button button--secondary"
                disabled={busy || refreshing || !holderId}
                onClick={() => void runAction(handleRefresh)}
              >
                {refreshing ? (
                  <>
                    <span className="btn-spinner" aria-hidden="true" />
                    Refreshing…
                  </>
                ) : (
                  "Refresh"
                )}
              </button>
            </div>

            {refreshing && items === null ? (
              <p className="hint">Loading pseudonyms…</p>
            ) : items && items.length === 0 ? (
              <p className="hint">
                No pseudonyms yet. Create a slot above, then register the passkey.
              </p>
            ) : items && items.length > 0 ? (
              <div className="privacy-list__scroll panel-scroll">
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
              </div>
            ) : null}
          </div>
        </div>
      </section>
    </AuthGate>
  );
}
