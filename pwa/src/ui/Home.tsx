import { useState } from "react";
import { ROAD_GUIDE, roadGuideEnabled, setRoadGuideEnabled } from "../config";
import { generateHandle } from "../protocol/handles";
import { Ring } from "./Ring";

export function Home(props: {
  onDrive: (handle: string, convoyCode: string) => void;
  onSimulate: (handle: string, convoyCode: string) => void;
}) {
  const [handle] = useState(generateHandle);
  const [convoyCode, setConvoyCode] = useState("");
  const [roadGuide, setRoadGuide] = useState(roadGuideEnabled);
  return (
    <div className="home">
      <div className="home-inner">
        <div className="hero-dial">
          <Ring state="idle" peers={3} onTap={() => {}} decorative />
        </div>

        <div className="eyebrow rise r1">same-traffic voice</div>
        <h1 className="wordmark rise r2">
          CAR&nbsp;<span>RADIO</span>
        </h1>
        <p className="tagline rise r3">
          Talk to the cars around you. Eyes up, hands on the wheel — nothing to
          read, nothing to find.
        </p>

        <div className="cta-row rise r4">
          <button className="btn-primary" onClick={() => props.onDrive(handle, convoyCode)}>
            Start driving
          </button>
          <button className="btn-quiet" onClick={() => props.onSimulate(handle, convoyCode)}>
            Open simulator
          </button>
        </div>

        <div className="trip-plate rise r5">
          <span className="plate-label">this trip you are</span>
          <span className="plate-handle">{handle}</span>
        </div>

        <input
          className="convoy-input rise r5"
          type="text"
          placeholder="convoy code (optional — friends only)"
          value={convoyCode}
          onChange={(e) => setConvoyCode(e.target.value)}
          autoCapitalize="none"
          autoCorrect="off"
        />

        {ROAD_GUIDE && (
          <label className="guide-toggle rise r5">
            <input
              type="checkbox"
              checked={roadGuide}
              onChange={(e) => {
                setRoadGuide(e.target.checked);
                setRoadGuideEnabled(e.target.checked);
              }}
            />
            <span>
              <strong>Road Guide (AI)</strong>
              <span className="dim"> answers questions about the road, scenery &amp; places nearby — nothing else</span>
            </span>
          </label>
        )}

        <div className="get-apps rise r6">
          <span className="dim">native apps (dev builds): </span>
          <a
            href="https://github.com/otrajman/carradio/releases/download/latest-builds/carradio-debug.apk"
            target="_blank"
            rel="noreferrer"
          >
            Android APK
          </a>
          {" · "}
          <a
            href="https://github.com/otrajman/carradio/releases/tag/latest-builds"
            target="_blank"
            rel="noreferrer"
          >
            iOS (sideload)
          </a>
          {" · "}
          <a href="https://github.com/otrajman/carradio" target="_blank" rel="noreferrer">
            source
          </a>
        </div>
      </div>

      <div className="road-strip" aria-hidden="true">
        <div className="road-dashes" />
      </div>
    </div>
  );
}
