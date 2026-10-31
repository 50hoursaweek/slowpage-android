//! Slowpage for iPadOS, Rust side: a UniFFI bridge over the shared `hushhour-core` crate
//! (Markdown scanner, statistics, undo history, soundscapes, file decoding) plus what the
//! Linux app keeps in its own `src/` and the iPad redoes natively: the document rules of
//! `docs/document-safety.md` (`document`), the spotlight layer over the scanner
//! (`highlight`), the colour schemes (`palette`), the preferences schema (`prefs`) and the
//! audio mixer without a device (`mixer`). `ffi` is the UniFFI surface over all of it. The
//! host (Swift) owns the text view, the files and the audio output.

pub mod document;
pub mod ffi;
pub mod highlight;
pub mod mixer;
pub mod palette;
pub mod prefs;

pub use hushhour_core::{dsp, history, markdown, music, rng, stats};

uniffi::setup_scaffolding!("hushhour");
