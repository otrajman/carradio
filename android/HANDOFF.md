# HANDOFF — Android client

**2026-07 update — native h3-java removed.** The `com.uber:h3` JNI dependency is gone: its
prebuilt `.so` files are 4KB-aligned and cannot load on 16KB page-size devices (e.g. Galaxy
Z Fold 7 / Android 15+ 16KB mode). H3 cell math is now a pure-Kotlin port —
`core/H3Lite.kt` + `core/H3LiteTables.kt`, mechanically ported from the verified Swift
implementation in `ios/CarRadio/Sources/Core/H3Lite(.Tables).swift` — exposing
`latLngToCell` (canonical lowercase hex, identical to h3-js) and pentagon-safe `gridDisk`.
`service/H3Provider` keeps its old public API (`isAvailable` is now always `true`;
`cellAddress`; `gridDisk`) but does no native loading; the `extractH3Natives` Gradle task,
h3 jniLibs sourceSet, h3 packaging excludes, and the h3 catalog entry were all removed.
Validated by `H3LiteTest` against `app/src/test/resources/h3_fixtures.json` — 2,720
latLngToCell cases (res 7-9 random global points plus pole/equator/antimeridian extremes at
res 0-15) and 1,008 gridDisk cases (k=1..3 at res 7-9, covering all 36 res-7/8/9 pentagons
and every pentagon neighbor), all generated from official h3-js v4 by
`android/tools/gen-h3-fixtures.mjs` (rerun it with node if H3Lite is ever edited; gridDisk
fixtures are compared as sorted sets since H3Lite's BFS order differs from h3-js spiral
order). The former "x86_64 emulators degrade to no-rooms mode" caveat no longer applies.

State: complete and carefully reviewed. **The full app was never compiled** (no Android SDK
on the authoring machine), but two meaningful verification passes were done:

1. The entire `core/` package **plus all four test classes compiles with kotlinc 2.0.21
   (-language-version 1.9) and all 31 JUnit tests pass** on a plain JVM.
2. Every supabase-kt call was **checked against the real 2.6.1 jars from Maven Central via
   javap** (signatures listed below), and the H3 4.1.1 jar's native layout and method names
   were verified the same way.

Expect any remaining "fix the red squiggles" work to be concentrated in the Android-SDK-facing
files (service/audio/ui/car), not in protocol logic.

## What is implemented (mapped to PROTOCOL.md)

| Protocol section | Where |
|---|---|
| §1 identity (trip per launch, 4 h reuse, local handles) | `service/RadioService.ensureTrip`, `core/HandleGenerator`, `data/SettingsStore` |
| §2 units | m/s + degrees end-to-end; mph conversion only in `GeoMath.senderRadiusMeters` |
| §3 rooms/presence/payload | `service/RoomManager`, `core/RoomMath`, `core/BurstPayload` (rooms `room:<res7>`, k-ring 1 subscribe, ≤4-cell forward publish, presence `{trip_id,handle,heading,speed,kind}` @10 s on own cell only) |
| §4 send pipeline | `RadioService.sendBurst`: shadowban RPC (60 s cache, pretend-send) → Storage upload `<trip>/<msg>.ogg` → `messages` insert (EWKT point) → fan-out broadcast |
| §5 receive filter | `core/BurstFilter` — pure Kotlin, ordered checks, LRU-200 dedupe, 800 m bubble, ±60° ahead cone, radius clamp [0.5, 3] mi; §5.6 age-drop lives in `audio/PlaybackQueue` |
| §6 breadcrumbs | `service/BreadcrumbManager`: res-8 cadence, RPC `get_breadcrumbs(p_trip_id,...)` (the deployed 6-arg signature, **not** the `p_heading` variant PROTOCOL mentions), persistent played-ids in DataStore (cap 2000), 1/45 s trickle, "Earlier here: " prefix |
| §7 elastic mode | `core/ElasticModeTracker` (pure, injectable clock); literal reading: heading skipped + radius ∞, ahead-cone kept |
| §8 mutes | swipe-down / Next-Track / "hey radio mute" → local mute set + `mute_events` insert; sender never notified |
| §9 earcons | `audio/Earcons` — synthesized PCM via AudioTrack, exact specs incl. triple-chime before every system burst |
| §10 audio session | `audio/AudioFocusHelper` (TRANSIENT_MAY_DUCK, re-entrant) + media3 `MediaSession` over a `ForwardingPlayer` (play/pause→PTT, next→skip+mute) |
| §11 flags | DataStore-backed: wakeword (default on), synthetic nodes (default on), fake-GPS demo |
| §12 synthetic nodes | edge-fn POST once per res-7 cell per session; scripts → triple-chime + TTS; defensive response parsing |
| §13 drive lock | >4.5 m/s for 5 s locks, <2 m/s for 30 s unlocks, 8 s passenger long-press |

Tests (pure JVM): `BurstFilterTest` (18 cases incl. protocol anchor values), `HandleGeneratorTest`
(pins word lists verbatim), `RoomMathTest`, `ElasticModeTrackerTest`.

## Key decisions

