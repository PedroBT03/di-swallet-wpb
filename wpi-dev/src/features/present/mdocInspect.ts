import { decode } from "cbor-x";
import { base64UrlToBytes } from "../../auth/base64";
import type { SharedLeafClaim } from "./sdJwtInspect";

export interface ParsedMdocPresentation {
  queryId?: string;
  presentation: string;
  format: "mdoc";
  docType: string;
  version: string | null;
  /** Decoded CBOR tree (for structure tab). */
  structure: unknown;
  disclosedClaims: SharedLeafClaim[];
  isDemoStub: boolean;
}

export function isMdocPresentation(presentation: string): boolean {
  if (!presentation || presentation.startsWith("demo-vp:")) {
    return false;
  }
  if (presentation.startsWith("eyJ") || presentation.includes("~")) {
    return false;
  }
  return /^[A-Za-z0-9_-]+$/.test(presentation);
}

function decodeEmbeddedCbor(value: unknown): unknown {
  if (value instanceof Uint8Array) {
    try {
      return decodeEmbeddedCbor(decode(value));
    } catch {
      return value;
    }
  }
  if (Array.isArray(value)) {
    return value.map((entry) => decodeEmbeddedCbor(entry));
  }
  if (value && typeof value === "object") {
    const record = value as Record<string, unknown>;
    const out: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(record)) {
      out[String(key)] = decodeEmbeddedCbor(entry);
    }
    return out;
  }
  return value;
}

function shortClaimName(claim: string): string {
  return claim.includes(".") ? (claim.split(".").pop() ?? claim) : claim;
}

const MDOC_METADATA_KEYS = new Set(["digestID", "random", "elementIdentifier", "elementValue", "tag"]);

function isPlainClaimMap(value: unknown): value is Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return false;
  }
  const keys = Object.keys(value);
  if (keys.length === 0) {
    return false;
  }
  return keys.every((key) => !/^\d+$/.test(key) && !MDOC_METADATA_KEYS.has(key));
}

function tryParseJsonValue(text: string): unknown | null {
  const trimmed = text.trim();
  if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
    return null;
  }
  try {
    return JSON.parse(trimmed) as unknown;
  } catch {
    return null;
  }
}

function normalizeClaimValue(value: unknown): unknown {
  const decoded = decodeEmbeddedCbor(value);
  if (typeof decoded === "string") {
    return tryParseJsonValue(decoded) ?? decoded;
  }
  return decoded;
}

function pushFlattenedClaims(
  claims: SharedLeafClaim[],
  namespace: string,
  claim: string,
  value: unknown,
): void {
  const normalized = normalizeClaimValue(value);

  if (/^\d+$/.test(claim) && isPlainClaimMap(normalized)) {
    for (const [nestedClaim, nestedValue] of Object.entries(normalized)) {
      claims.push({ claim: shortClaimName(nestedClaim), value: normalizeClaimValue(nestedValue) });
    }
    return;
  }

  if (isPlainClaimMap(normalized) && (claim === namespace || /^\d+$/.test(claim))) {
    for (const [nestedClaim, nestedValue] of Object.entries(normalized)) {
      claims.push({ claim: shortClaimName(nestedClaim), value: normalizeClaimValue(nestedValue) });
    }
    return;
  }

  if (isPlainClaimMap(normalized) && Object.keys(normalized).every((key) => !key.includes("."))) {
    for (const [nestedClaim, nestedValue] of Object.entries(normalized)) {
      claims.push({ claim: shortClaimName(nestedClaim), value: normalizeClaimValue(nestedValue) });
    }
    return;
  }

  claims.push({
    claim: shortClaimName(claim.includes(".") ? claim : `${namespace}.${claim}`),
    value: normalized,
  });
}

function pushIssuerItemClaims(
  claims: SharedLeafClaim[],
  namespace: string,
  item: unknown,
): void {
  const map = unwrapTaggedItem(item);
  if (!map) {
    return;
  }
  const identifier = map.elementIdentifier;
  if (typeof identifier === "string") {
    const normalizedValue = normalizeClaimValue(map.elementValue);
    if (/^\d+$/.test(identifier) && isPlainClaimMap(normalizedValue)) {
      for (const [nestedClaim, nestedValue] of Object.entries(normalizedValue)) {
        claims.push({ claim: shortClaimName(nestedClaim), value: normalizeClaimValue(nestedValue) });
      }
      return;
    }
    claims.push({
      claim: shortClaimName(`${namespace}.${identifier}`),
      value: normalizedValue,
    });
    return;
  }
  const normalized = normalizeClaimValue(map);
  if (isPlainClaimMap(normalized)) {
    for (const [nestedClaim, nestedValue] of Object.entries(normalized)) {
      claims.push({ claim: shortClaimName(nestedClaim), value: normalizeClaimValue(nestedValue) });
    }
  }
}

