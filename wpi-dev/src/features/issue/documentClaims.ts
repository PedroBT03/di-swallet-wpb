export interface DocumentClaimOption {
  id: string;
  label: string;
  group: string;
}

/** Mandatory CIR PID attributes + metadata issued in the demo (SD-JWT claim names). */
export const PID_ISSUANCE_CLAIMS: DocumentClaimOption[] = [
  { id: "given_name", label: "Given name", group: "Mandatory attributes" },
  { id: "family_name", label: "Family name", group: "Mandatory attributes" },
  { id: "birthdate", label: "Date of birth", group: "Mandatory attributes" },
  { id: "place_of_birth", label: "Place of birth", group: "Mandatory attributes" },
  { id: "nationalities", label: "Nationality", group: "Mandatory attributes" },
  { id: "date_of_expiry", label: "Expiry date", group: "Mandatory metadata" },
  { id: "issuing_authority", label: "Issuing authority", group: "Mandatory metadata" },
  { id: "issuing_country", label: "Issuing country", group: "Mandatory metadata" },
  { id: "address.locality", label: "City (demo optional)", group: "Demo optional" },
  { id: "address.country", label: "Country (demo optional)", group: "Demo optional" },
];

/** mDL claims returned by the simulator (ISO 18013-5 namespace). */
export const MDL_ISSUANCE_CLAIMS: DocumentClaimOption[] = [
  { id: "given_name", label: "Given name", group: "mDL attributes" },
  { id: "family_name", label: "Family name", group: "mDL attributes" },
  { id: "birth_date", label: "Date of birth", group: "mDL attributes" },
  { id: "driving_privileges", label: "Driving categories", group: "mDL attributes" },
  { id: "issuing_country", label: "Issuing country", group: "mDL attributes" },
];

export function groupDocumentClaims(
  options: DocumentClaimOption[],
): Record<string, DocumentClaimOption[]> {
  return options.reduce<Record<string, DocumentClaimOption[]>>((groups, option) => {
    if (!groups[option.group]) {
      groups[option.group] = [];
    }
    groups[option.group].push(option);
    return groups;
  }, {});
}
