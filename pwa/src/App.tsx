import { useRef, useState } from "react";
import { convoyTagFromCode } from "./protocol/convoy";
import { unlockAudio } from "./services/audio";
import { RadioEngine } from "./services/engine";
import { GeoProvider, ManualProvider } from "./services/location";
import { BurstRecorder } from "./services/recorder";
import { SimController } from "./sim/controller";
import { MASS_PIKE_EAST } from "./sim/routes";
import { DriveMode } from "./ui/DriveMode";
import { Home } from "./ui/Home";
import { Simulator } from "./ui/Simulator";

type Mode = "home" | "drive" | "sim";

export default function App() {
  const [mode, setMode] = useState<Mode>("home");
  const engineRef = useRef<RadioEngine | null>(null);
  const simRef = useRef<SimController | null>(null);
  const recorderRef = useRef(new BurstRecorder());
  const [error, setError] = useState<string | null>(null);

  const startDrive = async (handle: string, convoyCode: string) => {
    unlockAudio(); // must happen inside the click gesture
    try {
      const engine = new RadioEngine(
        new GeoProvider(),
        handle,
        await convoyTagFromCode(convoyCode),
      );
      await engine.start();
      engineRef.current = engine;
      setMode("drive");
    } catch (e) {
      setError(String(e));
    }
  };

  const startSim = async (handle: string, convoyCode: string) => {
    unlockAudio();
    try {
      const provider = new ManualProvider();
      const engine = new RadioEngine(provider, handle, await convoyTagFromCode(convoyCode));
      await engine.start();
      const sim = new SimController(provider, MASS_PIKE_EAST);
      sim.start();
      engineRef.current = engine;
      simRef.current = sim;
      setMode("sim");
    } catch (e) {
      setError(String(e));
    }
  };

  const exit = () => {
    recorderRef.current.cancel();
    simRef.current?.stop();
    simRef.current = null;
    engineRef.current?.stop();
    engineRef.current = null;
    setMode("home");
  };

  if (mode === "drive" && engineRef.current) {
    return (
      <DriveMode engine={engineRef.current} recorder={recorderRef.current} onExit={exit} />
    );
  }
  if (mode === "sim" && engineRef.current && simRef.current) {
    return (
      <Simulator
        engine={engineRef.current}
        recorder={recorderRef.current}
        sim={simRef.current}
        route={MASS_PIKE_EAST}
        onExit={exit}
      />
    );
  }
  return (
    <>
      <Home
        onDrive={(h, c) => void startDrive(h, c)}
        onSimulate={(h, c) => void startSim(h, c)}
      />
      {error && (
        <div style={{ position: "fixed", bottom: 12, left: 12, right: 12, color: "var(--red)", fontSize: 13, textAlign: "center" }}>
          {error}
        </div>
      )}
    </>
  );
}
