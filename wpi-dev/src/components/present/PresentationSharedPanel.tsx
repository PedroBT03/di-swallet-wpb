import { useEffect, useMemo, useState } from "react";
import {
  extractPresentationTokens,
  formatClaimValue,
  isSdContainerValue,
} from "../../features/present/sdJwtInspect";
import {
  listDisclosedClaims,
  parsePresentationToken,
  presentationFormatLabel,
  type ParsedPresentation,
} from "../../features/present/presentationParse";
import type { VpToken } from "../../types/openid4vp";

type SharedTab = "values" | "token" | "structure";

interface PresentationSharedPanelProps {
  vpToken: VpToken | null | undefined;
}

export function PresentationSharedPanel({ vpToken }: PresentationSharedPanelProps) {
  const [tab, setTab] = useState<SharedTab>("values");
  const [parsed, setParsed] = useState<ParsedPresentation[]>([]);
  const [parseError, setParseError] = useState<string | null>(null);

  const tokens = useMemo(
    () => extractPresentationTokens(vpToken?.presentationsByQueryId),
    [vpToken?.presentationsByQueryId],
  );

  useEffect(() => {
    let cancelled = false;
    if (tokens.length === 0) {
      setParsed([]);
      setParseError(null);
      return;
    }
    void (async () => {
      try {
        const next = await Promise.all(
          tokens.map(({ queryId, presentation }) => parsePresentationToken(presentation, queryId)),
        );
        if (!cancelled) {
          setParsed(next);
          setParseError(null);
        }
      } catch (err) {
        if (!cancelled) {
          setParseError(err instanceof Error ? err.message : "Failed to parse presentation token");
          setParsed([]);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [tokens]);

  if (!vpToken || tokens.length === 0) {
    return (
      <div className="present-shared">
        <p className="hint">
          No presentation token in the session response. Use Developer details to refresh the
          session after unlock.
        </p>
      </div>
    );
  }

  const disclosedClaims = parsed.flatMap((item) => listDisclosedClaims(item));
  const formats = [...new Set(parsed.map((item) => presentationFormatLabel(item)))];

  return (
    <div className="present-shared">
      <h3 className="present-shared__title">What was shared</h3>
      <p className="hint present-shared__lead">
        Plaintext values disclosed in this presentation.
        {formats.includes("SD-JWT") ? (
          <>
            {" "}
            PID uses SD-JWT disclosures; nested <code>_sd</code> containers are omitted.
          </>
        ) : null}
        {formats.includes("ISO 18013-5 mdoc (DeviceResponse)") ? (
          <>
            {" "}
            mDL uses an ISO 18013-5 <code>DeviceResponse</code> (CBOR, base64url) with
            device-signed namespaces — not a JWT.
          </>
        ) : null}
      </p>

      <div className="tab-bar" role="tablist" aria-label="Shared presentation views">
        <button
          type="button"
          role="tab"
          aria-selected={tab === "values"}
          className={`tab-bar__btn ${tab === "values" ? "is-active" : ""}`}
          onClick={() => setTab("values")}
        >
          Shared values
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === "token"}
          className={`tab-bar__btn ${tab === "token" ? "is-active" : ""}`}
          onClick={() => setTab("token")}
        >
          Wire token
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === "structure"}
          className={`tab-bar__btn ${tab === "structure" ? "is-active" : ""}`}
          onClick={() => setTab("structure")}
        >
          Token structure
        </button>
      </div>

      {parseError ? (
        <div className="alert alert--error" role="alert">
          {parseError}
        </div>
      ) : null}

      {tab === "values" ? (
        <div className="present-shared__panel" role="tabpanel">
          {parsed.some((item) => item.kind === "sd-jwt" && item.isDemoStub) ? (
            <p className="hint">
              Demo stub token (no real SD-JWT). Issue a PID on Wallet and present again for
              claim-level debug values.
            </p>
          ) : null}
          {parsed.some((item) => item.kind === "mdoc" && item.isDemoStub) ? (
            <p className="hint">
              Demo mdoc fallback (<code>demo.mdoc</code>) — the wallet had no stored mDL bound to
              this presentation. Sync an issued mDL on Wallet, then pick the real credential at
              consent (not the synthetic demo candidate).
            </p>
          ) : null}
          {disclosedClaims.length === 0 ? (
            <p className="hint">No decoded claim values in this token.</p>
          ) : (
            <div className="table-wrap">
              <table className="data-table present-shared__table">
                <thead>
                  <tr>
                    <th>Claim</th>
                    <th>Value (debug)</th>
                    {parsed.length > 1 ? <th>Query</th> : null}
                  </tr>
                </thead>
                <tbody>
                  {disclosedClaims.map((row) => (
                    <tr key={`${row.queryId ?? "q"}-${row.claim}`}>
                      <td>
                        <code>{row.claim}</code>
                      </td>
                      <td>
                        <strong>{formatClaimValue(row.value)}</strong>
                      </td>
                      {parsed.length > 1 ? <td>{row.queryId ?? "pid"}</td> : null}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ) : null}

      {tab === "token" ? (
        <div className="present-shared__panel" role="tabpanel">
          {parsed.map((item) => (
            <div key={item.queryId ?? item.presentation} className="present-shared__token-block">
              {item.queryId ? (
                <p className="hint present-shared__query-label">
                  Query <code>{item.queryId}</code>
                </p>
              ) : null}
              <pre className="present-shared__pre mono-sm">{item.presentation}</pre>
            </div>
          ))}
        </div>
      ) : null}

      {tab === "structure" ? (
        <div className="present-shared__panel" role="tabpanel">
          {parsed.map((item) => (
            <div key={`struct-${item.queryId ?? "default"}`} className="present-shared__struct">
              {item.queryId ? (
                <h4 className="present-shared__struct-title">
                  Query {item.queryId} ({presentationFormatLabel(item)})
                </h4>
              ) : (
                <h4 className="present-shared__struct-title">{presentationFormatLabel(item)}</h4>
              )}
              {item.kind === "sd-jwt" && item.isDemoStub ? (
                <p className="hint">Stub: {item.presentation}</p>
              ) : null}
              {item.kind === "mdoc" ? (
                <>
                  <section className="present-shared__struct-section">
                    <h5>DeviceResponse (decoded CBOR)</h5>
                    <p className="hint present-shared__struct-note">
                      Remote OpenID4VP carries mDL as a <code>DeviceResponse</code> artifact (not
                      NFC/BLE). <code>deviceSigned.nameSpaces</code> holds the selectively disclosed
                      attributes sent to the verifier.
                    </p>
                    <p className="hint">
                      docType: <code>{item.docType}</code>
                      {item.version ? (
                        <>
                          {" "}
                          · version <code>{item.version}</code>
                        </>
                      ) : null}
                    </p>
                    <pre className="present-shared__pre mono-sm">
                      {JSON.stringify(item.structure, replacerBigInt, 2)}
                    </pre>
                  </section>
                </>
              ) : null}
              {item.kind === "sd-jwt" && !item.isDemoStub ? (
                <>
                  <section className="present-shared__struct-section">
                    <h5>Issuer-signed JWT payload</h5>
                    <p className="hint present-shared__struct-note">
                      Top-level <code>_sd</code> lists digests of all disclosable fields in the
                      PID. Only disclosures after <code>~</code> in the wire token reveal values.
                    </p>
                    <pre className="present-shared__pre mono-sm">
                      {JSON.stringify(item.issuerPayload, null, 2)}
                    </pre>
                  </section>
                  <section className="present-shared__struct-section">
                    <h5>Disclosures in this presentation</h5>
                    {item.disclosures.length === 0 ? (
                      <p className="hint">None</p>
                    ) : (
                      <ul className="present-shared__disc-list">
                        {item.disclosures.map((disc) => (
                          <li key={disc.raw}>
                            <div>
                              <code>{disc.claim}</code>
                              {isSdContainerValue(disc.value) ? (
                                <span className="hint"> (nested container, not a leaf value)</span>
                              ) : null}
                              <span className="hint">
                                {" "}
                                digest {disc.digest.slice(0, 12)}…
                              </span>
                            </div>
                            <pre className="present-shared__pre mono-sm">{disc.raw}</pre>
                            <pre className="present-shared__pre mono-sm present-shared__pre--decoded">
                              {JSON.stringify([disc.salt, disc.claim, disc.value], null, 2)}
                            </pre>
                          </li>
                        ))}
                      </ul>
                    )}
                  </section>
                  {item.keyBindingJwt ? (
                    <section className="present-shared__struct-section">
                      <h5>Key-binding JWT</h5>
                      <pre className="present-shared__pre mono-sm">{item.keyBindingJwt}</pre>
                      <pre className="present-shared__pre mono-sm">
                        {JSON.stringify(item.keyBindingPayload, null, 2)}
                      </pre>
                    </section>
                  ) : null}
                </>
              ) : null}
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}

function replacerBigInt(_key: string, value: unknown): unknown {
  if (typeof value === "bigint") {
    return value.toString();
  }
  if (value instanceof Uint8Array) {
    return `<bytes len=${value.length}>`;
  }
  return value;
}
