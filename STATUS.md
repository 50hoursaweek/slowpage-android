# Status

Detailed notes, audits and device runs are kept in the maintainer's local QA records (not in this repository).

## Done
- Gate A: the Rust core built for Android, music heard on the tablet (2026-10-29).
- Phase 3: the app shell, the first usable build. Audit closed 2026-10-29; all device tests passed on the tablet.
- Phase 4: the editor (a TextView-based page with highlighting, spotlight, anchor, preview, keyboard shortcuts, large-document limits). Audit closed 2026-10-30; device tests passed on a Huawei tablet.
- Phase 5: audio (music, soundscapes, imported tracks, lifecycle). Device run 7/7 pass.
- Phase 6: the iPad's dialogs, welcome tour and background pattern. The phases 5+6 audit closed 2026-10-30. Known MINOR: a damaged music file with a valid header still imports; it is reported, and Music turns Off, on first play.
- Phase 7: release prep.
  - LICENSE, THIRD-PARTY-NOTICES.md, the README, version 1.0.0 (code 4), the launcher icon, release signing.
  - The iPad crate and assets are vendored in `vendor/inkpad-ipados/`.
  - The release APK is built from `6d7a56c`: SHA-256 `d6398e70f9b7b138f7826407a7261a0f412d13d011b0788c0b2f141a62ce07e0`, certificate `B1:B8:E4:68:DB:42:16:C5:DD:4B:D9:81:01:4F:A3:2A:90:8A:D4:13:C6:3E:6B:DA:D5:D3:48:FE:C1:20:31:36`.
  - Audit round 1: 0 BLOCKING; its MINOR documentation fixes are done.
  - The editor Menu keeps only the file items, Undo and Redo, the Appearance and Sound and music pop-ups, Welcome and About (Francesco's install test).

## Emulator
- The SDK and the AVDs live on an external drive. Start an AVD, with stdin detached, and point every Android variable at that drive (`E` = its folder):
  `ANDROID_HOME=$E/sdk ANDROID_SDK_ROOT=$E/sdk ANDROID_AVD_HOME=$E/avd ANDROID_USER_HOME=$E/user-home nohup $E/sdk/emulator/emulator -avd slowpage-tablet -no-window -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect < /dev/null > log 2>&1 &`
- `slowpage-tablet`: AOSP arm64 API 31, 2000x1200, 240 dpi, landscape (`user_rotation 0`), no hardware keyboard.
- `slowpage-api28`: the same command with `-avd slowpage-api28`, for the caret on API 26–28. With 8 GiB of RAM, run one emulator at a time.

## Blocked
- Nothing.

## Next
- Francesco's install test of the release APK (export documents, uninstall the test build, then install), then tag v1.0.0 at `6d7a56c` and publish. Both are Francesco's steps.
