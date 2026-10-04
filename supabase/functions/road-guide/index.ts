// Road Guide (PROTOCOL §17): a gated Gemini Live voice that answers a traveler's route /
// scenery / POI talk — and stays silent for everything else.
//
// POST { trip_id, message_id, lat, lng, heading, speed }
//   → 200 { respond: false }                                   (the default, for any reason)
//   → 200 { respond: true, id, text, audio_path, handle }      (private answer for this trip)
//
// Pipeline (every step fails closed; see ../_shared/gate.ts):
//   0. kill switch + key present
//   1. request checks: the message exists, belongs to this trip, is a fresh voice burst in
//      the trip's own storage folder; trip not shadowbanned; per-trip rate limits
//   2. classifier (audio → transcript + topic)           model A
//   3. deterministic transcript screen (injection, length, junk)
//   4. independent verifier (transcript only)             model B — both must agree
//   5. answer text (search-grounded) from the gated transcript + location context
//   6. deterministic output screen + output verifier      model B   ∥  TTS of the answer
//   7. store WAV in synthetic_voice, log, return
// ROAD_GUIDE_ENGINE=live swaps 5–6 for one Gemini Live turn (slower: audio at speaking pace).
// Every evaluation is logged to road_guide_events (24 h retention) for tuning.

import { createClient } from "npm:@supabase/supabase-js@2";
import {
  fenceUntrusted,
  inputDecision,
  outputDecision,
  parseInputClassification,
  parseOutputVerdict,
  parseTranscriptVerdict,
  rateLimit,
  screenTranscript,
} from "../_shared/gate.ts";
import {
  audioMimeForPath,
  audioPart,
  generateJson,
  generateText,
  geminiKey,
  liveAnswer,
  MODELS,
  synthesizeSpeech,
  VOICES,
} from "../_shared/gemini.ts";
import {
  CLASSIFIER_SCHEMA,
  CLASSIFIER_SYSTEM,
  GUIDE_SYSTEM,
  OUTPUT_VERIFIER_SCHEMA,
  OUTPUT_VERIFIER_SYSTEM,
  VERIFIER_SCHEMA,
  VERIFIER_SYSTEM,
} from "./prompts.ts";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
);

const HANDLE = "Road Guide";
const ENGINE = Deno.env.get("ROAD_GUIDE_ENGINE") === "live" ? "live" : "tts";
const GUIDE_STYLE = "upbeat tour guide; brisk, no long pauses";
const MAX_MESSAGE_AGE_MS = 2 * 60_000;
const MAX_AUDIO_BYTES = 262_144;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });
const silent = () => json({ respond: false });

interface Req {
  trip_id: string;
  message_id: string;
  lat: number;
  lng: number;
  heading: number;
  speed: number;
}

function parseReq(x: any): Req | null {
  if (!x || typeof x !== "object") return null;
  const num = (v: unknown) => typeof v === "number" && Number.isFinite(v);
  if (typeof x.trip_id !== "string" || !UUID_RE.test(x.trip_id)) return null;
  if (typeof x.message_id !== "string" || !UUID_RE.test(x.message_id)) return null;
  if (!num(x.lat) || !num(x.lng) || Math.abs(x.lat) > 90 || Math.abs(x.lng) > 180) return null;
  return {
    trip_id: x.trip_id.toLowerCase(),
    message_id: x.message_id.toLowerCase(),
    lat: x.lat,
    lng: x.lng,
    heading: num(x.heading) ? ((x.heading % 360) + 360) % 360 : 0,
    speed: num(x.speed) ? Math.max(0, x.speed) : 0,
  };
}

async function log(
  r: Req,
  outcome: "responded" | "gated" | "rate_limited" | "error",
  reasons: string[],
  extra: {
    transcript?: string; category?: string; response_text?: string; response_audio_path?: string;
    timings?: Record<string, number>;
  } = {},
) {
  await supabase.from("road_guide_events").insert({
    trip_id: r.trip_id,
    message_id: r.message_id,
    outcome,
    reasons,
    transcript: extra.transcript?.slice(0, 1000) ?? null,
    category: extra.category ?? null,
    response_text: extra.response_text?.slice(0, 1000) ?? null,
    response_audio_path: extra.response_audio_path ?? null,
    timings: extra.timings ?? null,
  });
}

/** Stage stopwatch: `t.mark("x")` records ms since the previous mark; `t.all` adds total. */
function stopwatch() {
  const t0 = Date.now();
  let last = t0;
  const marks: Record<string, number> = {};
  return {
    mark(name: string) {
      const now = Date.now();
      marks[name] = now - last;
      last = now;
    },
    get all() {
      return { ...marks, total: Date.now() - t0 };
    },
  };
}

// --- Location context ----------------------------------------------------------------

