// Road Guide verification gate (PROTOCOL §17). Pure TypeScript, no imports: the models
// only SUPPLY verdicts; this module DECIDES. Every rule fails closed — a missing field,
// a malformed verdict, or any disagreement means the Road Guide stays silent.
//
// Layers, in order (all must pass to speak):
//   1. Request/abuse limits  (rateLimit)            — before any model call
//   2. Transcript screen     (screenTranscript)     — deterministic: length, injection, junk
//   3. Input decision        (inputDecision)        — two independent classifiers must agree
//   4. Output decision       (outputDecision)       — deterministic screen + output verifier
//
// Unit tests: gate.test.ts (node --test).

export const CATEGORIES = [
  "route",            // where the road goes, distances, next town, junctions
  "road_conditions",  // traffic, construction, surface, climbs/descents
  "scenery",          // what the rider/driver can see: landscape, landmarks, views
  "poi",              // places along the way: food, fuel, water, rest stops, parks, museums
  "local_history",    // history/culture of the places being passed
  "weather_on_route", // weather affecting this trip
  "off_topic",
] as const;
export type Category = typeof CATEGORIES[number];

export const ALLOWED_CATEGORIES: ReadonlySet<Category> = new Set(
  CATEGORIES.filter((c) => c !== "off_topic"),
);

export const THRESHOLDS = {
  inputConfidence: 0.8,
  verifierConfidence: 0.8,
  outputConfidence: 0.8,
  minTranscriptWords: 3,
  maxTranscriptChars: 600,
  maxResponseWords: 70,
  minResponseWords: 3,
} as const;

export const LIMITS = {
  /** Gate evaluations per trip per 10 minutes (every own burst is evaluated). */
  evalsPer10Min: 20,
  /** Minimum spacing between two spoken answers to the same trip. */
  minResponseGapMs: 20_000,
  /** Spoken answers per trip per hour. */
  responsesPerHour: 30,
} as const;

/** First-pass classifier over the rider's AUDIO (transcribes + classifies). */
export interface InputClassification {
  transcript: string;
  on_topic: boolean;
  category: Category;
  /** Would a good road-trip guide naturally respond (question, request, or remark about what's around)? */
  warrants_response: boolean;
  confidence: number;
  injection_suspected: boolean;
}

/** Independent second opinion over the TRANSCRIPT only, with a different prompt. */
export interface TranscriptVerdict {
  on_topic: boolean;
  category: Category;
  confidence: number;
}

/** Verifier over the Road Guide's own answer before it is spoken. */
export interface OutputVerdict {
  on_topic: boolean;          // stays on route/scenery/POI
  answers_rider: boolean;     // actually addresses what the rider said
  unsafe: boolean;            // distracting/dangerous instructions, harmful content
  speculative: boolean;       // presents uncertain facts as certain (hallucination risk)
  confidence: number;
}

export interface Decision {
  pass: boolean;
  reasons: string[];
}

// --- 1. Rate limits ----------------------------------------------------------------

export function rateLimit(
  nowMs: number,
  recentEvalTimesMs: number[],
  recentResponseTimesMs: number[],
): Decision {
  const reasons: string[] = [];
  const evals10 = recentEvalTimesMs.filter((t) => nowMs - t < 10 * 60_000).length;
  if (evals10 >= LIMITS.evalsPer10Min) reasons.push("rate_evals");
  const lastResponse = Math.max(-Infinity, ...recentResponseTimesMs);
  if (nowMs - lastResponse < LIMITS.minResponseGapMs) reasons.push("rate_gap");
  const responsesHour = recentResponseTimesMs.filter((t) => nowMs - t < 3_600_000).length;
  if (responsesHour >= LIMITS.responsesPerHour) reasons.push("rate_hourly");
  return { pass: reasons.length === 0, reasons };
}

// --- 2. Deterministic transcript screen ------------------------------------------------

const INJECTION_PATTERNS: RegExp[] = [
  /\b(ignore|disregard|forget|override)\b[^.?!]{0,40}\b(instructions?|rules?|prompts?|guidelines?|previous|above|system)\b/i,
  /\bsystem\s*prompt\b/i,
  /\b(developer|dev|god|jailbreak|dan)\s+mode\b/i,
  /\bjailbreak\b/i,
  /\byou\s+are\s+(now|no\s+longer)\b/i,
  /\b(pretend|act|behave|roleplay)\s+(to\s+be|as|like)\b/i,
  /\bnew\s+(instructions?|rules?|persona)\b/i,
  /\b(reveal|repeat|print|show)\b[^.?!]{0,30}\b(prompt|instructions?|rules)\b/i,
  /<\/?\s*(system|assistant|user|instructions?)\s*>/i,
];

export function wordCount(s: string): number {
  return s.trim().split(/\s+/).filter(Boolean).length;
}

export function screenTranscript(transcript: unknown): Decision {
  const reasons: string[] = [];
  if (typeof transcript !== "string") return { pass: false, reasons: ["transcript_missing"] };
  const t = transcript.trim();
  if (wordCount(t) < THRESHOLDS.minTranscriptWords) reasons.push("transcript_too_short");
  if (t.length > THRESHOLDS.maxTranscriptChars) reasons.push("transcript_too_long");
  if (INJECTION_PATTERNS.some((re) => re.test(t))) reasons.push("injection_pattern");
  if (/https?:\/\/|www\./i.test(t)) reasons.push("transcript_url");
  return { pass: reasons.length === 0, reasons };
}

// --- 3. Input decision: two independent classifiers must agree -------------------------

function isCategory(x: unknown): x is Category {
  return typeof x === "string" && (CATEGORIES as readonly string[]).includes(x);
}

