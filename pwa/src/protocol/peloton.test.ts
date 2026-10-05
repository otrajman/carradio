// PROTOCOL §16 conformance — the same vectors and scenarios as PelotonTest.kt / PelotonTests.swift.
import { describe, expect, it } from "vitest";
import { convoyTagFromCode } from "./convoy";
import { evaluateBurst } from "./filter";
import { destination } from "./geo";
import {
  DEMO_TAG,
  isDemoCode,
  OPEN_ROAD_TAG,
  cleanRiderName,
  VoxDetector,
  type VoxEvent,
  generatePackCode,
  geoChannel,
  levelDbfs,
  openRoadTag,
  packChannel,
  packInRange,
  packTagFromCode,
  SILENCE_DB,
} from "./peloton";
import type { BurstPayload } from "./types";

describe("PelotonTag", () => {
  it("matches the cross-platform vectors", async () => {
    expect(await packTagFromCode("HILL-4821")).toBe("0675040865c1d052");
    expect(await openRoadTag()).toBe(OPEN_ROAD_TAG);
  });

  it("reserves the DEMO pack", async () => {
    expect(await packTagFromCode("DEMO")).toBe(DEMO_TAG);
    expect(isDemoCode(" demo ")).toBe(true);
    expect(isDemoCode("DEMO-1")).toBe(false);
  });

  it("normalizes case, spaces and punctuation", async () => {
    const tag = await packTagFromCode("hill4821");
    expect(await packTagFromCode("  Hill 4821 ")).toBe(tag);
    expect(await packTagFromCode("h.i.l.l-48_21")).toBe(tag);
    expect(await packTagFromCode("ab-1")).toBeNull();
    expect(await packTagFromCode("   ")).toBeNull();
    expect(await packTagFromCode(null)).toBeNull();
  });

  it("never collides with a car convoy tag", async () => {
    expect(await packTagFromCode("hill4821")).not.toBe(await convoyTagFromCode("hill4821"));
  });

  it("mints joinable codes", async () => {
    let seed = 7;
    const rand = () => ((seed = (seed * 1103515245 + 12345) % 2 ** 31) / 2 ** 31);
    for (let i = 0; i < 50; i++) {
      const code = generatePackCode(rand);
      expect(code).toMatch(/^[A-Z]+-\d{4}$/);
      expect(await packTagFromCode(code)).not.toBeNull();
    }
  });

  it("names channels", () => {
    expect(packChannel("0675040865c1d052")).toBe("peloton:pack:0675040865c1d052");
    expect(geoChannel("882a100d25fffff")).toBe("peloton:geo:882a100d25fffff");
  });

  it("is dropped by Car Radio's filter and played by a pack member", () => {
    const burst: BurstPayload = {
      v: 1,
      message_id: "m1",
      trip_id: "rider",
      handle: "Rider",
      kind: "voice",
      audio_path: "voice_bursts/rider/m1.m4a",
      text: null,
      lat: 37,
      lng: -122,
      heading: 0,
      speed: 0,
      h3_r9: "",
      created_at: new Date().toISOString(),
      convoy: OPEN_ROAD_TAG,
    };
    const deps = { isMuted: () => false, hasSeen: () => false };
    const car = { tripId: "me", lat: 37, lng: -122, heading: 0, speed: 0, elastic: false };
    expect(evaluateBurst(burst, { ...car, convoyTag: null }, deps)).toBe("convoy");
    expect(evaluateBurst(burst, { ...car, convoyTag: OPEN_ROAD_TAG }, deps)).toBe("play");
  });
});

describe("PelotonGeo", () => {
  const sender = { lat: 37, lng: -122, heading: 90, speed: 9 };
  const receiverAt = (bearing: number, meters: number, heading: number, speed: number) => ({
    ...destination(37, -122, bearing, meters),
    heading,
    speed,
  });

  it("is a symmetric radius", () => {
    expect(packInRange(sender, receiverAt(270, 450, 90, 9))).toBe(true); // behind counts
    expect(packInRange(sender, receiverAt(90, 450, 90, 9))).toBe(true);
    expect(packInRange(sender, receiverAt(90, 600, 90, 9))).toBe(false);
  });

  it("drops oncoming riders only when both are moving", () => {
    const oncoming = receiverAt(90, 100, 270, 9);
    expect(packInRange(sender, oncoming)).toBe(false);
    expect(packInRange({ ...sender, speed: 1 }, oncoming)).toBe(true); // regroup stop
    expect(packInRange(sender, receiverAt(90, 100, 140, 9))).toBe(true); // switchback ±60°
  });
});

