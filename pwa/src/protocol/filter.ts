// Receive filter, implemented exactly per docs/PROTOCOL.md §5.
// Pure function: no I/O, no globals — the same math ships on Android and iOS.
import {
  AHEAD_DOT_MIN,
  HEADING_DOT_MIN,
  MIN_HEADING_SPEED_MPS,
  NEAR_BUBBLE_M,
  senderRadiusM,
} from "./constants";
import { bearingDeg, haversineM, headingDot, toRad } from "./geo";
import type { BurstPayload, DropReason } from "./types";

export interface ReceiverState {
  tripId: string;
  lat: number;
  lng: number;
  heading: number;
  speed: number; // m/s
  elastic: boolean;
}

export interface FilterDeps {
  isMuted: (tripId: string) => boolean;
  hasSeen: (messageId: string) => boolean;
}

export function evaluateBurst(
  p: BurstPayload,
  r: ReceiverState,
  deps: FilterDeps,
): DropReason {
  // §5.1 self
  if (p.trip_id === r.tripId) return "self";
  // §5.2 dedupe (senders fan out to several rooms)
  if (deps.hasSeen(p.message_id)) return "duplicate";
  // §5.3 mute
  if (deps.isMuted(p.trip_id)) return "muted";

  // §5.4 heading match — skipped for system bursts, slow receivers, elastic mode
  const skipHeading =
    p.kind === "system" || r.speed < MIN_HEADING_SPEED_MPS || r.elastic;
  if (!skipHeading && headingDot(p.heading, r.heading) < HEADING_DOT_MIN) {
    return "heading";
  }

  // §5.5 distance / sender-owned forward cone
  if (r.elastic) return "play"; // reach = subscribed rooms in elastic mode
  const d = haversineM(p.lat, p.lng, r.lat, r.lng);
  if (d <= NEAR_BUBBLE_M) return "play";
  const bearingToReceiver = bearingDeg(p.lat, p.lng, r.lat, r.lng);
  const aheadDot = Math.cos(toRad(bearingToReceiver - p.heading));
  if (aheadDot >= AHEAD_DOT_MIN && d <= senderRadiusM(p.speed)) return "play";
  return "out-of-cone";
}
