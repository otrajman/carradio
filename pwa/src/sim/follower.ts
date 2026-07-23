// Advances a position along a route polyline at a target speed. Pure math.
import { bearingDeg, destination, haversineM } from "../protocol/geo";
import type { Route } from "./routes";

export class RouteFollower {
  private route: Route;
  private seg = 0; // index into points; current segment is seg -> seg+1
  private lat: number;
  private lng: number;
  loop: boolean;

  constructor(route: Route, startFraction = 0, loop = true) {
    this.route = route;
    this.loop = loop;
    this.lat = route.points[0][0];
    this.lng = route.points[0][1];
    if (startFraction > 0) this.jumpToFraction(startFraction);
  }

  get position(): { lat: number; lng: number } {
    return { lat: this.lat, lng: this.lng };
  }

  get heading(): number {
    const [aLat, aLng] = this.route.points[this.seg];
    const next = this.route.points[Math.min(this.seg + 1, this.route.points.length - 1)];
    return bearingDeg(aLat, aLng, next[0], next[1]);
  }

  /** Move forward `speedMps * dtS` meters along the polyline. Returns new fix basis. */
  advance(speedMps: number, dtS: number): { lat: number; lng: number; heading: number } {
    let remaining = speedMps * dtS;
    while (remaining > 0) {
      const nextIdx = this.seg + 1;
      if (nextIdx >= this.route.points.length) {
        if (this.loop) {
          this.seg = 0;
          this.lat = this.route.points[0][0];
          this.lng = this.route.points[0][1];
          continue;
        }
        break;
      }
      const [nLat, nLng] = this.route.points[nextIdx];
      const dist = haversineM(this.lat, this.lng, nLat, nLng);
      if (dist <= remaining) {
        remaining -= dist;
        this.lat = nLat;
        this.lng = nLng;
        this.seg = nextIdx;
      } else {
        const brg = bearingDeg(this.lat, this.lng, nLat, nLng);
        const p = destination(this.lat, this.lng, brg, remaining);
        this.lat = p.lat;
        this.lng = p.lng;
        remaining = 0;
      }
    }
    return { lat: this.lat, lng: this.lng, heading: this.heading };
  }

  private totalLength(): number {
    let sum = 0;
    for (let i = 0; i + 1 < this.route.points.length; i++) {
      const [a, b] = [this.route.points[i], this.route.points[i + 1]];
      sum += haversineM(a[0], a[1], b[0], b[1]);
    }
    return sum;
  }

  jumpToFraction(f: number) {
    this.seg = 0;
    this.lat = this.route.points[0][0];
    this.lng = this.route.points[0][1];
    this.advance(this.totalLength() * Math.min(0.999, Math.max(0, f)), 1);
  }

  /** Reversed copy of the route (for oncoming traffic bots). */
  static reversed(route: Route): Route {
    return { name: `${route.name} (reverse)`, points: [...route.points].reverse() };
  }
}
