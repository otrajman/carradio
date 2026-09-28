// RadioEngine: one instance per trip. Owns GPS → rooms → filter → play queue,
// plus the send pipeline. UI layers (drive mode, simulator) only observe it.
import type { RealtimeChannel } from "@supabase/supabase-js";
import {
  BUCKET,
  roadGuideEnabled,
  SUPABASE_ANON_KEY,
  SUPABASE_URL,
  SYNTHETIC_NODES,
} from "../config";
import {
  BURST_MAX_AGE_S,
  BREADCRUMB_MIN_GAP_MS,
  ELASTIC_AFTER_MS,
  PLAYED_IDS_CAP,
  PRESENCE_INTERVAL_MS,
  SHADOWBAN_CACHE_MS,
  senderRadiusM,
} from "../protocol/constants";
import { evaluateBurst } from "../protocol/filter";
import { haversineM } from "../protocol/geo";
import { publishCells, roomTopic, subscribeCells } from "../protocol/rooms";
import type { Breadcrumb, BurstPayload, DropReason, GpsFix } from "../protocol/types";
import { latLngToCell } from "h3-js";
import { PlayQueue, earcons, voiceSeedOf } from "./audio";
import type { LocationProvider } from "./location";
import { supabase } from "./supabase";

export interface PresencePeer {
  trip_id: string;
  handle: string;
  heading: number;
  speed: number;
  kind: "human" | "system";
}

export interface FilterEvent {
  at: number;
  handle: string;
  tripId: string;
  reason: DropReason;
  distanceM: number | null;
  isBreadcrumb: boolean;
}

export interface EngineSnapshot {
  tripId: string | null;
  handle: string;
  fix: GpsFix | null;
  roomCells: string[];
  peers: PresencePeer[];
  recording: boolean;
  sending: boolean;
  elastic: boolean;
  shadowbanned: boolean;
  nowPlayingHandle: string | null;
  events: FilterEvent[];
  started: boolean;
}

type Listener = () => void;

const PLAYED_KEY = "carradio.playedIds";

export class RadioEngine {
  readonly queue = new PlayQueue();
  private listeners = new Set<Listener>();
  private snapshot: EngineSnapshot;

  private tripId: string | null = null;
  private handle: string;
  private provider: LocationProvider;
  private fix: GpsFix | null = null;

  private channels = new Map<string, RealtimeChannel>();
  private ownCell: string | null = null;
  private presenceChannel: RealtimeChannel | null = null;
  private presenceTimer: ReturnType<typeof setInterval> | null = null;

  private seenIds: string[] = [];
  private seenSet = new Set<string>();
  private mutedSet = new Set<string>();

  private playedIds: Set<string>;
  private lastCrumbCell: string | null = null;
  private lastCrumbAt = 0;
  private syntheticCells = new Set<string>();

  private lastHeardAt = Date.now();
  private elastic = false;
  private elasticTimer: ReturnType<typeof setInterval> | null = null;

  private shadowbanned = false;
  private shadowbanCheckedAt = 0;

  readonly convoyTag: string | null;

  constructor(provider: LocationProvider, handle: string, convoyTag: string | null = null) {
    this.provider = provider;
    this.handle = handle;
    this.convoyTag = convoyTag;
    this.playedIds = new Set(
      JSON.parse(localStorage.getItem(PLAYED_KEY) ?? "[]") as string[],
    );
    this.queue.maxAgeS = BURST_MAX_AGE_S;
    this.snapshot = this.buildSnapshot();
    this.queue.onChange(() => this.publish());
  }

