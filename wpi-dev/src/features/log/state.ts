import type { TransactionLogSummary } from "../../types/transactionLog";

export function formatOccurredAt(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

export function transactionTypeLabel(type: string): string {
  return type.replace(/([a-z])([A-Z])/g, "$1 $2");
}

export function resultBadgeVariant(result: string): "up" | "down" | "unknown" {
  if (result === "Completed") return "up";
  if (result === "NotCompleted") return "down";
  return "unknown";
}

export function sortNewestFirst(entries: TransactionLogSummary[]): TransactionLogSummary[] {
  return [...entries].sort(
    (a, b) => new Date(b.occurredAt).getTime() - new Date(a.occurredAt).getTime(),
  );
}

export function downloadTextFile(filename: string, content: string, mimeType: string): void {
  const blob = new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}
