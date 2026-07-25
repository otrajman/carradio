// Drive Mode: the eyes-up screen. Tap ring = talk. Swipe down = skip + stealth-mute.
// Everything else is ambient status only (PRD "invisible interface" mandate).
import { useCallback, useEffect, useRef, useSyncExternalStore } from "react";
import { FEATURE_WAKEWORD } from "../config";
import type { RadioEngine } from "../services/engine";
import { earcons } from "../services/audio";
import type { BurstRecorder } from "../services/recorder";
import { registerMediaSession } from "../services/mediaSession";
import { WakeWordListener, wakeWordSupported } from "../services/wakeword";
import { Ring, type RingState } from "./Ring";

const MPH = 2.23694;

export function DriveMode(props: {
  engine: RadioEngine;
  recorder: BurstRecorder;
  onExit: () => void;
  simulated?: boolean;
  /** Rendered inside the simulator's phone frame: no exit button, no wake word. */
  embedded?: boolean;
}) {
  const { engine, recorder } = props;
  const snap = useSyncExternalStore(
    useCallback((fn) => engine.subscribe(fn), [engine]),
    () => engine.getSnapshot(),
  );

  const toggleTalk = useCallback(async () => {
    if (recorder.isRecording) {
      engine.setRecordingFlag(false);
      const blob = await recorder.stop();
      if (blob && blob.size > 2000) await engine.sendBurst(blob);
    } else {
      try {
        await recorder.start(() => void toggleTalk());
        earcons.micOpen();
        engine.setRecordingFlag(true);
      } catch {
        // mic denied — stay silent, ring stays idle
      }
    }
  }, [engine, recorder]);

  const skipMute = useCallback(() => {
    void engine.skipAndMute();
  }, [engine]);

  // Steering wheel / headset buttons via media session
  useEffect(
    () =>
      registerMediaSession({
        onTalkToggle: () => void toggleTalk(),
        onSkipMute: skipMute,
      }),
    [toggleTalk, skipMute],
  );

  // Wake word: "hey radio [mute|repeat]"
  useEffect(() => {
    if (!FEATURE_WAKEWORD || !wakeWordSupported() || props.simulated || props.embedded) return;
    const ww = new WakeWordListener((action) => {
      if (action === "talk") void toggleTalk();
      else if (action === "mute") skipMute();
      else if (action === "report") void engine.reportCurrentOrLast();
      else engine.queue.replayLast();
    });
    ww.start();
    return () => ww.stop();
  }, [engine, toggleTalk, skipMute, props.simulated]);

  // Swipe down anywhere = skip + stealth mute
  const touchStart = useRef<{ x: number; y: number } | null>(null);
  const onTouchStart = (e: React.TouchEvent) => {
    const t = e.touches[0];
    touchStart.current = { x: t.clientX, y: t.clientY };
  };
  const onTouchEnd = (e: React.TouchEvent) => {
    const s = touchStart.current;
    touchStart.current = null;
    if (!s) return;
    const t = e.changedTouches[0];
    if (t.clientY - s.y > 90 && Math.abs(t.clientX - s.x) < 80) skipMute();
  };

  const ringState: RingState = snap.recording
    ? "recording"
    : snap.nowPlayingHandle
      ? "receiving"
      : "idle";
  const mph = snap.fix ? Math.round(snap.fix.speed * MPH) : 0;
  const lastEvent = snap.events[snap.events.length - 1];

  return (
    <div
      className={`drive${props.embedded ? " embedded" : ""}`}
      onTouchStart={onTouchStart}
      onTouchEnd={onTouchEnd}
    >
      {!props.embedded && (
        <button className="exit-drive" onClick={props.onExit} aria-label="Exit">
          ✕
        </button>
      )}
      <div className="drive-top">
        <div className="eyebrow">on air{props.simulated ? " · sim" : ""}</div>
        <div className="handle">{snap.handle.toUpperCase()}</div>
        <div className="drive-hud">
          <span>
            <b>{mph}</b> mph
          </span>
          <span>
            <b>{snap.peers.length}</b> nearby
          </span>
          {snap.elastic && <span className="badge-elastic">wide net</span>}
        </div>
      </div>

      <Ring state={ringState} peers={snap.peers.length} onTap={() => void toggleTalk()} />

      <div className="drive-bottom">
        <div className="ring-status">
          {/* status text lives under the ring visually via flow, kept simple */}
        </div>
        {snap.nowPlayingHandle ? (
          <div className="drive-hint" style={{ color: "var(--green)" }}>
            {snap.nowPlayingHandle}
          </div>
        ) : snap.recording ? (
          <div className="drive-hint" style={{ color: "var(--red)" }}>
            broadcasting — tap to send
          </div>
        ) : (
          <div className="drive-hint">tap to talk · swipe down to skip</div>
        )}
        {lastEvent && (
          <div className="ticker">
            <span className={lastEvent.reason === "play" ? "play" : "drop"}>
              {lastEvent.handle}
              {" · "}
              {lastEvent.reason === "play" ? "played" : lastEvent.reason}
              {lastEvent.distanceM != null && ` · ${lastEvent.distanceM} m`}
            </span>
          </div>
        )}
        {(snap.nowPlayingHandle || engine.queue.lastFinished) && (
          <button
            className="report-chip"
            onClick={() => void engine.reportCurrentOrLast()}
          >
            report speaker
          </button>
        )}
      </div>
    </div>
  );
}
