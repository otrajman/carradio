// Desk-demo simulator, v2: night-drive stage on the left (road, cars, live
// transmit indicators), the real phone UI on the right, and a settings overlay
// that covers the phone panel for bot/speed/moderation controls + filter log.
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { senderRadiusM, NEAR_BUBBLE_M } from "../protocol/constants";
import { toRad } from "../protocol/geo";
import type { RadioEngine } from "../services/engine";
import type { BurstRecorder } from "../services/recorder";
import type { SimController } from "../sim/controller";
import type { Bot } from "../sim/bots";
import type { Route } from "../sim/routes";
import { DriveMode } from "./DriveMode";

const MPH = 2.23694;

export function Simulator(props: {
  engine: RadioEngine;
  recorder: BurstRecorder;
  sim: SimController;
  route: Route;
  onExit: () => void;
}) {
  const { engine, sim, recorder } = props;
  const snap = useSyncExternalStore(
    useCallback((fn) => engine.subscribe(fn), [engine]),
    () => engine.getSnapshot(),
  );
  const [, force] = useState(0);
  useEffect(() => sim.onChange(() => force((n) => n + 1)), [sim]);
  const [showSettings, setShowSettings] = useState(false);

  // Continuous canvas render for smooth pulses/motion.
  const canvasRef = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    let raf = 0;
    const loop = () => {
      const cv = canvasRef.current;
      if (cv && sim.userFix) {
        const rect = cv.parentElement!.getBoundingClientRect();
        if (cv.width !== Math.floor(rect.width) || cv.height !== Math.floor(rect.height)) {
          cv.width = Math.floor(rect.width);
          cv.height = Math.floor(rect.height);
        }
        draw(cv, props.route, sim, engine);
      }
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, [sim, engine, props.route]);

  const [busy, setBusy] = useState(false);
  const guard = (fn: () => Promise<unknown>) => async () => {
    if (busy) return;
    setBusy(true);
    try {
      await fn();
    } catch (e) {
      console.error(e);
    } finally {
      setBusy(false);
    }
  };

  const mph = Math.round(sim.userSpeedMps * MPH);
  const speaking = sim.bots.find((b) => Date.now() < b.speakingUntil);

  return (
    <div className="sim2">
      <div className="sim2-stage">
        <canvas ref={canvasRef} />
        <div className="stage-title">
          <span className="eyebrow">simulator</span>
          <span className="stage-road">{props.route.name}</span>
        </div>
        <div className="stage-caption">
          {speaking ? (
            <span className="on-air-chip">● {speaking.handle} transmitting</span>
          ) : (
            <span className="dim">
              {sim.bots.length} bot driver{sim.bots.length === 1 ? "" : "s"} on the road
              {sim.chatter ? " · chatter on" : " · chatter off"}
            </span>
          )}
        </div>
        <button className="stage-exit" onClick={props.onExit}>
          ‹ exit simulator
        </button>
      </div>

      <div className="sim2-right">
        <div className="sim2-toolbar">
          <span className="dim">
            {mph} mph · {snap.peers.length} in presence
            {snap.shadowbanned && <span className="badge-banned"> · shadowbanned</span>}
            {engine.convoyTag && <span className="badge-elastic"> · convoy</span>}
          </span>
          <button className="chip" onClick={() => setShowSettings((v) => !v)}>
            {showSettings ? "close" : "settings"}
          </button>
        </div>

        <div className="phone-frame">
          <div className="phone-notch" />
          <DriveMode
            engine={engine}
            recorder={recorder}
            onExit={() => {}}
            simulated
            embedded
          />
        </div>

        {showSettings && (
          <div className="settings-overlay">
            <SettingsPanel
              engine={engine}
              sim={sim}
              snap={snap}
              guard={guard}
              onForce={() => force((n) => n + 1)}
            />
          </div>
        )}
      </div>
    </div>
  );
}

