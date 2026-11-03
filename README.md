# Port OS for Android TV

An Android TV / Google TV app that turns a television into a Port OS device. Once paired with a Port OS
account, an agent (or you, through the website, CLI or MCP) can tell the TV to play a YouTube video or a
Spotify link, play an audio clip from a URL, put a text notification on screen, change the volume, pause, skip,
and ask what is playing, with no extra hub in the house.

```
"play the latest All-In podcast on the TV"
  agent ──► YouTube plugin (find video) ──► Android TV plugin ──FCM──► this app ──► YouTube app
                                                   ▲                       │
                                                   └──── state reports ────┘  (now playing, volume)
```

- **Supported TVs:** Google TV and Android TV devices with Google Play Services (Android 8.0 / API 26+).
  Fire TV and other TVs without Play Services are not supported; delivery is Firebase Cloud Messaging only.
- **Reference device:** TCL Google TV.
- **Cloud side:** the Port OS Android TV plugin, which pushes commands to the app and reads its reports.
- **Download:** the newest signed APK is always
  [portos-android-tv.apk](https://github.com/portpowered/portos-android-tv/releases/latest/download/portos-android-tv.apk)
  from the [latest release](https://github.com/portpowered/portos-android-tv/releases/latest). Setup steps:
  [Connect an Android TV](https://portoperatingsystem.com/docs/user-guides/android-tv/).

This directory is self-contained: it reads nothing from the Go code. Its only contract with the backend is
the public HTTP API and the FCM message format documented below, so it can move to its own repository.

## Quick start

| You want to… | Run |
| --- | --- |
| Build a debug APK | `make` |
| Run every lint gate | `make lint` |
| Run unit tests | `make test` |
| Run tests on an emulator or TV | `make test-device` |
| Install on the connected TV/emulator | `make install` |
| Grant the one-time TV permissions | `make setup-tv` |
| Publish a release APK | `make deploy` |
| Publish the public source copy | `make publish-repo` |

Requirements: JDK 17–21 (`JAVA_HOME`; the Makefile prefers a scoop `temurin21-jdk` if present), the Android
SDK (`ANDROID_HOME`, default `%LOCALAPPDATA%\Android\Sdk`) and `adb`. Gradle comes from the wrapper.

## Installing on a TV (one time)

The app is sideloaded during alpha. Android blocks apps from opening other apps from the background unless
they hold the *display over other apps* permission, which Google TV has no settings screen for, so one
`adb` step is part of setup. The same step grants notification access, which lets the app see what is playing, and
switches on the accessibility service, which presses remote buttons and captures the screen.

1. On the TV: **Settings → System → About**, select **Android TV OS build** seven times to unlock developer options.
2. **Settings → System → Developer options**: turn on **USB debugging** and **Wireless debugging**.
3. On a computer on the same network: `adb connect <tv-ip>:5555` (or `adb pair` for wireless debugging) and
   accept the prompt on the TV.
4. `make install setup-tv ADB_SERIAL=<tv-ip>:5555`
5. Open **Port OS** on the TV, then approve the code it shows at the Port OS website (or scan the QR code).

`make setup-tv` runs:

```bash
adb shell appops set com.portoperatingsystem.tv SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener com.portoperatingsystem.tv/com.portoperatingsystem.tv.state.MediaSessionListener
bash scripts/enable-accessibility.sh com.portoperatingsystem.tv
```

The last line is these two `adb` commands, with the service appended to whatever accessibility services are already
enabled (never replacing them):

```bash
adb shell settings put secure enabled_accessibility_services com.portoperatingsystem.tv/com.portoperatingsystem.tv.control.PortOsAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

If the TV already has an accessibility service, read the current value with
`adb shell settings get secure enabled_accessibility_services` and append the Port OS service after a `:`.
The app's status screen and its connectivity report say when the service is not enabled.

### Optional: local adb input (taps and arrow keys)

Some TVs, such as the TCL Google TV on Android 12, declare no touchscreen (not even `faketouch`), and Android silently
drops accessibility gestures on such a device. Below Android 13 the arrow keys and Select only work in apps that
expose accessibility focus, which YouTube does not. If you want taps and the arrow keys to work there, let the app use
the TV's own adb daemon:

1. On the TV, in **Developer options**, keep **USB debugging** on and make sure network debugging is on: the TV's adb
   daemon must listen on TCP port 5555 (`adb connect <tv-ip>:5555` from a computer works). The app connects to
   `127.0.0.1:5555`. Android's TLS "wireless debugging" port is not supported.
2. The app generates its own adb key the first time it finds the port open, so shortly after it starts the TV shows
   **Allow USB debugging?** for it. Choose **Always allow from this computer** and **Allow**. That is the whole opt-in;
   there is no setting in the app.
3. Within about fifteen minutes (the next state report; sooner after a command) the app tells Port OS that taps and
   the arrow keys now work, and the next route discovery adds `touch-input` and the arrow keys to the TV.

It is strictly optional. If network debugging is off, or you decline the prompt, nothing changes: the app uses the
accessibility service, and a tap on a TV with no touch input is reported as failed in the log
(`TAP_NEEDS_TOUCH_OR_ADB`) instead of being silently dropped. The app never nags: a TV with the port closed is
re-checked every 15 minutes without any visible effect, and a key you have not allowed is re-offered at most every six
hours.

Security: ADB grants broad control of the TV (it can install apps, read the screen and inject any input). The app only
runs `input tap` and `input keyevent` and only talks to its own TV over loopback, but while network debugging is on,
any computer on your network that you have allowed can use it. The app's private key never leaves the TV (it is kept
in the app's private, non-backed-up storage); the prompt shows its fingerprint. Revoke it any time in
**Developer options -> Revoke USB debugging authorizations**, or turn network debugging off.

## How it works

1. **Pairing.** On first launch the app creates a P-256 key in the Android Keystore and starts the OAuth
   device-code flow against Port OS, showing the user code and a QR code. When you approve it, the app
   receives a user token (DPoP-bound refresh) and establishes an Android TV plugin installation, submitting
   its FCM registration token, device id, name and public key. The TV becomes an endpoint in your account.
2. **Commands (cloud → TV).** The plugin sends an FCM data message per Port OS command. The app's
   `FirebaseMessagingService` decodes it and runs it: play a `portos-media://` URI through the right app's
   deep link, play/pause/next/previous through media keys, volume and mute through `AudioManager`. Two commands
   run inside the app itself:
   - **Text notification** (`notification/notification-trigger`, `triggerType` `IMMEDIATE` only). `NotificationActivity`
     is started through the same background-launch path as a deep link (`SYSTEM_ALERT_WINDOW`). It is a translucent
     window with a large card (title and body), so video behind it keeps playing. It closes after 15 seconds
     (30 seconds when `priority` is `HIGH` or `URGENT`) or when Back is pressed; a second notification replaces the
     first and restarts the timer.
   - **Play audio by URI** (`media-player/play-uri`, public HTTPS, `FILE` only, optional `title` and
     `offsetInMilliseconds`). `PlaybackService` is a Media3 `MediaSessionService` around an ExoPlayer; Media3 keeps it
     in the foreground (`mediaPlayback`) with a playback notification while audio plays. Because it owns a media
     session, media keys, `MediaSessionListener` state reports and volume treat it like any other player. It stops
     itself when the clip ends, fails, or is stopped. Live streams and transcoding are not supported.
   - **Seek and skip** (`media-player/seek`, `skip-forward`, `skip-backward`, `start-over`). They go to the session
     `StateReporter` would report (the one playing, else loading, else paused: `mostRelevant`) through its
     `MediaController.TransportControls.seekTo`, not through media keys. `seek` jumps to `positionMilliseconds`; a skip
     seeks `deltaPositionMilliseconds` (required, greater than 0) from the position the session reports now, advanced
     to this moment while playing; `start-over` is `seekTo(0)`. A position outside the item is moved to 0 or to the
     item's length when the session reports one. Android ignores `seekTo` unless the session advertises
     `ACTION_SEEK_TO` (256) in its playback state actions, so a session without it fails with `SEEK_NOT_SUPPORTED`
     instead of reporting success; no session fails with `NO_MEDIA_SESSION`, no notification-listener access with
     `MEDIA_ACCESS_NOT_ALLOWED`, and a skip from a session with no position with `POSITION_UNKNOWN`. YouTube TV
     advertises actions=382 (pause, play, rewind, previous, next, fast forward and seek) and Spotify's TV session
     advertises seek, so both work; an app that does not advertise seek fails as above.
   - **Open an app** (`application-launcher/launch-application`, `catalogVendorId` 0 and an Android package as
     `applicationId`). The app uses `getLeanbackLaunchIntentForPackage`, falling back to `getLaunchIntentForPackage`,
     and starts it in a new task through the same `SYSTEM_ALERT_WINDOW` background-launch check as `play-media`
     (`BACKGROUND_LAUNCH_NOT_ALLOWED` without `make setup-tv`). A package that is not installed or has no launcher
     activity fails with `APP_NOT_LAUNCHABLE`. Every report carries `reports.applications`, the packages with a
     Leanback or regular launcher activity, which is the interface's `application-list`. Android 11 hides other
     apps unless the manifest's `<queries>` names them, so the manifest declares the two launcher intents
     (MAIN with LEANBACK_LAUNCHER and with LAUNCHER) in addition to the YouTube and Spotify packages.
   - **Remote buttons** (`keypad-input/send-key`: UP, DOWN, LEFT, RIGHT, SELECT, BACK, HOME, PLAY, PAUSE). PLAY and PAUSE
     use the media-key path above. The rest go through `PortOsAccessibilityService`: BACK and HOME are global
     actions, and the D-pad keys use `GLOBAL_ACTION_DPAD_*` on Android 13 and later. Before that, Select clicks the
     focused node and the arrows move focus with `focusSearch`. Pressing SELECT dismisses YouTube's "Who's watching?"
     picker. Without the service the key fails with `ACCESSIBILITY_NOT_ENABLED`. Below Android 13, when local adb is
     available (see above), the arrows and SELECT are `input keyevent` instead, which needs no focused node and no
     accessibility service; when it is not and no node has focus, the key fails with `KEY_NEEDS_FOCUS`.
   - **Touch tap** (`touch-input/tap`, `x` and `y` as fractions from 0 to 1 of the screen, 0,0 top-left). Below Android
     13 SELECT can only click a focused accessibility node, and YouTube exposes none, so an agent takes a snapshot,
     finds the target in it and taps there. With local adb the app runs `input tap x y` at the real display pixel.
     Otherwise, and only on a TV that declares `touchscreen` or `faketouch`, `PortOsAccessibilityService` performs a
     50 ms stroke with
     `dispatchGesture` at the fraction of the real display size (not the snapshot size), which needs
     `android:canPerformGestures="true"` in the service config. A TV that enabled the service before app 0.4.0 must
     turn it off and on again (`make setup-tv`) for Android to grant the new capability. The tap waits for Android's
     gesture callback, so a cancelled gesture fails with `TAP_CANCELLED`; a TV with no touch input and no adb fails with
     `TAP_NEEDS_TOUCH_OR_ADB`; without the service the tap fails with `ACCESSIBILITY_NOT_ENABLED`. Failures are
     logged on the TV (`PortOsCommand`); FCM is one-way, so Port OS does not see them.
   - **Screen capture** (`snapshot/get-snapshot`). FCM is one-way, so the app answers through the webhook: the same
     service takes a screenshot (`takeScreenshot`, Android 11 and later), shrinks it to a JPEG of at most 4 MiB,
     starting at the full width (up to 3840 px, a whole 4K frame) at quality 85 and stepping down (the installation
     webhook accepts 8 MiB bodies), and posts it signed, tagged with the request's message id.
     The plugin turns it into the `get-snapshot-response` that the waiting caller receives. If the service is
     off or nothing fits the limit, no answer is posted and the caller times out after its reply window.
3. **State (TV → cloud).** The app watches active media sessions and the volume, and posts attribute reports
   (now playing, playback state, volume, mute, connectivity) to the plugin's webhook, signed with the device
   key. A heartbeat every 15 minutes (WorkManager's minimum) keeps connectivity fresh. Each report also says which
   input the TV can deliver (`reports.input`: `touch`, `directionalKeys`), so the plugin only declares `touch-input`
   and the arrow keys on a route when they can work: touch needs a touch feature or local adb, the arrows need
   Android 13 or local adb.
   key. While a session is PLAYING it also reports every minute (`PlayingReports`) so `positionMs` stays close, and a
   heartbeat every 15 minutes (WorkManager's minimum) keeps connectivity fresh. A forced attribute query arrives as a
   `control-routes-provider/report-attributes` push: the app samples the TV, waits out any command that ran in the last
   1.2 seconds, and posts an ordinary report with `requestId` set to the push's message id, which completes the
   host's waiting call. It picks the session that is PLAYING (then buffering, then paused) rather than the first one
   Android lists, because an idle app's session is often listed ahead of the one playing.

### FCM message format (v1)

All values are strings, as FCM data messages require.

| Key | Meaning |
| --- | --- |
| `v` | Protocol version, `1` |
| `id` | Port OS message id, echoed in logs and reports |
| `type` | Canonical Port OS message type, for example `port1/systems/zero/capability-interfaces/media-player/play-media` |
| `body` | The canonical message body as JSON |

Unknown versions and types are ignored and logged; the app never guesses.

### State report format

`POST /webhooks/android-tv/installations/{authLinkId}`, `Content-Type: application/jose`, body a compact JWS
(`alg` ES256, `typ` JWT) signed with the key submitted at pairing. Claims:

```json
{
  "iat": 1761990000,
  "sampledAtMs": 1761990000123,
  "jti": "5b0c…",
  "requestId": "<message id; only on a report that answers report-attributes>",
  "reports": {
    "playbackState": "PLAYING",
    "nowPlaying": { "active": true, "app": "com.google.android.youtube.tv", "provider": "YOUTUBE", "mediaId": "portos-media://portoperatingsystem.com/youtube/video/video/dQw4w9WgXcQ", "name": "…", "artist": "…", "positionMs": 1234 },
    "volume": 40,
    "muted": false,
    "connectivity": { "backgroundLaunchAllowed": true, "mediaAccessAllowed": true }
  }
}
```

`provider` and `mediaId` are sent only when the session's metadata proves them (`MediaIdentity`: the package names the
provider; `METADATA_KEY_MEDIA_ID`, `METADATA_KEY_MEDIA_URI` and the description's id/uri give a `spotify:track:` URI,
an open.spotify.com URL, an 11 character YouTube video id or a watch URL), never guessed. `positionMs` is the session's
position advanced by the time since Android last updated it, at the playback speed.
`playbackState` and `nowPlaying` are omitted when unknown (no media session, or media access not granted);
the plugin treats missing values as unknown. `iat` and `sampledAtMs` (when the state was read, in milliseconds) must be within five minutes and `jti` is single use.
The plugin stamps every attribute with `sampledAtMs`: the host refuses a different value at the same sample time, so a whole-second stamp collides when the TV reports twice in a second.
`connectivity.accessibilityEnabled` says whether the accessibility service is switched on; the plugin logs a
warning when it is false and older apps simply omit it.

A screenshot answer uses the same envelope with a `snapshot` claim instead of `reports`:

```json
{
  "iat": 1761990000,
  "jti": "9f2c…",
  "snapshot": { "requestId": "<message id of the get-snapshot push>", "width": 1280, "height": 720, "data": "<base64 JPEG>" }
}
```

The plugin accepts only a JPEG of at most 524,288 bytes whose `requestId` matches a request it pushed in the last
60 seconds, and answers each request once.

## Layout

```
devices/android-tv/
├── Makefile                 the whole public surface (build, lint, test, install, deploy)
├── build.gradle.kts         shared strict gates for every module
├── config/detekt/detekt.yml detekt exceptions, each with a reason
├── gradle/libs.versions.toml every dependency version, in one place
├── protocol/                pure Kotlin (JVM), no Android imports, fast tests
│   └── src/main/kotlin/com/portoperatingsystem/tv/protocol/
│       ├── command/         FCM data → typed Command (decoding, validation, display time by priority)
│       └── media/           portos-media URI parsing and deep-link resolution
└── app/                     the Android app
    └── src/main/kotlin/com/portoperatingsystem/tv/
        ├── auth/            device-code pairing, Keystore key, DPoP proofs, token storage
        ├── cloud/           Port OS HTTP client (establish, reports)
        ├── messaging/       FirebaseMessagingService, token rotation
        ├── control/         command execution: launcher, media keys, volume, notification and audio dispatch,
        │                    the accessibility service (remote keys, screenshots) and its upload
        ├── playback/        play-uri: Media3 ExoPlayer + MediaSession foreground service
        ├── state/           media-session listener, state snapshot and signed reports, workers
        └── ui/              pairing and status screens, text notification overlay (leanback)
```

Rule of thumb: anything that can be decided without Android lives in `:protocol` and is unit tested on the
JVM. `:app` is the thin layer that touches Android APIs and is tested with Robolectric and on devices.

## Tests

- `make test`: JVM unit tests for `:protocol` (media URIs, deep links, command decoding) and for `:app`
  (JOSE/DPoP signing, the HTTP client and the pairing flow against `MockWebServer` under Robolectric, command
  execution against a fake `DevicePort`, report claims).
- `make test-device`: instrumented tests on the connected emulator or TV (`ADB_SERIAL`). None exist yet;
  on-device behavior was verified by hand (see the design doc's spike findings).
- End-to-end against a real backend: the backend's functional tests cover the plugin with a fake FCM; the
  app's side is covered by `make test-device` with a local server (see the design doc's test plan).

Emulators cannot run YouTube ("not supported on this device"), so launch tests assert the intent the app
starts; the real TV verifies playback.

## Lint

`make lint` fails on any finding. Nothing is baselined.

- **Kotlin compiler:** all warnings are errors, progressive mode on; `:protocol` uses explicit API mode.
- **ktlint** (`android_studio` style, 120 columns): formatting. `make format` fixes what it can.
- **detekt** with `allRules = true` and `maxIssues: 0`: every rule, including opt-in ones. Exceptions live in
  `config/detekt/detekt.yml` with a reason each.
- **Android Lint:** `warningsAsErrors`, `checkAllWarnings`, `abortOnError`.

## Configuration

Per-environment values are build properties, not secrets, and are committed in `app/environments/<env>.properties`:
the Port OS API base URL, the OAuth client id, and the Firebase app options (project id, application id,
API key, sender id). `make ENV=pilot` selects one; the default is `pilot`.

## Deploy

`make deploy ENV=pilot` builds a signed release APK, uploads it to the public downloads bucket and creates the
GitHub release `v<versionName>` in portpowered/portos-android-tv with it (run `make publish-repo` first so the
release tags the matching source), then prints both download URLs. The setup guide links to the GitHub one. The signing key is read from Secret Manager
(`portos-android-tv-signing-keystore`, `-password`); it never lives in the repository. Releases are manual.

Source and releases are also published to the public repository
[portpowered/portos-android-tv](https://github.com/portpowered/portos-android-tv), a release copy of this
directory. `make publish-repo` (`DRY_RUN=1` to preview) copies the tracked files there, without `CLAUDE.md` and with
`github/workflows/` as `.github/workflows/`, and pushes one "Sync from portos-monolith" commit. There, `ci.yml` runs
lint, tests and a build on every push, and pushing a tag that matches `versionName` (`v0.5.3`) runs `release.yml`.
It builds the signed APK and attaches `portos-android-tv.apk` and `portos-android-tv-<version>.apk` to a GitHub
release, so the `releases/latest/download/portos-android-tv.apk` link always serves the newest one. The release
workflow needs the repository secrets `PORTOS_TV_KEYSTORE_BASE64` and `PORTOS_TV_KEYSTORE_PASSWORD`, holding the same
key as Secret Manager: an APK signed with another key cannot update an installed app.

## Gotchas

- `play-uri` and text notifications need the same `make setup-tv` permission as a launch; without it the app
  logs `BACKGROUND_LAUNCH_NOT_ALLOWED`. Media3 session ids are unique per process, so tests that start
  `PlaybackService` destroy it afterwards.
- The accessibility service cannot be enabled from the app and Google TV has no settings screen for it, so
  `make setup-tv` writes `enabled_accessibility_services` over `adb`. Any setup step that rewrites that setting
  must append, or it turns off the TV's other accessibility services.
- Local adb uses the `dadb` client (Apache 2.0, pure Kotlin, ~130 KB). `LocalAdb` caches whether the daemon is usable
  and only probes on a schedule (open: 10 min, port closed: 15 min, key not allowed: 6 h, error: 5 min), because each
  probe of an unallowed key shows the TV's prompt again. Commands never probe; they use the cached answer and fall back
  to the accessibility service. Keep `InputRouter` the only place that chooses between the two.
- `GLOBAL_ACTION_DPAD_*` are API 33 constants that lint rejects at `minSdk` 26, so `KeyDispatcher` spells out the
  values; keep them in step with `AccessibilityService`.
- Without `make setup-tv`, launches fail silently: Android logs `BAL_BLOCK` and the app's `startActivity` returns
  normally. The app reports the missing permission as a state attribute so the agent can tell you.
- A plain `https://youtube.com/watch` intent resolves to a browser stub on Google TV; the app always targets
  `com.google.android.youtube.tv` explicitly.
- Deep standby can delay or drop FCM delivery, and the app cannot turn the TV on.
- Gradle needs JDK 17–21; the Makefile picks `~/scoop/apps/temurin21-jdk` when present. Newer JDKs fail early.
- On Windows, `make` runs recipes with `cmd.exe`, so the Makefile calls `gradlew.bat`. Do not pipe the first
  build of a session (`make build | tail`): the Gradle daemon it starts inherits the pipe and the pipe never closes.
- `MediaUri` uses `URLDecoder.decode(String, String)`: the `Charset` overload only exists from Android 13.
  `:protocol` is Android-linted for exactly this reason.
