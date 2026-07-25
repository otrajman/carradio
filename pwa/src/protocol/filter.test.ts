import { describe, expect, it } from "vitest";
import { senderRadiusM } from "./constants";
import { evaluateBurst, type ReceiverState } from "./filter";
import { destination } from "./geo";
import { publishCells, subscribeCells } from "./rooms";
import type { BurstPayload } from "./types";

const BASE_LAT = 42.3512;
const BASE_LNG = -71.2454;

function payload(over: Partial<BurstPayload>): BurstPayload {
  return {
    v: 1,
    message_id: crypto.randomUUID(),
    trip_id: "sender-1",
    handle: "Neon Falcon",
    kind: "voice",
    audio_path: "voice_bursts/x/y.webm",
    text: null,
    lat: BASE_LAT,
    lng: BASE_LNG,
    heading: 90,
    speed: 30,
    h3_r9: "892a100acafffff",
    created_at: new Date().toISOString(),
    ...over,
  };
}

function receiver(over: Partial<ReceiverState>): ReceiverState {
  return {
    tripId: "receiver-1",
    lat: BASE_LAT,
    lng: BASE_LNG,
    heading: 90,
    speed: 30,
    elastic: false,
    ...over,
  };
}

const deps = { isMuted: () => false, hasSeen: () => false };

describe("senderRadiusM", () => {
  it("matches the PRD anchor points", () => {
    expect(senderRadiusM(6.7056)).toBeCloseTo(0.5 * 1609.34, 0); // 15 mph
    expect(senderRadiusM(33.528)).toBeCloseTo(3 * 1609.34, 0); // 75 mph
    expect(senderRadiusM(0)).toBeCloseTo(0.5 * 1609.34, 0); // clamped low
    expect(senderRadiusM(60)).toBeCloseTo(3 * 1609.34, 0); // clamped high
  });
});

describe("evaluateBurst", () => {
  it("drops own bursts", () => {
    expect(evaluateBurst(payload({ trip_id: "receiver-1" }), receiver({}), deps)).toBe("self");
  });

  it("drops duplicates and muted senders", () => {
    expect(
      evaluateBurst(payload({}), receiver({}), { ...deps, hasSeen: () => true }),
    ).toBe("duplicate");
    expect(
      evaluateBurst(payload({}), receiver({}), { ...deps, isMuted: () => true }),
    ).toBe("muted");
  });

  it("drops oncoming traffic (heading mismatch)", () => {
    expect(evaluateBurst(payload({ heading: 270 }), receiver({ heading: 90 }), deps)).toBe(
      "heading",
    );
  });

  it("plays same-direction traffic in the near bubble", () => {
    const near = destination(BASE_LAT, BASE_LNG, 90, 500);
    expect(
      evaluateBurst(payload({ lat: near.lat, lng: near.lng }), receiver({}), deps),
    ).toBe("play");
  });

  it("asymmetric cone: fast sender reaches far-ahead receiver, slow sender does not reach behind", () => {
    // receiver 4 km ahead (east) of sender; sender fast (33.5 m/s = 75 mph → 3 mi ≈ 4.8 km)
    const ahead = destination(BASE_LAT, BASE_LNG, 90, 4000);
    expect(
      evaluateBurst(
        payload({ speed: 33.5 }),
        receiver({ lat: ahead.lat, lng: ahead.lng }),
        deps,
      ),
    ).toBe("play");
    // slow sender (truck at 15 mph → 0.5 mi ≈ 800 m): same receiver is out of reach
    expect(
      evaluateBurst(
        payload({ speed: 6.7 }),
        receiver({ lat: ahead.lat, lng: ahead.lng }),
        deps,
      ),
    ).toBe("out-of-cone");
  });

  it("drops receivers behind the sender beyond the near bubble", () => {
    const behind = destination(BASE_LAT, BASE_LNG, 270, 2000); // 2 km west of sender
    expect(
      evaluateBurst(
        payload({ speed: 33.5 }),
        receiver({ lat: behind.lat, lng: behind.lng }),
        deps,
      ),
    ).toBe("out-of-cone");
  });

  it("skips heading check for system bursts, slow receivers, and elastic mode", () => {
    const p = payload({ heading: 270, kind: "system", text: "alert", audio_path: null });
    expect(evaluateBurst(p, receiver({ heading: 90 }), deps)).toBe("play");
    expect(
      evaluateBurst(payload({ heading: 270 }), receiver({ heading: 90, speed: 1 }), deps),
    ).toBe("play");
    const far = destination(BASE_LAT, BASE_LNG, 270, 6000);
    expect(
      evaluateBurst(
        payload({ heading: 270 }),
        receiver({ heading: 90, lat: far.lat, lng: far.lng, elastic: true }),
        deps,
      ),
    ).toBe("play");
  });
});

describe("convoy mode (§14)", () => {
  const TAG = "aabbccdd00112233";

  it("convoy members hear each other regardless of heading/distance", () => {
    const far = destination(BASE_LAT, BASE_LNG, 270, 6000);
    expect(
      evaluateBurst(
        payload({ convoy: TAG, heading: 270 }),
        receiver({ convoyTag: TAG, lat: far.lat, lng: far.lng }),
        deps,
      ),
    ).toBe("play");
  });

  it("convoy members do not hear public traffic or other convoys", () => {
    expect(
      evaluateBurst(payload({}), receiver({ convoyTag: TAG }), deps),
    ).toBe("convoy");
    expect(
      evaluateBurst(
        payload({ convoy: "ffff000011112222" }),
        receiver({ convoyTag: TAG }),
        deps,
      ),
    ).toBe("convoy");
  });

  it("public receivers never hear convoy traffic", () => {
    expect(evaluateBurst(payload({ convoy: TAG }), receiver({}), deps)).toBe("convoy");
  });

  it("system bursts reach convoy members", () => {
    expect(
      evaluateBurst(
        payload({ kind: "system", text: "alert", audio_path: null }),
        receiver({ convoyTag: TAG }),
        deps,
      ),
    ).toBe("play");
  });

  it("mute still applies inside a convoy", () => {
    expect(
      evaluateBurst(payload({ convoy: TAG }), receiver({ convoyTag: TAG }), {
        ...deps,
        isMuted: () => true,
      }),
    ).toBe("muted");
  });
});

describe("rooms", () => {
  it("subscribe set is 7 res-7 cells containing own cell", () => {
    const cells = subscribeCells(BASE_LAT, BASE_LNG);
    expect(cells).toHaveLength(7);
    expect(new Set(cells).size).toBe(7);
    for (const c of cells) expect(c).toMatch(/^87[0-9a-f]+$/);
  });

  it("publish set grows with speed and stays capped", () => {
    const slow = publishCells(BASE_LAT, BASE_LNG, 90, 5);
    const fast = publishCells(BASE_LAT, BASE_LNG, 90, 33.5);
    expect(slow.length).toBeGreaterThanOrEqual(1);
    expect(fast.length).toBeGreaterThan(slow.length);
    expect(fast.length).toBeLessThanOrEqual(4);
  });
});
