// Convoy tag derivation per PROTOCOL §14 — identical on every platform.
export function normalizeConvoyCode(code: string): string {
  return code.trim().toLowerCase().replace(/\s+/g, " ");
}

/** First 16 hex chars of SHA-256(normalized code); null for empty input. */
export async function convoyTagFromCode(code: string): Promise<string | null> {
  const normalized = normalizeConvoyCode(code);
  if (!normalized) return null;
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(normalized),
  );
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("")
    .slice(0, 16);
}
