import type { PrivacyAction } from "../../types/privacy";

interface ActionUriListProps {
  actions: PrivacyAction[];
  emptyMessage?: string;
}

function displayChannel(action: PrivacyAction): string {
  if (action.channel) {
    const lower = action.channel.toLowerCase();
    return lower.charAt(0).toUpperCase() + lower.slice(1);
  }
  const uri = action.uri.toLowerCase();
  if (uri.startsWith("mailto:")) return "Email";
  if (uri.startsWith("tel:")) return "Phone";
  if (uri.startsWith("http://") || uri.startsWith("https://")) return "Web";
  return "Contact";
}

export function ActionUriList({ actions, emptyMessage }: ActionUriListProps) {
  if (actions.length === 0) {
    return <p className="hint">{emptyMessage ?? "No contact actions available."}</p>;
  }

  return (
    <ul className="action-uri-list">
      {actions.map((action) => (
        <li key={`${action.channel}:${action.uri}`} className="action-uri-list__item">
          <div className="action-uri-list__meta">
            <span className="action-uri-list__channel">{displayChannel(action)}</span>
          </div>
          <code className="action-uri-list__uri">{action.uri}</code>
          <div className="action-uri-list__buttons">
            <button
              type="button"
              className="button button--secondary button--sm"
              onClick={() => void navigator.clipboard.writeText(action.uri)}
            >
              Copy
            </button>
            <a className="button button--secondary button--sm" href={action.uri} target="_blank" rel="noreferrer">
              Open
            </a>
          </div>
        </li>
      ))}
    </ul>
  );
}
