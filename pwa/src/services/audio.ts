// Earcons (PROTOCOL §9, synthesized — no assets), serial play queue (§5.6), local TTS.
import { publicAudioUrl } from "../config";

let ctx: AudioContext | null = null;
function ac(): AudioContext {
  if (!ctx) ctx = new AudioContext();
  if (ctx.state === "suspended") void ctx.resume();
  return ctx;
}

function tone(freqStart: number, freqEnd: number, durMs: number, when = 0, gainDb = -12) {
  const c = ac();
  const t0 = c.currentTime + when / 1000;
  const osc = c.createOscillator();
  const gain = c.createGain();
  const g = Math.pow(10, gainDb / 20);
  osc.frequency.setValueAtTime(freqStart, t0);
  if (freqEnd !== freqStart) osc.frequency.exponentialRampToValueAtTime(freqEnd, t0 + durMs / 1000);
  gain.gain.setValueAtTime(0, t0);
  gain.gain.linearRampToValueAtTime(g, t0 + 0.008);
  gain.gain.linearRampToValueAtTime(0, t0 + durMs / 1000);
  osc.connect(gain).connect(c.destination);
  osc.start(t0);
  osc.stop(t0 + durMs / 1000 + 0.02);
}

function whoosh(durMs: number) {
  const c = ac();
  const t0 = c.currentTime;
  const len = Math.floor((durMs / 1000) * c.sampleRate);
  const buf = c.createBuffer(1, len, c.sampleRate);
  const data = buf.getChannelData(0);
  for (let i = 0; i < len; i++) data[i] = (Math.random() * 2 - 1) * (1 - i / len);
  const src = c.createBufferSource();
  src.buffer = buf;
  const filter = c.createBiquadFilter();
  filter.type = "bandpass";
  filter.Q.value = 1.2;
  filter.frequency.setValueAtTime(2000, t0);
  filter.frequency.exponentialRampToValueAtTime(300, t0 + durMs / 1000);
  const gain = c.createGain();
  gain.gain.value = 0.25;
  src.connect(filter).connect(gain).connect(c.destination);
  src.start(t0);
}

export const earcons = {
  incoming: () => tone(880, 440, 60),
  micOpen: () => {
    tone(520, 520, 80);
    tone(780, 780, 80, 110);
  },
  sent: () => whoosh(300),
  muted: () => tone(180, 180, 30, 0, -9),
  system: () => {
    tone(660, 660, 90);
    tone(830, 830, 90, 160);
    tone(990, 990, 90, 320);
  },
  breadcrumb: () => {
    tone(880, 440, 60);
    tone(880, 440, 60, 120);
  },
};

/** Unlock audio on first user gesture (browsers require it). */
export function unlockAudio() {
  ac();
  if ("speechSynthesis" in window) speechSynthesis.getVoices();
}

export interface QueueItem {
  messageId: string;
  tripId: string;
  handle: string;
  kind: "voice" | "system";
  audioPath: string | null;
  text: string | null;
  createdAt: string;
  isBreadcrumb: boolean;
  /** deterministic per-sender voice variation for TTS bursts */
  voiceSeed: number;
}

type QueueListener = (nowPlaying: QueueItem | null) => void;

/**
 * Serial playback: one burst at a time, live bursts dropped when stale (§5.6).
 * `stopCurrent()` supports skip/mute gestures.
 */
export class PlayQueue {
  private q: QueueItem[] = [];
  private playing: QueueItem | null = null;
  lastFinished: QueueItem | null = null;
  private currentAudio: HTMLAudioElement | null = null;
  private currentUtterance: SpeechSynthesisUtterance | null = null;
  private listeners = new Set<QueueListener>();
  maxAgeS = 60;

