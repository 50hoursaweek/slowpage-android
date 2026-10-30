# Status

## Done
- Phases 5+6 audit: CLOSED-AGREED 2026-10-30 (`audit/phase-6/round-3-codex-verification.md`, line in `audit/LATEST.md`). Code b2566b7, APK 81ae33c3…. Rounds: `audit/phase-6/round-2-claude.md`, `round-3-claude.md`. Open MINOR: a damaged file with a valid header still imports (reported on first play).
- Phases 5+6 merged (661c65b, APK ab61c0c0): Codex phase 5 round 1 fixes, the iPad's dialogs, welcome tour and background pattern; Francesco's run 7/7 PASS.
- Phase 5 (audio), Francesco's run on APK 0b78f921: 7/7 PASS (`audit/phase-5/device-try-francesco.md`).
- Phase 4 audit: CLOSED-AGREED 2026-10-30 (`audit/phase-4/round-3-codex-verification.md`, line in `audit/LATEST.md`). Code 36159ac, APK be304349….
- Phase 4 editor (E1–E15, V1–V6, K1): `audit/phase-4/editor-choice.md`, emulator notes in `audit/phase-4/emulator/README.md`. Francesco's run: tests 1–8 PASS on the Huawei (`audit/phase-4/device-try-francesco.md`).
- Phase 4 round 3 (Codex NEW-B1: no undo or redo in a read-only document; recovered text kept): `audit/phase-4/round-3-claude.md`. 126 JVM tests pass, lint 0 errors.
- Phase 4 round 2 (Codex new BLOCKING: input during a relayout; BLOCKING 3 by ruling B: paragraph cap 100,000, read-only over it; MINOR 5 checked on API 28; MINOR 6 re-measured): `audit/phase-4/round-2-claude.md`, code 189f040. 125 JVM tests pass, lint 0 errors.
- Phase 4 round 1 (Codex BLOCKING 1–3, MINOR 5–6, Francesco F1–F3): `audit/phase-4/round-1-claude.md`, code bddc7c7. Francesco's retest of APK 459a4688: PASS.
- Gate A: closed (Francesco heard the music; CLOSED-AGREED 2026-10-29 in `audit/LATEST.md`).
- Phase 3 audit: CLOSED-AGREED 2026-10-29 (`audit/phase-3/round-3-codex-verification.md`, line in `audit/LATEST.md`). Code 10be1d9, APK SHA-256 8ef890e0…. Accepted by Francesco (`audit/phase-3/ruling-large-drafts.md`): the large-draft cap as a known risk; the debug signing key is kept.
- Phase 3 F1–F4: all PASS on the tablet (Francesco, 2026-10-29).
- Phase 3 shell, first usable build (d744632), F1 first attempt (e38086c, not fixed on the tablet).
- Emulator installed on `/Volumes/Expansion/hushhour-android-emulator` (sdk: emulator, platform-tools, AOSP arm64 API 31 image; AVD `slowpage-tablet` in `avd/`, 2000x1200, 240 dpi, landscape, no hardware keyboard; `user-home/` for ANDROID_USER_HOME).

## In progress
- Nothing. Waiting for Francesco before phase 7.

## Emulator
- `/Volumes/Expansion/hushhour-android-emulator`. Start (stdin detached, ANDROID_HOME must point there too, the profile sets it to the old SDK):
  `E=/Volumes/Expansion/hushhour-android-emulator; ANDROID_HOME=$E/sdk ANDROID_SDK_ROOT=$E/sdk ANDROID_AVD_HOME=$E/avd ANDROID_USER_HOME=$E/user-home nohup $E/sdk/emulator/emulator -avd slowpage-tablet -no-window -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect < /dev/null > log 2>&1 &`
- Landscape is `user_rotation 0` (the display is landscape-natural).
- Also an API 28 AVD, `slowpage-api28` (same command with `-avd slowpage-api28`), for the caret on 26–28. With 8 GiB of RAM, run one emulator at a time.

## Blocked
- Nothing.

## Next
- Phase 7, when Francesco says.