describe("VoxDetector", () => {
  class Feed {
    vox = new VoxDetector();
    events: { t: number; ev: VoxEvent }[] = [];
    t = 0;
    run(levelDb: number, ms: number, frameMs = 20) {
      for (let left = ms; left > 0; left -= frameMs) {
        const ev = this.vox.onFrame(levelDb, frameMs);
        this.t += frameMs;
        if (ev !== "none") this.events.push({ t: this.t, ev });
      }
    }
    kinds() {
      return this.events.map((e) => e.ev);
    }
  }

  it("never transmits silence", () => {
    const f = new Feed();
    f.run(-65, 10_000);
    expect(f.events).toEqual([]);
  });

  it("sends one snippet for speech then a pause", () => {
    const f = new Feed();
    f.run(-62, 1_000);
    f.run(-24, 1_500);
    f.run(-62, 2_000);
    expect(f.kinds()).toEqual(["start", "stop-send"]);
    expect(f.events[0].t).toBe(1_120); // after the 120 ms attack
    expect(f.events[1].t).toBe(2_500 + 700); // after the 700 ms hangover
  });

  it("ignores speech during warmup", () => {
    const f = new Feed();
    f.run(-24, 400);
    f.run(-62, 3_000);
    expect(f.events).toEqual([]);
  });

  it("does not treat clicks and bumps as snippets", () => {
    const f = new Feed();
    f.run(-62, 1_000);
    f.run(-20, 60); // shorter than attack
    f.run(-62, 2_000);
    expect(f.events).toEqual([]);
    f.run(-20, 160); // passes attack, fails min speech
    f.run(-62, 2_000);
    expect(f.kinds()).toEqual(["start", "stop-discard"]);
  });

  it("raises the floor for steady wind instead of transmitting", () => {
    const f = new Feed();
    f.run(-30, 20_000);
    expect(f.events).toEqual([]);
    f.run(-12, 800);
    expect(f.kinds()[0]).toBe("start");
  });

  it("bounds a wind gust after quiet to at most one snippet", () => {
    const f = new Feed();
    f.run(-62, 1_000);
    f.run(-30, 30_000);
    const sends = f.kinds().filter((k) => k === "split" || k === "stop-send").length;
    expect(sends).toBeLessThanOrEqual(1);
    expect(f.vox.capturing).toBe(false);
  });

  it("streams a long monologue as 3 s chunks", () => {
    const f = new Feed();
    f.run(-62, 1_000);
    for (let i = 0; i < 23 * 5; i++) {
      f.run(-22, 160);
      f.run(-60, 40);
    }
    f.run(-62, 2_000);
    // 23 s of speech from t=1 s: a chunk boundary every 3 s (t=4 s … 22 s), then release.
    expect(f.kinds()).toEqual(["start", ...Array(7).fill("split"), "stop-send"]);
    expect(f.events[1].t).toBe(4_000);
  });

  it("reset abandons the snippet but keeps the floor", () => {
    const f = new Feed();
    f.run(-62, 1_000);
    f.run(-24, 300);
    expect(f.vox.capturing).toBe(true);
    f.vox.reset();
    expect(f.vox.capturing).toBe(false);
    expect(f.vox.noiseFloorDb).toBeCloseTo(-62, 3);
  });

  it("behaves the same with uneven 85 ms frames", () => {
    const f = new Feed();
    f.run(-62, 1_020, 85);
    f.run(-24, 1_530, 85);
    f.run(-62, 2_040, 85);
    expect(f.kinds()).toEqual(["start", "stop-send"]);
  });

  it("measures PCM levels", () => {
    expect(levelDbfs(new Float32Array(320))).toBe(SILENCE_DB);
    const full = Float32Array.from({ length: 320 }, (_, i) => (i % 2 === 0 ? 1 : -1));
    expect(levelDbfs(full)).toBeCloseTo(0, 2);
    const half = Float32Array.from({ length: 320 }, (_, i) => (i % 2 === 0 ? 0.5 : -0.5));
    expect(levelDbfs(half)).toBeCloseTo(-6.02, 2);
  });
});

describe("cleanRiderName", () => {
  it("keeps ordinary names and trims the rest", () => {
    expect(cleanRiderName("  Omer  ")).toBe("Omer");
    expect(cleanRiderName("Jean-Luc O'Neil Jr.")).toBe("Jean-Luc O'Neil Jr.");
    expect(cleanRiderName("Zoë 2")).toBe("Zoë 2");
  });
  it("strips markup, emoji and control characters", () => {
    expect(cleanRiderName("<b>Sam</b>\n\u0007")).toBe("b Sam b");
    expect(cleanRiderName("🚴 Kim")).toBe("Kim");
  });
  it("caps the length and returns null when nothing is left", () => {
    expect(cleanRiderName("A".repeat(50))).toBe("A".repeat(20));
    expect(cleanRiderName("   ")).toBeNull();
    expect(cleanRiderName("!!!")).toBeNull();
    expect(cleanRiderName(null)).toBeNull();
  });
});
