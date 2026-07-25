// Bot drivers: full protocol participants (trip row, presence, breadcrumb inserts,
// realtime broadcasts) so multi-tab demos exercise the real pipeline end to end.
import { createClient, type RealtimeChannel, type SupabaseClient } from "@supabase/supabase-js";
import { latLngToCell } from "h3-js";
import { SUPABASE_ANON_KEY, SUPABASE_URL } from "../config";
import { generateHandle } from "../protocol/handles";
import { publishCells, roomCell, roomTopic } from "../protocol/rooms";
import type { BurstPayload } from "../protocol/types";
import { broadcastToRooms } from "../services/engine";
import { RouteFollower } from "./follower";
import type { Route } from "./routes";

const BOT_LINES = [
  "Heads up, traffic slows down about a mile ahead, right lane is crawling.",
  "Anybody know why the left lane is blocked up here?",
  "Watch for debris in the middle lane, looks like a ladder.",
  "Smooth sailing ahead for the next few miles, cruise on.",
  "There's a speed trap just past the overpass, ease off a little.",
  "Big pothole coming up on the right, hug the left side.",
  "Merging traffic from the on-ramp, let them in folks.",
  "Rain picking up ahead, roads are getting slick.",
  "Anyone else seeing brake lights up past the bend?",
  "Just passed a tow truck working the shoulder, give them room.",
  "Gas is twenty cents cheaper at the next exit if anyone's running low.",
  "This stretch always backs up around this hour, no surprises today.",
  "Left lane finally opened up, we're rolling again.",
  "Deer on the grass median a half mile up, keep your eyes peeled.",
  "Sunset's unreal out here tonight, worth a look when it's safe.",
  "Construction barrels start in about two miles, they took the right lane.",
];

/** Replies referencing the previous speaker — CB call-and-response feel. */
const BOT_REPLIES = [
  "Copy that, {handle}, appreciate the heads up.",
  "Ten four, {handle}. I'll ease off.",
  "Thanks {handle}, just cleared that spot, it's already thinning out.",
  "Good looking out, {handle}.",
  "Yeah, confirming what {handle} said, saw the same thing.",
  "Roger, {handle}. Anything past the exit?",
];

export type BotRole = "ahead" | "behind" | "oncoming";

export class Bot {
  readonly handle: string;
  readonly role: BotRole;
  tripId: string | null = null;
  speedMps: number;
  private follower: RouteFollower;
  private client: SupabaseClient;
  private channel: RealtimeChannel | null = null;
  private cell: string | null = null;
  private lineIdx = Math.floor(Math.random() * BOT_LINES.length);
  lat = 0;
  lng = 0;
  heading = 0;
  /** epoch ms until which the canvas draws this bot as "transmitting". */
  speakingUntil = 0;

  constructor(route: Route, role: BotRole, startFraction: number, speedMps: number) {
    this.role = role;
    this.handle = generateHandle();
    this.speedMps = speedMps;
    const r = role === "oncoming" ? RouteFollower.reversed(route) : route;
    const f = role === "oncoming" ? 1 - startFraction : startFraction;
    this.follower = new RouteFollower(r, f);
    const p = this.follower.position;
    this.lat = p.lat;
    this.lng = p.lng;
    this.heading = this.follower.heading;
    // Own client = own websocket, so bot presence coexists with the user's channels.
    this.client = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
  }

  async init(): Promise<void> {
    const { data, error } = await this.client
      .from("trips")
      .insert({ phonetic_handle: this.handle })
      .select("id")
      .single();
    if (error) throw new Error(`bot trip failed: ${error.message}`);
    this.tripId = data.id;
    this.updatePresence(true);
  }

  tick(dtS: number) {
    const p = this.follower.advance(this.speedMps, dtS);
    this.lat = p.lat;
    this.lng = p.lng;
    this.heading = p.heading;
    this.updatePresence(false);
  }

  private lastPresenceAt = 0;
  private updatePresence(force: boolean) {
    if (!this.tripId) return;
    const cell = roomCell(this.lat, this.lng);
    const cellChanged = cell !== this.cell;
    if (cellChanged) {
      if (this.channel) void this.client.removeChannel(this.channel);
      this.cell = cell;
      this.channel = this.client.channel(roomTopic(cell));
      this.channel.subscribe((status) => {
        if (status === "SUBSCRIBED") void this.track();
      });
      return;
    }
    if (force || Date.now() - this.lastPresenceAt > 10_000) {
      void this.track();
    }
  }

  private async track() {
    if (!this.channel || !this.tripId) return;
    this.lastPresenceAt = Date.now();
    await this.channel.track({
      trip_id: this.tripId,
      handle: this.handle,
      heading: this.heading,
      speed: this.speedMps,
      kind: "human",
    });
  }

  /** A reply line addressed to the previous speaker. */
  static replyTo(handle: string): string {
    const t = BOT_REPLIES[Math.floor(Math.random() * BOT_REPLIES.length)];
    return t.replace("{handle}", handle);
  }

  /** Send a text burst through the real pipeline: insert breadcrumb + broadcast. */
  async speak(customText?: string): Promise<string> {
    if (!this.tripId) throw new Error("bot not initialized");
    const text = customText ?? BOT_LINES[this.lineIdx++ % BOT_LINES.length];
    this.speakingUntil = Date.now() + Math.max(2500, text.length * 65);
    const messageId = crypto.randomUUID();
    const payload: BurstPayload = {
      v: 1,
      message_id: messageId,
      trip_id: this.tripId,
      handle: this.handle,
      kind: "voice",
      audio_path: null,
      text,
      lat: this.lat,
      lng: this.lng,
      heading: this.heading,
      speed: this.speedMps,
      h3_r9: latLngToCell(this.lat, this.lng, 9),
      created_at: new Date().toISOString(),
    };
    const { error } = await this.client.from("messages").insert({
      id: messageId,
      trip_id: this.tripId,
      kind: "voice",
      text,
      h3_r9: payload.h3_r9,
      location: `SRID=4326;POINT(${this.lng} ${this.lat})`,
      heading: this.heading,
      speed: this.speedMps,
    });
    if (error) throw new Error(`bot insert failed: ${error.message}`);
    await broadcastToRooms(
      publishCells(this.lat, this.lng, this.heading, this.speedMps),
      payload,
    );
    return text;
  }

  /** Stealth-mute a target trip (for shadowban demos). */
  async mute(targetTripId: string): Promise<void> {
    if (!this.tripId) return;
    await this.client.from("mute_events").insert({
      muter_trip_id: this.tripId,
      muted_trip_id: targetTripId,
    });
  }

  destroy() {
    if (this.channel) void this.client.removeChannel(this.channel);
    this.client.realtime.disconnect();
  }
}
