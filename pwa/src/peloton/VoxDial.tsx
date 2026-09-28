// 60 radial ticks, the same instrument as the native apps and pelotoncb.com. Lit ticks
// follow the live mic level (orange on air, ink listening); while the pack plays a green
// sweep circles instead; paused, the ring rests dim.
import { useEffect, useState } from "react";
import type { MicState } from "../services/vox";

const N = 60;

export function VoxDial(props: { mic: MicState; level: number; playing: boolean }) {
  const [sweep, setSweep] = useState(0);
  const sweeping = props.playing || props.mic === "yielding";
  useEffect(() => {
    if (!sweeping) return;
    let raf = 0;
    const t0 = performance.now();
    const tick = (t: number) => {
      setSweep(((t - t0) / 1600) % 1);
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [sweeping]);

  const lit = sweeping ? 0 : Math.round(props.level * N);
  const cls = sweeping
    ? "pack"
    : props.mic === "on-air"
      ? "signal"
      : props.mic === "listening"
        ? "ink"
        : "muted";
  const head = Math.floor(sweep * N);

  return (
    <svg className={`pl-dial ${cls}`} viewBox="-100 -100 200 200" aria-hidden="true">
      {Array.from({ length: N }, (_, i) => {
        const a = (i / N) * 2 * Math.PI - Math.PI / 2;
        const major = i % 5 === 0;
        const r0 = major ? 74 : 79;
        const r1 = 96;
        let on = i < lit;
        let opacity = 1;
        if (sweeping) {
          const d = (i - head + N) % N;
          on = d < 12;
          opacity = on ? 1 - d / 12 : 1;
        }
        return (
          <line
            key={i}
            className={on ? "on" : ""}
            style={sweeping && on ? { opacity } : undefined}
            x1={(Math.cos(a) * r0).toFixed(2)}
            y1={(Math.sin(a) * r0).toFixed(2)}
            x2={(Math.cos(a) * r1).toFixed(2)}
            y2={(Math.sin(a) * r1).toFixed(2)}
          />
        );
      })}
    </svg>
  );
}
