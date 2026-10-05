# Car Radio

**Live demo: <https://car-radio.live/>** · [Android APK](https://github.com/otrajman/carradio/releases/download/latest-builds/carradio-debug.apk) · [all dev builds](https://github.com/otrajman/carradio/releases/tag/latest-builds)

Hyper-local, ephemeral voice chat for drivers in the same traffic. Spec: `prd.md`.
Wire protocol shared by all clients: `docs/PROTOCOL.md` (read this first — it also lists
every judgment call where v1 deviates from the PRD and why).

## What's here

| Path | What | Status |
|---|---|---|
| `supabase/` | Migrations + `synthetic-nodes` edge function (mirrors what is deployed) | **Live & tested** on project `trstelgemjdeqqdlasgw` |
| `pwa/` | Vite + React PWA: Drive Mode + desk-demo Simulator | **Built, unit-tested, E2E-tested** against the live backend |
| `android/` | Kotlin / Compose / Android Auto source project | Core logic **compiled + 31 JVM tests passing** here; Android-SDK layers unverified (no SDK on this machine) — see `android/HANDOFF.md` |
| `ios/` | Swift / SwiftUI / CarPlay source project (XcodeGen) | Core logic **compiled + 41 tests passing** on Linux Swift (H3 port verified vs 6,792 h3-js fixtures); app target needs Xcode — see `ios/HANDOFF.md` |

## PelotonCB — the same radio, for a group ride

A second app from this codebase: **hands-free group radio for cyclists**, phone only
(Android + iPhone, no car head units). Spec: `docs/PROTOCOL.md` §16.

- **Join a pack with a code** (`CLIMB-4821`; "New code" mints one and opens the share sheet)
  — everyone with the code hears each other at any distance. Or **ride open road**: any
  PelotonCB rider within 500 m heading your way.
- **Voice-activated**: once you join, the mic is open and every phrase you say goes out as
  a snippet (≤ 10 s) — no buttons — until you **pause** it (giant on-screen button or
  earbud tap). Half-duplex: your mic yields while the pack is talking.
- Daylight-first, glove-sized handlebar UI; the screen stays on during a ride.
- Same backend with no migration: packs ride in the convoy tag under a `pelotoncb:`
  namespace, so Car Radio never hears bikes and vice versa.
- **Riding alone? Enter `DEMO`**: a reserved pack the server keeps populated with three
  synthetic riders (Gemini-voiced ride chatter) — for trying the app, and for App Review.
  PROTOCOL §16.7, `supabase/functions/demo-pack/`.

| | Web (PWA) | Android | iOS |
|---|---|---|---|
| Try it | **<https://car-radio.live/peloton/>** — any phone browser, installable | flavor `peloton` (`gradle assemblePelotonDebug`, app id `com.pelotoncb.app`) | XcodeGen target/scheme `PelotonCB` (`com.pelotoncb.app`) |
| App code | `pwa/src/peloton/` + `pwa/src/services/{peloton,vox}.ts` | `android/app/src/peloton/` | `ios/PelotonCB/` |
| Shared with Car Radio | `pwa/src/protocol`, `services/{audio,engine,location,roadGuide}` | `src/main`: protocol core, audio, Supabase I/O, GPS, H3 | `CarRadio/Sources/Core` + `Services` |
| New shared core | `protocol/peloton.ts` (tags, reach, `VoxDetector`; `peloton.test.ts`) | `core/PelotonTag`, `PelotonGeo`, `VoxDetector` (+ `PelotonTest`) | `Core/PelotonTag`, `VoxDetector` (+ `PelotonTests`) |

The web app is a second Vite page in `pwa/` (`pwa/peloton/index.html`, own manifest + service
worker under `/peloton/`) deployed by the same Pages workflow. Browser caveats: the mic stops
when the phone locks or the tab goes to the background (a wake lock keeps the screen on during
a ride); pre-roll comes from a 0.6 s DelayNode in front of the MediaRecorder rather than PCM
splicing; snippets are Opus/WebM (Chrome, Firefox) or AAC/MP4 (Safari).

Car Radio's Android-only pieces now live in the `carradio` flavor (`android/app/src/carradio/`);
its APK/AAB paths moved to `apk/carradio/...` and `bundle/carradioRelease/...` (CI updated).

## AI voices (Gemini)

- **Synthetic nodes, voiced** — weather alerts + local trivia are rendered once by Gemini TTS
  and stored in the `synthetic_voice` bucket; every client (PWA, Android, iOS, both apps)
  plays the audio and falls back to on-device TTS of the same text. PROTOCOL §12.
- **Road Guide** — ask about the road, the scenery, or places nearby and a Gemini voice
  answers *you*. Everything else you say is ignored. A two-model verification gate with
  deterministic rules decides, and fails closed. PROTOCOL §17.
  `supabase/functions/road-guide/`, rules + tests in `supabase/functions/_shared/gate*.ts`.
- **Setup**: `supabase secrets set GEMINI_API_KEY=...` (no key → text-only alerts and a silent
  guide; nothing breaks). Optional: `ROAD_GUIDE_ENABLED=false` kill switch,
  `GEMINI_MODEL_{CLASSIFIER,VERIFIER,GUIDE,TTS,LIVE}`, `ROAD_GUIDE_ENGINE=live` / `GEMINI_VOICE_{TOWER,GUIDE}` overrides.
- **Gate eval with real models**: `GEMINI_API_KEY=... node supabase/functions/road-guide/eval/run-eval.ts`
  (fails on any false accept). Unit tests: `node --test supabase/functions/_shared/*.test.ts`.

## TestFlight

