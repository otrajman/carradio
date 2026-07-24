# Car Radio

**Live demo: <https://otrajman.github.io/carradio/>** · [Android APK](https://github.com/otrajman/carradio/releases/download/latest-builds/carradio-debug.apk) · [all dev builds](https://github.com/otrajman/carradio/releases/tag/latest-builds)

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

## Demo in 60 seconds

Open **<https://otrajman.github.io/carradio/>** (Chrome recommended), or run locally:

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
- Known gaps, accepted for v1 demo: no rate limiting per client, sender-enforced
  shadowban (spoofable client), public bucket listing by guessable UUID paths only.
  Hardening path: Supabase anonymous auth + per-trip ownership claims + signed URLs.

## Free-tier fallbacks in place of paid services

| Paid (PRD) | v1 free fallback |
|---|---|
| Picovoice Porcupine | Web Speech API (PWA) / SpeechRecognizer (Android) / SFSpeechRecognizer (iOS) |
| Mapbox road snapping | Heading-cone + speed-based dynamic radius only (`FEATURE_ROAD_SNAP` reserved) |
| OpenAI LLM + ElevenLabs TTS | Template scripts + on-device OS TTS |
| Mapbox incidents | NWS weather alerts + Wikipedia geosearch |

Drop keys into env config later to upgrade each seam — integration points are marked.
