# Android TV app notes

The cloud side (the Android TV plugin, the host's webhook limits, the capability-interface schemas) lives with the
Port OS server, not here. A change to a command or report needs a matching plugin change there, and the plugin must
keep accepting older app versions, because installed TVs update late.

- Remote buttons, taps (`dispatchGesture`, needs `canPerformGestures` in the service config) and screen capture all go through `PortOsAccessibilityService`. Enabling it by adb must append to
  `enabled_accessibility_services` (separator `:`), never overwrite it; `scripts/enable-accessibility.sh` does this.
- `KeyDispatcher` spells out the `GLOBAL_ACTION_DPAD_*` values (16 to 20) because lint rejects the API 33 constants at
  a lower minSdk; keep them in sync with the framework.
- `DevicePort` and `ScreenPort` are separate interfaces to stay under detekt `TooManyFunctions`; `CommandExecutor`
  takes both.
- A screenshot report must fit the Port OS host's 8 MiB installation webhook body (the rest of the API keeps a
  1 MiB limit). The JPEG is base64 inside claims that are
  base64url-encoded again by the signed report, so `ScreenshotEncoder` caps it at 4,194,304 bytes (about 7.1 MiB on the
  wire) and starts its ladder at 3840 px for a 4K frame. Keep `MAX_BYTES` in step with the plugin's `maxSnapshotBytes`
  and with that host limit.
- A new command type needs the protocol decoder and the app executor here, and the plugin's command and vocabulary on
  the server; release the app first, since the plugin may only send what installed apps understand.
- Remote input goes through `InputRouter`: local adb (`LocalAdb`, `dadb`, 127.0.0.1:5555) when the owner enabled
  network debugging and allowed the key, else the accessibility service. Android drops `dispatchGesture` on a device
  without `touchscreen`/`faketouch`, so the router refuses those taps instead of reporting success. `reports.input`
  (`touch`, `directionalKeys`) in `StateSnapshot` feeds the plugin's route discovery; change both together. Never probe
  adb per command: a probe of an unallowed key re-shows the TV's prompt.
- `touch-input/tap` carries fractions of the screen (0 to 1); `TapGesture` maps them onto the real display
  (`getRealMetrics`), never the snapshot size, because the snapshot is shrunk. Adding a service capability needs the
  service switched off and on again on an already-enabled TV.
- A new media resource kind (for example YouTube `playlist`) needs `DeepLinks.kt` and its test, the plugin's
  `mediaPlayerConfiguration` `ResourceKinds` and its discovery replay expectation on the server, plus an app version
  bump; the kind is unplayable until the new APK is released.
- A forced attribute query is `control-routes-provider/report-attributes` over FCM: `CommandExecutor` hands it to
  `StatePort`, the reporter samples and posts a report tagged `requestId`, and `PortOsMessagingService` skips the
  generic `requestReport()` for it. `StateReporter.commandRan()` makes a read right after another command wait up to
  1.2 s, because MediaSession state lags a key press; without it the read returns the pre-command state.
- Pick the media session by state (`mostRelevant`), not `getActiveSessions().first()`: an idle Spotify session is often
  listed ahead of the YouTube session that is playing. Media ids come only from `MediaIdentity` (inverse of
  `DeepLinks`); extend both together and add to `MediaIdentityTest`, and omit rather than guess.
- The 60 s report while PLAYING is an in-process scheduler (`PlayingReports`), not WorkManager (15 min minimum); it
  needs the process alive, which the bound `MediaSessionListener` provides.
- A state report's sample time must be millisecond precise: `StateSnapshot.sampledAtMs` is the wall clock read when
  the state was sampled (not when it is posted) and rides in the claims as `sampledAtMs`; the plugin stamps every
  attribute with it. The host refuses a different body at the same sample millisecond (`ErrSampleConflict`, a 500 on
  the webhook), and `iat` is whole seconds, so stamping by `iat` loses every reading after the first in a second.
  Keep the plugin's `sampledAtMs` window check (`reportSkewSeconds`) and the app's clock in step.
- Several media-session callbacks fire for one change, so `PortOsApplication.requestReport()` coalesces them
  (`CoalescedRequests`, 2 s) into one report; call `requestReport()`, never enqueue `ReportWorker` directly.
- `LocalAdb` probes an unallowed key once more after 3 minutes (the owner may not have reached the TV's prompt yet),
  then every 6 hours; a probe only runs when a report asks for input capabilities, so an allow shows by the next
  report (at most the 15 minute heartbeat).
- Seek and skip go through `MediaController.TransportControls.seekTo` (`SessionControl`), not media keys, and Android
  silently ignores `seekTo` unless the session advertises `ACTION_SEEK_TO` (256) in `PlaybackState.actions`; the
  command checks the bit and fails with `SEEK_NOT_SUPPORTED` instead of reporting success. Commands and reports pick
  their session with the one `mostRelevant` (`state/Sessions.kt`), so they never disagree about which app is playing.
- A media key the app dispatches through `AudioManager` never reaches its own Media3 session (seen on the TCL TV in
  0.6.0: pause and play were logged done and nothing changed). When that pick is the app's own session,
  `AndroidDevice.mediaKey` uses its `TransportControls` instead (`ownRelevantController`). Robolectric's
  `ShadowMediaController` does not forward transport calls, so tests check the session choice, not the call.
  A skip's distance is required (the schema makes it optional); there is no default.
- `PlayerActivity` shows the player `PlaybackService` keeps in its companion `currentPlayer` (in-process, main thread
  only), and finishes when that player goes idle or ends; the service starts it with each new play request. Stopping
  therefore means `player.stop()`, which both closes the screen and (via `StopWhenDone`) ends the service. Files for
  `play-video`/`play-audio` are downloaded to `cacheDir/media` and played locally because the plugin's URL expires in
  at most ten minutes; delete a file only when its item is replaced or the service is destroyed. A change to the
  `play-video`/`play-audio` bodies needs the plugin's `media.go` and the README body table changed together.
- Listing and launching apps is package visibility on Android 11+: only packages the manifest's `<queries>` names or
  that match a declared `<queries><intent>` are visible, so `AndroidApps` works only because the manifest declares the
  LEANBACK_LAUNCHER and LAUNCHER intents. Change one without the other and `launchable()` quietly returns less. A launch
  uses the same `canLaunchFromBackground` gate as play-media.
- `play-video`, `play-audio`, `show-images`, `add-images` and `remove-images` are answered with an `outcome` claim on a
  report tagged with the command's message id (`Answers` in `state/CommandOutcome.kt`, one answer per id; a command
  that replaces an in-flight one answers it `REPLACED`). The sink is `PlaybackService.outcomes`, wired in `AppGraph`.
  Show and file commands answer from the player listener on `STATE_READY`; add/remove answer when the items change.
  `StateReporter.reportOutcome` waits for the media session to show the new queue length before reading state.
  Robolectric's ExoPlayer never reaches READY, so tests call `PlaybackService.answerWhenShown` directly. Keep the
  plugin's `playback-result` handling and the failure codes in step with this.
