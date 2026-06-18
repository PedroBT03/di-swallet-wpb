import type { PresentationConsentView } from "../../types/openid4vp";

interface ConsentScreenProps {
  view: PresentationConsentView;
  selectedIds: string[];
  onToggleCandidate: (candidateId: string, queryId: string) => void;
  onApprove: () => void;
  onReject: () => void;
  busy: boolean;
  selectionError: string | null;
}

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
  const warnings = [
    ...registryWarnings,
    ...minimization.warnings,
  ];

  return (
    <section className="card present-consent" aria-labelledby="consent-heading">
      <h2 id="consent-heading" className="card__title">
        Presentation consent
      </h2>

      <dl className="details-list">
        <div className="details-list__row">
          <dt>Verifier</dt>
          <dd>{verifier.displayName ?? verifier.clientId}</dd>
        </div>
        <div className="details-list__row">
          <dt>Client id</dt>
          <dd>
            <code>{verifier.clientId}</code>
          </dd>
        </div>
        <div className="details-list__row">
          <dt>Trust</dt>
          <dd>
            <span
              className={`status-badge status-badge--${verifier.trusted ? "up" : "down"}`}
            >
              {verifier.trusted ? "Trusted" : "Not trusted"}
            </span>
            {verifier.trustReason ? (
              <span className="hint present-consent__trust-reason"> — {verifier.trustReason}</span>
            ) : null}
          </dd>
        </div>
        {view.intendedUse.length > 0 ? (
          <div className="details-list__row">
            <dt>Intended use</dt>
            <dd>
              <ul className="present-consent__list">
                {view.intendedUse.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </dd>
          </div>
        ) : null}
        {view.privacyPolicyUri ? (
          <div className="details-list__row">
            <dt>Privacy policy</dt>
            <dd>
              <a href={view.privacyPolicyUri} target="_blank" rel="noreferrer">
                {view.privacyPolicyUri}
              </a>
            </dd>
          </div>
        ) : null}
      </dl>

      {warnings.length > 0 ? (
        <div className="alert alert--info present-consent__warnings" role="status">
          <strong>Warnings</strong>
          <ul className="present-consent__list">
            {warnings.map((w) => (
              <li key={w.code}>
                <code>{w.code}</code> — {w.message}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <div className="present-consent__section">
        <h3 className="present-consent__subtitle">Requested data</h3>
        {queries.length === 0 ? (
          <p className="hint">No DCQL queries in this request.</p>
        ) : (
          <ul className="present-query-list">
            {queries.map((query) => (
              <li key={query.queryId} className="present-query-list__item">
                <div className="present-query-list__head">
                  <strong>Query {query.queryId}</strong>
                  <span className="hint">{query.format}</span>
                </div>
                {query.credentialTypeHints.length > 0 ? (
                  <p className="hint">
                    Types: {query.credentialTypeHints.join(", ")}
                  </p>
                ) : null}
                <ul className="present-claim-list">
                  {query.requestedClaims.map((claim) => (
                    <li key={`${query.queryId}-${claim.path}`}>
                      <code>{claim.path}</code>
                      {claim.label !== claim.path ? (
                        <span className="hint"> — {claim.label}</span>
                      ) : null}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        )}
      </div>

      {choiceGroups.length > 0 ? (
        <div className="present-consent__section">
          <h3 className="present-consent__subtitle">Credentials to present</h3>
          {choiceGroups.map((group) => (
            <fieldset key={group.queryId} className="present-choice-group">
              <legend>
                {group.credentialType} ({group.format})
                {group.requiresUserSelection ? (
                  <span className="hint"> — choose one</span>
                ) : null}
              </legend>
              {group.candidates.length === 0 ? (
                <p className="hint">No matching credentials in wallet.</p>
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
                          <span>
                            {candidate.label}
                            {candidate.credentialId != null ? (
                              <span className="hint"> (id {candidate.credentialId})</span>
                            ) : null}
                            {candidate.deviceBound ? (
                              <span className="status-badge status-badge--up present-choice-list__badge">
                                device-bound
                              </span>
                            ) : null}
                          </span>
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

      {selectionError ? (
        <div className="alert alert--error" role="alert">
          {selectionError}
        </div>
      ) : null}

      <div className="toolbar toolbar--compact present-consent__actions">
        <button type="button" disabled={busy} onClick={onApprove}>
          Approve &amp; present
        </button>
        <button
          type="button"
          className="button--danger"
          disabled={busy}
          onClick={onReject}
        >
          Reject
        </button>
      </div>
    </section>
  );
}
