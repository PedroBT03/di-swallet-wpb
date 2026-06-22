/** Maps stored credential configuration ids to holder-facing labels. */
export function formatCredentialTypeLabel(credentialType: string): string {
  const normalized = credentialType.trim().toLowerCase().replaceAll("-", "_");
  if (
    normalized === "pid" ||
    normalized === "pid_jwt" ||
    normalized === "eu.europa.ec.eudi.pid_jwt_vc_json" ||
    normalized.includes("pid_jwt") ||
    normalized.includes("eudi.pid")
  ) {
    return "PID";
  }
  if (
    normalized === "mdl" ||
    normalized.includes("mdoc_mdl") ||
    normalized.includes("driving_licence") ||
    normalized.includes("driver_license") ||
    normalized.includes("18013") ||
    normalized.includes("mdl")
  ) {
    return "Driving licence";
  }
  if (!credentialType.trim()) {
    return credentialType;
  }
  return credentialType
    .split(/[_-]+/)
    .filter((part) => part.length > 0)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}