  // ---- observation ----
  subscribe(fn: Listener): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }
  getSnapshot(): EngineSnapshot {
    return this.snapshot;
  }
  private events: FilterEvent[] = [];
  private publish() {
    this.snapshot = this.buildSnapshot();
    for (const fn of this.listeners) fn();
  }
  private buildSnapshot(): EngineSnapshot {
    return {
      tripId: this.tripId,
      handle: this.handle,
      fix: this.fix,
      roomCells: [...this.channels.keys()],
      peers: this.collectPeers(),
      recording: this.recording,
      sending: this.sending,
      elastic: this.elastic,
      shadowbanned: this.shadowbanned,
      nowPlayingHandle: this.queue.nowPlaying?.handle ?? null,
      events: this.events.slice(-40),
      started: this.started,
    };
  }

  private started = false;
  private recording = false;
  private sending = false;

  // ---- lifecycle ----
  async start(): Promise<void> {
    if (this.started) return;
    const { data, error } = await supabase
      .from("trips")
      .insert({ phonetic_handle: this.handle })
      .select("id")
      .single();
    if (error) throw new Error(`trip create failed: ${error.message}`);
    this.tripId = data.id;
    this.started = true;
    this.provider.start((fix) => this.onFix(fix));
    this.elasticTimer = setInterval(() => this.updateElastic(), 15_000);
    this.presenceTimer = setInterval(() => this.trackPresence(), PRESENCE_INTERVAL_MS);
    // On-air welcome: give breadcrumbs/live traffic a moment to arrive; if the
    // queue is still empty, the station greets you (system voice, triple-chime).
    setTimeout(() => {
      if (!this.started || !this.queue.isEmpty) return;
      const peers = this.collectPeers().filter((p) => p.kind !== "system").length;
      const around =
        peers === 0
          ? "Quiet out here right now."
          : peers === 1
            ? "One driver nearby."
            : `${peers} drivers nearby.`;
      this.queue.enqueue({
        messageId: `welcome-${crypto.randomUUID()}`,
        tripId: "system-welcome",
        handle: "Radio Tower",
        kind: "system",
        audioPath: null,
        text: `You're on the air as ${this.handle}. ${around}`,
        createdAt: new Date().toISOString(),
        isBreadcrumb: false,
        voiceSeed: 0,
      });
    }, 2500);
    this.publish();
  }

  stop() {
    this.started = false;
    this.provider.stop();
    if (this.elasticTimer) clearInterval(this.elasticTimer);
    if (this.presenceTimer) clearInterval(this.presenceTimer);
    for (const ch of this.channels.values()) void supabase.removeChannel(ch);
    this.channels.clear();
    this.presenceChannel = null;
    this.queue.stopCurrent();
    this.publish();
  }

  setRecordingFlag(v: boolean) {
    this.recording = v;
    this.publish();
  }

  // ---- GPS → rooms ----
  private onFix(fix: GpsFix) {
    this.fix = fix;
    const cells = subscribeCells(fix.lat, fix.lng);
    const ownCell = cells[0];
    const want = new Set(cells);
    for (const [cell, ch] of this.channels) {
      if (!want.has(cell)) {
        void supabase.removeChannel(ch);
        this.channels.delete(cell);
      }
    }
    for (const cell of cells) {
      if (!this.channels.has(cell)) this.channels.set(cell, this.joinRoom(cell));
    }
    if (ownCell !== this.ownCell) {
      this.ownCell = ownCell;
      this.presenceChannel = this.channels.get(ownCell) ?? null;
      this.trackPresence();
      if (SYNTHETIC_NODES && !this.syntheticCells.has(ownCell)) {
        this.syntheticCells.add(ownCell);
        void this.fetchSynthetic(fix);
      }
    }
    // breadcrumbs keyed on finer res-8 cell changes
    const r8 = latLngToCell(fix.lat, fix.lng, 8);
    if (r8 !== this.lastCrumbCell) {
      this.lastCrumbCell = r8;
      void this.fetchBreadcrumbs(fix);
    }
    this.publish();
  }

  private joinRoom(cell: string): RealtimeChannel {
    const ch = supabase.channel(roomTopic(cell), {
      config: { broadcast: { self: false, ack: false } },
    });
    ch.on("broadcast", { event: "burst" }, ({ payload }) => {
      this.onBurst(payload as BurstPayload, false);
    });
    // presence listener required — Realtime only sends presence events to
    // clients that register interest
    ch.on("presence", { event: "sync" }, () => this.publish());
    ch.subscribe();
    return ch;
  }

  private trackPresence() {
    if (!this.presenceChannel || !this.fix || !this.tripId) return;
    void this.presenceChannel.track({
      trip_id: this.tripId,
      handle: this.handle,
      heading: this.fix.heading,
      speed: this.fix.speed,
      kind: "human",
    } satisfies PresencePeer);
  }

  private collectPeers(): PresencePeer[] {
    const peers: PresencePeer[] = [];
    for (const ch of this.channels.values()) {
      const state = ch.presenceState<PresencePeer>();
      for (const key of Object.keys(state)) {
        for (const p of state[key]) {
          if (p.trip_id && p.trip_id !== this.tripId) peers.push(p);
        }
      }
    }
    return peers;
  }

  // ---- receive ----
  private onBurst(p: BurstPayload, isBreadcrumb: boolean) {
    if (!this.fix || !this.tripId) return;
    const reason = evaluateBurst(
      p,
      {
        tripId: this.tripId,
        lat: this.fix.lat,
        lng: this.fix.lng,
        heading: this.fix.heading,
        speed: this.fix.speed,
        elastic: this.elastic,
        convoyTag: this.convoyTag,
      },
      {
        isMuted: (id) => this.mutedSet.has(id),
        hasSeen: (id) => this.seenSet.has(id),
      },
    );
    this.markSeen(p.message_id);
    if (reason === "duplicate") return; // fan-out echo; protocol-internal, not worth logging
    const dist =
      this.fix ? Math.round(haversineM(p.lat, p.lng, this.fix.lat, this.fix.lng)) : null;
    this.events.push({
      at: Date.now(),
      handle: p.handle,
      tripId: p.trip_id,
      reason,
      distanceM: dist,
      isBreadcrumb,
    });
    if (reason !== "play") {
      this.publish();
      return;
    }
    this.lastHeardAt = Date.now();
    if (this.elastic) this.setElastic(false);
    this.queue.enqueue({
      messageId: p.message_id,
      tripId: p.trip_id,
      handle: p.handle,
      kind: p.kind,
      audioPath: p.audio_path,
      text: p.text,
      createdAt: p.created_at,
      isBreadcrumb,
      voiceSeed: voiceSeedOf(p.trip_id),
    });
    this.rememberPlayed(p.message_id);
    this.publish();
  }

  private markSeen(id: string) {
    if (this.seenSet.has(id)) return;
    this.seenSet.add(id);
    this.seenIds.push(id);
    if (this.seenIds.length > 200) {
      const drop = this.seenIds.shift()!;
      this.seenSet.delete(drop);
    }
  }

  private rememberPlayed(id: string) {
    this.playedIds.add(id);
    if (this.playedIds.size > PLAYED_IDS_CAP) {
      const trim = [...this.playedIds].slice(-PLAYED_IDS_CAP);
      this.playedIds = new Set(trim);
    }
    localStorage.setItem(PLAYED_KEY, JSON.stringify([...this.playedIds]));
  }

  // ---- elastic mode (§7) ----
  private updateElastic() {
    const humanPeers = this.collectPeers().filter((p) => p.kind !== "system");
    if (this.elastic) {
      if (humanPeers.length >= 2) this.setElastic(false);
    } else if (
      humanPeers.length === 0 &&
      Date.now() - this.lastHeardAt > ELASTIC_AFTER_MS
    ) {
      this.setElastic(true);
    }
  }
  private setElastic(v: boolean) {
    if (this.elastic !== v) {
      this.elastic = v;
      this.publish();
    }
  }

  // ---- send pipeline (§4) ----
  async sendBurst(blob: Blob): Promise<void> {
    if (!this.fix || !this.tripId || this.sending) return;
    this.sending = true;
    this.publish();
    try {
      if (await this.checkShadowban()) {
        earcons.sent(); // pretend-send
        return;
      }
      const messageId = crypto.randomUUID();
      const audioPath = `${BUCKET}/${this.tripId}/${messageId}.webm`;
      const { error: upErr } = await supabase.storage
        .from(BUCKET)
        .upload(`${this.tripId}/${messageId}.webm`, blob, {
          contentType: "audio/webm",
        });
      if (upErr) throw new Error(`upload: ${upErr.message}`);
      const payload = this.buildPayload(messageId, audioPath, null, "voice");
      const { error: insErr } = await supabase.from("messages").insert({
        id: messageId,
        trip_id: this.tripId,
        kind: "voice",
        audio_path: audioPath,
        h3_r9: payload.h3_r9,
        location: `SRID=4326;POINT(${this.fix.lng} ${this.fix.lat})`,
        heading: payload.heading,
        speed: payload.speed,
        convoy_tag: this.convoyTag,
      });
      if (insErr) throw new Error(`insert: ${insErr.message}`);
      await broadcastToRooms(
        publishCells(this.fix.lat, this.fix.lng, this.fix.heading, this.fix.speed),
        payload,
      );
      earcons.sent();
      if (roadGuideEnabled()) void this.askRoadGuide(payload);
    } finally {
      this.sending = false;
      this.publish();
    }
  }

  buildPayload(
    messageId: string,
    audioPath: string | null,
    text: string | null,
    kind: "voice" | "system",
  ): BurstPayload {
    const f = this.fix!;
    return {
      v: 1,
      message_id: messageId,
      trip_id: this.tripId!,
      handle: this.handle,
      kind,
      audio_path: audioPath,
      text,
      lat: f.lat,
      lng: f.lng,
      heading: ((f.heading % 360) + 360) % 360,
      speed: f.speed,
      h3_r9: latLngToCell(f.lat, f.lng, 9),
      created_at: new Date().toISOString(),
      convoy: this.convoyTag,
    };
  }

  private async checkShadowban(): Promise<boolean> {
    if (Date.now() - this.shadowbanCheckedAt < SHADOWBAN_CACHE_MS) {
      return this.shadowbanned;
    }
    const { data } = await supabase.rpc("is_shadowbanned", {
      p_trip_id: this.tripId,
    });
    this.shadowbanned = data === true;
    this.shadowbanCheckedAt = Date.now();
    this.publish();
    return this.shadowbanned;
  }

  // ---- mute (§8) ----
  async skipAndMute(): Promise<string | null> {
    const current = this.queue.stopCurrent();
    if (!current || !this.tripId) return null;
    earcons.muted();
    if (current.tripId && current.tripId !== this.tripId) {
      this.mutedSet.add(current.tripId);
      await supabase.from("mute_events").insert({
        muter_trip_id: this.tripId,
        muted_trip_id: current.tripId,
      });
    }
    this.publish();
    return current.handle;
  }

  /**
   * §8 report: reports the currently-playing (or last finished) burst, stops it,
   * and mutes the sender locally. The sender is never notified.
   */
  async reportCurrentOrLast(reason: string | null = null): Promise<string | null> {
    const target = this.queue.nowPlaying ?? this.queue.lastFinished;
    if (!target || !this.tripId || target.tripId === this.tripId) return null;
    if (this.queue.nowPlaying) this.queue.stopCurrent();
    earcons.muted();
    this.mutedSet.add(target.tripId);
    await supabase.from("reports").insert({
      reporter_trip_id: this.tripId,
      reported_trip_id: target.tripId,
      message_id: target.messageId,
      reason,
    });
    this.publish();
    return target.handle;
  }

  // ---- breadcrumbs (§6) ----
  private async fetchBreadcrumbs(fix: GpsFix) {
    if (!this.tripId) return;
    if (Date.now() - this.lastCrumbAt < BREADCRUMB_MIN_GAP_MS) return;
    const radius = Math.max(1600, senderRadiusM(fix.speed));
    const { data, error } = await supabase.rpc("get_breadcrumbs", {
      p_trip_id: this.tripId,
      p_lat: fix.lat,
      p_lng: fix.lng,
      p_radius_m: radius,
      p_since_hours: 24,
      p_limit: 10,
      p_convoy: this.convoyTag,
    });
    if (error || !data) return;
    const fresh = (data as Breadcrumb[]).filter((m) => !this.playedIds.has(m.id));
    if (fresh.length === 0) return;
    const m = fresh[0]; // newest unheard; one per gap window so live traffic wins
    this.lastCrumbAt = Date.now();
    const ageH = (Date.now() - Date.parse(m.created_at)) / 3600e3;
    const text =
      m.kind === "system" && ageH > 1 && m.text ? `Earlier here: ${m.text}` : m.text;
    this.onBurst(
      {
        v: 1,
        message_id: m.id,
        trip_id: m.trip_id,
        handle: m.handle,
        kind: m.kind,
        audio_path: m.audio_path,
        text,
        lat: m.lat,
        lng: m.lng,
        heading: m.heading,
        speed: m.speed,
        h3_r9: latLngToCell(m.lat, m.lng, 9),
        created_at: m.created_at,
      },
      true,
    );
  }

  // ---- Road Guide (§17) ----
  /** The server gates this burst; only route/scenery/POI talk gets a private answer. */
  private async askRoadGuide(sent: BurstPayload) {
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
      if (!res.ok) return;
      const r = await res.json();
      if (r?.respond !== true || typeof r.text !== "string" || !this.started) return;
      this.queue.enqueue({
        messageId: typeof r.id === "string" ? r.id : crypto.randomUUID(),
        tripId: "road-guide",
        handle: "Road Guide",
        kind: "system",
        audioPath: typeof r.audio_path === "string" ? r.audio_path : null,
        text: r.text,
        createdAt: new Date().toISOString(),
        isBreadcrumb: true, // private answer: age-exempt
        voiceSeed: 0,
      });
      this.publish();
    } catch {
      // offline or gated — silence is the correct failure mode
    }
  }

  // ---- synthetic nodes (§12) ----
  private async fetchSynthetic(fix: GpsFix) {
    try {
      await fetch(`${SUPABASE_URL}/functions/v1/synthetic-nodes`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${SUPABASE_ANON_KEY}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ lat: fix.lat, lng: fix.lng }),
      });
      // Results surface through the breadcrumb query; force one soon.
      this.lastCrumbAt = 0;
      this.lastCrumbCell = null;
    } catch {
      // offline or throttled — fine
    }
  }
}

// ---- REST broadcast fan-out (publish without joining channels) ----
export async function broadcastToRooms(
  cells: string[],
  payload: BurstPayload,
): Promise<void> {
  const res = await fetch(`${SUPABASE_URL}/realtime/v1/api/broadcast`, {
    method: "POST",
    headers: {
      apikey: SUPABASE_ANON_KEY,
      Authorization: `Bearer ${SUPABASE_ANON_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      messages: cells.map((cell) => ({
        topic: roomTopic(cell),
        event: "burst",
        payload,
        private: false,
      })),
    }),
  });
  if (!res.ok) {
    throw new Error(`broadcast failed: ${res.status} ${await res.text()}`);
  }
}