`.github/workflows/testflight.yml` archives and uploads both apps on every `ios/` push
(macos-26 runner: App Store Connect requires the iOS 26 SDK). Gated on the repository
variable `TESTFLIGHT_ENABLED=true` plus the secrets listed at the top of the workflow:
team id, Apple Distribution `.p12` + password, App Store Connect API key, and one
`IOS_APP_STORE` provisioning profile per app (created through the ASC API with
`.secrets/asc-profiles.mjs`, local only — Xcode's automatic signing needs a registered
device for the archive step and the team has none). Signing settings sit on the app targets
in `ios/project.yml`; command-line overrides would leak into Swift package targets.
Car Radio signs with the CarPlay entitlement only when its profile carries it; until the
App ID's CarPlay Communication capability is actually enabled it falls back to
`CarRadio-TestFlight.entitlements`. Build number = workflow run number.
App Store names: **CarRadio Live** ("Car Radio" was taken; the on-device name stays Car
Radio) and **PelotonCB**. iOS bundle ids: `live.car-radio.app` (Apple already had
`com.carradio.app` registered to someone else; Android keeps `com.carradio.app`) and
`com.pelotoncb.app`. Both plists set `ITSAppUsesNonExemptEncryption=false`.

## pelotoncb.com

`peloton-site/` is the PelotonCB landing page + privacy policy (static, `CNAME` included),
served by GitHub Pages from its own repo because a Pages site takes one custom domain.

## Demo in 60 seconds

Open **<https://car-radio.live/>** (Chrome recommended), or run locally:

```bash
cd pwa
npm install
npm run dev        # http://localhost:5173
```

1. Open the app → **Open simulator**. You start "driving" I-90 East near Boston.
2. Spawn **+ ahead** and **+ oncoming** bots, then hit **random bot talks**.
   - The same-direction bot plays (TTS voice, "pop" earcon first).
   - The oncoming bot is dropped — watch the **filter decisions** log show `heading`.
3. Click **● talk**, allow the mic, speak, click **■ send burst** — your real voice goes
   through Storage → Postgres breadcrumb → Realtime fan-out. Open a second tab, start the
   simulator there too, and the other tab hears you (same H3 rooms).
4. **3 bots mute me** → wait a few seconds → your next talk pretend-sends: you're
   shadowbanned for an hour (the `shadowbanned` badge appears; senders are never told —
   the badge exists only for demo visibility).
5. Real driving: **Start driving** on a phone uses live GPS; Drive Mode locks to the
   ring UI. Wake word ("hey radio", Chrome only), media-key push-to-talk, swipe-down
   skip+mute all active.

Unit tests (`npm test`): filter math, cone asymmetry, room sets — 10 passing.

## Backend (Supabase)

- Tables: `trips`, `messages` (PostGIS point + H3 res-9), `mute_events`, `shadowbans`.
- Shadowban trigger: 3 distinct muters in 5 min → 1 h ban, checked by senders via
  `is_shadowbanned()` RPC. Verified live.
- `get_breadcrumbs()` RPC: PostGIS `ST_DWithin` radius query for cold-start replay.
- Storage bucket `voice_bursts`: public read, insert-only for anon, 256 KB cap/burst.
- `pg_cron` hourly: hard-deletes messages/objects > 24 h (ephemerality guarantee).
- Realtime: public broadcast channels `room:<h3-res7>`; publish via the Realtime REST
  fan-out endpoint (no channel join needed to send). Verified live end to end.
- Edge function `synthetic-nodes`: NWS alerts + Wikipedia geosearch → templated scripts
  inserted as `kind=system` breadcrumbs; clients TTS them (triple-chime prefix).
  Free/keyless. Verified live (try Boston coords).

## Security posture (demo-grade, revisit before real launch)

- Anon key can insert trips/messages/mutes but cannot read tables directly (RPCs only),
  cannot update/delete anything, and cannot forge `kind=system` messages (RLS-verified).
- Insert policies enforce real invariants (advisor pass 2, migration 0005): handle must
  match the "Adjective Animal" shape, no self-mutes, no self-reports. `shadowbans` has an
  explicit deny-all client policy; `rls_auto_enable` is off the RPC surface.
- PostGIS lives in the `extensions` schema (migration 0008 relocated it out of `public`
  via drop/recreate, preserving `messages.location` as WKT across the move). This cleared
  the `spatial_ref_sys` RLS advisor ERROR and the postgis-in-public WARNs at the root;
  the interim trigger guard from migrations 0006–0007 is retired because anon holds no
  write grants on the relocated `spatial_ref_sys`.
- `get_breadcrumbs` / `is_shadowbanned` advisor WARNs are intentional: they are the
  anon read API (SECURITY DEFINER with capped inputs and pinned search_path).
- Known gaps, accepted for v1 demo: no rate limiting per client, sender-enforced
  shadowban (spoofable client), public bucket listing by guessable UUID paths only.
  Hardening path: Supabase anonymous auth + per-trip ownership claims + signed URLs.

## Free-tier fallbacks in place of paid services

| Paid (PRD) | v1 free fallback |
|---|---|
| Picovoice Porcupine | Web Speech API (PWA) / SpeechRecognizer (Android) / SFSpeechRecognizer (iOS) |
| Mapbox road snapping | Heading-cone + speed-based dynamic radius only (`FEATURE_ROAD_SNAP` reserved) |
| OpenAI LLM + ElevenLabs TTS | Template scripts voiced by **Gemini TTS** (on-device TTS fallback) |
| Mapbox incidents | NWS weather alerts + Wikipedia geosearch |

Drop keys into env config later to upgrade each seam — integration points are marked.
