# Car Radio — Shared Client Protocol (v1)

Every client (PWA, Android, iOS) MUST implement this protocol identically. This document is
the source of truth for wire formats and filter math. If a client deviates, cross-platform
delivery breaks silently.

## 0. Deviations from the PRD (judgment calls, v1 free tier)

| PRD says | v1 does | Why |
|---|---|---|
| H3 res 9/10 rooms, k-ring 1 | **Rooms at H3 res 7**; res 9 stored per-message for breadcrumbs | Res 9 + k-ring 1 gives ~400 m of reach, which contradicts the 3-mile forward cone. Res 7 cells (~2.4 km across) with k-ring 1 subscribe + forward fan-out publish cover the 3-mile cone with ≤7 subscriptions and ≤4 publishes. |
| Mapbox/Valhalla road snapping | Skipped (feature-flagged off) | No free tier without keys. Heading + dynamic radius filtering only. |
| Porcupine wake word | Web Speech API (PWA), SFSpeechRecognizer (iOS), SpeechRecognizer (Android) behind a `wakeword` feature flag | No Picovoice key. Same trigger phrase: "hey radio". |
| OpenAI LLM + ElevenLabs TTS for synthetic nodes | Template-based script formatting in an Edge Function + **client-side OS TTS** | Free. Synthetic messages carry `text`; clients synthesize locally. |
| Server drops packets for mutes | Client-side drop | Broadcasts are client-fired peer fan-out; there is no server hop to filter at. Mutes are stored in DB + cached locally; receiver drops on arrival. |

## 1. Identity

- On every app launch (or after 4 h idle) the client creates a new **trip**:
  `INSERT INTO trips (phonetic_handle) ... RETURNING id`.
- `phonetic_handle` is generated **locally**: `<Adjective> <Animal>` from the fixed word
  lists in `docs/handles.json` (identical across platforms). Example: "Neon Falcon".
- `trip_id` (UUID) is the only identity ever put on the wire. Never send device ids,
  user ids, or names.

## 2. Units & conventions

- **Speed: meters/second** everywhere on the wire and in the DB. Convert to mph only in UI.
- **Heading: degrees clockwise from true north, [0, 360).**
- Coordinates: WGS84 lat/lng, decimal degrees.
- Timestamps: ISO-8601 UTC strings.
- H3 indexes: lowercase hex strings (h3-js / h3 native canonical form).

## 3. Realtime rooms (Supabase Realtime Broadcast)

- Room name: `room:<h3_res7_index>` e.g. `room:872a1008bffffff`.
- Channels are **public broadcast** channels (`broadcast: { self: false, ack: false }`).
- **Subscribe set** (receiver): `gridDisk(myCell_res7, 1)` → 7 channels. Recompute on every
  res-7 cell change; unsubscribe channels that left the set.
- **Publish set** (sender): own res-7 cell + the res-7 cells intersecting the forward ray:
  take points at 1.2 km intervals along the sender's heading out to
  `senderRadius(speed)` (see §5), convert each to res-7, dedupe, cap at 4 cells.
- **Presence**: each client tracks presence on its *own* cell's channel only, with payload
  `{ trip_id, handle, heading, speed, kind }` throttled to one update / 10 s. Used for the
  density radar and elastic-radius fallback. Never write live location to Postgres.

### Broadcast event

Event name: `burst`. Payload (JSON):

```json
{
  "v": 1,
  "message_id": "uuid",
  "trip_id": "uuid",
  "handle": "Neon Falcon",
  "kind": "voice",              // "voice" | "system"
  "audio_path": "voice_bursts/<trip_id>/<message_id>.webm",  // null for system/text bursts
  "text": null,                  // non-null for system bursts (client TTS) or bot bursts
  "lat": 37.7749,
  "lng": -122.4194,
  "heading": 271.5,
  "speed": 29.1,                 // m/s
  "h3_r9": "892a100acafffff",
  "created_at": "2026-07-23T05:12:00.000Z"
}
```

`audio_path` is a path inside the public `voice_bursts` storage bucket; full URL =
`<SUPABASE_URL>/storage/v1/object/public/<audio_path>`.

