import { Link } from "react-router-dom";
import { JsonPanel } from "../JsonPanel";
import {
  decodeJwtParts,
  formatInitRequestForDev,
  type WalletInitExchange,
} from "../../features/wallet/initExchange";
import type { WalletInitResult } from "../../types/wallet";

const CMD_PORTAL_URL = "https://cmd.autenticacao.gov.pt/";

interface WalletProvisionStatusProps {
  walletState: WalletInitResult | null;
}

/** Compact status shown inside the wallet unit panel after provision. */
export function WalletProvisionStatus({ walletState }: WalletProvisionStatusProps) {
  if (!walletState?.walletId) {
    return null;
  }

  const isCandidate = walletState.state === "CANDIDATE";
  const isValid = walletState.state === "VALID";

  return (
    <div className="wallet-provision-status">
      <p className="wallet-provision-status__summary">
        Wallet created. Device bound, holder key in HSM, attestations issued (WIA + KA).
      </p>

      {isCandidate ? (
        <div className="wallet-cmd-prompt">
          <p className="wallet-cmd-prompt__text">
            <strong>Next:</strong> identify with{" "}
            <a href={CMD_PORTAL_URL} target="_blank" rel="noreferrer">
              Chave Móvel Digital
            </a>{""}
            . In production the browser opens the CMD portal here.
          </p>
          <p className="hint wallet-cmd-prompt__lab">
            Lab: no redirect. On <Link to="/issue">Issue</Link>, use{" "}
            <em>Simulate CMD login &amp; continue</em> when issuing a PID.
          </p>
          <Link to="/issue" className="button button--secondary button--sm">
            Continue to Issue
          </Link>
        </div>
      ) : null}

      {isValid ? (
        <p className="wallet-provision-status__valid hint">
          Identity verified. Wallet state is <code>VALID</code>.
        </p>
      ) : null}
    </div>
  );
}

interface WalletInitDevPanelProps {
  exchange: WalletInitExchange | null;
  walletState: WalletInitResult | null;
}

/** Collapsed developer payloads; kept separate so the main layout stays uniform. */
export function WalletInitDevPanel({ exchange, walletState }: WalletInitDevPanelProps) {
  if (!exchange) {
    return null;
  }

  const wiaJwt = exchange.response.wia ?? walletState?.wia;
  const wiaDecoded = wiaJwt ? decodeJwtParts(wiaJwt) : null;
  const kaJwt = exchange.response.ka ?? walletState?.ka;
  const kaDecoded = kaJwt ? decodeJwtParts(kaJwt) : null;

  return (
    <section className="card wallet-panel">
      <details className="present-dev-details protocol-exchange wallet-init-dev">
        <summary>Developer: provision payloads</summary>
        <div className="present-dev-details__body">
          <p className="hint protocol-exchange__summary">
            <code>{exchange.endpoint}</code> at{" "}
            {new Date(exchange.completedAt).toLocaleString()}
            {exchange.postSync
              ? ` · summary sync ${new Date(exchange.postSync.completedAt).toLocaleString()}`
              : null}
          </p>
          <dl className="details-list protocol-exchange__list">
            <div className="details-list__row">
              <dt>wallet_id</dt>
              <dd>
                <code>{exchange.response.walletId}</code>
              </dd>
            </div>
            <div className="details-list__row">
              <dt>state</dt>
              <dd>
                <code>{exchange.response.state}</code>
              </dd>
            </div>
          </dl>
          <JsonPanel
            title="POST /wallet/init request"
            data={formatInitRequestForDev(exchange.request)}
          />
          <JsonPanel title="POST /wallet/init response" data={exchange.response} />
          {exchange.postSync ? (
            <JsonPanel
              title="GET /wallet/summary after sync"
              data={{
                walletUnit: exchange.postSync.walletUnit,
                key: exchange.postSync.key,
              }}
            />
          ) : null}
          {wiaJwt ? <JsonPanel title="WIA JWT" data={{ wia: wiaJwt }} /> : null}
          {wiaDecoded ? (
            <JsonPanel title="WIA JWT decoded" data={wiaDecoded} />
          ) : null}
          {kaJwt ? <JsonPanel title="KA JWT" data={{ ka: kaJwt }} /> : null}
          {kaDecoded ? (
            <JsonPanel title="KA JWT decoded" data={kaDecoded} />
          ) : null}
        </div>
      </details>
    </section>
  );
}