function bearingDeg(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const r = Math.PI / 180;
  const y = Math.sin((lng2 - lng1) * r) * Math.cos(lat2 * r);
  const x = Math.cos(lat1 * r) * Math.sin(lat2 * r) -
    Math.sin(lat1 * r) * Math.cos(lat2 * r) * Math.cos((lng2 - lng1) * r);
  return ((Math.atan2(y, x) / r) + 360) % 360;
}

function relativeSide(heading: number, bearing: number, moving: boolean): string {
  if (!moving) return "nearby";
  const d = ((bearing - heading + 540) % 360) - 180;
  if (Math.abs(d) <= 30) return "ahead";
  if (Math.abs(d) >= 150) return "behind";
  return d > 0 ? "to the right" : "to the left";
}

/** Nearby named places from Wikipedia geosearch (keyless), described relative to travel. */
async function nearbyPlaces(r: Req): Promise<string[]> {
  try {
    const url = `https://en.wikipedia.org/w/api.php?action=query&list=geosearch` +
      `&gscoord=${r.lat.toFixed(5)}%7C${r.lng.toFixed(5)}&gsradius=10000&gslimit=8&format=json&origin=*`;
    const res = await fetch(url, {
      headers: { "User-Agent": "carradio-road-guide (car-radio.live)" },
      signal: AbortSignal.timeout(4_000),
    });
    const pages = (await res.json())?.query?.geosearch ?? [];
    const moving = r.speed >= 3;
    return pages.map((p: any) => {
      const km = (p.dist / 1000).toFixed(1);
      const side = relativeSide(r.heading, bearingDeg(r.lat, r.lng, p.lat, p.lon), moving);
      return `${p.title} — ${km} km ${side}`;
    });
  } catch {
    return [];
  }
}

function contextBlock(r: Req, places: string[]): string {
  const mph = Math.round(r.speed * 2.23694);
  const mode = r.speed < 3 ? "stopped or very slow" : r.speed < 13 ? `about ${mph} mph (likely cycling)` : `about ${mph} mph`;
  return [
    `Traveler location: ${r.lat.toFixed(5)}, ${r.lng.toFixed(5)}; heading ${Math.round(r.heading)}° from north; moving ${mode}.`,
    places.length ? `Named places within 10 km (from Wikipedia):\n- ${places.join("\n- ")}` : "No named places found nearby.",
  ].join("\n");
}

