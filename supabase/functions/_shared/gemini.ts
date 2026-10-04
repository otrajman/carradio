// Minimal Gemini Developer API client for edge functions (plain fetch + WebSocket, no SDK).
// Verified against ai.google.dev docs on 2026-09-27; model ids are env-overridable so a
// rename never needs a code deploy. Every call returns null on ANY failure — callers treat
// null as "stay silent / fall back", never as an error to surface.

import { base64ToBytes, bytesToBase64, concatBytes, pcm16ToWav } from "./wav.ts";

const API = "https://generativelanguage.googleapis.com/v1beta";
const LIVE_WS =
  "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

const env = (k: string, d: string) => Deno.env.get(k) ?? d;

export const MODELS = {
  /** Audio-in transcription + first-pass topic classifier (cheapest GA model w/ audio). */
  classifier: env("GEMINI_MODEL_CLASSIFIER", "gemini-3.5-flash-lite"),
  /** Independent second opinion + output verifier: a DIFFERENT model on purpose. */
  verifier: env("GEMINI_MODEL_VERIFIER", "gemini-3.8-flash"),
  tts: env("GEMINI_MODEL_TTS", "gemini-3.8-flash-lite-tts"),
  /** Road Guide answer text (search-grounded); voiced by `tts`. */
  guide: env("GEMINI_MODEL_GUIDE", "gemini-3.8-flash"),
  /** Optional Road Guide engine (ROAD_GUIDE_ENGINE=live): one Live turn, audio streamed by
   *  the model. ~3x slower end to end than guide+tts because audio arrives at speaking pace. */
  live: env("GEMINI_MODEL_LIVE", "gemini-3.8-live"),
};

export const VOICES = {
  /** Synthetic nodes ("Radio Tower"): informative, steady. */
  tower: env("GEMINI_VOICE_TOWER", "Charon"),
  /** Road Guide: warm, distinct from the tower so riders can tell them apart. */
  guide: env("GEMINI_VOICE_GUIDE", "Kore"),
};

export function geminiKey(): string | null {
  const k = Deno.env.get("GEMINI_API_KEY");
  return k && k.length > 10 ? k : null;
}

type Part = { text: string } | { inlineData: { mimeType: string; data: string } };

async function post(model: string, body: unknown, timeoutMs: number): Promise<Response | null> {
  const key = geminiKey();
  if (!key) return null;
  try {
    return await fetch(`${API}/models/${model}:generateContent`, {
      method: "POST",
      headers: { "x-goog-api-key": key, "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch {
    return null;
  }
}

function candidateText(json: any): string | null {
  const parts = json?.candidates?.[0]?.content?.parts;
  if (!Array.isArray(parts)) return null;
  const text = parts.map((p: any) => (typeof p?.text === "string" ? p.text : "")).join("");
  return text || null;
}

/**
 * generateContent with JSON-schema output. Uses the current `responseFormat` field; if the
 * API rejects it (400), retries once with the older responseMimeType/responseJsonSchema
 * pair, which the docs mark deprecated but still document. Returns parsed JSON or null.
 */
export async function generateJson(
  model: string,
  systemInstruction: string,
  parts: Part[],
  schema: Record<string, unknown>,
  timeoutMs = 12_000,
): Promise<unknown | null> {
  const base = {
    systemInstruction: { parts: [{ text: systemInstruction }] },
    contents: [{ role: "user", parts }],
  };
  const attempts = [
    { ...base, generationConfig: { temperature: 0, responseFormat: { text: { mimeType: "application/json", schema } } } },
    { ...base, generationConfig: { temperature: 0, responseMimeType: "application/json", responseJsonSchema: schema } },
  ];
  for (const body of attempts) {
    const res = await post(model, body, timeoutMs);
    if (!res) return null;
    if (res.status === 400) continue; // schema field not accepted → try the other form
    if (!res.ok) return null;
    try {
      const text = candidateText(await res.json());
      return text ? JSON.parse(text) : null;
    } catch {
      return null;
    }
  }
  return null;
}

/**
 * Plain text answer (optionally Google-Search grounded). Returns the trimmed text or null.
 */
export async function generateText(
  model: string,
  systemInstruction: string,
  userText: string,
  opts: { search?: boolean; timeoutMs?: number } = {},
): Promise<string | null> {
  const res = await post(model, {
    systemInstruction: { parts: [{ text: systemInstruction }] },
    contents: [{ role: "user", parts: [{ text: userText }] }],
    generationConfig: { temperature: 0.4 },
    ...(opts.search ? { tools: [{ googleSearch: {} }] } : {}),
  }, opts.timeoutMs ?? 12_000);
  if (!res?.ok) return null;
  try {
    const text = candidateText(await res.json());
    return text?.trim() || null;
  } catch {
    return null;
  }
}

export function audioPart(bytes: Uint8Array, mimeType: string): Part {
  return { inlineData: { mimeType, data: bytesToBase64(bytes) } };
}

/** Gemini's accepted input types for our burst containers (audio/mp4 is NOT accepted). */
export function audioMimeForPath(path: string): string | null {
  const ext = path.split(".").pop()?.toLowerCase();
  switch (ext) {
    case "ogg": return "audio/ogg";
    case "webm": return "audio/webm";
    case "m4a":
    case "mp4": return "audio/m4a";
    case "aac": return "audio/aac";
    case "wav": return "audio/wav";
    case "mp3": return "audio/mp3";
    default: return null;
  }
}

function isWav(bytes: Uint8Array): boolean {
  return bytes.length > 12 && String.fromCharCode(...bytes.subarray(0, 4)) === "RIFF" &&
    String.fromCharCode(...bytes.subarray(8, 12)) === "WAVE";
}

/**
 * Single-speaker TTS → WAV bytes. 3.8 TTS models return a complete WAV by default; older
 * ones return raw 24 kHz L16 — both handled.
 *
 * `style` is a natural-language delivery instruction ("calm, unhurried", "quickly and
 * energetically"); it goes in the prompt the documented way ("Say <style>: <text>"), which
 * the model follows far more reliably than the speech_metadata hint (kept for older models).
 */
export async function synthesizeSpeech(
  text: string,
  voice: string,
  style: string,
  timeoutMs = 20_000,
): Promise<Uint8Array | null> {
  const res = await post(MODELS.tts, {
    contents: [{ role: "user", parts: [{ text: `Say ${style}: ${text}`, speech_metadata: { style } }] }],
    generationConfig: {
      responseModalities: ["AUDIO"],
      speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: voice } } },
    },
  }, timeoutMs);
  if (!res?.ok) return null;
  try {
    const json = await res.json();
    const b64 = json?.candidates?.[0]?.content?.parts?.find((p: any) => p?.inlineData?.data)
      ?.inlineData?.data;
    if (typeof b64 !== "string") return null;
    const bytes = base64ToBytes(b64);
    return isWav(bytes) ? bytes : pcm16ToWav(bytes, 24_000);
  } catch {
    return null;
  }
}

