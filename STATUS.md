# Status

## Done
- Gate A: closed (Francesco heard the music; CLOSED-AGREED 2026-10-29 in `audit/LATEST.md`).
- Phase 3 F1–F4: all PASS on the tablet (Francesco, 2026-10-29).
- Phase 3 shell, first usable build (d744632), F1 first attempt (e38086c, not fixed on the tablet).
- Emulator installed on `/Volumes/Expansion/hushhour-android-emulator` (sdk: emulator, platform-tools, AOSP arm64 API 31 image; AVD `slowpage-tablet` in `avd/`, 2000x1200, 240 dpi, landscape, no hardware keyboard; `user-home/` for ANDROID_USER_HOME).

## In progress
- Phase 3 Codex round 2 answered (`audit/phase-3/round-2-claude.md`): stale-copy rollback, large drafts, caret on API 26–27, recovery leftovers fixed; MINOR 7 (release key) waits for Francesco. Open observation: one ANR in the Gate A music start (main-thread Oboe open), for phase 5.

## Emulator
- `/Volumes/Expansion/hushhour-android-emulator`. Start (stdin detached, ANDROID_HOME must point there too, the profile sets it to the old SDK):
  `E=/Volumes/Expansion/hushhour-android-emulator; ANDROID_HOME=$E/sdk ANDROID_SDK_ROOT=$E/sdk ANDROID_AVD_HOME=$E/avd ANDROID_USER_HOME=$E/user-home nohup $E/sdk/emulator/emulator -avd slowpage-tablet -no-window -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect < /dev/null > log 2>&1 &`
- Landscape is `user_rotation 0` (the display is landscape-natural).

## Blocked
- Nothing.

## Next
- PMO ruling on round 2; Francesco's decision on the release key (MINOR 7).
