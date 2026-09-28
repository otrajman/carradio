// Browser VOX recorder (PROTOCOL §16.4). The mic stays open; the shared VoxDetector turns
// per-frame levels into snippet boundaries, and a MediaRecorder captures each snippet.
//
// Pre-roll without splicing containers: the recorder listens to the mic through a
// DelayNode, so when the detector fires "start" the recorder's first samples are already
// PRE_ROLL_S in the past — the first syllable (spoken during the attack window) survives.
// The same delay means a snippet stopped at release carries hangover − delay of trailing
// silence (~0.5 s) instead of the full 1.1 s.
//
// Half-duplex: `yieldTurn()` lets the current phrase finish (bounded), then holds the mic
// while the pack plays; `releaseTurn()` reopens it. Frames are not fed to the detector while
// held, so the speaker's output never pollutes the noise floor.
import { levelDbfs, VoxDetector, type VoxEvent } from "../protocol/peloton";

export type MicState = "off" | "listening" | "on-air" | "yielding" | "paused";

export interface VoxCallbacks {
  onSnippet: (blob: Blob) => void;
  onMic: (state: MicState) => void;
  /** 0..1 meter for the dial, ~10 Hz. */
  onLevel: (level: number) => void;
}

const PRE_ROLL_S = 0.6;
const MAX_YIELD_WAIT_MS = 12_000;
const LEVEL_POST_MS = 100;

export class VoxRecorder {
  private ctx: AudioContext | null = null;
  private stream: MediaStream | null = null;
  private delayed: MediaStream | null = null;
  private meter: ScriptProcessorNode | AudioWorkletNode | null = null;
  private source: MediaStreamAudioSourceNode | null = null;
  private rec: MediaRecorder | null = null;
  private chunksOf = new WeakMap<MediaRecorder, Blob[]>();
  private mime = "";
  private detector = new VoxDetector();
  private held = false;
  private turnWaiters: (() => void)[] = [];
  private lastLevelPost = 0;
  private running = false;
  private lastMic: MicState = "off";

  constructor(private cb: VoxCallbacks) {}

  get isRunning() {
    return this.running;
  }

  static supported(): boolean {
    return (
      typeof MediaRecorder !== "undefined" &&
      !!navigator.mediaDevices?.getUserMedia &&
      typeof AudioContext !== "undefined"
    );
  }

  async start(): Promise<void> {
    if (this.running) return;
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    const ctx = new AudioContext();
    if (ctx.state === "suspended") await ctx.resume();
    this.ctx = ctx;
    this.source = ctx.createMediaStreamSource(this.stream);

    const delay = ctx.createDelay(PRE_ROLL_S + 0.1);
    delay.delayTime.value = PRE_ROLL_S;
    const dest = ctx.createMediaStreamDestination();
    this.source.connect(delay).connect(dest);
    this.delayed = dest.stream;

    this.mime = pickMime();
    this.detector.resetAll();
    await this.attachMeter(ctx, this.source);
    this.running = true;
    this.held = false;
    this.setMic("listening");
  }

  /** Stop everything; any snippet in progress is dropped. */
  stop() {
    this.running = false;
    this.discardSnippet();
    this.turnWaiters.splice(0).forEach((w) => w());
    this.meter?.disconnect();
    this.source?.disconnect();
    this.meter = null;
    this.source = null;
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    this.delayed = null;
    void this.ctx?.close();
    this.ctx = null;
    this.setMic("off");
  }