  onChange(fn: QueueListener): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }
  private emit() {
    for (const fn of this.listeners) fn(this.playing);
  }

  get nowPlaying(): QueueItem | null {
    return this.playing;
  }

  get isEmpty(): boolean {
    return this.playing === null && this.q.length === 0;
  }

  enqueue(item: QueueItem) {
    this.q.push(item);
    void this.pump();
  }

  stopCurrent(): QueueItem | null {
    const was = this.playing;
    if (this.currentAudio) {
      this.currentAudio.onended = null;
      this.currentAudio.pause();
      this.currentAudio = null;
    }
    if (this.currentUtterance) {
      this.currentUtterance.onend = null;
      speechSynthesis.cancel();
      this.currentUtterance = null;
    }
    this.playing = null;
    this.emit();
    void this.pump();
    return was;
  }

  private async pump() {
    if (this.playing) return;
    let item: QueueItem | undefined;
    while ((item = this.q.shift())) {
      const ageS = (Date.now() - Date.parse(item.createdAt)) / 1000;
      if (!item.isBreadcrumb && ageS > this.maxAgeS) continue; // stale live burst
      break;
    }
    if (!item) return;
    this.playing = item;
    this.emit();
    try {
      if (item.kind === "system") earcons.system();
      else earcons[item.isBreadcrumb ? "breadcrumb" : "incoming"]();
      await sleep(item.kind === "system" ? 550 : 180);
      if (this.playing !== item) return; // skipped during earcon
      if (item.audioPath) await this.playUrl(publicAudioUrl(item.audioPath));
      else if (item.text) await this.speak(item);
    } catch {
      // unplayable burst — move on
    }
    if (this.playing === item) {
      this.lastFinished = item;
      this.playing = null;
      this.emit();
    }
    void this.pump();
  }

  /** "Hey Radio, repeat" — replay the last finished burst (age check bypassed). */
  replayLast() {
    if (this.lastFinished) {
      this.enqueue({ ...this.lastFinished, isBreadcrumb: true });
    }
  }

  private playUrl(url: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const a = new Audio(url);
      this.currentAudio = a;
      a.onended = () => {
        this.currentAudio = null;
        resolve();
      };
      a.onerror = () => {
        this.currentAudio = null;
        reject(new Error("audio failed"));
      };
      void a.play().catch(reject);
    });
  }

  private speak(item: QueueItem): Promise<void> {
    return new Promise((resolve) => {
      if (!("speechSynthesis" in window)) return resolve();
      const u = new SpeechSynthesisUtterance(item.text ?? "");
      const ranked = rankedVoices();
      if (item.kind === "system") {
        u.rate = 1.02;
        u.pitch = 1.0;
        if (ranked.length) u.voice = ranked[0];
      } else {
        // Deterministic per-sender voice from the GOOD voices only, with mild
        // variation — extreme pitch shifts are what make TTS sound robotic.
        if (ranked.length) u.voice = ranked[item.voiceSeed % ranked.length];
        u.pitch = 0.94 + (item.voiceSeed % 4) * 0.045; // 0.94–1.08
        u.rate = 0.98 + (item.voiceSeed % 3) * 0.04; // 0.98–1.06
      }
      this.currentUtterance = u;
      u.onend = () => {
        this.currentUtterance = null;
        resolve();
      };
      u.onerror = () => {
        this.currentUtterance = null;
        resolve();
      };
      speechSynthesis.speak(u);
    });
  }
}

/**
 * Web Speech voices ranked by realism. Chrome's "Google …" network voices are
 * dramatically better than local espeak/flite; Edge's "… Natural" better still.
 */
let voiceCache: SpeechSynthesisVoice[] | null = null;
function rankedVoices(): SpeechSynthesisVoice[] {
  if (voiceCache && voiceCache.length) return voiceCache;
  const all = speechSynthesis.getVoices().filter((v) => v.lang.startsWith("en"));
  const score = (v: SpeechSynthesisVoice): number => {
    const n = v.name.toLowerCase();
    if (n.includes("natural") || n.includes("neural")) return 0;
    if (n.includes("google")) return 1;
    if (n.includes("samantha") || n.includes("daniel") || n.includes("karen")) return 2;
    if (n.includes("espeak") || n.includes("espeak-ng")) return 9;
    return 5;
  };
  voiceCache = [...all].sort((a, b) => score(a) - score(b));
  // drop the espeak tier entirely when anything better exists
  const good = voiceCache.filter((v) => score(v) < 9);
  if (good.length) voiceCache = good;
  return voiceCache;
}
if ("speechSynthesis" in window) {
  speechSynthesis.onvoiceschanged = () => {
    voiceCache = null;
  };
}

function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}

/** Stable small hash for per-sender TTS voice variation. */
export function voiceSeedOf(tripId: string): number {
  let h = 0;
  for (let i = 0; i < tripId.length; i++) h = (h * 31 + tripId.charCodeAt(i)) >>> 0;
  return h;
}
