// Synthetic nodes: turn keyless public APIs (NWS alerts, Wikipedia geosearch) into
// conversational "System" breadcrumbs near the caller. Clients TTS the returned text.
import { createClient } from "npm:@supabase/supabase-js@2";
import { latLngToCell } from "npm:h3-js@4";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
);

const SYSTEM_HANDLE = "Radio Tower";
const DEDUPE_RADIUS_M = 3000;
const DEDUPE_HOURS = 6;

async function getSystemTripId(): Promise<string> {
  const { data } = await supabase
    .from("trips")
    .select("id")
    .eq("phonetic_handle", SYSTEM_HANDLE)
    .gt("created_at", new Date(Date.now() - 24 * 3600e3).toISOString())
    .limit(1);
  if (data && data.length > 0) return data[0].id;
  const { data: created, error } = await supabase
    .from("trips")
    .insert({ phonetic_handle: SYSTEM_HANDLE })
    .select("id")
    .single();
  if (error) throw error;
  return created.id;
}

async function fetchWeatherScripts(lat: number, lng: number): Promise<string[]> {
  try {
    const res = await fetch(
      `https://api.weather.gov/alerts/active?point=${lat.toFixed(4)},${lng.toFixed(4)}`,
      { headers: { "User-Agent": "carradio-demo (synthetic-nodes)", Accept: "application/geo+json" } },
    );
    if (!res.ok) return [];
    const json = await res.json();
    const feats = (json.features ?? []).slice(0, 2);
    return feats
      .filter((f: any) => ["Extreme", "Severe", "Moderate"].includes(f.properties?.severity))
      .map((f: any) => {
        const p = f.properties;
        const event = p.event ?? "weather alert";
        return `Heads up — ${event} in this area.`.slice(0, 200);
      });
  } catch {
    return [];
  }
}

async function fetchTriviaScripts(lat: number, lng: number): Promise<string[]> {
  try {
    const geoUrl =
      `https://en.wikipedia.org/w/api.php?action=query&list=geosearch` +
      `&gscoord=${lat.toFixed(5)}%7C${lng.toFixed(5)}&gsradius=3000&gslimit=3&format=json&origin=*`;
    const geo = await (await fetch(geoUrl)).json();
    const pages = geo?.query?.geosearch ?? [];
    if (pages.length === 0) return [];
    const page = pages[Math.floor(pages.length / 2)]; // avoid always picking the same top hit
    const exUrl =
      `https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro&explaintext` +
      `&exsentences=1&pageids=${page.pageid}&format=json&origin=*`;
    const ex = await (await fetch(exUrl)).json();
    let extract: string = ex?.query?.pages?.[page.pageid]?.extract ?? "";
    // keep it to one breath
    if (extract.length > 160) extract = extract.slice(0, 157).replace(/[,;: ]+\S*$/, "") + "…";
    if (!extract) return [`You're passing ${page.title}.`];
    return [`You're passing ${page.title} — ${extract}`.slice(0, 220)];
  } catch {
    return [];
  }
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  try {
    const { lat, lng } = await req.json();
    if (typeof lat !== "number" || typeof lng !== "number" || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
      return new Response(JSON.stringify({ error: "lat/lng required" }), { status: 400, headers: CORS });
    }

    // Dedupe: if system breadcrumbs already exist nearby and fresh, return those.
    const { data: existing } = await supabase.rpc("get_breadcrumbs", {
      p_trip_id: null,
      p_lat: lat,
      p_lng: lng,
      p_radius_m: DEDUPE_RADIUS_M,
      p_since_hours: DEDUPE_HOURS,
      p_limit: 10,
    });
    const existingSystem = (existing ?? []).filter((m: any) => m.kind === "system");
    if (existingSystem.length > 0) {
      return new Response(JSON.stringify({ messages: existingSystem, cached: true }), {
        headers: { ...CORS, "Content-Type": "application/json" },
      });
    }

    const [weather, trivia] = await Promise.all([
      fetchWeatherScripts(lat, lng),
      fetchTriviaScripts(lat, lng),
    ]);
    const scripts = [...weather, ...trivia].slice(0, 3);
    if (scripts.length === 0) {
      return new Response(JSON.stringify({ messages: [] }), {
        headers: { ...CORS, "Content-Type": "application/json" },
      });
    }

    const tripId = await getSystemTripId();
    const h3r9 = latLngToCell(lat, lng, 9);
    const rows = scripts.map((text) => ({
      trip_id: tripId,
      kind: "system",
      text,
      h3_r9: h3r9,
      location: `SRID=4326;POINT(${lng} ${lat})`,
      heading: 0,
      speed: 0,
    }));
    const { data: inserted, error } = await supabase
      .from("messages")
      .insert(rows)
      .select("id, trip_id, kind, text, heading, speed, created_at");
    if (error) throw error;

    const messages = (inserted ?? []).map((m: any) => ({
      ...m,
      handle: SYSTEM_HANDLE,
      audio_path: null,
      lat,
      lng,
    }));
    return new Response(JSON.stringify({ messages, cached: false }), {
      headers: { ...CORS, "Content-Type": "application/json" },
    });
  } catch (e) {
    return new Response(JSON.stringify({ error: String(e) }), {
      status: 500,
      headers: { ...CORS, "Content-Type": "application/json" },
    });
  }
});