  /** Half-duplex: resolve once the rider's current phrase is out of the way, mic held. */
  yieldTurn(): Promise<void> {
    if (!this.running || this.held) {
      this.held = true;
      return Promise.resolve();
    }
    if (!this.detector.capturing) {
      this.held = true;
      this.setMic("yielding");
      return Promise.resolve();
    }
    this.setMic("yielding");
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        // Phrase ran long: send what we have and take the turn.
        this.finishSnippet(true);
        done();
      }, MAX_YIELD_WAIT_MS);
      const done = () => {
        clearTimeout(timer);
        this.held = true;
        resolve();
      };
      this.turnWaiters.push(done);
    });
  }

  releaseTurn() {
    if (!this.running) return;
    this.held = false;
    this.detector.reset();
    this.setMic("listening");
  }

  private setMic(s: MicState) {
    if (s === this.lastMic) return;
    this.lastMic = s;
    this.cb.onMic(s);
  }

  // ---- frames -------------------------------------------------------------------------

  private onFrame(samples: Float32Array, sampleRate: number) {
    if (!this.running) return;
    const db = levelDbfs(samples);
    const now = performance.now();
    if (now - this.lastLevelPost >= LEVEL_POST_MS) {
      this.lastLevelPost = now;
      // -60 dBFS → 0, -10 dBFS → 1
      this.cb.onLevel(Math.min(1, Math.max(0, (db + 60) / 50)));
    }
    if (this.held) return;
    const ms = (samples.length / sampleRate) * 1000;
    this.handle(this.detector.onFrame(db, ms));
  }

  private handle(ev: VoxEvent) {
    switch (ev) {
      case "start":
        this.beginSnippet();
        break;
      case "stop-send":
        this.finishSnippet(true);
        this.wakeWaiters();
        break;
      case "stop-discard":
        this.finishSnippet(false);
        this.wakeWaiters();
        break;
      case "split":
        this.finishSnippet(true);
        this.beginSnippet();
        break;
      default:
        break;
    }
  }

  private wakeWaiters() {
    this.turnWaiters.splice(0).forEach((w) => w());
  }

  private beginSnippet() {
    if (!this.delayed || this.rec) return;
    const rec = new MediaRecorder(
      this.delayed,
      this.mime ? { mimeType: this.mime, audioBitsPerSecond: 24_000 } : { audioBitsPerSecond: 24_000 },
    );
    // Each recorder owns its chunks: data arrives asynchronously after stop(), possibly
    // after the next snippet has already begun.
    const chunks: Blob[] = [];
    rec.ondataavailable = (e) => {
      if (e.data.size > 0) chunks.push(e.data);
    };
    this.chunksOf.set(rec, chunks);
    this.rec = rec;
    rec.start();
    this.setMic("on-air");
  }

  private finishSnippet(send: boolean) {
    const rec = this.rec;
    this.rec = null;
    if (this.held) this.setMic("yielding");
    else if (this.running) this.setMic("listening");
    if (!rec) return;
    const chunks = this.chunksOf.get(rec) ?? [];
    this.chunksOf.delete(rec);
    const type = rec.mimeType || this.mime || "audio/webm";
    rec.onstop = () => {
      if (send && chunks.length) this.cb.onSnippet(new Blob(chunks, { type }));
    };
    if (rec.state !== "inactive") rec.stop();
  }

  private discardSnippet() {
    this.detector.reset();
    this.finishSnippet(false);
  }

  // ---- metering ---------------------------------------------------------------------

  private async attachMeter(ctx: AudioContext, src: AudioNode) {
    const sampleRate = ctx.sampleRate;
    if (ctx.audioWorklet) {
      try {
        const url = `${import.meta.env.BASE_URL}peloton/vox-meter.js`;
        await withTimeout(ctx.audioWorklet.addModule(url), WORKLET_LOAD_TIMEOUT_MS);
        const node = new AudioWorkletNode(ctx, "vox-meter");
        node.port.onmessage = (e) => this.onFrame(e.data as Float32Array, sampleRate);
        src.connect(node);
        this.meter = node;
        return;
      } catch {
        // fall through to ScriptProcessor
      }
    }
    const sp = ctx.createScriptProcessor(1024, 1, 1);
    sp.onaudioprocess = (e) => this.onFrame(e.inputBuffer.getChannelData(0), sampleRate);
    src.connect(sp);
    sp.connect(ctx.destination); // required for onaudioprocess to fire in some browsers
    this.meter = sp;
  }
}

const WORKLET_LOAD_TIMEOUT_MS = 2_500;

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error("timeout")), ms);
    p.then(
      (v) => {
        clearTimeout(t);
        resolve(v);
      },
      (e) => {
        clearTimeout(t);
        reject(e);
      },
    );
  });
}

function pickMime(): string {
  if (typeof MediaRecorder === "undefined") return "";
  for (const m of ["audio/webm;codecs=opus", "audio/webm", "audio/mp4"]) {
    if (MediaRecorder.isTypeSupported(m)) return m;
  }
  return "";
}

/** Storage extension + content type for a recorded snippet blob. */
export function snippetFormat(blob: Blob): { ext: string; contentType: string } {
  if (blob.type.startsWith("audio/mp4")) return { ext: "m4a", contentType: "audio/mp4" };
  return { ext: "webm", contentType: "audio/webm" };
}