- **BurstFilter is Android-free** and takes injected geometry (`GeoMath`)/H3 (`RoomMath`
  lambda) so all protocol math runs in plain JVM tests.
- **One plain `Service`** (not `MediaSessionService`/`LifecycleService`) with FGS types
  `location|mediaPlayback` (+`microphone` on API 30+). MediaSession wraps the burst-playback
  ExoPlayer in a `ForwardingPlayer` whose `play()/pause()/seekToNext()` are hijacked for
  PTT/skip — the standard media3 interception pattern.
- **State sharing** via a `RadioState` singleton of `MutableStateFlow`s (service writes; the
  Compose UI and the Car App screen read). Single-process app; no binder plumbing.
- **Elastic §7 read literally**: the ahead-of-sender requirement is kept even in elastic mode
  (radius→∞, heading skipped). If the PWA read it differently, align `BurstFilter.geometryDecision`.
- `get_breadcrumbs` matches the **deployed migration signature** (p_trip_id, p_lat, p_lng,
  p_radius_m, p_since_hours, p_limit); PROTOCOL §6's `p_heading` param does not exist in the DB.
- Geometry insert uses an EWKT string (`SRID=4326;POINT(lng lat)`), which PostgREST casts via
  the geometry input function.
- Wake-word burst endpointing = MediaRecorder amplitude polling (speech then 1.5 s silence),
  since SpeechRecognizer can't hold the mic while MediaRecorder records.

## Verified against real artifacts (javap on Maven Central jars)

- **supabase-kt 2.6.1**:
  - `PostgrestQueryBuilder.insert(T, defaultToNull, builder)` + `PostgrestRequestBuilder.select()` ✓
  - top-level `rpc(Postgrest, String, T, ...)` (import `io.github.jan.supabase.postgrest.rpc`) ✓
  - `PostgrestResult.decodeAs/decodeSingle/decodeList` are members (no import) ✓
  - `Storage.from(String)` / `BucketApi.upload(String, ByteArray, Boolean)` ✓ (returns
    `FileUploadResponse`, which `Repository` ignores)
  - `channel(SupabaseClient, String, builder)` non-suspend; builder has `broadcast {}` with
    `receiveOwnBroadcasts`/`acknowledgeBroadcasts` ✓
  - `RealtimeChannel.subscribe()` (default param), `broadcast(String, JsonObject)`,
    `track(JsonObject)`, `untrack()` are interface members ✓; `broadcastFlow`/
    `presenceChangeFlow` are top-level in the same package ✓; `Realtime.removeChannel` ✓
  - `RealtimeChannelImpl.broadcast` contains an HTTP `broadcastUrl`/`BroadcastApiBody` path —
    the forward fan-out to never-joined channels (RoomManager) has a real fallback ✓
- **h3**: *(historical — h3-java has since been replaced by the pure-Kotlin `core/H3Lite`,
  see the 2026-07 note at the top; the fixture suite in `H3LiteTest` is the verification.)*
- **core/ + tests**: compile under kotlinc (1.9 language level) and 31/31 tests pass.

## Unverified — check these first if something misbehaves

1. **Whole-app compile**: the Android-SDK-facing layers (service, audio, ui, car, manifest,
   Gradle wiring) have never been through AGP. Compose/Gradle matrix (Kotlin 1.9.22 ↔
   compose compiler 1.5.8 ↔ BOM 2024.02.01 ↔ AGP 8.2.2 ↔ Gradle 8.6) follows the published
   compatibility tables but was not resolved on this machine.
2. **Synthetic-nodes response shape**: `Repository.parseSyntheticResponse` accepts a bare
   array, `{messages:[...]}`, or `{scripts:[...]}` with `text` fields — confirm against the
   deployed edge function and delete the dead branches.
3. **media3 ForwardingPlayer command advertising**: `getAvailableCommands()` is overridden to
   force PLAY_PAUSE/SEEK_TO_NEXT availability so hardware buttons dispatch while the player
   is idle. If buttons don't arrive, check `MediaSession` callback `onMediaButtonEvent`.
4. **Car app host behavior**: `PaneTemplate` with 3 rows + 2 actions is within template
   quotas; emoji in the density meter row may render poorly on some head units — fall back to
   plain text if so.
5. **Runtime behaviors only a device can prove**: OEM `SpeechRecognizer` loops, MediaRecorder
   OGG/OPUS on specific vendors, presence timing, and the realtime room throughput.

## Deliberate deviations / trade-offs

- Notification actions use a plain notification, not MediaStyle; the MediaSession still
  handles hardware keys.
- `RadioState` reset on service destroy also clears the Car screen (it shows the
  "start on your phone" message).
- Mute of a *system* sender inserts a `mute_events` row only if the synthetic trip exists in
  `trips`; local synthetic bursts use the fake trip id `"synthetic-node"`, and `muteTrip` on
  it will fail the FK insert silently (caught) — locally it still mutes.
- ~~On x86_64 emulators without H3: rooms/breadcrumbs off, mic/earcons/UI still demoable.~~
  (Obsolete: H3 is pure Kotlin now and works on every ABI.)
