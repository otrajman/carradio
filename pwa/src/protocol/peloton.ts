// PelotonCB shared core, PROTOCOL §16 — identical to core/PelotonTag.kt, PelotonGeo.kt and
// VoxDetector.kt on Android and their Swift twins. Pure: no I/O, no DOM.
import { haversineM, headingDot } from "./geo";

// ---- §16.1 pack identity ---------------------------------------------------------------

const NAMESPACE = "pelotoncb:";
export const MIN_CODE_LENGTH = 4;

/** sha256("pelotoncb:open")[:16] — every open-road rider shares this tag. */
export const OPEN_ROAD_TAG = "edf37905db8bba1b";

/** Codes compare on ASCII letters + digits only: "Hill-4821" == "hill 4821" == "HILL4821". */
export function normalizePackCode(code: string): string {
  return code.toLowerCase().replace(/[^a-z0-9]/g, "");
}

async function sha256Prefix16(s: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("")
    .slice(0, 16);
}

/** Tag for a join-by-code pack; null when the code is too short to be a pack. */
export async function packTagFromCode(code: string | null | undefined): Promise<string | null> {
  const normalized = normalizePackCode(code ?? "");
  if (normalized.length < MIN_CODE_LENGTH) return null;
  return sha256Prefix16(`${NAMESPACE}code:${normalized}`);
}

/** Recomputes the open-road tag (tests pin it against OPEN_ROAD_TAG). */
export function openRoadTag(): Promise<string> {
  return sha256Prefix16(`${NAMESPACE}open`);
}

export function packChannel(tag: string): string {
  return `peloton:pack:${tag}`;
}

export function geoChannel(res8Cell: string): string {
  return `peloton:geo:${res8Cell}`;
}

const WORDS = [
  "HILL", "SPIN", "DRAFT", "SPRINT", "CLIMB", "GRAVEL", "TEMPO", "CADENCE",
  "ECHELON", "BONK", "CHAIN", "GRUPPO", "DESCENT", "RIDGE", "COL", "PAVE",
];

/** Human-shareable code for a new pack, e.g. "CLIMB-4821". */
export function generatePackCode(rand: () => number = Math.random): string {
  const word = WORDS[Math.floor(rand() * WORDS.length)];
  return `${word}-${1000 + Math.floor(rand() * 9000)}`;
}

// ---- §16.5 rider name ------------------------------------------------------------------

export const RIDER_NAME_MAX = 20;

/**
 * Optional display name a rider types on the join screen; also applied to every received
 * handle. Letters, digits, spaces and . ' - only, whitespace collapsed, ≤ 20 chars.
 * Null when nothing usable is left (the generated handle is used instead).
 */
