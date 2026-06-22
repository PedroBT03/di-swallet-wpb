import { ProtocolExchangePanel } from "../ProtocolExchangePanel";

const CMD_PORTAL_URL = "https://cmd.autenticacao.gov.pt/";

interface CmdIdentityPanelProps {
  authorizationUrl?: string | null;
  wiaJwtPreview?: string | null;
  busy?: boolean;
  onContinue: () => void;
  onCancel: () => void;
}

export function CmdIdentityPanel({
  authorizationUrl,
  wiaJwtPreview,
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
        credential is issued. You authenticate with <strong>NIF + PIN + OTP</strong>; the issuer
        receives an <code>id_token</code> with your citizen <code>sub</code> and binds it to this
        wallet.
      </p>

      <ol className="issue-cmd__steps">
        <li>Wallet resolves the credential offer and authenticates the issuer.</li>
        <li>Browser opens the issuer authorization endpoint (below in a real deployment).</li>
        <li>CMD portal: citizen login (NIF, PIN, OTP).</li>
        <li>Issuer validates wallet (WIA/WUA) and issues the PID or attestation.</li>
      </ol>

      <div className="toolbar toolbar--compact">
        <button type="button" disabled={busy} onClick={onContinue}>
          {busy ? "Working…" : "Simulate CMD login & continue"}
        </button>
        <button type="button" className="button--secondary" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
      </div>

      <ProtocolExchangePanel
        title="Developer: CMD & OAuth exchange"
        summary="Lab shortcut: the button above skips the real CMD redirect and exchanges a simulated authorization code. WIA is attached to the OAuth request per ARF Topic 9 / ISSU_21."
        items={[
          { label: "CMD portal (production)", value: CMD_PORTAL_URL },
          {
            label: "Issuer authorization URL (simulated)",
            value: authorizationUrl ?? "(available after Prepare authorization)",
          },
          {
            label: "WIA JWT (preview)",
            value: wiaJwtPreview
              ? wiaJwtPreview.length > 72
                ? `${wiaJwtPreview.slice(0, 72)}…`
                : wiaJwtPreview
              : "(attached when authorization is prepared)",
          },
          {
            label: "Identity binding (production)",
            value: "PATCH /wallet/{wallet_id}/identity { user_sub }",
          },
        ]}
      />
    </section>
  );
}
