# Third-party notices

Slowpage for Android is MIT-licensed (`LICENSE`). The APK also carries the work of others, listed here. Every licence text below ships inside the app: About → Credits and licences.

The fonts, music, typing sounds, licence texts, icon art and the iPad app's Rust crate are vendored, unmodified, in `vendor/inkpad-ipados/`. They come from the iPad app's repository at commit `873d4f4`, which took the assets from the Linux app's repository ([HushHour](https://github.com/50hoursaweek/hushhour)) at a tagged release. `vendor/inkpad-ipados/VENDORED.md` gives every vendored file with its licence. `hushhour-core` and the other Rust crates are fetched by Cargo when building, not stored here.

## The core
| Component | Licence | Source |
|---|---|---|
| hushhour-core 0.4.2: Markdown scanner, statistics, undo history, soundscapes, music decoding | MIT | https://github.com/50hoursaweek/hushhour |
| hushhour-ipad (`HushHourCore`): the UniFFI bridge, document rules, mixer, background-pattern reference | MIT | Vendored: `vendor/inkpad-ipados/HushHourCore` (from the iPad app, same author) |
| UniFFI (Mozilla), the Kotlin bindings and their runtime | MPL 2.0, unmodified | https://github.com/mozilla/uniffi-rs |
| Symphonia, audio decoding | MPL 2.0, unmodified | https://github.com/pdeljanov/Symphonia |
| Every other Rust crate linked into the core | MPL 2.0, MIT, Apache 2.0, BSD-3-Clause, Zlib, Unicode-3.0, Unlicense, per crate | `LICENSES-rust.txt`, generated from `Cargo.lock` |

The background-pattern shader (`app/src/main/java/.../editor/Backdrop.kt`) is a GLSL port of the core's own pattern, which is MIT and by the same author.

## Fonts (SIL Open Font License 1.1, unmodified; `vendor/inkpad-ipados/HushHour/HushHour/Resources/Fonts`)
| Font | Files | Author |
|---|---|---|
| IBM Plex Mono | Regular, Italic, Bold, Bold Italic | IBM |
| IBM Plex Sans | Regular, Italic, Bold, Bold Italic | IBM |
| Literata | Regular, Italic, Bold, Bold Italic | TypeTogether for Google Fonts |
| Lato | Regular (the dialogs) | Łukasz Dziedzic |

## Sounds and music
| Asset | Licence | Origin |
|---|---|---|
| Typing sounds, two sets (built into the core; `vendor/inkpad-ipados/HushHourCore/assets/sounds`) | CC0 1.0 | Cut from "Keyboard Soundpack #1" by unicaegames, OpenGameArt: https://opengameart.org/content/keyboard-soundpack-1-typing-and-single-keystrokes |
| Six music loops (`Late cafe`, `Lofi desk`, `Low strings`, `Nylon strings`, `Slow horizon`, `Wordless`) | CC0 1.0 | Music loops generated with Google Lyria 3 (AI, SynthID watermark). Dedicated to the public domain under CC0, to the extent any rights exist. From the HushHour repository; `vendor/inkpad-ipados/HushHour/HushHour/Resources/Music` |
| Soundscapes | MIT | Synthesised in code by hushhour-core |

## Images
| Asset | Licence | Origin |
|---|---|---|
| App icon (launcher and About) | MIT, with the app | The iPad app's icon, by the author; `vendor/inkpad-ipados/HushHour/HushHour/Resources/Assets.xcassets` |

## Android libraries (Apache License 2.0, unmodified)
| Library | Source |
|---|---|
| Oboe (Google), the audio output stream | https://github.com/google/oboe |
| Java Native Access (JNA), which loads the core; dual-licensed Apache 2.0 / LGPL 2.1+, used under Apache 2.0 | https://github.com/java-native-access/jna |
| Kotlin standard library, kotlinx.coroutines (JetBrains) | https://github.com/JetBrains/kotlin |
| AndroidX: Jetpack Compose, Activity, Lifecycle, RecyclerView, Annotation (Google) | https://android.googlesource.com/platform/frameworks/support |
| LLVM libc++ (`libc++_shared.so`, from the Android NDK): Apache 2.0 with LLVM Exceptions | https://github.com/llvm/llvm-project |

## In this repository
| File | Licence |
|---|---|
| `vendor/inkpad-ipados/` | Per file, as listed above and in its `VENDORED.md` |
| `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` (Gradle) | Apache License 2.0 |
| `app/src/main/assets/licences/LICENSE-Apache-2.0.txt`, `LICENSE-LLVM.txt` | The licence texts themselves |
