// Desk-demo simulator: drive a fake route, spawn bot drivers, watch the filter
// decide, and hear the real pipeline end to end (storage, realtime, TTS).
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { senderRadiusM, NEAR_BUBBLE_M } from "../protocol/constants";
import { toRad } from "../protocol/geo";
import type { RadioEngine } from "../services/engine";
import { earcons } from "../services/audio";
import type { BurstRecorder } from "../services/recorder";
import type { SimController } from "../sim/controller";
import type { Route } from "../sim/routes";

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

  const canvasRef = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const cv = canvasRef.current;
    if (!cv || !sim.userFix) return;
    draw(cv, props.route, sim, snap.recording, snap.nowPlayingHandle != null);
  });

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

  const toggleTalk = guard(async () => {
    if (recorder.isRecording) {
      engine.setRecordingFlag(false);
      const blob = await recorder.stop();
      if (blob && blob.size > 2000) await engine.sendBurst(blob);
    } else {
      await recorder.start();
      earcons.micOpen();
      engine.setRecordingFlag(true);
    }
  });

  const mph = Math.round(sim.userSpeedMps * MPH);

  return (
    <div className="sim">
      <div className="sim-stage">
        <canvas ref={canvasRef} width={900} height={700} />
      </div>
      <div className="sim-rail">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <h2>Simulator</h2>
          <button className="chip" onClick={props.onExit}>
            exit
          </button>
        </div>

        <div className="sim-status">
          you are <b>{snap.handle}</b> on {props.route.name}
          {engine.convoyTag && (
            <>
              {" "}
              · <span className="badge-elastic">convoy</span>
            </>
          )}
          <br />
          {mph} mph · {snap.peers.length} in presence · rooms {snap.roomCells.length}
          {snap.elastic && (
            <>
              {" "}
              · <span className="badge-elastic">elastic</span>
            </>
          )}
          {snap.shadowbanned && (
            <>
              {" "}
              · <span className="badge-banned">shadowbanned</span>
            </>
          )}
        </div>

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
              force((n) => n + 1);
            }}
          />
          <div className="sim-row">
            <button className={`chip ${snap.recording ? "warn" : "go"}`} onClick={toggleTalk}>
              {snap.recording ? "■ send burst" : "● talk (mic)"}
            </button>
            <button className="chip" onClick={() => void engine.skipAndMute()}>
              skip + mute
            </button>
            <button className="chip warn" onClick={() => void engine.reportCurrentOrLast()}>
              report speaker
            </button>
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
          {sim.bots.map((b) => (
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
            {snap.events.length === 0 && <div>no bursts received yet</div>}
          </div>
        </div>
      </div>
    </div>
  );
}

// ---- canvas rendering ----
function draw(
  cv: HTMLCanvasElement,
  route: Route,
  sim: SimController,
  recording: boolean,
  receiving: boolean,
) {
  const ctx = cv.getContext("2d");
  if (!ctx || !sim.userFix) return;
  const { width: W, height: H } = cv;
  ctx.clearRect(0, 0, W, H);

  const u = sim.userFix;
  const mPerPx = 9000 / W; // ~9 km across
  const cosLat = Math.cos(toRad(u.lat));
  const px = (lat: number, lng: number): [number, number] => [
    W / 2 + ((lng - u.lng) * 111320 * cosLat) / mPerPx,
    H / 2 - ((lat - u.lat) * 110574) / mPerPx,
  ];

  // road
  ctx.beginPath();
  route.points.forEach(([la, ln], i) => {
    const [x, y] = px(la, ln);
    if (i === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  });
  ctx.strokeStyle = "#232c36";
  ctx.lineWidth = 26 / (mPerPx / 10);
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.stroke();
  ctx.strokeStyle = "#39434f";
  ctx.lineWidth = 2;
  ctx.setLineDash([14, 18]);
  ctx.stroke();
  ctx.setLineDash([]);

  // user reach: near bubble + forward cone arc
  const [ux, uy] = px(u.lat, u.lng);
  const reachPx = senderRadiusM(u.speed) / mPerPx;
  const nearPx = NEAR_BUBBLE_M / mPerPx;
  ctx.beginPath();
  ctx.arc(ux, uy, nearPx, 0, Math.PI * 2);
  ctx.strokeStyle = "rgba(245,165,36,0.25)";
  ctx.lineWidth = 1;
  ctx.stroke();
  const hd = toRad(u.heading) - Math.PI / 2; // canvas: 0 = east; heading 0 = north
  ctx.beginPath();
  ctx.moveTo(ux, uy);
  ctx.arc(ux, uy, reachPx, hd - Math.PI / 3, hd + Math.PI / 3);
  ctx.closePath();
  ctx.fillStyle = "rgba(245,165,36,0.07)";
  ctx.fill();
  ctx.strokeStyle = "rgba(245,165,36,0.3)";
  ctx.stroke();

  // bots
  for (const b of sim.bots) {
    const [bx, by] = px(b.lat, b.lng);
    drawCar(ctx, bx, by, b.heading, b.role === "oncoming" ? "#8a93a0" : "#cdd6de");
    ctx.fillStyle = "#66707a";
    ctx.font = "11px ui-monospace, monospace";
    ctx.textAlign = "center";
    ctx.fillText(b.handle, bx, by + 22);
  }

  // user car
  const color = recording ? "#f4655f" : receiving ? "#4ade80" : "#f5a524";
  drawCar(ctx, ux, uy, u.heading, color, 1.35);
}

function drawCar(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  headingDeg: number,
  color: string,
  scale = 1,
) {
  ctx.save();
  ctx.translate(x, y);
  ctx.rotate(toRad(headingDeg));
  ctx.scale(scale, scale);
  ctx.beginPath();
  ctx.moveTo(0, -9);
  ctx.lineTo(6, 7);
  ctx.lineTo(0, 4);
  ctx.lineTo(-6, 7);
  ctx.closePath();
  ctx.fillStyle = color;
  ctx.fill();
  ctx.restore();
}