function SettingsPanel(props: {
  engine: RadioEngine;
  sim: SimController;
  snap: ReturnType<RadioEngine["getSnapshot"]>;
  guard: (fn: () => Promise<unknown>) => () => Promise<void>;
  onForce: () => void;
}) {
  const { engine, sim, snap, guard, onForce } = props;
  const mph = Math.round(sim.userSpeedMps * MPH);
  return (
    <>
      <h2>Simulation settings</h2>

      <div className="sim-section">
        <label>your speed — {mph} mph</label>
        <input
          type="range"
          min={2}
          max={38}
          step={1}
          value={sim.userSpeedMps}
          onChange={(e) => {
            sim.userSpeedMps = Number(e.target.value);
            onForce();
          }}
        />
        <div className="sim-row">
          <label className="check-row">
            <input
              type="checkbox"
              checked={sim.chatter}
              onChange={(e) => {
                sim.chatter = e.target.checked;
                onForce();
              }}
            />
            ambient CB chatter
          </label>
        </div>
      </div>

      <div className="sim-section">
        <label>bot drivers ({sim.bots.length})</label>
        <div className="sim-row">
          <button className="chip" onClick={guard(() => sim.spawnBot("ahead"))}>
            + ahead
          </button>
          <button className="chip" onClick={guard(() => sim.spawnBot("behind"))}>
            + behind
          </button>
          <button className="chip" onClick={guard(() => sim.spawnBot("oncoming"))}>
            + oncoming
          </button>
        </div>
        <div className="sim-row">
          <button className="chip go" onClick={guard(() => sim.botSpeak())}>
            random bot talks
          </button>
          <button
            className="chip warn"
            onClick={guard(async () => {
              if (snap.tripId) await sim.gangMute(snap.tripId);
            })}
          >
            3 bots mute me
          </button>
        </div>
        {sim.bots.map((b: Bot) => (
          <div className="bot-line" key={b.handle}>
            <span>
              {b.handle} <span className="role">{b.role}</span>
            </span>
            <span className="sim-row">
              <button className="chip" onClick={guard(() => sim.botSpeak(b))}>
                talk
              </button>
              <button className="chip" onClick={() => sim.removeBot(b)}>
                ×
              </button>
            </span>
          </div>
        ))}
        {sim.lastBotLine && (
          <div className="sim-status">
            <b>{sim.lastBotLine.handle}</b>: “{sim.lastBotLine.text}”
          </div>
        )}
      </div>

      <div className="sim-section">
        <label>moderation</label>
        <div className="sim-row">
          <button className="chip" onClick={() => void engine.skipAndMute()}>
            skip + mute
          </button>
          <button className="chip warn" onClick={() => void engine.reportCurrentOrLast()}>
            report speaker
          </button>
        </div>
      </div>

      <div className="sim-section" style={{ borderBottom: "none" }}>
        <label>filter decisions</label>
        <div className="sim-log">
          {[...snap.events].reverse().map((e, i) => (
            <div key={`${e.at}-${i}`}>
              <span className={e.reason === "play" ? "play" : "drop"}>
                {new Date(e.at).toLocaleTimeString()} {e.handle}
                {e.isBreadcrumb ? " (crumb)" : ""} —{" "}
                {e.reason === "play" ? "PLAY" : <span className="reason">{e.reason}</span>}
                {e.distanceM != null && ` @ ${e.distanceM}m`}
              </span>
            </div>
          ))}
          {snap.events.length === 0 && <div className="dim">no bursts received yet</div>}
        </div>
      </div>
    </>
  );
}

