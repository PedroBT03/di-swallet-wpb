const CMD_PORTAL_URL = "https://cmd.autenticacao.gov.pt/";

interface CmdIdentityPanelProps {
  busy?: boolean;
  onContinue: () => void;
  onCancel: () => void;
}

export function CmdIdentityPanel({
  busy = false,
  onContinue,
  onCancel,
}: CmdIdentityPanelProps) {
  return (
    <section className="card present-unlock issue-cmd">
      <h2 className="card__title">Chave Móvel Digital (CMD)</h2>
      <p className="present-unlock__lead">
        In production, the PID Provider redirects you to the{" "}
        <strong>Chave Móvel Digital</strong> portal to prove your identity at LoA High before the
        credential is issued. The issuer receives an <code>id_token</code> with your citizen{" "}
        <code>sub</code> and binds it to this wallet.
      </p>

      <ol className="issue-cmd__steps">
        <li>Wallet resolves the credential offer and authenticates the issuer.</li>
        <li>
          Browser opens the{" "}
          <a href={CMD_PORTAL_URL} target="_blank" rel="noreferrer">
            CMD portal
          </a>{" "}
          (NIF, PIN, OTP).
        </li>
        <li>Issuer validates wallet attestations (WIA + KA) and issues the credential.</li>
        <li>
          Lab: use <strong>Simulate CMD login &amp; continue</strong> below to skip the redirect.
        </li>
      </ol>

      <div className="toolbar toolbar--compact">
        <button type="button" disabled={busy} onClick={onContinue}>
          {busy ? "Working…" : "Simulate CMD login & continue"}
        </button>
        <button type="button" className="button--secondary" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
      </div>
    </section>
  );
}
