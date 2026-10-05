// PelotonEngine: one instance per ride. Owns trip identity, GPS → pack/geo channels,
// the §16 receive filter, half-duplex playback and the VOX send pipeline. The UI only
// observes it. Car Radio's RadioEngine is the sibling for the road; the shared pieces
// (filter, queue, earcons, Road Guide, REST broadcast) are reused, the reach rules differ.
import type { RealtimeChannel } from "@supabase/supabase-js";
import { gridDisk, latLngToCell } from "h3-js";
import { BUCKET, roadGuideEnabled, SUPABASE_ANON_KEY, SUPABASE_URL } from "../config";
import { BURST_MAX_AGE_S, PRESENCE_INTERVAL_MS, SHADOWBAN_CACHE_MS } from "../protocol/constants";
import { evaluateBurst } from "../protocol/filter";
import {
  cleanRiderName,
  GEO_RES,
  DEMO_RIDERS, geoChannel, isDemoCode,
  OPEN_ROAD_TAG,
  packChannel,
  packInRange,
} from "../protocol/peloton";
import type { BurstPayload, GpsFix } from "../protocol/types";
import { earcons, PlayQueue, voiceSeedOf } from "./audio";
import { broadcastToTopics } from "./engine";
import type { LocationProvider } from "./location";
import { requestRoadGuide } from "./roadGuide";
import { supabase } from "./supabase";
import { snippetFormat, VoxRecorder, type MicState } from "./vox";

export interface PelotonSnapshot {
  tripId: string | null;
  handle: string;
  /** Display code for a pack ride, null on open road. */
  packCode: string | null;
  fix: GpsFix | null;
  riders: number;
  mic: MicState;
  micLevel: number;
  paused: boolean;
  playing: boolean;
  speakerHandle: string | null;
  lastSpeakerHandle: string | null;
  snippetsSent: number;
  status: string | null;
  started: boolean;
}

type Listener = () => void;

const TURN_RELEASE_DELAY_MS = 350;
const DEMO_PING_MS = 15_000;

/** A chunk of the same phrase follows within this long of the previous one ending. */
const CONTINUATION_GAP_MS = 2_000;
/** §16.6: how long the pack gets to answer before the Road Guide does. */
const REPLY_WAIT_MS = 8_000;

export class PelotonEngine {
  readonly queue = new PlayQueue();
  private listeners = new Set<Listener>();
  private snapshot: PelotonSnapshot;

  private tripId: string | null = null;
  private fix: GpsFix | null = null;
  private started = false;
  private paused = false;
  private micState: MicState = "off";
  private micLevel = 0;
  private snippetsSent = 0;
  private status: string | null = "Waiting for GPS…";
  private lastSpeakerHandle: string | null = null;

  private channels = new Map<string, RealtimeChannel>();
  private ownTopic: string | null = null;
  private trackedTopic: string | null = null;
  private presenceTimer: ReturnType<typeof setInterval> | null = null;
  private demoTimer: ReturnType<typeof setInterval> | null = null;
  private releaseTimer: ReturnType<typeof setTimeout> | null = null;

  private seenIds: string[] = [];
  private seenSet = new Set<string>();
  private mutedSet = new Set<string>();
  private shadowbanned = false;
  private shadowbanCheckedAt = 0;
  private sendChain: Promise<void> = Promise.resolve();
  /** Last time a pack burst passed the receive filter. */
  private lastIncomingAt = 0;

  private vox: VoxRecorder;

  /** What the pack sees: the rider's own name when they gave one, else the trip handle. */
  private handle: string;

  /**
   * `packTag` null = open road (fixed OPEN_ROAD_TAG on the wire). `riderName` is the
   * optional §16.5 display name; the trip row always carries the generated `tripHandle`.
   */
  constructor(
    private provider: LocationProvider,
    private tripHandle: string,
    private packTag: string | null,
    private packCode: string | null,
    riderName: string | null = null,
  ) {
    this.handle = cleanRiderName(riderName) ?? tripHandle;
    this.queue.maxAgeS = BURST_MAX_AGE_S;
    this.queue.prefetch = true;
    this.queue.continuationGapMs = CONTINUATION_GAP_MS;
    this.queue.beforeTurn = () => {
      if (this.releaseTimer) clearTimeout(this.releaseTimer);
      this.releaseTimer = null;
      return this.vox.yieldTurn();
    };
    this.queue.afterTurn = () => {
      // Let the speaker's tail die out before reopening the mic; the next queued burst
      // cancels this and keeps the mic yielded.
      this.releaseTimer = setTimeout(() => {
        this.releaseTimer = null;
        if (!this.paused) this.vox.releaseTurn();
      }, TURN_RELEASE_DELAY_MS);
    };
    this.queue.onChange((now) => {
      if (now) this.lastSpeakerHandle = now.handle;
      this.publish();
    });
    this.vox = new VoxRecorder({
      onSnippet: (blob, whole) => this.enqueueSend(blob, whole),
      onMic: (m) => {
        this.micState = this.paused ? "paused" : m;
        this.publish();
      },
      onLevel: (l) => {
        this.micLevel = l;
        this.publish();
      },
    });
    this.snapshot = this.buildSnapshot();
  }

