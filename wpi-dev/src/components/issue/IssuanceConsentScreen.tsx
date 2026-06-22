import type { IssuanceConsentView } from "../../types/openid4vci";

interface IssuanceConsentScreenProps {
  view: IssuanceConsentView;
  onApprove: () => void;
  onReject: () => void;
  busy: boolean;
}

export function IssuanceConsentScreen({
  view,
  onApprove,
  onReject,
  busy,
}: IssuanceConsentScreenProps) {
  const { issuer, claimPreview } = view;

  return (
    <section className="card present-consent issue-consent" aria-labelledby="issuance-consent-heading">
      <h2 id="issuance-consent-heading" className="card__title">
        Storage consent
      </h2>
      <p className="hint issue-consent__lead">
        Review what will be stored in your wallet before approving (ISSU_11).
      </p>

      <dl className="details-list">
        <div className="details-list__row">
          <dt>Issuer</dt>
          <dd>{issuer.displayName ?? issuer.credentialIssuerId ?? "Unknown issuer"}</dd>
        </div>
        {issuer.credentialIssuerId ? (
          <div className="details-list__row">
            <dt>Credential issuer</dt>
            <dd>
              <code>{issuer.credentialIssuerId}</code>
            </dd>
          </div>
        ) : null}
        {view.credentialConfigurationId ? (
          <div className="details-list__row">
            <dt>Configuration</dt>
            <dd>
              <code>{view.credentialConfigurationId}</code>
            </dd>
          </div>
        ) : null}
        <div className="details-list__row">
          <dt>Format</dt>
          <dd>
            <code>{view.format}</code>
          </dd>
        </div>
        <div className="details-list__row">
          <dt>Device-bound</dt>
          <dd>
            <span className={`status-badge status-badge--${view.deviceBound ? "up" : "unknown"}`}>
              {view.deviceBound ? "Yes" : "No"}
            </span>
          </dd>
        </div>
      </dl>

      <div className="present-consent__section">
        <h3 className="present-consent__subtitle">Claim preview</h3>
        {claimPreview.length === 0 ? (
          <p className="hint">No claim preview available for this credential.</p>
        ) : (
          <ul className="present-claim-list">
            {claimPreview.map((claim) => (
              <li key={claim.name}>
                <code>{claim.name}</code>
                {claim.previewAvailable && claim.value != null ? (
                  <span className="hint">: {claim.value}</span>
                ) : (
                  <span className="hint"> (preview unavailable)</span>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="toolbar toolbar--compact present-consent__actions">
        <button type="button" disabled={busy} onClick={onApprove}>
          Approve storage
        </button>
        <button type="button" className="button--danger" disabled={busy} onClick={onReject}>
          Reject
        </button>
      </div>
    </section>
  );
}
