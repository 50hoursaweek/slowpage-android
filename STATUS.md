# Status

## Done
- Gate A: closed (Francesco heard the music; CLOSED-AGREED 2026-10-29 in `audit/LATEST.md`).
- Phase 3 shell, first usable build (d744632), F1 first attempt (e38086c, not fixed on the tablet).
- Emulator installed on `/Volumes/Expansion/hushhour-android-emulator` (sdk: emulator, platform-tools, AOSP arm64 API 31 image; AVD `slowpage-tablet` in `avd/`, 2000x1200, 240 dpi, landscape, no hardware keyboard; `user-home/` for ANDROID_USER_HOME).

## In progress
- Phase 3 F1–F4 APK with Francesco for a retest (SHA-256 5cf4d28a…, see `audit/phase-3/fixes.md`). F2, F3, F4 checked on the emulator; F1 and F4 do not reproduce there even on the old build, so both still need the tablet. The editor shows a focus readout line for that retest.

## Emulator
- `/Volumes/Expansion/hushhour-android-emulator`. Start (stdin detached, ANDROID_HOME must point there too, the profile sets it to the old SDK):
  `E=/Volumes/Expansion/hushhour-android-emulator; ANDROID_HOME=$E/sdk ANDROID_SDK_ROOT=$E/sdk ANDROID_AVD_HOME=$E/avd ANDROID_USER_HOME=$E/user-home nohup $E/sdk/emulator/emulator -avd slowpage-tablet -no-window -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect < /dev/null > log 2>&1 &`
- Landscape is `user_rotation 0` (the display is landscape-natural).

## Blocked
- Nothing.

## Next
- Francesco retests F1–F4 on the tablet. If F1 still fails, a photo of the readout line, or the tablet on USB to this Mac for adb, would settle it.
- Then the end-of-phase Codex audit.