// --- Handler -------------------------------------------------------------------------

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);
  if (Deno.env.get("ROAD_GUIDE_ENABLED") === "false" || !geminiKey()) return silent();

  let r: Req | null = null;
  try {
    r = parseReq(await req.json());
    if (!r) return json({ error: "bad request" }, 400);

    const sw = stopwatch();
    // 1. The burst must be this trip's own, fresh, voice message in its own folder.
    //    (The three checks are independent: one round trip instead of three.)
    const sinceHour = new Date(Date.now() - 3_600_000).toISOString();
    const [{ data: msg }, { data: banned }, { data: recent }] = await Promise.all([
      supabase
        .from("messages")
        .select("id, trip_id, kind, audio_path, created_at")
        .eq("id", r.message_id)
        .maybeSingle(),
      supabase.rpc("is_shadowbanned", { p_trip_id: r.trip_id }),
      supabase
        .from("road_guide_events")
        .select("outcome, created_at")
        .eq("trip_id", r.trip_id)
        .gt("created_at", sinceHour),
    ]);
    const expectedPrefix = `voice_bursts/${r.trip_id}/`;
    if (!msg || msg.trip_id !== r.trip_id || msg.kind !== "voice" ||
        typeof msg.audio_path !== "string" || !msg.audio_path.startsWith(expectedPrefix) ||
        Date.now() - Date.parse(msg.created_at) > MAX_MESSAGE_AGE_MS) {
      return silent(); // not logged: could be a forged/foreign id
    }

    if (banned === true) return silent();

    const rows = recent ?? [];
    const limit = rateLimit(
      Date.now(),
      rows.filter((e) => e.outcome !== "rate_limited").map((e) => Date.parse(e.created_at)),
      rows.filter((e) => e.outcome === "responded").map((e) => Date.parse(e.created_at)),
    );
    if (!limit.pass) {
      await log(r, "rate_limited", limit.reasons);
      return silent();
    }

    sw.mark("preflight");

    // Location context needs nothing from the models: fetch it while they run.
    const placesP = nearbyPlaces(r).catch(() => [] as Awaited<ReturnType<typeof nearbyPlaces>>);

    // 2. Classifier on the rider's audio.
    const objectPath = msg.audio_path.slice("voice_bursts/".length);
    const mime = audioMimeForPath(objectPath);
    const { data: blob } = await supabase.storage.from("voice_bursts").download(objectPath);
    if (!mime || !blob || blob.size > MAX_AUDIO_BYTES) {
      await log(r, "gated", ["audio_unavailable"]);
      return silent();
    }
    const audio = new Uint8Array(await blob.arrayBuffer());
    sw.mark("download");
    const cls = parseInputClassification(await generateJson(
      MODELS.classifier,
      CLASSIFIER_SYSTEM,
      [audioPart(audio, mime), { text: "Transcribe and classify this clip." }],
      CLASSIFIER_SCHEMA,
    ));
    sw.mark("classifier");

    // 3. Cheap deterministic screen before spending the second model call.
    if (!cls || !screenTranscript(cls.transcript).pass ||
        !cls.on_topic || !cls.warrants_response || cls.injection_suspected) {
      const d = inputDecision(cls, null);
      await log(r, "gated", d.reasons.filter((x) => x !== "verifier_invalid"), {
        transcript: cls?.transcript, category: cls?.category, timings: sw.all,
      });
      return silent();
    }

    // 4 + 5. The independent verifier (different model, different prompt) and the answer
    // both need only the transcript, so they run concurrently. Nothing is returned until
    // the verifier passes: a rejected transcript's answer is discarded, so the gate is
    // still fail-closed — it just no longer adds its latency to the answer.
    const places = await placesP;
    const context = contextBlock(r, places);
    sw.mark("places");
    const userText = `${context}\n\nThe traveler said:\n${fenceUntrusted("RIDER", cls.transcript)}`;
    const [verdict, draft] = await Promise.all([
      generateJson(
        MODELS.verifier,
        VERIFIER_SYSTEM,
        [{ text: fenceUntrusted("RIDER", cls.transcript) }],
        VERIFIER_SCHEMA,
      ).then(parseTranscriptVerdict),
      ENGINE === "live"
        ? liveAnswer({ systemInstruction: GUIDE_SYSTEM, userText, voice: VOICES.guide })
        : generateText(MODELS.guide, GUIDE_SYSTEM, userText, { search: true })
          .then((text) => (text ? { text, wav: null as Uint8Array | null } : null)),
    ]);
    sw.mark(ENGINE === "live" ? "verifier+live" : "verifier+answer");
    const input = inputDecision(cls, verdict);
    if (!input.pass) {
      await log(r, "gated", input.reasons, {
        transcript: cls.transcript, category: cls.category, timings: sw.all,
      });
      return silent();
    }
    if (!draft || !draft.text) {
      await log(r, "error", [ENGINE === "live" ? "live_no_answer" : "no_answer"], {
        transcript: cls.transcript, category: cls.category, timings: sw.all,
      });
      return silent();
    }

    // 6. Verify what will be said before anyone hears it. The voice is synthesized in
    // parallel (tts engine) and thrown away if the verifier says no.
    const [outVerdict, wav] = await Promise.all([
      generateJson(
        MODELS.verifier,
        OUTPUT_VERIFIER_SYSTEM,
        [{
          text: `${context}\n\nTraveler's message:\n${fenceUntrusted("RIDER", cls.transcript)}\n\n` +
            `Proposed spoken answer:\n${fenceUntrusted("ANSWER", draft.text)}`,
        }],
        OUTPUT_VERIFIER_SCHEMA,
      ).then(parseOutputVerdict),
      draft.wav ? Promise.resolve(draft.wav) : synthesizeSpeech(draft.text, VOICES.guide, GUIDE_STYLE),
    ]);
    sw.mark(draft.wav ? "output_verifier" : "output_verifier+tts");
    const output = outputDecision(draft.text, outVerdict);
    if (!output.pass) {
      await log(r, "gated", output.reasons, {
        transcript: cls.transcript, category: cls.category, response_text: draft.text,
        timings: sw.all,
      });
      return silent();
    }
    if (!wav) {
      await log(r, "error", ["tts_failed"], {
        transcript: cls.transcript, category: cls.category, response_text: draft.text,
        timings: sw.all,
      });
      return silent();
    }
    const answer = { text: draft.text, wav };

    // 7. Store + return. Unguessable path; public-by-URL like rider bursts; 24 h cleanup.
    const id = crypto.randomUUID();
    const objectName = `guide/${r.trip_id}/${id}.wav`;
    const { error: upErr } = await supabase.storage
      .from("synthetic_voice")
      .upload(objectName, answer.wav, { contentType: "audio/wav" });
    sw.mark("upload");
    if (upErr) {
      await log(r, "error", ["upload_failed"], {
        transcript: cls.transcript, category: cls.category, timings: sw.all,
      });
      return silent();
    }
    const audioPath = `synthetic_voice/${objectName}`;
    await log(r, "responded", [], {
      transcript: cls.transcript, category: cls.category,
      response_text: answer.text, response_audio_path: audioPath, timings: sw.all,
    });
    return json({ respond: true, id, text: answer.text, audio_path: audioPath, handle: HANDLE });
  } catch (e) {
    console.error("road-guide failed", e);
    if (r) await log(r, "error", ["exception"]).catch(() => {});
    return silent();
  }
});
