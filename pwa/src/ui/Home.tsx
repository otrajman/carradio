import { useState } from "react";
import { generateHandle } from "../protocol/handles";

export function Home(props: {
  onDrive: (handle: string) => void;
  onSimulate: (handle: string) => void;
}) {
  const [handle] = useState(generateHandle);
  return (
    <div className="home">
      <div className="eyebrow">same-traffic voice</div>
      <div className="wordmark">
        CAR<span>RADIO</span>
      </div>
      <p className="tagline">
        Talk to the cars around you. Eyes up, hands on the wheel — nothing to read,
        nothing to find.
      </p>
      <button className="btn-primary" onClick={() => props.onDrive(handle)}>
        Start driving
      </button>
      <button className="btn-quiet" onClick={() => props.onSimulate(handle)}>
        Open simulator
      </button>
      <div className="handle-preview">
        this trip you are <b>{handle}</b>
      </div>
    </div>
  );
}
