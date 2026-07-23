// Push-to-talk recording: opus-in-webm, mono, ~24 kbps, 10 s cap (PROTOCOL §4).
import { MAX_BURST_SECONDS } from "../protocol/constants";

export class BurstRecorder {
  private stream: MediaStream | null = null;
  private rec: MediaRecorder | null = null;
  private chunks: Blob[] = [];
  private stopTimer: ReturnType<typeof setTimeout> | null = null;
  private resolveStop: ((b: Blob | null) => void) | null = null;

  get isRecording(): boolean {
    return this.rec?.state === "recording";
  }

  async start(onAutoStop?: () => void): Promise<void> {
    if (this.isRecording) return;
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
    });
    const mime = MediaRecorder.isTypeSupported("audio/webm;codecs=opus")
      ? "audio/webm;codecs=opus"
      : "audio/webm";
    this.chunks = [];
    this.rec = new MediaRecorder(this.stream, {
      mimeType: mime,
      audioBitsPerSecond: 24_000,
    });
    this.rec.ondataavailable = (e) => {
      if (e.data.size > 0) this.chunks.push(e.data);
    };
    this.rec.onstop = () => {
      const blob = this.chunks.length ? new Blob(this.chunks, { type: mime }) : null;
      this.cleanup();
      this.resolveStop?.(blob);
      this.resolveStop = null;
    };
    this.rec.start();
    this.stopTimer = setTimeout(() => {
      if (this.isRecording) {
        onAutoStop?.();
      }
    }, MAX_BURST_SECONDS * 1000);
  }

  /** Stops and resolves with the recorded blob (null if empty/failed). */
  stop(): Promise<Blob | null> {
    if (!this.rec || this.rec.state !== "recording") {
      this.cleanup();
      return Promise.resolve(null);
    }
    return new Promise((resolve) => {
      this.resolveStop = resolve;
      this.rec!.stop();
    });
  }

  cancel() {
    if (this.rec && this.rec.state === "recording") {
      this.rec.ondataavailable = null;
      this.rec.onstop = () => this.cleanup();
      this.rec.stop();
    } else {
      this.cleanup();
    }
  }

  private cleanup() {
    if (this.stopTimer) clearTimeout(this.stopTimer);
    this.stopTimer = null;
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    this.rec = null;
  }
}
