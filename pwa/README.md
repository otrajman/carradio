# Car Radio PWA

Vite + React + TypeScript. Works as the demo/testing client and the fallback mobile app
(Drive Mode). Implements `../docs/PROTOCOL.md` — the same filter math and wire format as
the native clients.

```bash
npm install
npm run dev      # dev server on :5173 (LAN-exposed for phone testing)
npm test         # vitest: protocol filter + room math
npm run build    # production bundle in dist/
```

Supabase URL/key are baked as demo defaults in `src/config.ts`; override with
`VITE_SUPABASE_URL` / `VITE_SUPABASE_ANON_KEY` (and `VITE_FEATURE_WAKEWORD`,
`VITE_SYNTHETIC_NODES`) in `.env.local`.

## Layout

- `src/protocol/` — pure logic mirrored on Android/iOS: constants, receive filter,
  geodesy, H3 room sets, handle generator. Unit-tested; no browser APIs.
- `src/services/` — engine (orchestrator), supabase client, audio (earcons/TTS/queue),
  recorder, location providers, wake word, media session.
- `src/sim/` — desk-demo simulator: route follower, bot drivers (full protocol
  participants with their own realtime connections), controller.
- `src/ui/` — Home, DriveMode (ring instrument), Simulator (stage + control rail).

## Hosting / CI

- **Public demo**: deployed to GitHub Pages by `.github/workflows/pwa.yml` on every push
  to `main` touching `pwa/` → <https://otrajman.github.io/carradio/>. The alias
  `https://trstelgemjdeqqdlasgw.supabase.co/functions/v1/app` 302-redirects there
  (the Supabase gateway rewrites HTML responses to text/plain, so direct edge hosting
  doesn't work — `vite.hosted.config.ts` + `supabase/functions/deploy-asset` remain
  as the abandoned-but-working-upload alternative).
- Native dev builds are linked from the Home screen (`latest-builds` GitHub release,
  updated by the Android/iOS workflows). Swap for store links at launch.

## Browser notes

- **Chrome/Edge** recommended: MediaRecorder opus, Web Speech wake word, TTS voices.
- Safari iOS: recording produces mp4/AAC (receivers don't care — they play by URL);
  no wake word; TTS works. Install to home screen for standalone mode.
- Browsers can't duck other apps' audio (native apps do this properly).
- Mic + geolocation need HTTPS (or localhost). For phone testing on LAN, tunnel or
  use `vite --host` + Chrome flag `unsafely-treat-insecure-origin-as-secure`.