export function cleanRiderName(raw: string | null | undefined): string | null {
  const cleaned = (raw ?? "")
    .replace(/[^\p{L}\p{N} .'-]/gu, " ")
    .replace(/\s+/g, " ")
    .trim();
  const clipped = [...cleaned].slice(0, RIDER_NAME_MAX).join("").trim();
  return clipped.length > 0 ? clipped : null;
}

// ---- §16.3 open-road reach ---------------------------------------------------------------

export const PACK_RADIUS_M = 500;
export const PACK_HEADING_DOT_MIN = 0.5; // ±60°: switchbacks and bends still match
export const PACK_MIN_SPEED_MPS = 3; // below this (regroup stop, climb crawl) heading is noise
export const GEO_RES = 8;

export interface RiderState {
  lat: number;
  lng: number;
  heading: number;
  speed: number;
}

/** Symmetric radius plus a loose same-direction check — riders behind you count too. */
export function packInRange(sender: RiderState, receiver: RiderState): boolean {
  const d = haversineM(sender.lat, sender.lng, receiver.lat, receiver.lng);
  if (d > PACK_RADIUS_M) return false;
  const bothMoving = sender.speed >= PACK_MIN_SPEED_MPS && receiver.speed >= PACK_MIN_SPEED_MPS;
  if (!bothMoving) return true;
  return headingDot(sender.heading, receiver.heading) >= PACK_HEADING_DOT_MIN;
}

// ---- §16.4 VOX state machine -------------------------------------------------------------

export interface VoxConfig {
  warmupMs: number;
  floorWindowMs: number;
  onsetMarginDb: number;
  sustainMarginDb: number;
  minOnsetDb: number;
  attackMs: number;
  hangoverMs: number;
  minSpeechMs: number;
  maxSnippetMs: number;
}

export const VOX_DEFAULTS: VoxConfig = {
  warmupMs: 600,
  floorWindowMs: 2_000,
  onsetMarginDb: 14,
  sustainMarginDb: 8,
  minOnsetDb: -50,
  attackMs: 120,
  hangoverMs: 700,
  minSpeechMs: 250,
  maxSnippetMs: 3_000,
};

export type VoxEvent =
  | "none"
  /** Speech onset: open a snippet, write the pre-roll first. */
  | "start"
  /** Snippet ended with enough speech: finalize and send. */
  | "stop-send"
  /** Snippet ended without enough speech: throw it away. */
  | "stop-discard"
  /** Chunk boundary mid-speech: send this snippet now, keep capturing into a new one. */
  | "split";

export const SILENCE_DB = -100;

/**
 * Feed one level (dBFS) per audio frame, any frame length, and act on the returned event.
 * Noise floor = minimum level over a sliding window: speech has inter-word dips, so the
 * minimum tracks steady wind/road noise and ignores the rider's voice.
 */
export class VoxDetector {
  readonly config: VoxConfig;
  capturing = false;
  /** Whether the most recent frame counted as speech (for tail trimming). */
  lastFrameWasSpeech = false;

  private window: { ms: number; db: number }[] = [];
  private windowMs = 0;
  private observedMs = 0;
  private aboveMs = 0;
  private snippetMs = 0;
  private speechMs = 0;
  private silenceMs = 0;

  constructor(config: Partial<VoxConfig> = {}) {
    this.config = { ...VOX_DEFAULTS, ...config };
  }

  get noiseFloorDb(): number {
    let min = Infinity;
    for (const w of this.window) if (w.db < min) min = w.db;
    return min === Infinity ? SILENCE_DB : min;
  }

  onFrame(levelDb: number, durationMs: number): VoxEvent {
    const c = this.config;
    // Floor from the window *before* this frame, so a loud onset can't raise its own bar.
    const floor = this.window.length === 0 ? levelDb : this.noiseFloorDb;
    this.pushWindow(levelDb, durationMs);
    this.observedMs += durationMs;

    if (!this.capturing) {
      this.lastFrameWasSpeech = false;
      if (this.observedMs < c.warmupMs) return "none";
      if (levelDb >= Math.max(floor + c.onsetMarginDb, c.minOnsetDb)) {
        this.aboveMs += durationMs;
        if (this.aboveMs >= c.attackMs) {
          this.capturing = true;
          this.snippetMs = this.aboveMs;
          this.speechMs = this.aboveMs;
          this.silenceMs = 0;
          this.aboveMs = 0;
          this.lastFrameWasSpeech = true;
          return "start";
        }
      } else {
        this.aboveMs = 0;
      }
      return "none";
    }

    this.snippetMs += durationMs;
    const speaking = levelDb >= Math.max(floor + c.sustainMarginDb, c.minOnsetDb - 6);
    this.lastFrameWasSpeech = speaking;
    if (speaking) {
      this.speechMs += durationMs;
      this.silenceMs = 0;
    } else {
      this.silenceMs += durationMs;
    }

    if (this.silenceMs >= c.hangoverMs) {
      const enough = this.speechMs >= c.minSpeechMs;
      this.endSnippet();
      return enough ? "stop-send" : "stop-discard";
    }
    if (this.snippetMs >= c.maxSnippetMs) {
      this.snippetMs = 0;
      this.speechMs = 0;
      return "split";
    }
    return "none";
  }

  /** Abandon any snippet in progress (pause / half-duplex hold). The floor is kept. */
  reset() {
    this.endSnippet();
    this.aboveMs = 0;
    this.lastFrameWasSpeech = false;
  }

  /** Full reset incl. noise floor and warmup (mic reopened). */
  resetAll() {
    this.reset();
    this.window = [];
    this.windowMs = 0;
    this.observedMs = 0;
  }

  private endSnippet() {
    this.capturing = false;
    this.snippetMs = 0;
    this.speechMs = 0;
    this.silenceMs = 0;
  }

  private pushWindow(db: number, ms: number) {
    this.window.push({ ms, db });
    this.windowMs += ms;
    while (this.windowMs - this.window[0].ms >= this.config.floorWindowMs) {
      this.windowMs -= this.window.shift()!.ms;
    }
  }
}

/** dBFS of a float PCM frame's RMS (samples in [-1, 1]); SILENCE_DB for digital silence. */
export function levelDbfs(samples: Float32Array, count = samples.length): number {
  if (count <= 0) return SILENCE_DB;
  let sum = 0;
  for (let i = 0; i < count; i++) sum += samples[i] * samples[i];
  const rms = Math.sqrt(sum / count);
  if (rms < 1 / 32768) return SILENCE_DB;
  return Math.max(SILENCE_DB, 20 * Math.log10(rms));
}
