// Filter math constants per docs/PROTOCOL.md. Change there first, then here.
export const ROOM_RES = 7; // realtime room H3 resolution
export const MSG_RES = 9; // per-message breadcrumb H3 resolution
export const HEADING_DOT_MIN = 0.85; // ±~32° same-direction cone
export const AHEAD_DOT_MIN = 0.5; // ±60° "receiver is ahead of sender" cone
export const NEAR_BUBBLE_M = 800; // omnidirectional radius
export const MIN_HEADING_SPEED_MPS = 3; // below this, heading is noise — skip check
export const BURST_MAX_AGE_S = 60; // live bursts older than this drop at dequeue
export const PUBLISH_STEP_M = 1200; // sample interval along the forward ray
export const PUBLISH_MAX_CELLS = 4;
export const PRESENCE_INTERVAL_MS = 10_000;
export const ELASTIC_AFTER_MS = 3 * 60_000;
export const DRIVE_LOCK_MPS = 4.5; // ~10 mph
export const MAX_BURST_SECONDS = 10;
export const BREADCRUMB_MIN_GAP_MS = 45_000;
export const PLAYED_IDS_CAP = 2000;
export const SHADOWBAN_CACHE_MS = 60_000;

const MPH_PER_MPS = 2.23694;
const METERS_PER_MILE = 1609.34;

/** Speed-based forward reach: 15 mph → 0.5 mi, 75 mph → 3 mi, clamped. */
export function senderRadiusM(speedMps: number): number {
  const mph = speedMps * MPH_PER_MPS;
  const miles = Math.min(3.0, Math.max(0.5, 0.5 + (mph - 15) * (2.5 / 60)));
  return miles * METERS_PER_MILE;
}
