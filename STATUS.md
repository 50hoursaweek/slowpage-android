# Status

## Done
- Phase 4 editor (E1–E15, V1–V6, K1): `audit/phase-4/editor-choice.md`, emulator notes in `audit/phase-4/emulator/README.md`. Francesco's run: tests 1–8 PASS on the Huawei (`audit/phase-4/device-try-francesco.md`).
- Phase 4 round 1 (Codex BLOCKING 1–3, MINOR 5–6, Francesco F1–F3): `audit/phase-4/round-1-claude.md`, code bddc7c7. 118 JVM tests pass, lint 0 errors.
- Gate A: closed (Francesco heard the music; CLOSED-AGREED 2026-10-29 in `audit/LATEST.md`).
- Phase 3 audit: CLOSED-AGREED 2026-10-29 (`audit/phase-3/round-3-codex-verification.md`, line in `audit/LATEST.md`). Code 10be1d9, APK SHA-256 8ef890e0…. Accepted by Francesco (`audit/phase-3/ruling-large-drafts.md`): the large-draft cap as a known risk; the debug signing key is kept.
- Phase 3 F1–F4: all PASS on the tablet (Francesco, 2026-10-29).
- Phase 3 shell, first usable build (d744632), F1 first attempt (e38086c, not fixed on the tablet).
- Emulator installed on `/Volumes/Expansion/hushhour-android-emulator` (sdk: emulator, platform-tools, AOSP arm64 API 31 image; AVD `slowpage-tablet` in `avd/`, 2000x1200, 240 dpi, landscape, no hardware keyboard; `user-home/` for ANDROID_USER_HOME).

## In progress
- Phase 4 round 1 APK sent to PMO. Waiting for a ruling on very long paragraphs (one 20 MiB paragraph holds the main thread about 37 s per layout) and for the round-1 verification.

## Emulator
- `/Volumes/Expansion/hushhour-android-emulator`. Start (stdin detached, ANDROID_HOME must point there too, the profile sets it to the old SDK):
  `E=/Volumes/Expansion/hushhour-android-emulator; ANDROID_HOME=$E/sdk ANDROID_SDK_ROOT=$E/sdk ANDROID_AVD_HOME=$E/avd ANDROID_USER_HOME=$E/user-home nohup $E/sdk/emulator/emulator -avd slowpage-tablet -no-window -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect < /dev/null > log 2>&1 &`
- Landscape is `user_rotation 0` (the display is landscape-natural).

## Blocked
- Nothing.

## Next
- Francesco tries the round-1 APK (5 MB typing and preview, 19 MB open); Codex verifies round 1.
