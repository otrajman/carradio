// Wire types per docs/PROTOCOL.md §3.
export interface BurstPayload {
  v: 1;
  message_id: string;
  trip_id: string;
  handle: string;
  kind: "voice" | "system";
  audio_path: string | null;
  text: string | null;
  lat: number;
  lng: number;
  heading: number; // degrees from true north [0,360)
  speed: number; // m/s
  h3_r9: string;
  created_at: string;
}

export interface GpsFix {
  lat: number;
  lng: number;
  heading: number; // degrees, may be NaN when stationary
  speed: number; // m/s
  timestamp: number; // epoch ms
}

export interface Breadcrumb {
  id: string;
  trip_id: string;
  handle: string;
  kind: "voice" | "system";
  audio_path: string | null;
  text: string | null;
  lat: number;
  lng: number;
  heading: number;
  speed: number;
  created_at: string;
}

export type DropReason =
  | "play"
  | "self"
  | "duplicate"
  | "muted"
  | "heading"
  | "out-of-cone"
  | "stale";
