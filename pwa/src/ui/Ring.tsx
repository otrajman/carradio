// The instrument: a tuner dial. Tick ring + state glow; a sweep and a live
// waveform while receiving; a pulsing core while transmitting. Amber idle,
// green receiving, red recording — the only colors that move (PRD §5C).
export type RingState = "idle" | "receiving" | "recording";

const COLORS: Record<RingState, string> = {
  idle: "var(--amber)",
  receiving: "var(--green)",
  recording: "var(--red)",
};

const TICKS = 72;

export function Ring(props: {
  state: RingState;
  peers: number;
  onTap: () => void;
  /** decorative mode: no button semantics, slower ambience (Home hero) */
  decorative?: boolean;
}) {
  const color = COLORS[props.state];
  // network density lights up a run of ticks
  const litTicks = Math.min(TICKS, Math.round((props.peers / 8) * TICKS));

  return (
    <div
      className={`ring-wrap ring-${props.state}${props.decorative ? " ring-deco" : ""}`}
      onPointerDown={props.decorative ? undefined : props.onTap}
      role={props.decorative ? undefined : "button"}
      aria-label={props.decorative ? undefined : "Talk"}
    >
      <svg viewBox="0 0 200 200" width="100%" height="100%">
        {/* tick ring */}
        <g>
          {Array.from({ length: TICKS }, (_, i) => {
            const a = (i / TICKS) * Math.PI * 2 - Math.PI / 2;
            const major = i % 6 === 0;
            const r0 = major ? 84 : 87;
            const r1 = 93;
            const lit = i < litTicks;
            return (
              <line
                key={i}
                x1={100 + r0 * Math.cos(a)}
                y1={100 + r0 * Math.sin(a)}
                x2={100 + r1 * Math.cos(a)}
                y2={100 + r1 * Math.sin(a)}
                stroke={lit ? color : "var(--edge2)"}
                strokeWidth={major ? 2 : 1}
                opacity={lit ? 0.95 : major ? 0.8 : 0.5}
              />
            );
          })}
        </g>

        {/* main dial ring with soft glow */}
        <circle className="dial-glow" cx="100" cy="100" r="74" fill="none" stroke={color} strokeWidth="2.5" />
        <circle cx="100" cy="100" r="66" fill="none" stroke={color} strokeWidth="0.75" opacity="0.28" />

        {/* rotating sweep: slow ambient scan when idle, fast when receiving */}
        <g className="dial-sweep" style={{ transformOrigin: "100px 100px" }}>
          <path
            d="M 100 100 L 100 26 A 74 74 0 0 1 138 35 Z"
            fill={color}
            opacity={props.state === "receiving" ? 0.14 : 0.05}
          />
          <line x1="100" y1="100" x2="100" y2="26" stroke={color} strokeWidth="1.2" opacity="0.5" />
        </g>

        {/* center: waveform while receiving, core while recording */}
        {props.state === "receiving" && (
          <g className="dial-wave">
            {[-2, -1, 0, 1, 2].map((i) => (
              <rect
                key={i}
                className={`wave-bar wb${Math.abs(i)}`}
                x={97 + i * 11}
                y={88}
                width="5"
                height="24"
                rx="2.5"
                fill={color}
              />
            ))}
          </g>
        )}
        {props.state === "recording" && (
          <circle className="dial-core" cx="100" cy="100" r="26" fill={color} fillOpacity="0.28" stroke={color} strokeWidth="1.5" />
        )}
        {props.state === "idle" && !props.decorative && (
          <circle cx="100" cy="100" r="4" fill={color} opacity="0.9" />
        )}
      </svg>
    </div>
  );
}
