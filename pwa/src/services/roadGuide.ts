// PROTOCOL §17 client contract, shared by Car Radio and PelotonCB engines.
import { SUPABASE_ANON_KEY, SUPABASE_URL } from "../config";
import type { BurstPayload } from "../protocol/types";
import type { QueueItem } from "./audio";

/**
 * Asks the gated Road Guide about a burst this trip just sent. Resolves to a local-only
 * system burst to enqueue, or null — for any reason, including errors; silence is the
 * correct failure mode.
 */
export async function requestRoadGuide(sent: BurstPayload): Promise<QueueItem | null> {
  try {
    const res = await fetch(`${SUPABASE_URL}/functions/v1/road-guide`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${SUPABASE_ANON_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        trip_id: sent.trip_id,
        message_id: sent.message_id,
        lat: sent.lat,
        lng: sent.lng,
        heading: sent.heading,
        speed: sent.speed,
      }),
    });
    if (!res.ok) return null;
    const r = await res.json();
    if (r?.respond !== true || typeof r.text !== "string") return null;
    return {
      messageId: typeof r.id === "string" ? r.id : crypto.randomUUID(),
      tripId: "road-guide",
      handle: "Road Guide",
      kind: "system",
      audioPath: typeof r.audio_path === "string" ? r.audio_path : null,
      text: r.text,
      createdAt: new Date().toISOString(),
      isBreadcrumb: true, // private answer: age-exempt
      voiceSeed: 0,
    };
  } catch {
    return null;
  }
}
