# Car Radio — iOS + CarPlay client

Native SwiftUI client for the Car Radio spatial walkie-talkie. Implements
`docs/PROTOCOL.md` v1 exactly (rooms, filter math, earcons, breadcrumbs,
elastic mode) against the shared Supabase backend.

- Swift 5.9+, SwiftUI, iOS 16 minimum, bundle id `com.carradio.app`
- Supabase via [supabase-swift](https://github.com/supabase/supabase-swift) v2
  (PostgREST, Storage, Functions, Realtime V2 broadcast + presence)
- H3 spatial indexing via a **built-in pure-Swift port** (`H3Lite.swift`) — no
  third-party H3 dependency. See "H3Lite" below.

## Building

Requires a Mac with Xcode 15+ and [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```bash
brew install xcodegen
cd ios
xcodegen generate        # produces CarRadio.xcodeproj
open CarRadio.xcodeproj
```

Xcode resolves the `supabase-swift` SPM package on first open (network
required). Build and run the `CarRadio` scheme; run unit tests with ⌘U
(`CarRadioTests`).

There are no assets, storyboards, or codegen steps — all audio cues are
synthesized at runtime and the UI is pure SwiftUI.

### Signing & entitlements

`CarRadio/Resources/CarRadio.entitlements` declares
`com.apple.developer.carplay-communication`. **This entitlement requires
Apple's approval** (apply at <https://developer.apple.com/contact/carplay/>).
Until your team is granted it:

- Simulator builds work fine (including the CarPlay simulator:
  *I/O ▸ External Displays ▸ CarPlay* in the iOS Simulator).
- For device builds without the grant, temporarily remove the key from the
  entitlements file (the phone app runs fine without CarPlay).

Background modes `audio` and `location` are declared in Info.plist. Location
runs in "when in use" + background-updates mode; no Always prompt is required
for the core flow.

## What's implemented

| Area | Where |
|---|---|
| H3 rooms (res 7 + k-ring 1 subscribe, forward-ray publish ≤ 4) | `Core/RoomManager.swift` |
| Receive filter §5 (heading dot ≥ 0.85, 800 m bubble, ahead-cone ≥ 0.5, senderRadius) | `Core/BurstFilter.swift` |
| Elastic mode §7 (3 min silence → regional; exits on strict pass / ≥ 2 peers) | `Core/ElasticMode.swift` |
| Handles (verbatim `docs/handles.json` lists) | `Core/HandleGenerator.swift` |
| Send pipeline §4 (shadowban check first, upload, insert, fan-out broadcast) | `Services/SendPipeline.swift` |
| Breadcrumbs §6 (res-8 trigger, played-ids persisted, ≤ 1/45 s drip) | `Services/BreadcrumbService.swift` |
| Synthetic nodes §12 (once per res-7 cell/session, TTS + triple-chime) | `Services/BreadcrumbService.swift` |
| Earcons §9 (synthesized PCM, no assets) | `Services/EarconPlayer.swift` |
| Audio session §10 (duck others on play, playAndRecord on talk) | `Services/AudioSessionController.swift` |
| Media buttons (Play/Pause = PTT, Next = skip + stealth-mute) | `Services/NowPlayingService.swift` |
| Wake word "hey radio" (mute / repeat / talk; feature-flagged) | `Services/WakeWordService.swift` |
| Drive Mode UI §13 (dark, tap-to-talk, swipe-down mute, > 4.5 m/s lock) | `UI/DriveModeView.swift` |
| CarPlay radar dashboard (no maps; info template + Talk / Skip+Mute) | `CarPlay/CarPlaySceneDelegate.swift` |

Recording is AAC (`kAudioFormatMPEG4AAC`) mono 24 kbps `.m4a`, 10 s cap,
uploaded to `voice_bursts/<trip_id>/<message_id>.m4a`.

## H3Lite

`Core/H3Lite.swift` + `Core/H3LiteTables.swift` are a minimal pure-Swift port
of Uber H3 v4.1.0 covering exactly `latLngToCell` (res 0–15) and `gridDisk`
(pentagon-safe). The lookup tables were mechanically extracted from the H3 C
source; the algorithm was ported from `faceijk.c` / `coordijk.c` /
`h3Index.c` / `algos.c`.

During development the port was validated against **official h3-js v4 on
6,792 fixtures** (5,304 `latLngToCell` vectors across res 0–15 including
poles and pentagon regions, and 1,488 `gridDisk` sets including every res 7–9
pentagon and its neighbors) with **zero mismatches**, plus a JS twin validated
on 23,872 checks. `CarRadioTests/H3LiteTests.swift` pins a curated subset.

**If you ever touch `H3Lite.swift` or `H3LiteTables.swift`, re-verify against
h3-js before shipping** — room names must match the PWA/Android clients
byte-for-byte or cross-platform delivery breaks silently.

`gridDisk` ordering note: results are a deterministic BFS order (origin
first), not h3-js's spiral order. The protocol only uses the disk as a *set*
of rooms, so this does not affect interop.

## Known limitations

- **Wake word** uses `SFSpeechRecognizer` (no Picovoice key in v1, per
  PROTOCOL §0). It shares the mic with the burst recorder, so listening
  suspends during recording; recognition sessions restart every ~50 s.
  On-device recognition is requested when the device supports it. If speech
  permission is denied the feature silently disables.
- **Steering-wheel buttons** route to the app only while iOS considers it the
  now-playing app; another app actively playing audio can take that role back
  between bursts.
- **Road-graph snapping** is feature-flagged off (`FEATURE_ROAD_SNAP`), per
  PROTOCOL §0.
- Broadcasting to publish-set rooms outside the subscribe set opens a
  short-lived channel per send.
- The CarPlay template auto-starts a trip when the car connects.

## Layout

```
ios/
  project.yml                  # XcodeGen spec (app + unit tests + SPM)
  CarRadio/
    Sources/
      Core/                    # pure Swift, no UIKit — shared logic
      App/                     # entry point, Constants, AppModel
      Services/                # Supabase, realtime, audio, location, speech
      UI/                      # SwiftUI Drive Mode
      CarPlay/                 # CPTemplateApplicationScene delegate
    Resources/                 # Info.plist, entitlements
  CarRadioTests/               # XCTest (also run on Linux during development)
  HANDOFF.md                   # implementation notes / unverified items
```
