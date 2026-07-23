// "Hey Radio" wake word via Web Speech API (Chrome only; Porcupine slot in v2).
// Commands per PROTOCOL: "hey radio mute", "hey radio repeat", bare "hey radio" = talk.
type WakeAction = "talk" | "mute" | "repeat";

const SR: typeof SpeechRecognition | undefined =
  (window as any).SpeechRecognition ?? (window as any).webkitSpeechRecognition;

export function wakeWordSupported(): boolean {
  return !!SR;
}

export class WakeWordListener {
  private rec: SpeechRecognition | null = null;
  private running = false;
  private onAction: (a: WakeAction) => void;

  constructor(onAction: (a: WakeAction) => void) {
    this.onAction = onAction;
  }

  start() {
    if (!SR || this.running) return;
    this.running = true;
    const rec = new SR();
    rec.continuous = true;
    rec.interimResults = false;
    rec.lang = "en-US";
    rec.onresult = (e: SpeechRecognitionEvent) => {
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const text = e.results[i][0].transcript.toLowerCase();
        if (!/\bhey,?\s+radio\b/.test(text)) continue;
        if (/\bmute\b/.test(text)) this.onAction("mute");
        else if (/\brepeat\b/.test(text)) this.onAction("repeat");
        else this.onAction("talk");
      }
    };
    rec.onend = () => {
      // Chrome stops recognition periodically; restart while enabled.
      if (this.running) {
        try {
          rec.start();
        } catch {
          /* already starting */
        }
      }
    };
    rec.onerror = () => {
      /* onend fires next and restarts */
    };
    this.rec = rec;
    try {
      rec.start();
    } catch {
      this.running = false;
    }
  }

  stop() {
    this.running = false;
    this.rec?.stop();
    this.rec = null;
  }
}
