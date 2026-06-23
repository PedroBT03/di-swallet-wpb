import { base64UrlToBytes, bytesToBase64Url } from "../../auth/base64";

export interface DecodedDisclosure {
  raw: string;
  salt: string;
  claim: string;
  value: unknown;
  digest: string;
  referencedInJwt: boolean;
}

export interface ParsedSdJwtPresentation {
  queryId?: string;
  presentation: string;
  issuerJwt: string;
  issuerHeader: unknown;
  issuerPayload: unknown;
  disclosures: DecodedDisclosure[];
  keyBindingJwt: string | null;
  keyBindingPayload: unknown;
  isDemoStub: boolean;
}

function parseJsonBase64Url(segment: string): unknown {
  const text = new TextDecoder().decode(base64UrlToBytes(segment));
  return JSON.parse(text) as unknown;
}

function isCompactJwt(segment: string): boolean {
  return segment.split(".").length === 3;
}

function decodeDisclosure(raw: string): Omit<DecodedDisclosure, "digest" | "referencedInJwt"> | null {
  try {
    const text = new TextDecoder().decode(base64UrlToBytes(raw));
    const parsed = JSON.parse(text) as unknown;
    if (!Array.isArray(parsed) || parsed.length < 2) {
      return null;
    }
    return {
      raw,
      salt: String(parsed[0]),
      claim: String(parsed[1]),
      value: parsed.length > 2 ? parsed[2] : undefined,
    };
  } catch {
    return null;
  }
}

async function sha256Base64Url(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return bytesToBase64Url(new Uint8Array(digest));
}

function collectSdHashes(payload: unknown): Set<string> {
  const hashes = new Set<string>();
  if (!payload || typeof payload !== "object") {
    return hashes;
  }
  const walk = (node: unknown) => {
    if (!node || typeof node !== "object") {
      return;
    }
    const record = node as Record<string, unknown>;
    const sd = record._sd;
    if (Array.isArray(sd)) {
      for (const item of sd) {
        if (typeof item === "string") {
          hashes.add(item);
        }
      }
    }
    for (const value of Object.values(record)) {
      if (value && typeof value === "object") {
        walk(value);
      }
    }
  };
  walk(payload);
  return hashes;
}

function splitPresentationSegments(presentation: string): {
  issuerJwt: string;
  disclosures: string[];
  keyBindingJwt: string | null;
} {
  if (!presentation.includes("~") && !presentation.startsWith("eyJ")) {
    return { issuerJwt: "", disclosures: [], keyBindingJwt: null };
  }

  const parts = presentation.split("~");
  const issuerJwt = parts[0] ?? "";
  const middle = parts.slice(1);
  const disclosures: string[] = [];
  let keyBindingJwt: string | null = null;

  for (let index = middle.length - 1; index >= 0; index -= 1) {
    const segment = middle[index];
    if (!segment) {
      continue;
    }
    if (!keyBindingJwt && isCompactJwt(segment)) {
      keyBindingJwt = segment;
      continue;
    }
    disclosures.unshift(segment);
  }

  return { issuerJwt, disclosures, keyBindingJwt };
}

export function formatClaimValue(value: unknown): string {
  if (value == null) {
    return "";
  }
  if (typeof value === "string") {
    return value;
  }
  if (Array.isArray(value)) {
    if (value.every((entry) => typeof entry === "string")) {
      return value.join(", ");
    }
  }
  return JSON.stringify(value);
}

/** True when the disclosure value is an SD-JWT nested object container (`{ _sd, _sd_alg }`). */
export function isSdContainerValue(value: unknown): boolean {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return Array.isArray(record._sd) && record._sd_alg === "sha-256";
}


function sdDigestsInValue(value: unknown): string[] {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return [];
  }
  const sd = (value as Record<string, unknown>)._sd;
  if (!Array.isArray(sd)) {
    return [];
  }
  return sd.filter((item): item is string => typeof item === "string");
}

function parentClaimByChildDigest(disclosures: DecodedDisclosure[]): Map<string, string> {
  const map = new Map<string, string>();
  for (const disc of disclosures) {
    if (!isSdContainerValue(disc.value)) {
      continue;
    }
    for (const digest of sdDigestsInValue(disc.value)) {
      map.set(digest, disc.claim);
    }
  }
  return map;
}

