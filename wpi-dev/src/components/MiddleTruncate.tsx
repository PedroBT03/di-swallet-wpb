interface MiddleTruncateProps {
  text: string;
  /** Characters kept visible at the end (suffix). */
  endChars?: number;
  className?: string;
  title?: string;
}

/**
 * Fills available width and shows start…end so long tokens (e.g. JWTs) remain distinguishable.
 */
export function MiddleTruncate({
  text,
  endChars = 10,
  className,
  title,
}: MiddleTruncateProps) {
  if (text.length <= endChars + 2) {
    return (
      <code className={className} title={title ?? text}>
        {text}
      </code>
    );
  }

  const end = text.slice(-endChars);
  const start = text.slice(0, -endChars);

  return (
    <code className={className} title={title ?? text}>
      <span className="middle-truncate">
        <span className="middle-truncate__start">{start}</span>
        <span className="middle-truncate__sep" aria-hidden="true">
          …
        </span>
        <span className="middle-truncate__end">{end}</span>
      </span>
    </code>
  );
}