/** Unwraps CBOR tag-24 style `{ value, tag }` wrappers around issuer items. */
function unwrapTaggedItem(item: unknown): Record<string, unknown> | null {
  const decoded = decodeEmbeddedCbor(item);
  if (!decoded || typeof decoded !== "object" || Array.isArray(decoded)) {
    return null;
  }
  const record = decoded as Record<string, unknown>;
  if (record.value && typeof record.value === "object" && !Array.isArray(record.value)) {
    return record.value as Record<string, unknown>;
  }
  return record;
}

/**
 * Reads claims from mdoc nameSpaces — both IssuerSigned item arrays
 * (`elementIdentifier` / `elementValue`) and DeviceSigned maps (`claim → value`).
 */
function extractNamespaceClaims(nameSpaces: unknown): SharedLeafClaim[] {
  const namespaces = decodeEmbeddedCbor(nameSpaces) as Record<string, unknown> | null;
  if (!namespaces || typeof namespaces !== "object" || Array.isArray(namespaces)) {
    return [];
  }

  const claims: SharedLeafClaim[] = [];
  for (const [namespace, namespaceValue] of Object.entries(namespaces)) {
    const decoded = normalizeClaimValue(namespaceValue);

    if (Array.isArray(decoded)) {
      for (const item of decoded) {
        pushIssuerItemClaims(claims, namespace, item);
      }
      continue;
    }

    if (decoded && typeof decoded === "object" && !Array.isArray(decoded)) {
      const record = decoded as Record<string, unknown>;
      if (isPlainClaimMap(record) && !namespace.includes(".") && !/^\d+$/.test(namespace)) {
        for (const [claim, value] of Object.entries(record)) {
          pushFlattenedClaims(claims, namespace, claim, value);
        }
        continue;
      }
      for (const [claim, value] of Object.entries(record)) {
        pushFlattenedClaims(claims, namespace, claim, value);
      }
    }
  }

  return claims.sort((a, b) => a.claim.localeCompare(b.claim));
}

function extractDeviceSignedClaims(deviceSigned: unknown): SharedLeafClaim[] {
  const signed = decodeEmbeddedCbor(deviceSigned) as Record<string, unknown> | null;
  if (!signed) {
    return [];
  }
  return extractNamespaceClaims(signed.nameSpaces);
}

function isIssuerSignedRoot(root: Record<string, unknown>): boolean {
  return root.nameSpaces != null && root.issuerAuth != null && root.documents == null;
}

function inferDocTypeFromRoot(root: Record<string, unknown>): string {
  if (typeof root.docType === "string") {
    return root.docType;
  }
  const namespaces = decodeEmbeddedCbor(root.nameSpaces) as Record<string, unknown> | null;
  if (namespaces && typeof namespaces === "object" && !Array.isArray(namespaces)) {
    const firstKey = Object.keys(namespaces)[0];
    if (firstKey === "demo.mdoc") {
      return "demo.mdoc";
    }
    if (firstKey?.includes("18013")) {
      return "org.iso.18013.5.1.mDL";
    }
    if (firstKey) {
      return firstKey;
    }
  }
  return "issuer-signed";
}

/** Parses an ISO 18013-5 mdoc artifact (base64url CBOR) for lab debug views. */
export function parseMdocPresentation(
  presentation: string,
  queryId?: string,
): ParsedMdocPresentation {
  if (presentation.startsWith("demo-vp:")) {
    return {
      queryId,
      presentation,
      format: "mdoc",
      docType: "demo",
      version: null,
      structure: null,
      disclosedClaims: [],
      isDemoStub: true,
    };
  }

  const bytes = base64UrlToBytes(presentation);
  const root = decodeEmbeddedCbor(decode(bytes)) as Record<string, unknown>;

  if (root.documents && Array.isArray(root.documents) && root.documents.length > 0) {
    const document = decodeEmbeddedCbor(root.documents[0]) as Record<string, unknown>;
    const docType = String(document.docType ?? "unknown");
    const disclosedClaims = extractDeviceSignedClaims(document.deviceSigned).map((claim) => ({
      ...claim,
      queryId,
    }));

    return {
      queryId,
      presentation,
      format: "mdoc",
      docType,
      version: typeof root.version === "string" ? root.version : null,
      structure: root,
      disclosedClaims,
      isDemoStub: docType === "demo.mdoc",
    };
  }

  if (isIssuerSignedRoot(root)) {
    const docType = inferDocTypeFromRoot(root);
    const disclosedClaims = extractNamespaceClaims(root.nameSpaces).map((claim) => ({
      ...claim,
      queryId,
    }));

    return {
      queryId,
      presentation,
      format: "mdoc",
      docType,
      version: typeof root.version === "string" ? root.version : null,
      structure: root,
      disclosedClaims,
      isDemoStub: docType === "demo.mdoc",
    };
  }

  return {
    queryId,
    presentation,
    format: "mdoc",
    docType: "unknown",
    version: typeof root.version === "string" ? root.version : null,
    structure: root,
    disclosedClaims: [],
    isDemoStub: false,
  };
}
