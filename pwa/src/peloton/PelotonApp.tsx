// PelotonCB web app: join screen → ride screen. Mirrors the Android/iOS handlebar UI.
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { ROAD_GUIDE, roadGuideEnabled, setRoadGuideEnabled } from "../config";
import { generateHandle } from "../protocol/handles";
import { cleanRiderName, generatePackCode, packTagFromCode, RIDER_NAME_MAX } from "../protocol/peloton";
import { unlockAudio } from "../services/audio";
import { GeoProvider } from "../services/location";
import { registerMediaSession } from "../services/mediaSession";
import { PelotonEngine } from "../services/peloton";
import { VoxRecorder } from "../services/vox";
import { VoxDial } from "./VoxDial";

const APK_URL =
  "https://github.com/otrajman/carradio/releases/download/latest-builds/pelotoncb-debug.apk";

export default function PelotonApp() {
  const [engine, setEngine] = useState<PelotonEngine | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);

  const start = async (code: string | null, name: string | null) => {
    unlockAudio(); // inside the tap gesture
    setError(null);
    setStarting(true);
    try {
      const tag = code ? await packTagFromCode(code) : null;
      if (code && !tag) throw new Error("A pack code needs at least 4 letters or digits.");
      const e = new PelotonEngine(
        new GeoProvider(),
        generateHandle(),
        tag,
        tag ? code!.trim().toUpperCase() : null,
        name,
      );
      await e.start();
      setEngine(e);
    } catch (e) {
      setError(friendly(e));
    } finally {
      setStarting(false);
    }
  };

  const leave = () => {
    engine?.stop();
    setEngine(null);
  };

  if (engine) return <RideScreen engine={engine} onLeave={leave} />;
  return <JoinScreen onJoin={(c, n) => void start(c, n)} busy={starting} error={error} />;
}

function friendly(e: unknown): string {
  const msg = String((e as Error)?.message ?? e);
  if (/Permission|NotAllowed|denied/i.test(msg)) {
    return "PelotonCB needs the microphone and your location. Allow both and try again.";
  }
  return msg;
}

// ---- join --------------------------------------------------------------------------------

const NAME_KEY = "peloton_rider_name";

function JoinScreen(props: {
  onJoin: (code: string | null, name: string | null) => void;
  busy: boolean;
  error: string | null;
}) {
  const [code, setCode] = useState(() => {
    try {
      return new URLSearchParams(location.search).get("pack") ?? "";
    } catch {
      return "";
    }
  });
  const [name, setName] = useState(() => {
    try {
      return localStorage.getItem(NAME_KEY) ?? "";
    } catch {
      return "";
    }
  });
  const [roadGuide, setRoadGuide] = useState(roadGuideEnabled);
  const supported = VoxRecorder.supported();

  const join = (packCode: string | null) => {
    const clean = cleanRiderName(name);
    try {
      if (clean) localStorage.setItem(NAME_KEY, clean);
      else localStorage.removeItem(NAME_KEY);
    } catch {
      // private mode: the name just isn't remembered
    }
    props.onJoin(packCode, clean);
  };

  const mint = async () => {
    const c = generatePackCode();
    setCode(c);
    await shareCode(c);
  };

  return (
    <div className="pl-screen pl-join">
      <div className="pl-wordmark">
        <b>PELOTON</b>
        <i>CB</i>
      </div>
      <div className="pl-eyebrow">Group ride radio · web</div>
      <h1 className="pl-h1">
        Just talk.
        <br />
        The <em>pack</em> hears you.
      </h1>

      <label className="pl-eyebrow" htmlFor="rider-name">
        Your name · optional
      </label>
      <input
        id="rider-name"
        className="pl-name-input"
        type="text"
        autoComplete="nickname"
        autoCapitalize="words"
        autoCorrect="off"
        spellCheck={false}
        maxLength={RIDER_NAME_MAX}
        placeholder="Leave blank for a random one"
        value={name}
        onChange={(e) => setName(e.target.value)}
      />

      <label className="pl-eyebrow" htmlFor="pack-code">
        Pack code
      </label>
      <div className="pl-code-row">
        <input
          id="pack-code"
          className="pl-code-input"
          type="text"
          inputMode="text"
          autoCapitalize="characters"
          autoCorrect="off"
          spellCheck={false}
          placeholder="CLIMB-4821"
          value={code}
          onChange={(e) => setCode(e.target.value)}
        />
        <button className="pl-btn pl-btn-ghost" onClick={() => void mint()}>
          New code
        </button>
      </div>
      <button
        className="pl-btn pl-btn-primary pl-btn-big"
        disabled={props.busy || !supported}
        onClick={() => join(code)}
      >
        {props.busy ? "JOINING…" : "JOIN PACK"}
      </button>
      <div className="pl-or">OR</div>
      <button
        className="pl-btn pl-btn-ink pl-btn-big"
        disabled={props.busy || !supported}
        onClick={() => join(null)}
      >
        RIDE OPEN ROAD
      </button>
      <p className="pl-fine">
        Open road: any PelotonCB rider within 500 m heading your way. A pack hears each other
        at any distance. Riding alone? Enter the code <b>DEMO</b> to join a sample pack of
        three riders.
      </p>

      {ROAD_GUIDE && (
        <label className="pl-toggle">
          <input
            type="checkbox"
            checked={roadGuide}
            onChange={(e) => {
              setRoadGuide(e.target.checked);
              setRoadGuideEnabled(e.target.checked);
            }}
          />
          <span>
            <strong>ROAD GUIDE AI</strong>
            <span className="pl-muted">
              {" "}
              answers questions about the route, scenery and places nearby, just to you.
              Everything else it ignores.
            </span>
          </span>
        </label>
      )}

      {!supported && (
        <p className="pl-error">
          This browser can't record audio. Try Chrome, Safari 15+, or the Android app.
        </p>
      )}
      {props.error && <p className="pl-error">{props.error}</p>}

      <p className="pl-fine pl-bottom">
        Voice-activated: once you join, every phrase you say goes out as a short snippet until
        you pause the mic. Keep the screen on — a phone browser drops the mic when it locks.
        <br />
        <a href={APK_URL}>Android app</a> · <a href="https://pelotoncb.com/privacy.html">Privacy</a>
        {" · "}
        <a href="https://pelotoncb.com/">pelotoncb.com</a>
      </p>
    </div>
  );
}