export interface LiveAnswer {
  /** What the model actually said (output audio transcription). */
  text: string;
  wav: Uint8Array;
}

/**
 * One gated turn on the Live API: open a session, send a single text turn, collect the
 * spoken answer (24 kHz PCM) and its transcription until turnComplete. The session never
 * sees raw rider audio — only the transcript that already passed the input gate.
 */
export function liveAnswer(opts: {
  systemInstruction: string;
  userText: string;
  voice: string;
  timeoutMs?: number;
}): Promise<LiveAnswer | null> {
  const key = geminiKey();
  if (!key) return Promise.resolve(null);
  return new Promise((resolve) => {
    const audio: Uint8Array[] = [];
    let transcript = "";
    let settled = false;
    const ws = new WebSocket(`${LIVE_WS}?key=${encodeURIComponent(key)}`);
    ws.binaryType = "arraybuffer";

    const finish = (ok: boolean) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try { ws.close(); } catch { /* already closed */ }
      if (!ok || audio.length === 0) return resolve(null);
      resolve({ text: transcript.trim(), wav: pcm16ToWav(concatBytes(audio), 24_000) });
    };
    const timer = setTimeout(() => finish(false), opts.timeoutMs ?? 25_000);

    ws.onopen = () => {
      ws.send(JSON.stringify({
        setup: {
          model: `models/${MODELS.live}`,
          generationConfig: {
            responseModalities: ["AUDIO"],
            speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: opts.voice } } },
          },
          systemInstruction: { parts: [{ text: opts.systemInstruction }] },
          tools: [{ googleSearch: {} }],
          outputAudioTranscription: {},
        },
      }));
    };

    ws.onmessage = (ev) => {
      let msg: any;
      try {
        const raw = typeof ev.data === "string" ? ev.data : new TextDecoder().decode(ev.data);
        msg = JSON.parse(raw);
      } catch {
        return;
      }
      if (msg.setupComplete) {
        ws.send(JSON.stringify({
          clientContent: { turns: [{ role: "user", parts: [{ text: opts.userText }] }], turnComplete: true },
        }));
        return;
      }
      const sc = msg.serverContent;
      if (!sc) return;
      for (const part of sc.modelTurn?.parts ?? []) {
        const data = part?.inlineData?.data;
        if (typeof data === "string") audio.push(base64ToBytes(data));
      }
      if (typeof sc.outputTranscription?.text === "string") transcript += sc.outputTranscription.text;
      if (sc.interrupted) finish(false);
      if (sc.turnComplete) finish(true);
    };
    ws.onerror = () => finish(false);
    ws.onclose = () => finish(false);
  });
}