function isConfidence(x: unknown): x is number {
  return typeof x === "number" && Number.isFinite(x) && x >= 0 && x <= 1;
}

/** Validates untrusted model JSON into an InputClassification, or null (fail closed). */
export function parseInputClassification(x: unknown): InputClassification | null {
  if (!x || typeof x !== "object") return null;
  const o = x as Record<string, unknown>;
  if (typeof o.transcript !== "string" || typeof o.on_topic !== "boolean" ||
      !isCategory(o.category) || typeof o.warrants_response !== "boolean" ||
      !isConfidence(o.confidence) || typeof o.injection_suspected !== "boolean") return null;
  return {
    transcript: o.transcript, on_topic: o.on_topic, category: o.category,
    warrants_response: o.warrants_response, confidence: o.confidence,
    injection_suspected: o.injection_suspected,
  };
}

export function parseTranscriptVerdict(x: unknown): TranscriptVerdict | null {
  if (!x || typeof x !== "object") return null;
  const o = x as Record<string, unknown>;
  if (typeof o.on_topic !== "boolean" || !isCategory(o.category) || !isConfidence(o.confidence)) {
    return null;
  }
  return { on_topic: o.on_topic, category: o.category, confidence: o.confidence };
}

export function parseOutputVerdict(x: unknown): OutputVerdict | null {
  if (!x || typeof x !== "object") return null;
  const o = x as Record<string, unknown>;
  if (typeof o.on_topic !== "boolean" || typeof o.answers_rider !== "boolean" ||
      typeof o.unsafe !== "boolean" || typeof o.speculative !== "boolean" ||
      !isConfidence(o.confidence)) return null;
  return {
    on_topic: o.on_topic, answers_rider: o.answers_rider, unsafe: o.unsafe,
    speculative: o.speculative, confidence: o.confidence,
  };
}

export function inputDecision(
  cls: InputClassification | null,
  verdict: TranscriptVerdict | null,
): Decision {
  if (!cls) return { pass: false, reasons: ["classifier_invalid"] };
  const reasons = [...screenTranscript(cls.transcript).reasons];
  if (cls.injection_suspected) reasons.push("classifier_injection");
  if (!cls.on_topic || !ALLOWED_CATEGORIES.has(cls.category)) reasons.push("input_off_topic");
  if (!cls.warrants_response) reasons.push("no_response_warranted");
  if (cls.confidence < THRESHOLDS.inputConfidence) reasons.push("input_low_confidence");
  if (!verdict) {
    reasons.push("verifier_invalid");
  } else {
    if (!verdict.on_topic || !ALLOWED_CATEGORIES.has(verdict.category)) {
      reasons.push("verifier_off_topic");
    }
    if (verdict.confidence < THRESHOLDS.verifierConfidence) reasons.push("verifier_low_confidence");
  }
  return { pass: reasons.length === 0, reasons };
}

// --- 4. Output decision --------------------------------------------------------------

const UNSAFE_OUTPUT_PATTERNS: RegExp[] = [
  // Eyes-off-road / hands-off-bars instructions.
  /\b(look|check|glance)\s+(at|down\s+at)\s+(your\s+)?(phone|screen|map|device)\b/i,
  /\b(take|snap|grab)\s+(a\s+)?(photo|picture|pic|selfie|video)\b/i,
  /\b(text|type|tap|scroll)\s+(it|this|that|them|me|your)\b/i,
  /\b(speed\s+up|go\s+faster|floor\s+it|race)\b/i,
  // Model breaking scope / persona.
  /\bas\s+an?\s+(ai|language\s+model)\b/i,
  /\b(system\s+prompt|my\s+instructions)\b/i,
];

export function screenResponse(text: unknown): Decision {
  if (typeof text !== "string") return { pass: false, reasons: ["response_missing"] };
  const reasons: string[] = [];
  const words = wordCount(text);
  if (words < THRESHOLDS.minResponseWords) reasons.push("response_too_short");
  if (words > THRESHOLDS.maxResponseWords) reasons.push("response_too_long");
  if (/https?:\/\/|www\.|\[[^\]]*\]\(|```|[#*_]{2,}/i.test(text)) reasons.push("response_markup");
  if (UNSAFE_OUTPUT_PATTERNS.some((re) => re.test(text))) reasons.push("response_unsafe_pattern");
  return { pass: reasons.length === 0, reasons };
}

export function outputDecision(text: unknown, verdict: OutputVerdict | null): Decision {
  const reasons = [...screenResponse(text).reasons];
  if (!verdict) {
    reasons.push("output_verifier_invalid");
  } else {
    if (!verdict.on_topic) reasons.push("output_off_topic");
    if (!verdict.answers_rider) reasons.push("output_not_responsive");
    if (verdict.unsafe) reasons.push("output_unsafe");
    if (verdict.speculative) reasons.push("output_speculative");
    if (verdict.confidence < THRESHOLDS.outputConfidence) reasons.push("output_low_confidence");
  }
  return { pass: reasons.length === 0, reasons };
}

/**
 * Untrusted text going INTO a prompt: strip control chars and anything that could fake a
 * delimiter, then fence it. The prompts tell the model the fenced text is data, never
 * instructions — a second line of defence behind the injection screen.
 */
export function fenceUntrusted(label: string, text: string): string {
  const clean = text
    .replace(/[\u0000-\u0008\u000B-\u001F\u007F]/g, " ")
    .replace(/<{2,}|>{2,}|"""|```/g, " ")
    .slice(0, THRESHOLDS.maxTranscriptChars);
  return `<<<${label}\n${clean}\n${label}>>>`;
}
