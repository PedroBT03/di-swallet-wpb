import type { PresentationConsentView } from "../../types/openid4vp";
import { formatCredentialTypeLabel } from "../../utils/credentialType";

interface ConsentScreenProps {
  view: PresentationConsentView;
  selectedIds: string[];
  onToggleCandidate: (candidateId: string, queryId: string) => void;
  onApprove: () => void;
  onReject: () => void;
  busy: boolean;
  selectionError: string | null;
}

function formatClaimPath(path: string): string {
  const labels: Record<string, string> = {
    given_name: "Given name",
    family_name: "Family name",
    birthdate: "Date of birth",
    birth_date: "Date of birth",
    place_of_birth: "Place of birth",
    nationalities: "Nationality",
    driving_privileges: "Driving categories",
    date_of_expiry: "Expiry date",
    issuing_authority: "Issuing authority",
    issuing_country: "Issuing country",
    "address.locality": "City",
    "address.street_address": "Street address",
    "address.postal_code": "Postal code",
    "address.country": "Country",
  };
  return labels[path] ?? path.replaceAll(".", " › ");
}

/** Holder-facing noise in local demo flows (verifier emulator, registry off). */
const DEMO_CONSENT_WARNING_CODES = new Set(["registry_validation_disabled"]);

export function ConsentScreen({
  view,
  selectedIds,
  onToggleCandidate,
  onApprove,
  onReject,
  busy,
  selectionError,
}: ConsentScreenProps) {
  const { verifier, minimization, registryWarnings, queries, choiceGroups } = view;
  const warnings = [...registryWarnings, ...minimization.warnings].filter(
    (w) => !DEMO_CONSENT_WARNING_CODES.has(w.code),
  );
  const allRequestedClaims = queries.flatMap((q) => q.requestedClaims);

  return (
    <section className="card present-consent" aria-labelledby="consent-heading">
      <h2 id="consent-heading" className="card__title">
        Review what to share
      </h2>
      <p className="present-consent__intro">
        <strong>{verifier.displayName ?? verifier.clientId}</strong> is requesting specific
        attributes from your wallet. With selective disclosure, only the items below are sent.
        Everything else in the requested credential stays hidden.
      </p>

      <div className="present-consent__trust">
        <span
          className={`status-badge status-badge--${verifier.trusted ? "up" : "down"}`}
        >
          {verifier.trusted ? "Trusted verifier" : "Unverified verifier"}
        </span>
        {verifier.trustReason ? (
          <span className="hint present-consent__trust-reason">{verifier.trustReason}</span>
        ) : null}
      </div>

      {warnings.length > 0 ? (
        <div className="alert alert--info present-consent__warnings" role="status">
          <strong>Warnings</strong>
          <ul className="present-consent__list">
            {warnings.map((w) => (
              <li key={w.code}>
                <code>{w.code}</code>: {w.message}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <div className="present-consent__section present-consent__section--highlight">
        <h3 className="present-consent__subtitle">What you will share</h3>
        {allRequestedClaims.length === 0 ? (
          <p className="hint">This request does not list specific claim paths (full credential may be requested).</p>
        ) : (
          <ul className="present-claim-chips" aria-label="Attributes to disclose">
            {allRequestedClaims.map((claim) => (
              <li key={claim.path} className="present-claim-chips__item">
                <span className="present-claim-chips__label">
                  {claim.label !== claim.path ? claim.label : formatClaimPath(claim.path)}
                </span>
                <code className="present-claim-chips__path">{claim.path}</code>
              </li>
            ))}
          </ul>
        )}
        <p className="hint present-consent__privacy-note">
          Selective disclosure: the presentation token includes only these fields. Other
          attributes in the same credential are not revealed.
        </p>
      </div>

      {view.intendedUse.length > 0 ? (
        <div className="present-consent__section">
          <h3 className="present-consent__subtitle">Why they need it</h3>
          <ul className="present-consent__list">
            {view.intendedUse.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </div>
      ) : null}

      {choiceGroups.length > 0 ? (
        <div className="present-consent__section">
          <h3 className="present-consent__subtitle">Credential to use</h3>
          {choiceGroups.map((group) => (
            <fieldset key={group.queryId} className="present-choice-group">
              <legend>
                {formatCredentialTypeLabel(group.credentialType)}
                {group.requiresUserSelection ? (
                  <span className="hint"> (choose one)</span>
                ) : null}
              </legend>
              {group.candidates.length === 0 ? (
                <p className="hint">
                  No matching credentials in wallet. Issue a PID or mDL on Wallet, sync, then try
                  again.
                </p>
              ) : (
                <ul className="present-choice-list">
                  {group.candidates.map((candidate) => {
                    const inputType = group.requiresUserSelection ? "radio" : "checkbox";
                    const name = group.requiresUserSelection
                      ? `choice-${group.queryId}`
                      : undefined;
                    const checked = selectedIds.includes(candidate.candidateId);

                    return (
                      <li key={candidate.candidateId}>
                        <label className="present-choice-list__label">
                          <input
                            type={inputType}
                            name={name}
                            checked={checked}
                            disabled={busy}
                            onChange={() =>
                              onToggleCandidate(candidate.candidateId, group.queryId)
                            }
                          />
                          <span>{candidate.label}</span>
                        </label>
                      </li>
                    );
                  })}
                </ul>
              )}
            </fieldset>
          ))}
        </div>
      ) : null}

      {view.privacyPolicyUri ? (
        <p className="hint present-consent__policy">
          <a href={view.privacyPolicyUri} target="_blank" rel="noreferrer">
            Verifier privacy policy
          </a>
        </p>
      ) : null}

      {selectionError ? (
        <div className="alert alert--error" role="alert">
          {selectionError}
        </div>
      ) : null}

      <div className="toolbar toolbar--compact present-consent__actions">
        <button type="button" disabled={busy} onClick={onApprove}>
          Share &amp; present
        </button>
        <button
          type="button"
          className="button--danger"
          disabled={busy}
          onClick={onReject}
        >
          Decline
        </button>
      </div>
    </section>
  );
}
