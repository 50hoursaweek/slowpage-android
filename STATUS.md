# Status

## Done
- Gate A: closed (Francesco heard the music; CLOSED-AGREED 2026-10-29 in `audit/LATEST.md`).
- Phase 3 shell, first usable build (d744632), F1 first attempt (e38086c, not fixed on the tablet).
- Emulator installed on `/Volumes/Expansion/hushhour-android-emulator` (sdk: emulator, platform-tools, AOSP arm64 API 31 image; AVD `slowpage-tablet` in `avd/`, 2000x1200, 240 dpi, landscape, no hardware keyboard; `user-home/` for ANDROID_USER_HOME).

## In progress (stopped for the Mac reboot, 2026-10-29)
- F2 (theme colours), F3 (bundled fonts), F4 (state-based text field that scrolls itself) coded in the working tree, **uncommitted**. 50 of 51 unit tests pass; the Robolectric F4 scroll test fails (scroll stays 0), to be settled on the emulator.
- F1 (tap does not focus the editor): cause unknown; needs the emulator.
- Emulator: it boots headless when stdin is detached (that was the earlier "hang"):
  `ANDROID_SDK_ROOT=$E/sdk ANDROID_AVD_HOME=$E/avd ANDROID_USER_HOME=$E/user-home nohup $E/sdk/emulator/emulator -avd slowpage-tablet -no-window -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect < /dev/null > log 2>&1 &`
  It got as far as `adb devices` showing `emulator-5554 offline` (still booting) before the stop. Not yet driven with adb.

## Blocked
- Nothing. No APK since c0fba5db (Francesco's rule: emulator check before every APK).

## Next
- Boot the emulator; install the debug APK; drive the 9 "what to try" steps and F1–F4 with adb; screenshots to `audit/phase-3/emulator/`.
- Fix F1, settle F4; update `audit/phase-3/fixes.md`; commit; one APK; ping PMO with commit + SHA-256 and what the emulator cannot show (the Huawei keyboard).
- Then the end-of-phase Codex audit.
