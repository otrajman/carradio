// The instrument: one ring, whole-screen tap target. Amber idle, green receiving,
// red recording — the only colors that ever move on this screen (PRD §5C).
export type RingState = "idle" | "receiving" | "recording";

const COLORS: Record<RingState, string> = {
  idle: "var(--amber)",
  receiving: "var(--green)",
  recording: "var(--red)",
};

export function Ring(props: {
  state: RingState;
  peers: number;
  onTap: () => void;
}) {
  const color = COLORS[props.state];
  const pulses = Math.min(3, Math.max(props.state === "idle" ? 1 : 2, Math.ceil(props.peers / 2)));
  return (
    <div className="ring-wrap" onPointerDown={props.onTap} role="button" aria-label="Talk">
      <svg viewBox="0 0 200 200" width="100%" height="100%">
        {Array.from({ length: pulses }, (_, i) => (
          <circle
            key={i}
            cx="100"
            cy="100"
            r="78"
            fill="none"
            stroke={color}
            strokeWidth="1.5"
            opacity="0.5"
            style={{
              transformOrigin: "100px 100px",
              animation: `ringPulse 2.6s ${i * 0.85}s ease-out infinite`,
            }}
          />
        ))}
        <circle cx="100" cy="100" r="78" fill="none" stroke={color} strokeWidth="3" />
        <circle cx="100" cy="100" r="56" fill="none" stroke={color} strokeWidth="1" opacity="0.35" />
        <circle
          cx="100"
          cy="100"
          r="34"
          fill={props.state === "recording" ? color : "none"}
          fillOpacity={props.state === "recording" ? 0.22 : 0}
          stroke={color}
          strokeWidth="1"
          opacity="0.6"
        />
      </svg>
    </div>
  );
}
