// Location abstraction: real GPS or the simulator injects fixes through one interface.
import type { GpsFix } from "../protocol/types";
import { bearingDeg, haversineM } from "../protocol/geo";

export interface LocationProvider {
  start(cb: (fix: GpsFix) => void): void;
  stop(): void;
}

/** Real geolocation. Derives heading/speed from deltas when the fix lacks them. */
export class GeoProvider implements LocationProvider {
  private watchId: number | null = null;
  private last: GpsFix | null = null;

  start(cb: (fix: GpsFix) => void) {
    this.watchId = navigator.geolocation.watchPosition(
      (pos) => {
        const { latitude, longitude, heading, speed } = pos.coords;
        let h = heading ?? NaN;
        let s = speed ?? NaN;
        if ((Number.isNaN(h) || h === null) && this.last) {
          const d = haversineM(this.last.lat, this.last.lng, latitude, longitude);
          if (d > 5) h = bearingDeg(this.last.lat, this.last.lng, latitude, longitude);
          else h = this.last.heading;
        }
        if (Number.isNaN(s) || s === null) {
          if (this.last) {
            const dt = (pos.timestamp - this.last.timestamp) / 1000;
            const d = haversineM(this.last.lat, this.last.lng, latitude, longitude);
            s = dt > 0 ? d / dt : 0;
          } else s = 0;
        }
        const fix: GpsFix = {
          lat: latitude,
          lng: longitude,
          heading: Number.isNaN(h) ? 0 : h,
          speed: Math.max(0, s),
          timestamp: pos.timestamp,
        };
        this.last = fix;
        cb(fix);
      },
      (err) => console.warn("geolocation error", err.message),
      { enableHighAccuracy: true, maximumAge: 1000, timeout: 15000 },
    );
  }

  stop() {
    if (this.watchId !== null) navigator.geolocation.clearWatch(this.watchId);
    this.watchId = null;
    this.last = null;
  }
}

/** Manual provider — the simulator pushes fixes into it. */
export class ManualProvider implements LocationProvider {
  private cb: ((fix: GpsFix) => void) | null = null;
  start(cb: (fix: GpsFix) => void) {
    this.cb = cb;
  }
  stop() {
    this.cb = null;
  }
  push(fix: GpsFix) {
    this.cb?.(fix);
  }
}