/** Maps nested leaf claim names to qualified paths using the parent SD-JWT container. */
export function qualifyDisclosureClaimPath(
  disc: DecodedDisclosure,
  allInPresentation: DecodedDisclosure[],
): string {
  if (disc.claim.includes(".")) {
    return disc.claim;
  }
  const parent = parentClaimByChildDigest(allInPresentation).get(disc.digest);
  return parent ? `${parent}.${disc.claim}` : disc.claim;
}

/** Leaf disclosures carry holder-visible values; container disclosures are structural only. */
export function isLeafDisclosure(
  disc: DecodedDisclosure,
  allInPresentation: DecodedDisclosure[],
): boolean {
  if (isSdContainerValue(disc.value)) {
    return false;
  }
  // Hide duplicate container rows even if the value shape differs slightly.
  if (
    disc.claim === "address" &&
    allInPresentation.some((d) => d !== disc && d.claim === "address" && isSdContainerValue(d.value))
  ) {
    return false;
  }
  return true;
}

function sharedClaimKey(claim: string, value: unknown): string {
  return `${claim}\0${JSON.stringify(value)}`;
}

export interface SharedLeafClaim {
  queryId?: string;
  claim: string;
  value: unknown;
}

/** Human-visible claims included in this presentation (wire-token disclosures, leaf only). */
export function listSharedLeafClaims(parsed: ParsedSdJwtPresentation): SharedLeafClaim[] {
  const seen = new Set<string>();
  const rows: SharedLeafClaim[] = [];
  for (const disc of parsed.disclosures) {
    if (!isLeafDisclosure(disc, parsed.disclosures)) {
      continue;
    }
    const claim = qualifyDisclosureClaimPath(disc, parsed.disclosures);
    const key = sharedClaimKey(claim, disc.value);
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    rows.push({ queryId: parsed.queryId, claim, value: disc.value });
  }
  rows.sort((a, b) => a.claim.localeCompare(b.claim));
  return rows;
}

/** Parses an SD-JWT VP string into issuer JWT, disclosures, and optional KB-JWT. */
export async function parseSdJwtPresentation(
  presentation: string,
  queryId?: string,
): Promise<ParsedSdJwtPresentation> {
  if (presentation.startsWith("demo-vp:")) {
    return {
      queryId,
      presentation,
      issuerJwt: "",
      issuerHeader: null,
      issuerPayload: null,
      disclosures: [],
      keyBindingJwt: null,
      keyBindingPayload: null,
      isDemoStub: true,
    };
  }

  const { issuerJwt, disclosures, keyBindingJwt } = splitPresentationSegments(presentation);
  let issuerHeader: unknown = null;
  let issuerPayload: unknown = null;

  if (issuerJwt && isCompactJwt(issuerJwt)) {
    const segments = issuerJwt.split(".");
    try {
      issuerHeader = parseJsonBase64Url(segments[0]);
      issuerPayload = parseJsonBase64Url(segments[1]);
    } catch {
      issuerHeader = null;
      issuerPayload = null;
    }
  }

  let keyBindingPayload: unknown = null;
  if (keyBindingJwt) {
    try {
      keyBindingPayload = parseJsonBase64Url(keyBindingJwt.split(".")[1]);
    } catch {
      keyBindingPayload = null;
    }
  }

  const sdHashes = collectSdHashes(issuerPayload);
  const decoded: DecodedDisclosure[] = [];
  for (const raw of disclosures) {
    const base = decodeDisclosure(raw);
    if (!base) {
      continue;
    }
    const digest = await sha256Base64Url(raw);
    decoded.push({
      ...base,
      digest,
      referencedInJwt: sdHashes.size === 0 || sdHashes.has(digest),
    });
  }

  return {
    queryId,
    presentation,
    issuerJwt,
    issuerHeader,
    issuerPayload,
    disclosures: decoded,
    keyBindingJwt,
    keyBindingPayload,
    isDemoStub: false,
  };
}

export function extractPresentationTokens(
  presentationsByQueryId: Record<string, string[]> | undefined,
): Array<{ queryId: string; presentation: string }> {
  if (!presentationsByQueryId) {
    return [];
  }
  return Object.entries(presentationsByQueryId).flatMap(([queryId, values]) =>
    values.map((presentation) => ({ queryId, presentation })),
  );
}