// ---- canvas rendering ----
function draw(
  cv: HTMLCanvasElement,
  route: Route,
  sim: SimController,
  engine: RadioEngine,
) {
  const ctx = cv.getContext("2d");
  if (!ctx || !sim.userFix) return;
  const { width: W, height: H } = cv;
  const now = Date.now();
  const snap = engine.getSnapshot();

  // night asphalt backdrop with a soft center glow + vignette
  ctx.clearRect(0, 0, W, H);
  const bgGrad = ctx.createRadialGradient(W / 2, H / 2, 60, W / 2, H / 2, Math.max(W, H) * 0.75);
  bgGrad.addColorStop(0, "#0d1218");
  bgGrad.addColorStop(1, "#05070a");
  ctx.fillStyle = bgGrad;
  ctx.fillRect(0, 0, W, H);

  // faint dot grid for depth
  ctx.fillStyle = "rgba(140,160,180,0.05)";
  for (let gx = (W / 2) % 48; gx < W; gx += 48) {
    for (let gy = (H / 2) % 48; gy < H; gy += 48) {
      ctx.fillRect(gx, gy, 1.5, 1.5);
    }
  }

  const u = sim.userFix;
  const mPerPx = 7000 / Math.min(W, 1400); // ~7 km across
  const cosLat = Math.cos(toRad(u.lat));
  const px = (lat: number, lng: number): [number, number] => [
    W / 2 + ((lng - u.lng) * 111320 * cosLat) / mPerPx,
    H / 2 - ((lat - u.lat) * 110574) / mPerPx,
  ];

  // road: shoulder glow, tarmac, edge lines, center dashes
  const roadPath = () => {
    ctx.beginPath();
    route.points.forEach(([la, ln], i) => {
      const [x, y] = px(la, ln);
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
  };
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  roadPath();
  ctx.strokeStyle = "rgba(120,150,190,0.08)";
  ctx.lineWidth = 58;
  ctx.stroke();
  roadPath();
  ctx.strokeStyle = "#161c24";
  ctx.lineWidth = 40;
  ctx.stroke();
  roadPath();
  ctx.strokeStyle = "rgba(220,230,240,0.16)";
  ctx.lineWidth = 42;
  ctx.setLineDash([1, 0]);
  ctx.stroke();
  roadPath();
  ctx.strokeStyle = "#151b23";
  ctx.lineWidth = 38;
  ctx.stroke();
  roadPath();
  ctx.strokeStyle = "rgba(245,165,36,0.5)";
  ctx.lineWidth = 2;
  ctx.setLineDash([16, 22]);
  ctx.lineDashOffset = -(now / 40) % 38; // traffic flows
  ctx.stroke();
  ctx.setLineDash([]);

  // user reach: near bubble + forward cone. The true reach can be kilometres —
  // drawn uncapped it becomes a screen-crossing band whose apex is unreadable
  // (it looks like it points backwards). Cap the drawn beam so it visibly
  // emanates from the car, brightest at the apex, and mark clipped reach with
  // a dashed arc at the cap.
  const [ux, uy] = px(u.lat, u.lng);
  const reachPx = senderRadiusM(u.speed) / mPerPx;
  const nearPx = NEAR_BUBBLE_M / mPerPx;
  const hd = toRad(u.heading) - Math.PI / 2;
  const drawnReach = Math.min(reachPx, Math.min(W, H) * 0.38);
  const cone = ctx.createRadialGradient(ux, uy, 0, ux, uy, drawnReach);
  cone.addColorStop(0, "rgba(245,165,36,0.26)");
  cone.addColorStop(0.35, "rgba(245,165,36,0.10)");
  cone.addColorStop(1, "rgba(245,165,36,0)");
  ctx.beginPath();
  ctx.moveTo(ux, uy);
  ctx.arc(ux, uy, drawnReach, hd - Math.PI / 3, hd + Math.PI / 3);
  ctx.closePath();
  ctx.fillStyle = cone;
  ctx.fill();
  if (reachPx > drawnReach) {
    // reach extends past the drawn beam
    ctx.beginPath();
    ctx.arc(ux, uy, drawnReach + 8, hd - Math.PI / 3.4, hd + Math.PI / 3.4);
    ctx.strokeStyle = "rgba(245,165,36,0.35)";
    ctx.lineWidth = 1.5;
    ctx.setLineDash([3, 7]);
    ctx.stroke();
    ctx.setLineDash([]);
  }
  // heading needle: short bright axis from the nose so direction is unmistakable
  ctx.beginPath();
  ctx.moveTo(ux + 14 * Math.cos(hd), uy + 14 * Math.sin(hd));
  ctx.lineTo(ux + 52 * Math.cos(hd), uy + 52 * Math.sin(hd));
  ctx.strokeStyle = "rgba(245,165,36,0.85)";
  ctx.lineWidth = 2;
  ctx.stroke();
  ctx.beginPath();
  ctx.arc(ux, uy, nearPx, 0, Math.PI * 2);
  ctx.strokeStyle = "rgba(245,165,36,0.18)";
  ctx.lineWidth = 1;
  ctx.stroke();

  // bots
  ctx.textAlign = "center";
  for (const b of sim.bots) {
    const [bx, by] = px(b.lat, b.lng);
    const talking = now < b.speakingUntil;
    if (talking) {
      // expanding transmit rings
      for (let i = 0; i < 3; i++) {
        const t = ((now / 900 + i / 3) % 1);
        ctx.beginPath();
        ctx.arc(bx, by, 14 + t * 46, 0, Math.PI * 2);
        ctx.strokeStyle = `rgba(74,222,128,${0.5 * (1 - t)})`;
        ctx.lineWidth = 1.5;
        ctx.stroke();
      }
    }
    drawCar(ctx, bx, by, b.heading, b.role === "oncoming" ? "#7c8794" : "#d7dee6", talking);
    ctx.font = "600 11px 'Chakra Petch', monospace";
    ctx.fillStyle = talking ? "#4ade80" : "rgba(160,172,184,0.85)";
    ctx.fillText(b.handle.toUpperCase(), bx, by + 30);
  }

  // user car with breathing amber glow (green when receiving, red when recording)
  const color = snap.recording ? "#f4655f" : snap.nowPlayingHandle ? "#4ade80" : "#f5a524";
  const breath = 10 + Math.sin(now / 600) * 3;
  const glow = ctx.createRadialGradient(ux, uy, 2, ux, uy, 26 + breath);
  glow.addColorStop(0, `${color}55`);
  glow.addColorStop(1, `${color}00`);
  ctx.beginPath();
  ctx.arc(ux, uy, 26 + breath, 0, Math.PI * 2);
  ctx.fillStyle = glow;
  ctx.fill();
  drawCar(ctx, ux, uy, u.heading, color, false, 1.5);
  ctx.font = "700 12px 'Chakra Petch', monospace";
  ctx.fillStyle = color;
  ctx.fillText("YOU", ux, uy - 26); // above the car; bot labels sit below theirs
}

function drawCar(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  headingDeg: number,
  color: string,
  talking: boolean,
  scale = 1.15,
) {
  ctx.save();
  ctx.translate(x, y);
  ctx.rotate(toRad(headingDeg));
  ctx.scale(scale, scale);
  // body
  ctx.beginPath();
  const r = 3;
  ctx.roundRect(-6, -11, 12, 22, r);
  ctx.fillStyle = color;
  ctx.fill();
  // windshield
  ctx.beginPath();
  ctx.roundRect(-4, -6, 8, 5, 1.5);
  ctx.fillStyle = "rgba(10,14,18,0.6)";
  ctx.fill();
  // headlights
  ctx.fillStyle = talking ? "#4ade80" : "rgba(255,244,214,0.9)";
  ctx.fillRect(-5, -11.5, 3, 2);
  ctx.fillRect(2, -11.5, 3, 2);
  ctx.restore();
}
