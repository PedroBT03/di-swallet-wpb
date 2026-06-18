const LOG_KEY_KEY = "wpi-dev.logKeyB64";

export function saveLogKeyBase64(value: string): void {
  sessionStorage.setItem(LOG_KEY_KEY, value);
}

export function loadLogKeyBase64(): string | null {
  return sessionStorage.getItem(LOG_KEY_KEY);
}

export function clearLogKeyBase64(): void {
  sessionStorage.removeItem(LOG_KEY_KEY);
}
