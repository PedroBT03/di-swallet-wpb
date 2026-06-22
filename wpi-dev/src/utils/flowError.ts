/** Holder-facing messages for issuance and presentation terminal errors. */
export function formatFlowError(code: string, message?: string): string {
  switch (code) {
    case "credentials_revoked":
      return (
        "Your matching credential is revoked and cannot be presented. " +
        "Go to Wallet → Issue to obtain a new PID or mDL, then try again."
      );
    case "no_matching_credentials":
      return (
        "No credential in your wallet matches this verifier request. " +
        "Issue the required PID or mDL on Wallet, sync, then try again."
      );
    case "policy_rejected":
      return message && message.trim().length > 0
        ? `Wallet policy blocked this request: ${message}`
        : "Wallet policy blocked this presentation request.";
    case "trust_rejected":
      return "This verifier is not trusted by your wallet.";
    case "registry_rejected":
      return message && message.trim().length > 0
        ? `Registry validation failed: ${message}`
        : "The verifier failed registry validation.";
    case "consent_denied":
      return "You declined to share credentials with this verifier.";
    default:
      break;
  }

  if (message && message.trim().length > 0) {
    return message;
  }
  return code.replaceAll("_", " ");
}
