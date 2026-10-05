// Demo pack (PROTOCOL §16.7): the reserved pack code DEMO is never empty. Clients in it call
// this every ~15 s; each call speaks at most ONE scripted line from one of three synthetic
// riders, paced by a single demo_pack_state row so any number of clients share one cast.
// Bursts are ordinary §3 payloads from real trip rows, so every client plays them through
// its normal pipeline and mute/report keep working. Lines are voiced once with Gemini TTS
// (a distinct voice per rider) and cached in synthetic_voice/demo; with no key they go out
// text-only and clients TTS locally. Stateless on purpose: edge isolates are frozen after
// they respond, so nothing here outlives the request.
//
// POST { trip_id, lat?, lng? } → { ok: true, riders: [...], spoke: bool }
import { createClient } from "npm:@supabase/supabase-js@2";
import { synthesizeSpeech } from "../_shared/gemini.ts";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const supabase = createClient(SUPABASE_URL, SERVICE_KEY);

/** sha256("pelotoncb:code:demo")[:16] — PelotonTag.fromCode("DEMO") on every client. */
const DEMO_TAG = "d8b53d4ecd7e76c4";
const TOPIC = `peloton:pack:${DEMO_TAG}`;
/** Minimum gap between two bot lines, whoever triggers them. */
const MIN_GAP_MS = 12_000;
const STYLE = "a cyclist on a group ride talking into a radio, outdoors, breathing a little from the effort, natural and quick";

const BOTS = [
  { handle: "Copper Heron", voice: "Puck" },
  { handle: "Swift Gazelle", voice: "Aoede" },
  { handle: "Rusty Badger", voice: "Fenrir" },
];
const SCRIPT: { bot: number; text: string }[] = [
  { bot: 0, text: "Car back. Single file for a second." },
  { bot: 1, text: "Gravel on the left after this bend, stay wide." },
  { bot: 2, text: "Nice pull. Swapping off, next rider through." },
  { bot: 0, text: "Pothole, right side. Right side." },
  { bot: 1, text: "Regroup at the top of the climb. Nobody gets dropped today." },
  { bot: 2, text: "Anyone need water? There's a store at the gas station in about two miles." },
  { bot: 0, text: "Clear left. Rolling." },
  { bot: 1, text: "Coffee after the bridge? I'm buying if we make the light." },
];

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });
const riders = BOTS.map((b) => b.handle);

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);
  let body: any = {};
  try { body = await req.json(); } catch { /* empty body is fine */ }
  const lat = typeof body.lat === "number" && Number.isFinite(body.lat) ? body.lat : 0;
  const lng = typeof body.lng === "number" && Number.isFinite(body.lng) ? body.lng : 0;

  try {
    const { data: state } = await supabase.from("demo_pack_state").select("last_burst_at, next_index").eq("id", 1).maybeSingle();
    const last = state?.last_burst_at ? Date.parse(state.last_burst_at) : 0;
    if (Date.now() - last < MIN_GAP_MS) return json({ ok: true, riders, spoke: false });
    const index = (state?.next_index ?? 0) % SCRIPT.length;
    // Claim the slot first so two simultaneous pings don't both speak.
    const { data: claimed } = await supabase
      .from("demo_pack_state")
      .update({ last_burst_at: new Date().toISOString(), next_index: index + 1 })
      .eq("id", 1)
      .eq("next_index", state?.next_index ?? 0)
      .select("id");
    if (!claimed || claimed.length === 0) return json({ ok: true, riders, spoke: false });

    const line = SCRIPT[index];
    const bot = BOTS[line.bot];
    const [tripId, audioPath] = await Promise.all([ensureTrip(bot.handle), ensureAudio(line, bot.voice)]);
    await broadcast({
      v: 1,
      message_id: crypto.randomUUID(),
      trip_id: tripId,
      handle: bot.handle,
      kind: "voice",
      audio_path: audioPath,
      text: line.text,
      lat, lng, heading: 90, speed: 7,
      h3_r9: "",
      created_at: new Date().toISOString(),
      convoy: DEMO_TAG,
    });
    return json({ ok: true, riders, spoke: true, handle: bot.handle });
  } catch (e) {
    console.error("demo-pack failed", e);
    return json({ ok: false, riders, spoke: false });
  }
});

/** Bots are real trips (fresh row per 24 h) so mute/report FKs resolve. */
async function ensureTrip(handle: string): Promise<string> {
  const since = new Date(Date.now() - 24 * 3600e3).toISOString();
  const { data } = await supabase.from("trips").select("id").eq("phonetic_handle", handle).gt("created_at", since).limit(1);
  if (data && data.length) return data[0].id;
  const { data: created, error } = await supabase.from("trips").insert({ phonetic_handle: handle }).select("id").single();
  if (error) throw new Error(`trip insert: ${error.message}`);
  return created.id;
}

/** Voice a line once; cached by content hash. Returns the audio_path, or null (text only). */
async function ensureAudio(line: { text: string; bot: number }, voice: string): Promise<string | null> {
  const name = `${await hash8(`${line.text}|${voice}|${STYLE}`)}.wav`;
  const { data: existing } = await supabase.storage.from("synthetic_voice").list("demo", { search: name, limit: 1 });
  if (existing?.some((f) => f.name === name)) return `synthetic_voice/demo/${name}`;
  const wav = await synthesizeSpeech(line.text, voice, STYLE);
  if (!wav) return null;
  const { error } = await supabase.storage.from("synthetic_voice").upload(`demo/${name}`, wav, { contentType: "audio/wav", upsert: true });
  return error ? null : `synthetic_voice/demo/${name}`;
}

async function hash8(s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].slice(0, 8).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function broadcast(payload: unknown) {
  const res = await fetch(`${SUPABASE_URL}/realtime/v1/api/broadcast`, {
    method: "POST",
    headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}`, "Content-Type": "application/json" },
    body: JSON.stringify({ messages: [{ topic: TOPIC, event: "burst", payload, private: false }] }),
  });
  if (!res.ok) throw new Error(`broadcast ${res.status}: ${await res.text()}`);
}
