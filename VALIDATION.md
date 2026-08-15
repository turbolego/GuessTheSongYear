# GuessTheSongYear Android — Implementation and Validation Report

**Date:** 15 August 2026
**Scope:** Feature alignment with the related web application, responsive phone/tablet layout work, Android build validation, and end-to-end gameplay test creation.

## Completed implementation

The Android version now adopts the web app’s portable game mechanics while retaining its existing YouTube-based curated-video architecture. The work adds **Classic** and **Arcade** same-device multiplayer modes, remembers a two-to-eight-player party setup, persists played-song history and duplicate candidates, adds player statistics, and provides selectable song distributions: pure random, modern-prioritized, and custom decade weighting. The app exposes mode and distribution choices in the settings screen, and player statistics in the toolbar menu.

Spotify-specific features were intentionally not copied because they depend on Spotify OAuth, Premium playback, live metadata, or Spotify artist and genre APIs. This Android app instead plays from a local curated YouTube catalog, so playlist saving, Spotify login, live artist autocomplete, and API-sourced genre exclusions would not be reliable or meaningful equivalents.

| Area | Android implementation |
|---|---|
| Turn-based gameplay | **Arcade mode** gives exactly one local player a turn per song, reveals that player’s result, and advances to the next player for the next song. |
| Classic local multiplayer | Existing simultaneous guessing remains available; the reveal action is now shown when all player pickers are ready. |
| Player configuration | Names persist across sessions, enforce unique names, and are capped at eight players. |
| Song selection | Settings support pure random, modern-prioritized, and redistributable custom decade weights. |
| Repetition protection | Song history persists between sessions, tracks duplicate candidate skips, and can be cleared from settings. |
| Player analytics | Persisted guesses, exact answers, and total points are visible in the new statistics screen. |

## Responsive layout work

The gameplay screen now keeps the video at a **16:9 aspect ratio** and uses adaptive dimensions in the base, `w600dp`, and `w1240dp` resource qualifiers. Controls use bounded content widths, increased tablet spacing, scalable year input/picker widths, and scrollable settings/statistics surfaces. The local multiplayer setup and player-guess rows now reuse these shared dimensions rather than relying on phone-only hard-coded values.

| Qualifier | Horizontal padding | Video maximum height | Game content maximum width |
|---|---:|---:|---:|
| Base / phones | 16dp | 240dp | 560dp |
| `w600dp` / tablets | 32dp | 420dp | 720dp |
| `w1240dp` / large tablets | 64dp | 520dp | 840dp |

## Tests and validation

The JVM suite and debug build both completed successfully:

```text
./gradlew test assembleDebug --no-daemon
BUILD SUCCESSFUL
43 actionable tasks: 19 executed, 24 up-to-date
```

The new deterministic test class, `GamePreferencesTest`, validates custom-weight redistribution, Arcade turn rotation, and the eight-player cap. `GameplayE2ETest` was added under `app/src/androidTest` and compiled successfully. It drives the actual activity UI through two end-to-end flows: solo guess → reveal → next-round availability, and local Arcade mode → reveal → next-player rotation.

> **Device-runtime note:** I provisioned Android 37 and Android 35 phone/tablet emulator images and attempted headless execution. The sandbox lacks KVM access and has 3.9 GiB total RAM; the software-accelerated emulators could not complete boot. Therefore `connectedAndroidTest` could not be executed in this environment. The E2E test source **does compile successfully** and is ready to run on a local Android emulator or physical device with:
>
> ```bash
> ./gradlew connectedAndroidTest
> ```

The packaged APK contains the expected base, `w600dp`, and `w1240dp` responsive values, verified directly from the debug APK’s compiled resource table.

## Debug APK

The validated debug artifact is available at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The artifact is approximately **13 MB**.

## Source references

The implementation compared the Android repository with the connected-copy of the related web application. The portable gameplay behavior was derived from the web app’s Arcade mode, player configuration, history, score/state persistence, and custom weighting flows.[1] [2]

[1]: https://github.com/turbolego/GuessTheSongYear "GuessTheSongYear Android repository"
[2]: https://github.com/turbolego/RandomSpotifySongTest "RandomSpotifySongTest web repository"