async function shareCode(code: string) {
  const text = `Join my ride on PelotonCB — pack code ${code}. ${location.origin}${location.pathname}?pack=${encodeURIComponent(code)}`;
  try {
    if (navigator.share) {
      await navigator.share({ title: "PelotonCB", text });
      return;
    }
  } catch {
    // dismissed share sheet
  }
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    // clipboard blocked: the code is on screen anyway
  }
}

// ---- ride --------------------------------------------------------------------------------

function RideScreen(props: { engine: PelotonEngine; onLeave: () => void }) {
  const { engine } = props;
  const snap = useSyncExternalStore(
    useCallback((fn) => engine.subscribe(fn), [engine]),
    () => engine.getSnapshot(),
  );
  const [copied, setCopied] = useState(false);
  const wakeLock = useRef<WakeLockSentinel | null>(null);

  // Screen stays on during a ride (and the mic with it).
  useEffect(() => {
    const request = async () => {
      try {
        wakeLock.current = (await navigator.wakeLock?.request("screen")) ?? null;
      } catch {
        /* unsupported or denied */
      }
    };
    void request();
    const onVis = () => {
      if (document.visibilityState === "visible") void request();
    };
    document.addEventListener("visibilitychange", onVis);
    return () => {
      document.removeEventListener("visibilitychange", onVis);
      void wakeLock.current?.release();
    };
  }, []);

  useEffect(
    () =>
      registerMediaSession({
        onTalkToggle: () => engine.togglePause(),
        onSkipMute: () => void engine.skipAndMute(),
      }),
    [engine],
  );

  const share = async () => {
    if (!snap.packCode) return;
    await shareCode(snap.packCode);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  const label = snap.playing
    ? ["INCOMING", "pack"]
    : snap.mic === "on-air"
      ? ["ON AIR", "signal"]
      : snap.mic === "listening"
        ? ["LISTENING", "ink"]
        : snap.mic === "yielding"
          ? ["INCOMING", "pack"]
          : snap.mic === "paused"
            ? ["MIC PAUSED", "muted"]
            : ["CONNECTING", "muted"];
  const sub =
    snap.playing && snap.speakerHandle
      ? snap.speakerHandle
      : snap.mic === "on-air"
        ? "the pack hears you"
        : snap.mic === "listening"
          ? "just talk"
          : snap.paused
            ? "you still hear the pack"
            : " ";
  const canAct = snap.playing || snap.lastSpeakerHandle !== null;

  return (
    <div className="pl-screen pl-ride">
      <div className="pl-top">
        <button
          className="pl-where"
          onClick={() => void share()}
          disabled={!snap.packCode}
          aria-label={snap.packCode ? "Share pack code" : undefined}
        >
          <span className="pl-eyebrow">
            {snap.packCode
              ? engine.isDemo
                ? "Demo pack · sample riders"
                : copied
                  ? "Copied · share it"
                  : "Pack · tap to share"
              : "Open road · 500 m"}
          </span>
          <span className={`pl-where-main${snap.packCode ? " mono" : ""}`}>
            {snap.packCode ?? "ANY RIDER NEARBY"}
          </span>
        </button>
        <div className="pl-riders">
          <b className={snap.riders > 0 ? "pack" : "muted"}>{snap.riders}</b>
          <span className="pl-eyebrow">{snap.riders === 1 ? "rider" : "riders"}</span>
        </div>
      </div>

      <div className="pl-dial-wrap">
        <VoxDial mic={snap.mic} level={snap.micLevel} playing={snap.playing} />
        <div className="pl-dial-label">
          <b className={label[1]}>{label[0]}</b>
          <span>{sub}</span>
        </div>
      </div>

      {snap.status && <div className="pl-status">{snap.status}</div>}

      <button
        className={`pl-btn pl-btn-big pl-btn-huge ${snap.paused ? "pl-btn-primary" : "pl-btn-ink"}`}
        onClick={() => engine.togglePause()}
      >
        {snap.paused ? "RESUME MIC" : "PAUSE MIC"}
      </button>

      <div className="pl-row">
        <button className="pl-btn pl-btn-ghost" disabled={!canAct} onClick={() => void engine.skipAndMute()}>
          SKIP + MUTE
        </button>
        <button className="pl-btn pl-btn-ghost" disabled={!canAct} onClick={() => void engine.reportCurrentOrLast()}>
          REPORT
        </button>
        <button className="pl-btn pl-btn-ghost pl-muted" onClick={props.onLeave}>
          LEAVE
        </button>
      </div>

      <div className="pl-fine">
        You're {snap.handle} · {snap.snippetsSent} sent
      </div>
      <div className="pl-fine pl-fainter">Earbud tap: pause · next: skip + mute</div>
    </div>
  );
}
