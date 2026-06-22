import { JsonPanel } from "./JsonPanel";

interface ProtocolExchangePanelProps {
  title: string;
  summary?: string;
  items: { label: string; value: string | null | undefined; mono?: boolean }[];
  payload?: unknown;
  payloadTitle?: string;
  defaultOpen?: boolean;
}

export function ProtocolExchangePanel({
  title,
  summary,
  items,
  payload,
  payloadTitle = "Payload",
  defaultOpen = false,
}: ProtocolExchangePanelProps) {
  const visible = items.filter((item) => item.value != null && item.value !== "");

  if (visible.length === 0 && payload == null) {
    return null;
  }

  return (
    <details className="present-dev-details protocol-exchange" open={defaultOpen}>
      <summary>{title}</summary>
      <div className="present-dev-details__body">
        {summary ? <p className="hint protocol-exchange__summary">{summary}</p> : null}
        {visible.length > 0 ? (
          <dl className="details-list protocol-exchange__list">
            {visible.map((item) => (
              <div key={item.label} className="details-list__row">
                <dt>{item.label}</dt>
                <dd>
                  {item.mono !== false ? (
                    <code className="details-list__truncate" title={item.value ?? undefined}>
                      {item.value}
                    </code>
                  ) : (
                    item.value
                  )}
                </dd>
              </div>
            ))}
          </dl>
        ) : null}
        {payload != null ? <JsonPanel title={payloadTitle} data={payload} /> : null}
      </div>
    </details>
  );
}
