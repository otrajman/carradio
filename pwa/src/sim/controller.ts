// SimController: drives the user's ManualProvider along a route and manages bots.
import { haversineM as haversine } from "../protocol/geo";
import type { GpsFix } from "../protocol/types";
import type { ManualProvider } from "../services/location";
import { Bot, type BotRole } from "./bots";
import { RouteFollower } from "./follower";
import type { Route } from "./routes";

const TICK_MS = 1000;

export class SimController {
  private provider: ManualProvider;
  private follower: RouteFollower;
  private route: Route;
  private timer: ReturnType<typeof setInterval> | null = null;
  private listeners = new Set<() => void>();
  bots: Bot[] = [];
  userSpeedMps = 29; // ~65 mph
  running = false;
  lastBotLine: { handle: string; text: string } | null = null;
  userFix: GpsFix | null = null;

  constructor(provider: ManualProvider, route: Route) {
    this.provider = provider;
    this.route = route;
    this.follower = new RouteFollower(route, 0.3);
  }

  onChange(fn: () => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }
  private emit() {
    for (const fn of this.listeners) fn();
  }

  start() {
    if (this.running) return;
    this.running = true;
    this.tick(); // immediate first fix so the engine has state
    this.timer = setInterval(() => this.tick(), TICK_MS);
    this.emit();
  }

  stop() {
    this.running = false;
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    for (const b of this.bots) b.destroy();
    this.bots = [];
    this.emit();
  }

  private tick() {
    const dtS = TICK_MS / 1000;
    const p = this.follower.advance(this.userSpeedMps, dtS);
    this.userFix = {
      lat: p.lat,
      lng: p.lng,
      heading: p.heading,
      speed: this.userSpeedMps,
      timestamp: Date.now(),
    };
    this.provider.push(this.userFix);
    for (const b of this.bots) b.tick(dtS);
    this.emit();
  }

  /**
   * Spawn a bot relative to the user's route position.
   * ahead/behind share the user's direction; oncoming runs the reverse route.
   */
  async spawnBot(role: BotRole, offsetM = 900): Promise<Bot> {
    const routeLenM = this.routeLengthM();
    const userFrac = this.currentFraction();
    const df = offsetM / routeLenM;
    const frac =
      role === "ahead" ? Math.min(0.95, userFrac + df)
      : role === "behind" ? Math.max(0.02, userFrac - df)
      : Math.min(0.95, userFrac + df); // oncoming: placed ahead, driving toward user
    const speed = role === "oncoming" ? 27 : this.userSpeedMps * (role === "ahead" ? 0.85 : 1.1);
    const bot = new Bot(this.route, role, frac, speed);
    await bot.init();
    this.bots.push(bot);
    this.emit();
    return bot;
  }

  async botSpeak(bot?: Bot): Promise<void> {
    const b = bot ?? this.bots[Math.floor(Math.random() * this.bots.length)];
    if (!b) return;
    const text = await b.speak();
    this.lastBotLine = { handle: b.handle, text };
    this.emit();
  }

  /** Shadowban demo: three bots mute the user within seconds. */
  async gangMute(userTripId: string): Promise<void> {
    while (this.bots.length < 3) await this.spawnBot("behind", 400 + this.bots.length * 200);
    await Promise.all(this.bots.slice(0, 3).map((b) => b.mute(userTripId)));
    this.emit();
  }

  removeBot(bot: Bot) {
    bot.destroy();
    this.bots = this.bots.filter((b) => b !== bot);
    this.emit();
  }

  // route bookkeeping (approximate fraction from follower position)
  private routeLengthM(): number {
    let sum = 0;
    const pts = this.route.points;
    for (let i = 0; i + 1 < pts.length; i++) {
      sum += haversine(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]);
    }
    return sum;
  }

  /** Fraction along the route of the waypoint nearest the user. */
  private currentFraction(): number {
    if (!this.userFix) return 0.3;
    const pts = this.route.points;
    const total = this.routeLengthM();
    let cum = 0;
    let bestCum = 0;
    let bestDist = Infinity;
    for (let i = 0; i < pts.length; i++) {
      const d = haversine(this.userFix.lat, this.userFix.lng, pts[i][0], pts[i][1]);
      if (d < bestDist) {
        bestDist = d;
        bestCum = cum;
      }
      if (i + 1 < pts.length) {
        cum += haversine(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]);
      }
    }
    return Math.min(0.99, total > 0 ? bestCum / total : 0.3);
  }
}
