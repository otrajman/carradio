# HANDOFF — Car Radio iOS client

Status: complete implementation of `docs/PROTOCOL.md` v1. Written and
partially verified on Linux (no Xcode available); items below are split into
**verified** and **needs a Mac to verify**.

## Verified on this machine (Linux, Swift 6.1.2 toolchain)

- **H3Lite is NOT a fallback — the full H3 port shipped.** `Core/H3Lite.swift`
  (algorithm) + `Core/H3LiteTables.swift` (tables mechanically extracted from
  Uber H3 v4.1.0 C source by a script — zero hand-transcription).
  Compiled and executed here against fixtures generated from **official
  h3-js v4**: 6,792/6,792 passed (5,304 latLngToCell over res 0–15 incl.
  poles/pentagons; 1,488 gridDisk sets incl. every res 7–9 pentagon and each
  of their neighbors, k = 1–3). A JavaScript twin of the same port passed
  23,872 checks. Canonical lowercase-hex output matches h3-js exactly.
- **All 41 unit tests in `CarRadioTests/` pass** (compiled and run here with
  swift-corelibs XCTest via a scratch SwiftPM package that symlinks
  `CarRadio/Sources/Core` — the same files the app target compiles).
- **Every Swift source parses cleanly** (`swiftc -parse` on all files).
- **The exact supabase-swift API surface used compiles against
  supabase-swift 2.53.0** (probe package built on Linux): `from().insert()
  .select().single().execute().value`, `rpc(_:params:)`, `storage.from()
  .upload(_:data:options:)`, `functions.invoke(_:options:)`,
  `channel(_:options:)`, `broadcastStream(event:)`, `presenceChange()`,
  `subscribe()`, `track(_ codable)`, `broadcast(event:message:)`, `untrack()`,
  `removeChannel(_)`, `JSONObject`/`AnyJSON`.
- **Backend ground truth checked against the live Supabase project**:
  - `get_breadcrumbs(p_trip_id uuid, p_lat, p_lng, p_radius_m=1600,
    p_since_hours=24, p_limit=10)` returning `(id, trip_id, handle, kind,
    audio_path, text, lat, lng, heading, speed, created_at)`.
    NOTE: PROTOCOL §6 mentions a `p_heading` parameter — **the deployed
    function does not have it**; it takes `p_trip_id` instead (and excludes
    the caller's trip server-side). This client matches the deployed function.
  - `is_shadowbanned(p_trip_id uuid) → boolean`.
  - `messages` columns incl. `h3_r9` (15-hex check constraint) and `location`
    geometry — EWKT string inserts (`SRID=4326;POINT(lng lat)`) are what the
    deployed edge function itself uses.
  - `synthetic-nodes` edge function response shape:
    `{ messages: [{id, trip_id, handle, kind, text, audio_path, lat, lng,
    heading, speed, created_at}], cached }` (verify_jwt on — the anon key
    suffices; supabase-swift sends it).
  - `mute_events` has an identity `id` PK (not a composite PK) — plain inserts
    are correct; a DB trigger applies the shadowban rule.

## Needs a Mac to verify (could not be compiled here)

1. **The iOS app target itself** (UIKit/SwiftUI/AVFoundation/CarPlay/Speech
   frameworks don't exist on Linux). Everything parses, and the pure-Swift
   core compiles+tests, but expect the usual first-build friction: run
   `xcodegen generate` and build. Most likely friction points, in order:
   - Swift 6 strict-concurrency diagnostics if the project is opened with the
     Swift 6 language mode (project.yml pins SWIFT_VERSION 5.9, so warnings
     only).
   - `CPInformationTemplate.items/actions` mutability (documented settable
     since iOS 14; if Xcode disagrees, rebuild the template and call
     `setRootTemplate` again).
   - `AVAudioSession` option combos on older devices — combos used are
     documented-valid (.playback + .duckOthers +
     .interruptSpokenAudioAndMixWithOthers; .playAndRecord + .duckOthers +
     .allowBluetooth + .defaultToSpeaker).
2. **SwiftUI-lifecycle + CarPlay scene coexistence**: Info.plist declares only
   the `CPTemplateApplicationSceneSessionRoleApplication` configuration and
   lets SwiftUI own the phone scene. This is the widely used pattern, but it
   is worth a smoke test in the CarPlay simulator; if the phone scene fails to
   appear, add an explicit `UIWindowSceneSessionRoleApplication` configuration
   with no delegate class.
3. **Realtime behavior end-to-end** (join/leave timing, presence payload
   decode across clients, broadcast to not-yet-joined channels). The code path
   for publishing to rooms outside the subscribe set subscribes an ephemeral
   channel first; supabase-swift also supports HTTP broadcast for unjoined
   channels — if send latency is an issue, switch to that.
4. **supabase-swift version drift**: pinned `from: 2.53.0`. The `.upload(_:
   data:options:)` and channel APIs changed names across early 2.x versions;
   don't pin lower than 2.20.

## Judgment calls / deviations (flagged, all documented in code too)

- **Elastic-mode reading of §7**: "skip the heading check and treat
  senderRadius as ∞ within subscribed rooms" — implemented as: in elastic
  mode, steps §5.4 *and* §5.5 pass entirely (no heading gate, no ahead-cone
  gate, no radius gate; room membership is the only spatial bound). A strictly
  literal reading could keep the ahead-cone gate even with an infinite radius;
  that would mute half the (empty) road while in a mode whose purpose is
  "hearing someone is better than silence", so the regional reading was
  chosen. **The PWA and Android clients must match this choice** — none
  existed at the time of writing (pwa/ and android/ are empty).
- **PROTOCOL §6 RPC signature vs deployed DB**: deployed wins (see above).
  `p_radius_m = max(1600, senderRadius(speed))` per §6 is implemented.
- **Trip identity**: a new trip per `startTrip()` (app launch / user action).
  The "4 h idle" re-key from §1 is implicit — the app doesn't persist trip ids
  at all, so any relaunch is a fresh identity.
- **Mute with nothing playing** falls back to the last *finished* speaker
  (steering-wheel "Next" between bursts). System senders are never muted.
- **Breadcrumb geometry retries**: a breadcrumb that fails the geometry check
  is dropped from the pending list (not marked played); it can resurface on a
  later res-8 fetch. The dedupe LRU (200 ids) may suppress a quick retry —
  accepted simplification.
- **`handle` in presence and payloads** is generated locally per §1; the
  `trips` row insert is still done first so `trip_id` FKs hold.
- **Earcon "spatialized/panned forward"** (§9 incoming pop): implemented
  center-panned mono (`AVAudioPlayerNode.pan = 0`); true spatialization was
  out of scope.
- **Location cadence** is a 2 s throttle of CLLocation callbacks (not a timer
  wake) — battery-friendlier and equivalent at driving speeds.

## Where things live

Core protocol logic (`CarRadio/Sources/Core/*`) is pure Foundation with no
UIKit imports and is exercised by `CarRadioTests`. The app layer
(`App/`, `Services/`, `UI/`, `CarPlay/`) is intentionally thin glue around it;
`AppModel.swift` is the single orchestrator and the only stateful singleton.

Dev-time verification artifacts (h3-js fixture generator, the C-source table
extractor, the Linux test package) lived in a scratchpad outside the repo; the
extractor script's provenance is noted in `H3LiteTables.swift`. If you need to
regenerate or re-verify: `npm i h3-js@4`, generate fixtures with random
lat/lng at res 7–9 + `getPentagons()`, and compare against
`H3Lite.latLngToCell` / `gridDisk` output.
