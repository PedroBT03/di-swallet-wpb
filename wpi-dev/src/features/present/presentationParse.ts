import {
  isMdocPresentation,
  parseMdocPresentation,
  type ParsedMdocPresentation,
} from "./mdocInspect";
import {
  listSharedLeafClaims,
  parseSdJwtPresentation,
  type ParsedSdJwtPresentation,
} from "./sdJwtInspect";

export type ParsedPresentation =
  | ({ kind: "sd-jwt" } & ParsedSdJwtPresentation)
  | ({ kind: "mdoc" } & ParsedMdocPresentation);

export function isSdJwtPresentationToken(presentation: string): boolean {
  return presentation.startsWith("demo-vp:") || presentation.startsWith("eyJ") || presentation.includes("~");
}

export async function parsePresentationToken(
  presentation: string,
  queryId?: string,
): Promise<ParsedPresentation> {
  if (isMdocPresentation(presentation)) {
    return { kind: "mdoc", ...parseMdocPresentation(presentation, queryId) };
  }
  return { kind: "sd-jwt", ...(await parseSdJwtPresentation(presentation, queryId)) };
}

export function listDisclosedClaims(parsed: ParsedPresentation): Array<{
  queryId?: string;
  claim: string;
  value: unknown;
}> {
  if (parsed.kind === "mdoc") {
    return parsed.disclosedClaims;
  }
  return listSharedLeafClaims(parsed);
}

export function presentationFormatLabel(parsed: ParsedPresentation): string {
  return parsed.kind === "mdoc" ? "ISO 18013-5 mdoc (DeviceResponse)" : "SD-JWT";
}