  get wireTag(): string {
    return this.packTag ?? OPEN_ROAD_TAG;
  }
  get isPack(): boolean {
    return this.packTag !== null;
  }
  /** §16.7: the reserved DEMO pack is kept populated by the server while we're in it. */
  get isDemo(): boolean {
    return isDemoCode(this.packCode);
  }

  private async pingDemoPack() {
    if (!this.tripId) return;
    try {
      await fetch(`${SUPABASE_URL}/functions/v1/demo-pack`, {
        method: "POST",
        headers: { Authorization: `Bearer ${SUPABASE_ANON_KEY}`, "Content-Type": "application/json" },
        body: JSON.stringify({ trip_id: this.tripId, lat: this.fix?.lat, lng: this.fix?.lng }),
      });
    } catch {
      // offline: the pack just stays quiet until the next ping
    }
  }

  // ---- observation ----
  subscribe(fn: Listener): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }
  getSnapshot(): PelotonSnapshot {
    return this.snapshot;
  }
  private publish() {
    this.snapshot = this.buildSnapshot();
    for (const fn of this.listeners) fn();
  }
  private buildSnapshot(): PelotonSnapshot {
    const now = this.queue.nowPlaying;
    return {
      tripId: this.tripId,
      handle: this.handle,
      packCode: this.packCode,
      fix: this.fix,
      riders: this.countRiders() + (this.isDemo ? DEMO_RIDERS : 0),
      mic: this.paused ? "paused" : this.micState,
      micLevel: this.micLevel,
      paused: this.paused,
      playing: now !== null,
      speakerHandle: now?.handle ?? null,
      lastSpeakerHandle: this.lastSpeakerHandle,
      snippetsSent: this.snippetsSent,
      status: this.status,
      started: this.started,
    };
  }

  // ---- lifecycle ----
  async start(): Promise<void> {
    if (this.started) return;
    const { data, error } = await supabase
      .from("trips")
      .insert({ phonetic_handle: this.tripHandle })
      .select("id")
      .single();
    if (error) throw new Error(`trip create failed: ${error.message}`);
    this.tripId = data.id;
    this.started = true;
    this.provider.start((fix) => this.onFix(fix));
    this.presenceTimer = setInterval(() => this.trackPresence(), PRESENCE_INTERVAL_MS);
    if (this.isDemo) {
      void this.pingDemoPack();
      this.demoTimer = setInterval(() => void this.pingDemoPack(), DEMO_PING_MS);
    }
    await this.startMic();
    this.publish();
  }

  stop() {
    this.started = false;
    this.provider.stop();
    if (this.presenceTimer) clearInterval(this.presenceTimer);
    if (this.demoTimer) clearInterval(this.demoTimer);
    if (this.releaseTimer) clearTimeout(this.releaseTimer);
    this.vox.stop();
    for (const ch of this.channels.values()) void supabase.removeChannel(ch);
    this.channels.clear();
    this.queue.clear();
    if (this.releaseTimer) clearTimeout(this.releaseTimer);
    this.releaseTimer = null;
    this.publish();
  }

  private async startMic() {
    if (this.paused) return;
    try {
      await this.vox.start();
    } catch (e) {
      this.status = "Microphone unavailable";
      this.micState = "off";
      this.publish();
      throw e;
    }
  }

  /** Pause / resume transmit (screen button or earbud Play/Pause). You still hear the pack. */
  togglePause() {
    if (!this.started) return;
    this.paused = !this.paused;
    if (this.paused) {
      this.vox.stop();
      this.micState = "paused";
      earcons.muted();
    } else {
      earcons.micOpen(); // cue plays before the mic opens, so it isn't transmitted
      this.micState = "listening";
      setTimeout(() => void this.startMic().catch(() => {}), 250);
    }
    this.publish();
  }

  // ---- GPS → channels (§16.2) ----
  private onFix(fix: GpsFix) {
    const first = this.fix === null;
    this.fix = fix;
    if (first && this.status === "Waiting for GPS…") this.status = null;
    let own: string;
    let wanted: string[];
    if (this.packTag) {
      own = packChannel(this.packTag);
      wanted = [own];
    } else {
      const cell = latLngToCell(fix.lat, fix.lng, GEO_RES);
      own = geoChannel(cell);
      wanted = gridDisk(cell, 1).map(geoChannel);
    }
    const want = new Set(wanted);
    for (const [topic, ch] of this.channels) {
      if (!want.has(topic)) {
        void supabase.removeChannel(ch);
        this.channels.delete(topic);
        if (this.trackedTopic === topic) this.trackedTopic = null;
      }
    }
    for (const topic of wanted) {
      if (!this.channels.has(topic)) this.channels.set(topic, this.join(topic));
    }
    if (own !== this.ownTopic) {
      this.ownTopic = own;
      this.trackPresence();
    }
    this.publish();
  }

  private join(topic: string): RealtimeChannel {
    const ch = supabase.channel(topic, { config: { broadcast: { self: false, ack: false } } });
    ch.on("broadcast", { event: "burst" }, ({ payload }) => this.onBurst(payload as BurstPayload));
    ch.on("presence", { event: "sync" }, () => this.publish());
    ch.subscribe();
    return ch;
  }

  private trackPresence() {
    if (!this.ownTopic || !this.fix || !this.tripId) return;
    const ch = this.channels.get(this.ownTopic);
    if (!ch) return;
    if (this.trackedTopic && this.trackedTopic !== this.ownTopic) {
      void this.channels.get(this.trackedTopic)?.untrack();
    }
    this.trackedTopic = this.ownTopic;
    void ch.track({
      trip_id: this.tripId,
      handle: this.handle,
      heading: this.fix.heading,
      speed: this.fix.speed,
      kind: "rider",
    });
  }

  private countRiders(): number {
    const ids = new Set<string>();
    for (const ch of this.channels.values()) {
      const state = ch.presenceState<{ trip_id?: string }>();
      for (const key of Object.keys(state)) {
        for (const p of state[key]) {
          if (p.trip_id && p.trip_id !== this.tripId) ids.add(p.trip_id);
        }
      }
    }
    return ids.size;
  }

  // ---- receive (§5.1–5.3 + §16.3) ----
  private onBurst(p: BurstPayload) {
    if (!this.fix || !this.tripId) return;
    const receiver = {
      tripId: this.tripId,
      lat: this.fix.lat,
      lng: this.fix.lng,
      heading: this.fix.heading,
      speed: this.fix.speed,
      elastic: false,
      convoyTag: this.wireTag,
    };
    const reason = evaluateBurst(p, receiver, {
      isMuted: (id) => this.mutedSet.has(id),
      hasSeen: (id) => this.seenSet.has(id),
    });
    this.markSeen(p.message_id);
    if (reason !== "play" || p.kind === "system") return;
    if (!this.isPack && !packInRange(p, receiver)) return;
    this.lastIncomingAt = Date.now();
    // §16.6: people outrank the AI — a live burst cuts off a Road Guide answer.
    if (this.queue.nowPlaying?.kind === "system") this.queue.stopCurrent();
    this.queue.enqueue({
      messageId: p.message_id,
      tripId: p.trip_id,
      handle: cleanRiderName(p.handle) ?? "Rider",
      kind: p.kind,
      audioPath: p.audio_path,
      text: p.text,
      createdAt: p.created_at,
      isBreadcrumb: false,
      voiceSeed: voiceSeedOf(p.trip_id),
    });
  }

  private markSeen(id: string) {
    if (this.seenSet.has(id)) return;
    this.seenSet.add(id);
    this.seenIds.push(id);
    if (this.seenIds.length > 200) this.seenSet.delete(this.seenIds.shift()!);
  }

  // ---- transmit (VOX → §4 pipeline) ----
  private enqueueSend(blob: Blob, whole: boolean) {
    // Serial: the chunks of a phrase must reach the pack in order.
    this.sendChain = this.sendChain.then(() => this.sendSnippet(blob, whole)).catch(() => {});
  }

  private async sendSnippet(blob: Blob, whole: boolean) {
    if (!this.started) return;
    const trip = this.tripId;
    const fix = this.fix;
    if (!trip || !fix) {
      this.status = "Waiting for GPS — not sent";
      this.publish();
      return;
    }
    try {
      if (await this.checkShadowban()) {
        this.snippetsSent += 1; // §4.1 — pretend-send
        this.publish();
        return;
      }
      const messageId = crypto.randomUUID();
      const { ext, contentType } = snippetFormat(blob);
      const objectPath = `${trip}/${messageId}.${ext}`;
      const audioPath = `${BUCKET}/${objectPath}`;
      const { error: upErr } = await supabase.storage
        .from(BUCKET)
        .upload(objectPath, blob, { contentType });
      if (upErr) throw new Error(`upload: ${upErr.message}`);
      const payload: BurstPayload = {
        v: 1,
        message_id: messageId,
        trip_id: trip,
        handle: this.handle,
        kind: "voice",
        audio_path: audioPath,
        text: null,
        lat: fix.lat,
        lng: fix.lng,
        heading: ((fix.heading % 360) + 360) % 360,
        speed: fix.speed,
        h3_r9: latLngToCell(fix.lat, fix.lng, 9),
        created_at: new Date().toISOString(),
        convoy: this.wireTag,
      };
      // The pack hears it as soon as the audio is up; the row (moderation, Road Guide)
      // follows and never delays delivery.
      if (this.ownTopic) await broadcastToTopics([this.ownTopic], payload);
      this.snippetsSent += 1;
      this.status = null;
      this.publish();
      const { error: insErr } = await supabase.from("messages").insert({
        id: messageId,
        trip_id: trip,
        kind: "voice",
        audio_path: audioPath,
        h3_r9: payload.h3_r9,
        location: `SRID=4326;POINT(${fix.lng} ${fix.lat})`,
        heading: payload.heading,
        speed: payload.speed,
        convoy_tag: this.wireTag,
      });
      if (insErr) {
        console.warn("message row insert failed", insErr.message);
        return;
      }
      // No "sent" earcon: the rider may already be talking again and it would land in
      // the next snippet. The counter confirms instead.
      // §17: only a phrase that went out whole — a 3 s chunk is not a question.
      if (whole && roadGuideEnabled()) void this.askRoadGuide(payload);
    } catch (e) {
      console.warn("send failed", e);
      this.status = "Send failed — check connection";
      this.publish();
    }
  }

  /**
   * §17 answer to what this rider just said (private). With other riders around it is a
   * fallback (§16.6): the pack gets REPLY_WAIT_MS to answer first, and the AI answer is
   * dropped if anyone did.
   */
  private async askRoadGuide(sent: BurstPayload) {
    const askedAt = Date.now();
    const answer = await requestRoadGuide(sent);
    if (!answer) return;
    if (this.countRiders() > 0) {
      const wait = askedAt + REPLY_WAIT_MS - Date.now();
      if (wait > 0) await new Promise((r) => setTimeout(r, wait));
    }
    if (!this.started || this.lastIncomingAt > askedAt) return; // a rider replied
    this.queue.enqueue(answer);
  }

  private async checkShadowban(): Promise<boolean> {
    if (Date.now() - this.shadowbanCheckedAt < SHADOWBAN_CACHE_MS) return this.shadowbanned;
    const { data } = await supabase.rpc("is_shadowbanned", { p_trip_id: this.tripId });
    this.shadowbanned = data === true;
    this.shadowbanCheckedAt = Date.now();
    return this.shadowbanned;
  }

  // ---- mute / report (§8) ----
  async skipAndMute(): Promise<string | null> {
    const target = this.queue.stopCurrent() ?? this.queue.lastFinished;
    if (!target || !this.tripId || target.tripId === this.tripId) return null;
    earcons.muted();
    if (target.kind === "system") return target.handle; // skipped; never muted
    this.mutedSet.add(target.tripId);
    await supabase.from("mute_events").insert({
      muter_trip_id: this.tripId,
      muted_trip_id: target.tripId,
    });
    this.publish();
    return target.handle;
  }

  async reportCurrentOrLast(reason: string | null = null): Promise<string | null> {
    const target = this.queue.nowPlaying ?? this.queue.lastFinished;
    if (!target || !this.tripId || target.tripId === this.tripId) return null;
    if (target.kind === "system") return null;
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
}