Optional field `"convoy": "<tag>"` — present only in convoy mode (§14). Decoders MUST
ignore unknown payload fields (additive evolution).

## 4. Send pipeline (in order)

1. Check own shadowban: `SELECT is_shadowbanned(my_trip_id)`. If true, **pretend to send**
   (play the sent earcon, skip steps 2–4). Cache result for 60 s.
2. Upload audio to Storage: bucket `voice_bursts`, path `<trip_id>/<message_id>.webm`
   (PWA: `audio/webm;codecs=opus`; Android: `.ogg` opus; iOS: `.m4a` AAC — set the path
   extension to match, receivers play by URL and don't care).
3. `INSERT INTO messages (id, trip_id, kind, audio_path, text, h3_r9, location, heading, speed)`
   — this is the breadcrumb.
4. Broadcast the `burst` event to every room in the publish set (§3).

Target audio: ≤ 10 s per burst, mono, ~24 kbps opus (or AAC-HE on iOS).

## 5. Receive filter (run locally on every incoming burst, in order)

Let S = sender fields from payload, R = receiver's current GPS state.

1. **Self**: drop if `S.trip_id == R.trip_id`.
2. **Dedupe**: drop if `message_id` already seen (senders fan out to multiple rooms; keep an
   LRU of ~200 ids).
3. **Mute**: drop if `S.trip_id` is in the local mute set.
4. **Heading match**: `dot = sin(Sh)·sin(Rh) + cos(Sh)·cos(Rh)` (headings in radians).
   Drop if `dot < 0.85` (±31.8°). Skip this check when receiver speed < 3 m/s (parked/jam
   creep — heading is noise) or in elastic mode (§7). System bursts (`kind=system`) skip it too.
5. **Distance / forward cone** (asymmetric, sender-owned cone):
   - `d` = haversine(S, R) meters.
   - `senderRadius(v_mps)`: convert to mph; `miles = 0.5 + (mph − 15) × (2.5 / 60)`,
     clamped to [0.5, 3.0]; radius = miles × 1609.34. (15 mph → 0.5 mi, 75 mph → 3 mi.)
   - If `d ≤ 800` m: pass (omnidirectional near-bubble).
   - Else: receiver must be **ahead of the sender**: bearing β from S to R;
     `aheadDot = cos(radians(β − S.heading))`. Pass iff `aheadDot ≥ 0.5` (±60°) and
     `d ≤ senderRadius(S.speed)`. Otherwise drop.
6. **Play queue**: enqueue FIFO; never overlap two bursts; drop bursts older than 60 s at
   dequeue time (except breadcrumbs, §6).

## 6. Breadcrumbs (cold start)

- On every res-8 cell change (finer cadence than rooms), call RPC:
  `get_breadcrumbs(p_trip_id, p_lat, p_lng, p_radius_m, p_since_hours := 24, p_limit := 10)`
  with `p_radius_m = max(1600, senderRadius(R.speed))`. (Heading is not a parameter —
  directional filtering happens client-side in §5, same as live bursts.)
- The RPC returns recent messages ordered newest-first, already excluding the caller's trip.
- Client keeps a persistent played-ids set (survives app restart, capped at 2000); plays
  unheard breadcrumbs through the same filter as §5 **minus the age drop**, at most one
  breadcrumb per 45 s so live traffic wins.
- Announce with the breadcrumb earcon; prefix system TTS with "Earlier here: " when the
  breadcrumb is > 1 h old.

## 7. Elastic mode (density fallback)

- If, for **3 minutes**, presence across subscribed rooms shows 0 other non-system members
  AND no bursts passed the filter: enter elastic mode — skip §5.4 **and §5.5 entirely**
  (no heading, ahead-of-sender, or distance gating; self/dedupe/mute still apply). Reach is
  bounded only by the subscribed rooms (~2.4 km+; regional).
- Exit elastic mode immediately when any burst passes the strict filter or presence shows
  ≥ 2 peers.

## 8. Mute / block / report / moderation

- **Block (stealth mute)** — skip gesture (swipe down / Next-Track): stop current audio,
  add `S.trip_id` to the local mute set (persists for the receiver's trip), and
  `INSERT INTO mute_events (muter_trip_id, muted_trip_id)`.
- **Report** — explicit affordance (report button while/after a burst plays, or wake word
  "hey radio report"): reports the current-or-last played burst.
  `INSERT INTO reports (reporter_trip_id, reported_trip_id, message_id, reason)`.
  Reporting also mutes the reported trip locally (a reporter never hears them again).
- **Automated moderation** (DB triggers; clients never implement the rules, they only call
  `is_shadowbanned`):
  - 3 distinct muters / 5 min → shadowban 1 h.
  - 2 distinct reporters / 24 h → shadowban 24 h.
  - Reported messages and their audio are EXEMPT from the 24 h ephemerality cleanup and
    retained 30 days as moderation evidence.
- The sender is NEVER notified of mutes or reports. No UI ever reveals either.

## 9. Earcons

All clients implement the same five cues (synthesized locally, no assets required):

| Cue | Sound | Spec |
|---|---|---|
| incoming | soft "pop" | 60 ms sine burst 880→440 Hz, panned slightly forward/center |
| mic open | "bloop-bleep" | two 80 ms sines, 520 Hz then 780 Hz |
| sent | "whoosh" | 300 ms filtered noise sweep 2 kHz→300 Hz, fading |
| muted | low "click" | 30 ms sine at 180 Hz |
| system | triple-chime | three 90 ms sines 660/830/990 Hz, 70 ms gaps — always precedes `kind=system` playback |
| breadcrumb | double "pop" | incoming pop twice, 120 ms apart |

## 10. Audio session behavior

- Duck other audio (music/nav) to ~30 % while playing a burst or recording; restore after.
  - iOS: `AVAudioSession` category `.playback`, option `.duckOthers` (+ `.interruptSpokenAudioAndMixWithOthers`).
  - Android: `AudioFocusRequest` with `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`.
  - PWA: cannot duck other apps; just play (documented limitation).
- Register as a media session ("Car Radio — live") so steering wheel / headset buttons route in:
  - **Play/Pause** → push-to-talk toggle (start/stop recording).
  - **Next Track** → skip current burst + stealth-mute its sender.

## 11. Feature flags / env

Clients read a config (env vars for PWA, BuildConfig/xcconfig for native):

```
SUPABASE_URL, SUPABASE_ANON_KEY        # required
FEATURE_WAKEWORD=true|false            # default true where OS speech API exists
FEATURE_ROAD_SNAP=false                # reserved; no-op in v1
SYNTHETIC_NODES=true|false             # call synthetic-nodes edge function on cell change
```

## 12. Synthetic nodes

- Edge Function `synthetic-nodes` (POST `{ lat, lng }`) checks NWS alerts (api.weather.gov)
  and Wikipedia GeoSearch (both keyless), templates conversational scripts, inserts them as
  `kind=system` breadcrumb messages (deduped per cell per 6 h), and returns them.
- Clients with `SYNTHETIC_NODES=true` call it at most once per res-7 cell per session and
  play the returned scripts (triple-chime first). Everyone else picks them up via
  breadcrumbs. PelotonCB calls it too (played to the rider only, never to the pack).
- **Voiced by Gemini TTS** (2026-09): each new script is rendered once server-side
  (`GEMINI_MODEL_TTS`, "Radio Tower" voice) and stored as WAV in the `synthetic_voice`
  bucket; the row's `audio_path` is `synthetic_voice/nodes/<uuid>.wav`. Clients play a
  system burst's `audio_path` when present and **fall back to local TTS of `text`** when
  it's null or fails to play (no key configured, TTS error). `text` is always present.
- `publicAudioUrl` must accept both bucket prefixes (`voice_bursts/`, `synthetic_voice/`).

## 13. Drive Mode lock

When GPS speed > 4.5 m/s (~10 mph) for 5 s, the phone UI locks to Drive Mode: full-screen
tap-to-talk, swipe-down skip/mute, no lists, no keyboards, dark only. Unlocks after
speed < 2 m/s for 30 s or via a deliberate "I'm a passenger" long-press (8 s).

## 14. Convoy mode (friends-only interactions)

Opt-in private channel for a group that knows a shared invite code (road-trip convoy).

- UI: optional "convoy code" entered before the trip. Normalize: trim, lowercase,
  collapse spaces. `convoy_tag = first 16 hex chars of SHA-256(normalized code)` — the
  raw code never goes on the wire.
- Sending: payload carries `"convoy": <tag>`; the breadcrumb row sets `convoy_tag`.
- Receive filter, inserted after §5.3 (mute):
  - Receiver in convoy mode: play ONLY bursts with a matching tag (skip §5.4–§5.5 —
    convoy members hear each other regardless of heading within subscribed rooms);
    drop everything else, including all public traffic.
  - Receiver not in convoy mode: drop any burst carrying a convoy tag.
- Breadcrumbs: pass `p_convoy := <tag or null>` — the RPC returns only rows whose
  `convoy_tag` matches (null matches null).
- System bursts (`kind=system`) are never convoy-tagged and ARE played in convoy mode
  (safety alerts reach everyone).

## 15. Data retention (hard bounds, enforced by pg_cron hourly)

| Data | Retained |
|---|---|
| Voice/text messages + audio objects | 24 h |
| `synthetic_voice` objects (AI speech) | 24 h |
| `road_guide_events` (transcripts, answers, gate reasons) | 24 h |
| …unless referenced by a report | 30 days (moderation evidence) |
| mute_events | 24 h |
| reports | 30 days |
| shadowbans | until 24 h past expiry |
| trips | 48 h after last message is gone |

Everything is deleted automatically well inside 90 days. There are no user accounts;
trips are anonymous ephemeral identities.

## 16. PelotonCB (group ride radio for cyclists)

A second app built from the same codebase (Android flavor `peloton`, iOS target `PelotonCB`).
Same backend, same trips/handles (§1), send pipeline (§4), moderation (§8), earcons (§9) and
retention (§15). What differs is **who hears you** and **how you transmit**. No schema
change: peloton traffic rides in the §14 `convoy` field / `messages.convoy_tag` column.

### 16.1 Pack identity

- **Pack (join by code)**: normalize the code to ASCII `[a-z0-9]` only (lowercase, drop
  everything else — "Hill-4821" == "hill 4821"); must be ≥ 4 chars.
  `pack_tag = first 16 hex of SHA-256("pelotoncb:code:" + normalized)`.
  Vector: `HILL-4821` → `0675040865c1d052`.
- **Open road (geo)**: fixed `pack_tag = first 16 hex of SHA-256("pelotoncb:open")` =
  `edf37905db8bba1b`.
- The `pelotoncb:` namespace guarantees a bike code never equals a car convoy tag, so Car
  Radio clients drop all peloton bursts by the existing §14 rules, and car breadcrumbs
  (`p_convoy` null) never return peloton rows.
- Share codes are minted as `<WORD>-<4 digits>` (e.g. `CLIMB-4821`).

### 16.2 Channels

| Mode | Subscribe | Publish | Presence |
|---|---|---|---|
| Pack | `peloton:pack:<pack_tag>` only — no geography, a dropped rider 5 km back still hears the pack | same channel | same channel (= roster) |
| Open road | `peloton:geo:<h3_res8>` for `gridDisk(own_res8, 1)` | own res-8 cell only | own cell only |

Presence payload is §3's with `kind: "rider"`. Res-8 k-ring 1 covers the 500 m reach from
anywhere in the own cell, so a single publish suffices.

### 16.3 Receive filter

Run §5.1–§5.3 (self, dedupe, mute) and the §14 tag rule with `convoyTag = pack_tag`
(matching tag → play; anything else → drop; system bursts are ignored). Then:

- **Pack**: play. No heading or distance gating.
- **Open road**: `haversine(S, R) ≤ 500 m`, and when both S and R move ≥ 3 m/s,
  `headingDot(S, R) ≥ 0.5` (±60° — switchbacks match, an oncoming group doesn't).
  Symmetric: riders behind you count (no forward cone).

§5.6 queueing / 60 s age drop apply unchanged. No breadcrumbs. AI audio (§12 synthetic
nodes, §17 Road Guide) is governed by §16.6.
Received `handle`s are passed through the §16.5 cleaner before display.

### 16.4 Voice-activated transmit (VOX)

Once a ride starts the mic is open; each spoken phrase becomes one burst — no button.
The rider pauses/resumes transmit (screen button or earbud Play/Pause). Shared state
machine `VoxDetector` (Kotlin + Swift, identical tests), fed one dBFS level per frame:

- Noise floor = minimum frame level over a sliding 2 s window (tracks steady wind/road
  noise; speech's inter-word dips keep it from tracking the voice). 600 ms warmup.
- Onset: level ≥ max(floor + 14 dB, −50 dBFS) for 120 ms. Clients keep a 400 ms pre-roll
  and write it first so the first syllable isn't clipped.
- Release: 700 ms below max(floor + 8 dB, −56 dBFS) ends the phrase. < 250 ms of speech →
  discard. Clients write at most 250 ms of the trailing silence.
- **Chunked phrases**: every 3 s of a phrase still in progress the snippet is sent and
  capture continues into a new one, so the pack starts hearing a long phrase ~4 s after it
  began instead of after it ended (store-and-forward of whole 10 s phrases put 20–30 s
  between question and answer). Chunks are ordinary bursts — no new wire field.
  - Sender: sends are **serialized** (chunks arrive in order) and the order within a send is
    upload → **broadcast** → `messages` insert, so the row never delays delivery.
    The Road Guide (§17) is asked only about a phrase that went out as a single snippet.
  - Receiver: start downloading a burst's audio when it is queued, not when its turn comes.
    A voice burst from the sender whose previous burst finished playing ≤ 2 s ago is a
    continuation: play it with **no earcon**.
- Audio: AAC-LC `.m4a` on both native platforms (`audio/mp4`), path
  `<trip_id>/<message_id>.m4a`. The web app uploads whatever its MediaRecorder produces —
  Opus `.webm` (`audio/webm`) on Chrome/Firefox, AAC `.m4a` on Safari — so receivers must
  play by `audio_path` extension, never assume one container. Web pre-roll is ~600 ms (a
  DelayNode ahead of the recorder) and the trailing silence ~100 ms.
- **Half-duplex**: before an incoming burst plays, the receiver lets the rider's current
  phrase finish — the whole phrase, not just the current chunk, bounded at ~10–12 s, after
  which what was captured is sent — holds the mic while the pack plays, and reopens
  it ~350 ms after the last burst. A rider never re-transmits a teammate from the speaker.
- No "sent" earcon (it would land in the next phrase); `micOpen` plays on resume *before*
  the mic reopens, `muted` on pause.
- Media buttons: Play/Pause → pause/resume transmit, Next → skip + stealth mute (§8).

### 16.5 Rider name (optional)

The join screen has an optional name field, remembered on the device. Cleaner (identical on
all platforms: `cleanRiderName` / `RiderName.clean`): replace everything except Unicode
letters, digits, space and `. ' -` with a space, collapse whitespace, trim, clip to 20
characters; empty → no name. A name replaces the generated handle **on the wire only** —
the `handle` of burst and presence payloads. `trips.phonetic_handle` always keeps the
generated "Adjective Animal" handle (§1; the insert policy enforces that shape), so
moderation keys on the trip, never on the typed name. Receivers run the same cleaner over
every incoming `handle` before showing it (fallback "Rider").

### 16.6 AI fills silences, never talks over people

Both AI voices are private to the rider and yield to the pack:

- **Local comments** (§12 synthetic nodes; native apps only): fetched once per res-7 cell
  but *not played on arrival*. They wait in a pending list (≤ 3, oldest dropped) and one is
  played only after **30 s of silence** — nothing playing, the rider not on air, and no
  burst sent, received or finished in that time. Playing one restarts the clock. Each
  script is heard **once ever**: its id goes into the persistent played-ids set (§6) when
  it starts, so leaving and rejoining never repeats it.
- **Road Guide** (§17) is a fallback answer. The request goes out as before, but when
  presence shows other riders the client holds the answer until **8 s** after the question
  was sent, and drops it if any pack burst passed the receive filter in the meantime
  (someone replied). Riding alone, it plays as soon as it arrives.
- **People outrank the AI**: a pack burst that passes the filter while a `kind=system`
  item is playing cuts that item off, then plays.

## 17. Road Guide (gated Gemini voice, Car Radio + PelotonCB)

An AI guide that answers a traveler's own talk about **the route, road conditions, the
scenery, points of interest, local history, or weather on the route** — privately, to that
traveler — and stays silent for everything else. Opt-out switch on every client
(default on); server kill switch `ROAD_GUIDE_ENABLED=false`; no `GEMINI_API_KEY` → silent.

### 17.1 Client contract

After a successful §4 send (never for shadowbanned pretend-sends), if the switch is on:

`POST /functions/v1/road-guide { trip_id, message_id, lat, lng, heading, speed }`
→ `{ respond: false }` (the default, for any reason, including errors), or
→ `{ respond: true, id, text, audio_path, handle: "Road Guide" }`.

On `respond: true`, enqueue a **local-only** `kind=system` burst (`trip_id: "road-guide"`,
age-exempt, triple-chime, plays `audio_path` with `text` TTS fallback). It is never
broadcast, never a breadcrumb, and cannot be muted or reported (it's the traveler's own
request). In PelotonCB it goes through the half-duplex turn like any burst, and only as
a fallback when the pack doesn't reply (§16.6).

### 17.2 Verification gate (server; every layer fails closed)

1. **Request**: the message must exist, belong to `trip_id`, be `kind=voice`, < 2 min old,
   and live under `voice_bursts/<trip_id>/`. Shadowbanned trips get silence.
   Rate limits per trip: ≤ 20 evaluations / 10 min, ≥ 20 s between answers, ≤ 30 answers / h.
2. **Classifier** (model A, `GEMINI_MODEL_CLASSIFIER`, audio in): transcript + `on_topic`,
   `category` (enum), `warrants_response`, `confidence`, `injection_suspected` — JSON schema.
3. **Deterministic transcript screen**: ≥ 3 words, ≤ 600 chars, no URLs, no
   prompt-injection phrasing (role changes, "ignore instructions", "system prompt", fake tags).
4. **Independent verifier** (model B, `GEMINI_MODEL_VERIFIER`, transcript only, different
   prompt, transcript fenced as data). Two-key rule: both must say on-topic in an allowed
   category with confidence ≥ 0.8, and the classifier must say a response is warranted.
5. **Answer**: `GEMINI_MODEL_GUIDE` (Google Search grounding) gets the *gated transcript*
   (never raw audio) plus location context (position, heading, speed, named places within
   10 km with ahead/left/right/behind). Scope-locked system instruction; 1–3 sentences,
   < 60 words. Runs concurrently with step 4; the draft is discarded if 4 fails.
   (`ROAD_GUIDE_ENGINE=live` instead takes one Gemini Live turn, `GEMINI_MODEL_LIVE`,
   which also yields the audio — ~3x slower, since audio arrives at speaking pace.)
6. **Output gate**: deterministic screen (3–70 words, no markup/links, no
   eyes-off-road / speed-up instructions, no persona leaks) + output verifier (model B):
   on-topic, actually answers, not unsafe, not speculative, confidence ≥ 0.8. The TTS
   render (`GEMINI_MODEL_TTS`, voice `GEMINI_VOICE_GUIDE`, brisk tour-guide style) runs
   concurrently and is thrown away on a fail. Stage timings are logged in
   `road_guide_events.timings`; target ≈ 6–8 s end to end.
7. Only then: WAV → `synthetic_voice/guide/<trip_id>/<uuid>.wav`, logged `responded`.

Rules live in `supabase/functions/_shared/gate.ts` (models supply verdicts; code decides),
unit-tested in `gate.test.ts`. `road-guide/eval/run-eval.ts` runs 27 voiced cases through
the real models; **any false accept fails the run**.
