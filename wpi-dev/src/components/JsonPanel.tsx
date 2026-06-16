interface JsonPanelProps {
  title?: string;
  data: unknown;
  defaultOpen?: boolean;
}

export function JsonPanel({ title = "Raw response", data, defaultOpen = false }: JsonPanelProps) {
  return (
    <details className="json-panel" open={defaultOpen}>
      <summary>{title}</summary>
      <pre className="raw-json__pre">{JSON.stringify(data, null, 2)}</pre>
    </details>
  );
}
