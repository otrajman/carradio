# Car Radio — Android + Android Auto client

Native Kotlin client for Car Radio: a hyper-local, ephemeral voice walkie-talkie for drivers.
Implements the shared wire protocol in `../docs/PROTOCOL.md` (H3 res-7 realtime rooms, the §5
receive filter, breadcrumbs, elastic mode, stealth mutes, earcons) against the shared Supabase
backend.

## Requirements

- Android Studio Hedgehog/Iguana or newer (AGP 8.2.x, Java 17)
- Android SDK 34
- A device (or emulator) on **API 29+**. A real **arm64 device is strongly recommended** —
  the Uber H3 native library ships arm binaries; x86_64 emulators may run in a degraded
  "no rooms" mode (see Known limitations).

## Build

The Gradle **wrapper jar is intentionally not committed**. Either open the project in Android
Studio (it will use its bundled Gradle), or generate the wrapper once with a local Gradle
(8.5+) install:

```bash
cd android
gradle wrapper          # generates gradlew + wrapper jar per gradle/wrapper/gradle-wrapper.properties
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest    # pure-JVM protocol tests (BurstFilter, handles, fan-out, elastic mode)
```

Backend URL + anon key are baked into `app/src/main/java/com/carradio/app/Constants.kt`.

## Using it

1. Launch, grant location + microphone (+ notifications).
2. Tap **GO ON AIR**. A foreground service starts: GPS at 2 s cadence, H3 res-7 room
   subscriptions (k-ring 1), presence, breadcrumbs, synthetic nodes.
3. Tap the big button to talk (10 s max, Ogg/Opus ~24 kbps mono), tap again to send early.
4. Swipe down anywhere: skip the current burst and **stealth-mute** its sender.
5. Steering wheel / headset: **Play/Pause = push-to-talk toggle**, **Next = skip + mute**.
6. Say **"Hey Radio"** then speak to broadcast; "Hey Radio, mute" / "Hey Radio, repeat".
7. Above ~10 mph the phone UI locks into Drive Mode (dark, giant button only). Passengers can
   hold the lock banner for 8 s to unlock.

**Indoor demo**: Settings → "Fake GPS demo route" replays a bundled US-101 route at ~55 mph
(takes effect next time you go on air). Run the PWA in a browser with a nearby spoofed
location to hear cross-platform bursts.

**Android Auto**: the app declares a `COMMUNICATION` CarAppService ("radar dashboard": density
meter, handle, last speaker, Talk + Skip/Mute buttons). Test with the Desktop Head Unit
(`sdk/extras/google/auto/`) or an Auto-enabled head unit; enable "Unknown sources" in the
Android Auto developer settings because this build is not Play-signed.

## Project layout

```
app/src/main/java/com/carradio/app/
├── Constants.kt                 # backend config + protocol constants
├── CarRadioApp.kt, MainActivity.kt
├── core/                        # PURE Kotlin protocol logic (JVM unit-tested)
│   ├── BurstFilter.kt           #   §5 receive filter (no Android imports)
│   ├── BurstPayload.kt          #   §3 wire payload
│   ├── GeoMath.kt               #   haversine/bearing/senderRadius/destination point
│   ├── RoomMath.kt              #   §3 publish fan-out (H3 injected as lambda)
│   ├── ElasticModeTracker.kt    #   §7 density fallback
│   └── HandleGenerator.kt       #   §1 handles (word lists verbatim from docs/handles.json)
├── data/                        # Supabase I/O (PostgREST, Storage, RPCs, edge fn) + DataStore
├── audio/                       # Earcons (synthesized PCM), recorder, TTS, FIFO play queue
├── service/                     # Foreground service, rooms/realtime, GPS, breadcrumbs, wake word
├── car/                         # Android Auto CarAppService + radar screen
└── ui/                          # Compose Drive Mode + Settings (dark only)
```

## Known limitations

- **H3 natives on emulators**: `com.uber:h3` bundles JNI binaries; Android arm64/arm32 are
  covered, x86/x86_64 emulator images may fail to load. The app then disables rooms and
  shows "H3 unavailable" instead of crashing. Test on a physical device.
- **Forward fan-out to unsubscribed rooms** uses supabase-kt's HTTP broadcast fallback for
  channels that were never joined (verified present in the 2.6.1 artifact). Any per-cell
  failure is caught and logged; receivers still get most traffic via their k-ring 1
  subscribe set.
- **Wake word** uses the platform `SpeechRecognizer` in a restart loop, not a true keyword
  spotter: some OEM recognizers chime on every listen cycle, and recognition pauses during
  recording/playback (mic contention). Disable it in Settings if it misbehaves.
- **Media buttons** route to Car Radio only while its MediaSession is the most recent active
  one; if you switch to Spotify, the wheel buttons follow Spotify (OS behavior).
- **Road-graph snapping** (`FEATURE_ROAD_SNAP`) is not implemented, per PROTOCOL §0.
- Recording is capped at 10 s; the burst is Ogg/Opus and plays fine on the PWA. iOS `.m4a`
  bursts play back via ExoPlayer without special handling.
- The shadowban check is cached for 60 s and fails open when offline.
- No auth: the anon key + RLS demo posture from the repo migrations is assumed.
