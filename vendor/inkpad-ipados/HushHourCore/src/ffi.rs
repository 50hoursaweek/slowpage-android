//! The UniFFI surface the Swift app calls. Everything here is a thin mirror of the Rust
//! types in this crate and in `hushhour-core`: records and enums with the same fields, and
//! three objects (`Highlighter`, `Document`, `AudioEngine`) that lock their Rust value.
//! Offsets are UTF-8 byte offsets as `u64`; the host converts from UTF-16. The names avoid
//! clashes with SwiftUI (`Rgb`, `FontChoice`, `TextChange`) because the generated Swift is
//! imported next to it.
//!
//! The audio render path is not UniFFI: `hushhour_mixer_render` is a plain C function the
//! `AVAudioSourceNode` callback calls with its own buffer (no allocation, no bridge).

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use hushhour_core::history::{Position as CorePosition, position_at};
use hushhour_core::markdown::Kind;
use hushhour_core::music::{Music, MusicDirs};

use crate::document;
use crate::highlight::{self, FocusMode as CoreFocus, MONO_FAMILY};
use crate::mixer::{self, Key, Mixer};
use crate::palette::{self, ThemeKind};
use crate::prefs::{self, Backdrop as CoreBackdrop, FontKind, SoundSet as CoreSoundSet, ViewMode as CoreViewMode};
use hushhour_core::backdrop;
use hushhour_core::preview;

// ---------------------------------------------------------------------------------------
// Enums and records

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Theme {
    Light,
    Sepia,
    Dark,
}

impl From<Theme> for ThemeKind {
    fn from(t: Theme) -> Self {
        match t {
            Theme::Light => Self::Light,
            Theme::Sepia => Self::Sepia,
            Theme::Dark => Self::Dark,
        }
    }
}

impl From<ThemeKind> for Theme {
    fn from(t: ThemeKind) -> Self {
        match t {
            ThemeKind::Light => Self::Light,
            ThemeKind::Sepia => Self::Sepia,
            ThemeKind::Dark => Self::Dark,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FontChoice {
    Mono,
    Sans,
    Serif,
}

impl From<FontChoice> for FontKind {
    fn from(f: FontChoice) -> Self {
        match f {
            FontChoice::Mono => Self::Mono,
            FontChoice::Sans => Self::Sans,
            FontChoice::Serif => Self::Serif,
        }
    }
}

impl From<FontKind> for FontChoice {
    fn from(f: FontKind) -> Self {
        match f {
            FontKind::Mono => Self::Mono,
            FontKind::Sans => Self::Sans,
            FontKind::Serif => Self::Serif,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FocusMode {
    Off,
    Paragraph,
    Sentence,
}

impl From<FocusMode> for CoreFocus {
    fn from(f: FocusMode) -> Self {
        match f {
            FocusMode::Off => Self::Off,
            FocusMode::Paragraph => Self::Paragraph,
            FocusMode::Sentence => Self::Sentence,
        }
    }
}

impl From<CoreFocus> for FocusMode {
    fn from(f: CoreFocus) -> Self {
        match f {
            CoreFocus::Off => Self::Off,
            CoreFocus::Paragraph => Self::Paragraph,
            CoreFocus::Sentence => Self::Sentence,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SoundSet {
    Soft,
    Keyboard,
}

impl From<SoundSet> for CoreSoundSet {
    fn from(s: SoundSet) -> Self {
        match s {
            SoundSet::Soft => Self::Soft,
            SoundSet::Keyboard => Self::Keyboard,
        }
    }
}

impl From<CoreSoundSet> for SoundSet {
    fn from(s: CoreSoundSet) -> Self {
        match s {
            CoreSoundSet::Soft => Self::Soft,
            CoreSoundSet::Keyboard => Self::Keyboard,
        }
    }
}

/// What the window shows: the editor, the editor beside a live preview, or the preview alone.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ViewMode {
    Editor,
    Split,
    Preview,
}

impl From<ViewMode> for CoreViewMode {
    fn from(v: ViewMode) -> Self {
        match v {
            ViewMode::Editor => Self::Editor,
            ViewMode::Split => Self::Split,
            ViewMode::Preview => Self::Preview,
        }
    }
}

impl From<CoreViewMode> for ViewMode {
    fn from(v: CoreViewMode) -> Self {
        match v {
            CoreViewMode::Editor => Self::Editor,
            CoreViewMode::Split => Self::Split,
            CoreViewMode::Preview => Self::Preview,
        }
    }
}

/// The animated background behind the page: off, or one of the three patterns.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Backdrop {
    Off,
    Waves,
    Swell,
    Drift,
}

impl From<Backdrop> for CoreBackdrop {
    fn from(b: Backdrop) -> Self {
        match b {
            Backdrop::Off => Self::Off,
            Backdrop::Waves => Self::Waves,
            Backdrop::Swell => Self::Swell,
            Backdrop::Drift => Self::Drift,
        }
    }
}

impl From<CoreBackdrop> for Backdrop {
    fn from(b: CoreBackdrop) -> Self {
        match b {
            CoreBackdrop::Off => Self::Off,
            CoreBackdrop::Waves => Self::Waves,
            CoreBackdrop::Swell => Self::Swell,
            CoreBackdrop::Drift => Self::Drift,
        }
    }
}

/// What kind of key was pressed; each gets a slightly different sound.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum KeyKind {
    Character,
    Enter,
    Backspace,
}

impl From<KeyKind> for Key {
    fn from(k: KeyKind) -> Self {
        match k {
            KeyKind::Character => Self::Character,
            KeyKind::Enter => Self::Enter,
            KeyKind::Backspace => Self::Backspace,
        }
    }
}

/// What plays in the background: a generated soundscape, a bundled track or the user's own.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum MusicChoice {
    Off,
    Rain,
    Night,
    Train,
    Breeze,
    Drone,
    /// A track shipped with the app, by file name in the bundled music directory.
    Bundled { name: String },
    /// A file in the user's music directory, by file name.
    File { name: String },
}

impl From<MusicChoice> for Music {
    fn from(m: MusicChoice) -> Self {
        match m {
            MusicChoice::Off => Self::Off,
            MusicChoice::Rain => Self::Rain,
            MusicChoice::Night => Self::Night,
            MusicChoice::Train => Self::Train,
            MusicChoice::Breeze => Self::Breeze,
            MusicChoice::Drone => Self::Drone,
            MusicChoice::Bundled { name } => Self::Bundled(name),
            MusicChoice::File { name } => Self::File(name),
        }
    }
}

impl From<Music> for MusicChoice {
    fn from(m: Music) -> Self {
        match m {
            Music::Off => Self::Off,
            Music::Rain => Self::Rain,
            Music::Night => Self::Night,
            Music::Train => Self::Train,
            Music::Breeze => Self::Breeze,
            Music::Drone => Self::Drone,
            Music::Bundled(name) => Self::Bundled { name },
            Music::File(name) => Self::File { name },
        }
    }
}

/// The Markdown construct a span belongs to.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SpanKind {
    Text,
    Marker,
    Heading,
    Emphasis,
    Strong,
    StrongEmphasis,
    Code,
    CodeBlock,
    Link,
    Url,
    ListMarker,
}

impl From<Kind> for SpanKind {
    fn from(k: Kind) -> Self {
        match k {
            Kind::Text => Self::Text,
            Kind::Marker => Self::Marker,
            Kind::Heading => Self::Heading,
            Kind::Emphasis => Self::Emphasis,
            Kind::Strong => Self::Strong,
            Kind::StrongEmphasis => Self::StrongEmphasis,
            Kind::Code => Self::Code,
            Kind::CodeBlock => Self::CodeBlock,
            Kind::Link => Self::Link,
            Kind::Url => Self::Url,
            Kind::ListMarker => Self::ListMarker,
        }
    }
}

impl From<SpanKind> for Kind {
    fn from(k: SpanKind) -> Self {
        match k {
            SpanKind::Text => Self::Text,
            SpanKind::Marker => Self::Marker,
            SpanKind::Heading => Self::Heading,
            SpanKind::Emphasis => Self::Emphasis,
            SpanKind::Strong => Self::Strong,
            SpanKind::StrongEmphasis => Self::StrongEmphasis,
            SpanKind::Code => Self::Code,
            SpanKind::CodeBlock => Self::CodeBlock,
            SpanKind::Link => Self::Link,
            SpanKind::Url => Self::Url,
            SpanKind::ListMarker => Self::ListMarker,
        }
    }
}

/// A colour with components in `0.0..=1.0`.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Rgb {
    pub r: f32,
    pub g: f32,
    pub b: f32,
}

impl From<palette::Rgb> for Rgb {
    fn from(c: palette::Rgb) -> Self {
        Self { r: c.r, g: c.g, b: c.b }
    }
}

/// The six colours of a theme.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Palette {
    pub background: Rgb,
    pub text: Rgb,
    pub primary: Rgb,
    pub success: Rgb,
    pub warning: Rgb,
    pub danger: Rgb,
}

impl From<palette::Palette> for Palette {
    fn from(p: palette::Palette) -> Self {
        Self {
            background: p.background.into(),
            text: p.text.into(),
            primary: p.primary.into(),
            success: p.success.into(),
            warning: p.warning.into(),
            danger: p.danger.into(),
        }
    }
}

/// A half-open range `start..end`; UTF-8 byte offsets for text, line indices for lines.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct ByteRange {
    pub start: u64,
    pub end: u64,
}

impl From<std::ops::Range<usize>> for ByteRange {
    fn from(r: std::ops::Range<usize>) -> Self {
        Self { start: r.start as u64, end: r.end as u64 }
    }
}

impl ByteRange {
    fn to_range(self) -> std::ops::Range<usize> {
        self.start as usize..self.end as usize
    }
}

/// One highlighted span of a line, with byte and UTF-16 offsets relative to the line.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct Span {
    pub start: u64,
    pub end: u64,
    pub utf16_start: u64,
    pub utf16_end: u64,
    pub kind: SpanKind,
    /// Outside the spotlight: drawn faded towards the background.
    pub dim: bool,
}

/// What scanning one line produced.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScanResult {
    pub spans: Vec<Span>,
    /// The fence state after this line differs from what the next line was last scanned
    /// with: keep scanning, the following lines render differently now.
    pub next_changed: bool,
}

/// Spans of `line` with UTF-16 offsets added, walking the line once.
fn spans_of(line: &str, spans: Vec<(std::ops::Range<usize>, highlight::Highlight)>) -> Vec<Span> {
    let mut out = Vec::with_capacity(spans.len());
    let (mut byte, mut unit) = (0usize, 0usize);
    let mut advance = |to: usize| {
        unit += line[byte..to].encode_utf16().count();
        byte = to;
        unit
    };
    for (r, h) in spans {
        let utf16_start = advance(r.start);
        let utf16_end = advance(r.end);
        out.push(Span {
            start: r.start as u64,
            end: r.end as u64,
            utf16_start: utf16_start as u64,
            utf16_end: utf16_end as u64,
            kind: h.kind.into(),
            dim: h.dim,
        });
    }
    out
}

/// Byte offset of UTF-16 unit `units` in `text`, on a character boundary (an offset inside
/// a surrogate pair maps to the start of that character); past the end maps to the end.
fn utf16_to_byte(text: &str, units: usize) -> usize {
    let mut n = 0;
    for (i, c) in text.char_indices() {
        let next = n + c.len_utf16();
        if units < next {
            return i;
        }
        n = next;
    }
    text.len()
}

/// UTF-16 offset of byte `byte` in `text` (rounded down to a character boundary).
fn byte_to_utf16(text: &str, byte: usize) -> usize {
    let mut byte = byte.min(text.len());
    while !text.is_char_boundary(byte) {
        byte -= 1;
    }
    text[..byte].encode_utf16().count()
}

/// Byte offset of a line/column position (clamped to the line).
fn byte_of(text: &str, p: CorePosition) -> usize {
    let start = if p.line == 0 {
        0
    } else {
        match text.match_indices('\n').nth(p.line - 1) {
            Some((i, _)) => i + 1,
            None => return text.len(),
        }
    };
    let end = text[start..].find('\n').map_or(text.len(), |i| start + i);
    (start + p.column).min(end)
}

/// How a span is drawn: colour, family (code spans use the mono family) and face.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TextStyle {
    pub color: Rgb,
    pub family: String,
    pub bold: bool,
    pub italic: bool,
}

/// Word, character and reading-time statistics for the status line.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Stats {
    pub words: u64,
    pub characters: u64,
    pub sentences: u64,
    pub paragraphs: u64,
    pub reading_seconds: u64,
    /// "12 sec", "3 min", "1 h 5 min".
    pub reading_time: String,
}

/// Everything the highlighter needs to know about the editor state.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HighlightSettings {
    pub font: FontChoice,
    pub focus: FocusMode,
    /// Lines belonging to the paragraph under the cursor.
    pub paragraph: ByteRange,
    pub cursor_line: u64,
    /// Byte range of the sentence under the cursor, within `cursor_line`.
    pub sentence: ByteRange,
}

impl From<HighlightSettings> for highlight::Settings {
    fn from(s: HighlightSettings) -> Self {
        Self {
            family: FontKind::from(s.font).family(),
            focus: s.focus.into(),
            paragraph: s.paragraph.to_range(),
            cursor_line: s.cursor_line as usize,
            sentence: s.sentence.to_range(),
        }
    }
}

/// A caret position as line and UTF-8 byte column.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct Position {
    pub line: u64,
    pub column: u64,
}

impl From<Position> for CorePosition {
    fn from(p: Position) -> Self {
        Self { line: p.line as usize, column: p.column as usize }
    }
}

impl From<CorePosition> for Position {
    fn from(p: CorePosition) -> Self {
        Self { line: p.line as u64, column: p.column as u64 }
    }
}

/// A change the host applies to its text view: `range` (bytes) or `utf16` (units) of the
/// text as it was, replaced by `text`, then the caret at `caret` / `caret_utf16`. The line
/// numbers update the host's line table: lines `line..line + removed_lines + 1` became
/// `inserted_lines + 1` lines.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TextChange {
    pub range: ByteRange,
    pub utf16: ByteRange,
    pub text: String,
    pub caret: Position,
    pub caret_utf16: u64,
    pub line: u64,
    pub removed_lines: u64,
    pub inserted_lines: u64,
}

impl TextChange {
    /// `new_text` is the text after the change (its prefix up to the change is the old one).
    fn new(c: document::Change, new_text: &str) -> Self {
        let utf16_start = byte_to_utf16(new_text, c.range.start);
        let caret_utf16 = byte_to_utf16(new_text, byte_of(new_text, c.caret));
        Self {
            range: c.range.into(),
            utf16: ByteRange { start: utf16_start as u64, end: (utf16_start + c.removed_utf16) as u64 },
            text: c.text,
            caret: c.caret.into(),
            caret_utf16: caret_utf16 as u64,
            line: c.line as u64,
            removed_lines: c.removed_lines as u64,
            inserted_lines: c.inserted_lines as u64,
        }
    }
}

/// What an accepted edit did, for the host's line table and caret.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct EditResult {
    pub caret: Position,
    pub caret_utf16: u64,
    pub line: u64,
    pub removed_lines: u64,
    pub inserted_lines: u64,
}

/// What a save writes, and the generation it was taken from.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SaveRequest {
    pub generation: u64,
    pub text: String,
}

/// Where the text stands against the file.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum Persistence {
    /// Everything is on disk: New, Open and Close may proceed.
    Clean,
    /// Edited since the last save and no save running.
    Dirty,
    /// A save is in flight.
    Saving,
    /// The last save failed; the text is only in memory. Blocks destructive transitions.
    Failed { reason: String },
}

impl From<document::Persistence> for Persistence {
    fn from(p: document::Persistence) -> Self {
        match p {
            document::Persistence::Clean => Self::Clean,
            document::Persistence::Dirty => Self::Dirty,
            document::Persistence::Saving => Self::Saving,
            document::Persistence::Failed(reason) => Self::Failed { reason },
        }
    }
}

/// What became of a finished Open request.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Offer {
    Ignored,
    Deferred,
    Replaced,
}

impl From<document::Offer> for Offer {
    fn from(o: document::Offer) -> Self {
        match o {
            document::Offer::Ignored => Self::Ignored,
            document::Offer::Deferred => Self::Deferred,
            document::Offer::Replaced => Self::Replaced,
        }
    }
}

/// Why an edit or a load was not applied.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum DocumentError {
    /// The result would exceed the limit; nothing changed.
    TooLarge { limit_bytes: u64 },
    /// The range is reversed, past the end or not on character boundaries.
    BadRange,
}

impl std::fmt::Display for DocumentError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::TooLarge { limit_bytes } => write!(f, "the document would exceed {limit_bytes} bytes"),
            Self::BadRange => f.write_str("the range is not valid for this text"),
        }
    }
}

impl std::error::Error for DocumentError {}

impl From<document::Refused> for DocumentError {
    fn from(r: document::Refused) -> Self {
        match r {
            document::Refused::TooLarge { limit_bytes } => Self::TooLarge { limit_bytes },
            document::Refused::BadRange => Self::BadRange,
        }
    }
}

/// Everything the user can change from the menu. Same schema as the Linux app's prefs file.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Preferences {
    pub theme: Theme,
    pub font: FontChoice,
    pub size: f32,
    pub line_height: f32,
    pub focus: FocusMode,
    pub typewriter: bool,
    pub view: ViewMode,
    pub typing_sound: bool,
    pub sound_set: SoundSet,
    /// 0..=1.
    pub sound_volume: f32,
    pub show_welcome: bool,
    pub frost: bool,
    pub backdrop: Backdrop,
    /// 0..=0.9.
    pub frost_transparency: f32,
    /// 0..=1.
    pub frost_thickness: f32,
    /// Not saved: the app always starts silent.
    pub music: MusicChoice,
    /// 0..=1.
    pub music_volume: f32,
}

impl From<prefs::Preferences> for Preferences {
    fn from(p: prefs::Preferences) -> Self {
        Self {
            theme: p.theme.into(),
            font: p.font.into(),
            size: p.size,
            line_height: p.line_height,
            focus: p.focus.into(),
            typewriter: p.typewriter,
            view: p.view.into(),
            typing_sound: p.typing_sound,
            sound_set: p.sound_set.into(),
            sound_volume: p.sound_volume,
            show_welcome: p.show_welcome,
            frost: p.frost,
            backdrop: p.backdrop.into(),
            frost_transparency: p.frost_transparency,
            frost_thickness: p.frost_thickness,
            music: p.music.into(),
            music_volume: p.music_volume,
        }
    }
}

impl From<Preferences> for prefs::Preferences {
    fn from(p: Preferences) -> Self {
        Self {
            theme: p.theme.into(),
            font: p.font.into(),
            size: p.size,
            line_height: p.line_height,
            focus: p.focus.into(),
            typewriter: p.typewriter,
            view: p.view.into(),
            typing_sound: p.typing_sound,
            sound_set: p.sound_set.into(),
            sound_volume: p.sound_volume,
            show_welcome: p.show_welcome,
            frost: p.frost,
            backdrop: p.backdrop.into(),
            frost_transparency: p.frost_transparency,
            frost_thickness: p.frost_thickness,
            music: p.music.into(),
            music_volume: p.music_volume,
        }
    }
}

/// The clamps of the preferences and the document limit, as the Linux app has them.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Limits {
    pub min_size: f32,
    pub max_size: f32,
    pub default_line_height: f32,
    pub min_line_height: f32,
    pub max_line_height: f32,
    pub max_transparency: f32,
    pub max_document_bytes: u64,
}

// ---------------------------------------------------------------------------------------
// Functions

/// Statistics of `text`.
#[uniffi::export]
pub fn stats(text: String) -> Stats {
    stats_of(&text)
}

fn stats_of(text: &str) -> Stats {
    let s = hushhour_core::stats::compute(text);
    Stats {
        words: s.words as u64,
        characters: s.characters as u64,
        sentences: s.sentences as u64,
        paragraphs: s.paragraphs as u64,
        reading_seconds: s.reading_seconds,
        reading_time: s.reading_time(),
    }
}

/// The colours of a theme.
#[uniffi::export]
pub fn palette(theme: Theme) -> Palette {
    ThemeKind::from(theme).palette().into()
}

#[uniffi::export]
pub fn theme_label(theme: Theme) -> String {
    ThemeKind::from(theme).label().to_owned()
}

#[uniffi::export]
pub fn next_theme(theme: Theme) -> Theme {
    ThemeKind::from(theme).next().into()
}

/// The font family name of a choice ("IBM Plex Mono", "IBM Plex Sans", "Literata").
#[uniffi::export]
pub fn font_family(font: FontChoice) -> String {
    FontKind::from(font).family().to_owned()
}

#[uniffi::export]
pub fn font_label(font: FontChoice) -> String {
    FontKind::from(font).label().to_owned()
}

#[uniffi::export]
pub fn next_font(font: FontChoice) -> FontChoice {
    FontKind::from(font).next().into()
}

/// Average width of a character of running text in em; the measure is 66 of these.
#[uniffi::export]
pub fn font_char_em(font: FontChoice) -> f32 {
    FontKind::from(font).char_em()
}

/// The family code spans are drawn in whatever the body font.
#[uniffi::export]
pub fn mono_family() -> String {
    MONO_FAMILY.to_owned()
}

#[uniffi::export]
pub fn focus_label(focus: FocusMode) -> String {
    CoreFocus::from(focus).label().to_owned()
}

#[uniffi::export]
pub fn next_focus(focus: FocusMode) -> FocusMode {
    CoreFocus::from(focus).next().into()
}

#[uniffi::export]
pub fn sound_set_label(set: SoundSet) -> String {
    CoreSoundSet::from(set).label().to_owned()
}

#[uniffi::export]
pub fn music_label(music: MusicChoice) -> String {
    Music::from(music).label()
}

#[uniffi::export]
pub fn view_label(view: ViewMode) -> String {
    CoreViewMode::from(view).label().to_owned()
}

/// Editor → Split → Preview → Editor, as Ctrl/⌘-Shift-P cycles on Linux.
#[uniffi::export]
pub fn next_view(view: ViewMode) -> ViewMode {
    CoreViewMode::from(view).next().into()
}

// ---------------------------------------------------------------------------------------
// Animated background (`hushhour_core::backdrop`): the host draws it with a Metal port of
// the core's shader and checks the port against `backdrop_intensity`.

#[uniffi::export]
pub fn backdrop_label(backdrop: Backdrop) -> String {
    CoreBackdrop::from(backdrop).label().to_owned()
}

/// The shader's pattern number (`mode.x`): 0 for Off, then Waves 1, Swell 2, Drift 3.
#[uniffi::export]
pub fn backdrop_index(backdrop: Backdrop) -> u32 {
    CoreBackdrop::from(backdrop).pattern().map_or(0, |p| p.index())
}

/// The reference intensity at physical pixel (`x`, `y`) of a `width` × `height` page at
/// `time` seconds, `scale` the display scale. 0 for Off.
#[uniffi::export]
pub fn backdrop_intensity(backdrop: Backdrop, x: f32, y: f32, width: f32, height: f32, time: f32, scale: f32) -> f32 {
    CoreBackdrop::from(backdrop).pattern().map_or(0.0, |p| backdrop::intensity(p, x, y, width, height, time, scale))
}

/// The constants of the background, as the Linux app uses them.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct BackdropTiming {
    /// How far an intensity of 1 moves from the page colour towards the text colour.
    pub strength: f32,
    pub frame_interval_ms: u64,
    pub resting_interval_ms: u64,
    /// How far secondary text over the pattern may fade towards the page colour.
    pub max_secondary_fade: f32,
}

#[uniffi::export]
pub fn backdrop_timing() -> BackdropTiming {
    BackdropTiming {
        strength: backdrop::STRENGTH,
        frame_interval_ms: backdrop::FRAME_INTERVAL_MS,
        resting_interval_ms: backdrop::RESTING_INTERVAL_MS,
        max_secondary_fade: backdrop::MAX_SECONDARY_FADE,
    }
}

/// The animation clock, slowed by typing (`hushhour_core::backdrop::Motion`).
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct BackdropMotion {
    /// Seconds of animation; the shader's time.
    pub clock: f32,
    /// 0 = moving freely, 1 = brought to a stop by typing.
    pub calm: f32,
    /// The host may tick at the resting interval.
    pub resting: bool,
}

impl From<backdrop::Motion> for BackdropMotion {
    fn from(m: backdrop::Motion) -> Self {
        Self { clock: m.clock, calm: m.calm, resting: m.is_resting() }
    }
}

#[uniffi::export]
pub fn backdrop_motion() -> BackdropMotion {
    backdrop::Motion::default().into()
}

/// Move on by `dt` seconds of real time; `since_typed` is the time since the last edit.
#[uniffi::export]
pub fn backdrop_advance(motion: BackdropMotion, dt: f32, since_typed: Option<f32>) -> BackdropMotion {
    let mut m = backdrop::Motion { clock: motion.clock, calm: motion.calm };
    m.advance(dt, since_typed);
    m.into()
}

// ---------------------------------------------------------------------------------------
// Preview

/// What a preview block is.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum PreviewKind {
    Heading { level: u8 },
    Paragraph,
    /// Fenced or indented code; the text is in `code`.
    Code { language: Option<String> },
    Rule,
}

/// A run of uniformly styled text, or a hard line break (`is_break`, empty text).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PreviewInline {
    pub text: String,
    pub strong: bool,
    pub emphasis: bool,
    pub strike: bool,
    pub code: bool,
    /// `text` is an image's alternative text; `link` is the image address (never loaded).
    pub image: bool,
    pub link: Option<String>,
    pub is_break: bool,
}

/// One block of the preview, flattened from the core's tree for drawing in order: quotes and
/// lists become depths, a list item's first block carries its marker ("•", "3.", "☐", "☑").
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PreviewBlock {
    pub kind: PreviewKind,
    pub inlines: Vec<PreviewInline>,
    /// The text of a code block (line breaks kept); empty otherwise.
    pub code: String,
    /// Zero-based source line where the block starts, for keeping the preview level with the caret.
    pub line: u64,
    pub quote_depth: u32,
    pub list_depth: u32,
    pub marker: Option<String>,
}

fn inlines_of(inlines: &[preview::Inline]) -> Vec<PreviewInline> {
    inlines
        .iter()
        .map(|i| match i {
            preview::Inline::Text { text, style, link } => PreviewInline {
                text: text.clone(),
                strong: style.strong,
                emphasis: style.emphasis,
                strike: style.strike,
                code: style.code,
                image: style.image,
                link: link.clone(),
                is_break: false,
            },
            preview::Inline::Break => PreviewInline {
                text: String::new(),
                strong: false,
                emphasis: false,
                strike: false,
                code: false,
                image: false,
                link: None,
                is_break: true,
            },
        })
        .collect()
}

fn flatten(blocks: &[preview::Block], quote: u32, list: u32, mut marker: Option<String>, out: &mut Vec<PreviewBlock>) {
    for block in blocks {
        let line = block.line() as u64;
        let mut push = |kind: PreviewKind, inlines: Vec<PreviewInline>, code: String, marker: Option<String>| {
            out.push(PreviewBlock { kind, inlines, code, line, quote_depth: quote, list_depth: list, marker });
        };
        match block {
            preview::Block::Heading { level, inlines, .. } => push(PreviewKind::Heading { level: *level }, inlines_of(inlines), String::new(), marker.take()),
            preview::Block::Paragraph { inlines, .. } => push(PreviewKind::Paragraph, inlines_of(inlines), String::new(), marker.take()),
            preview::Block::Code { language, text, .. } => {
                push(PreviewKind::Code { language: language.clone() }, Vec::new(), text.clone(), marker.take())
            }
            preview::Block::Rule { .. } => push(PreviewKind::Rule, Vec::new(), String::new(), marker.take()),
            preview::Block::Quote { blocks, .. } => flatten(blocks, quote + 1, list, marker.take(), out),
            preview::Block::List { start, items, .. } => {
                for (index, item) in items.iter().enumerate() {
                    let item_marker = match (item.checked, start) {
                        (Some(true), _) => "☑".to_owned(),
                        (Some(false), _) => "☐".to_owned(),
                        (None, Some(n)) => format!("{}.", n + index as u64),
                        (None, None) => "•".to_owned(),
                    };
                    if item.blocks.is_empty() {
                        out.push(PreviewBlock {
                            kind: PreviewKind::Paragraph,
                            inlines: Vec::new(),
                            code: String::new(),
                            line,
                            quote_depth: quote,
                            list_depth: list + 1,
                            marker: Some(item_marker),
                        });
                    } else {
                        flatten(&item.blocks, quote, list + 1, Some(item_marker), out);
                    }
                }
            }
        }
    }
}

/// The Markdown preview of `text`, in drawing order. CommonMark with task lists and
/// strikethrough; raw HTML stays text. Linear in the input.
#[uniffi::export]
pub fn preview(text: String) -> Vec<PreviewBlock> {
    let document = preview::parse(&text);
    let mut out = Vec::new();
    flatten(&document.blocks, 0, 0, None, &mut out);
    out
}

/// The six built-in choices in menu order: Off, then the five soundscapes.
#[uniffi::export]
pub fn built_in_music() -> Vec<MusicChoice> {
    Music::BUILT_IN.iter().cloned().map(Into::into).collect()
}

/// The sentence around byte `column` of `line`, as byte offsets within the line.
#[uniffi::export]
pub fn sentence_at(line: String, column: u64) -> ByteRange {
    highlight::sentence_at(&line, column as usize).into()
}

/// Byte offset of a UTF-16 offset in `text` (on a character boundary, clamped).
#[uniffi::export]
pub fn utf16_to_utf8(text: String, offset: u64) -> u64 {
    utf16_to_byte(&text, offset as usize) as u64
}

/// UTF-16 offset of a byte offset in `text` (rounded down to a character boundary).
#[uniffi::export]
pub fn utf8_to_utf16(text: String, offset: u64) -> u64 {
    byte_to_utf16(&text, offset as usize) as u64
}

/// Colour and face for a span in a theme, with `font` as the body family.
#[uniffi::export]
pub fn style(kind: SpanKind, dim: bool, font: FontChoice, theme: Theme) -> TextStyle {
    let h = highlight::Highlight { kind: kind.into(), dim, family: FontKind::from(font).family() };
    let s = highlight::style(&h, &ThemeKind::from(theme).palette());
    TextStyle { color: s.color.into(), family: s.family.to_owned(), bold: s.bold, italic: s.italic }
}

/// Read the `key = value` text; anything missing or malformed keeps its default.
#[uniffi::export]
pub fn parse_preferences(text: String) -> Preferences {
    prefs::Preferences::parse(&text).into()
}

/// The text `parse_preferences` reads back. Music is not saved.
#[uniffi::export]
pub fn serialize_preferences(preferences: Preferences) -> String {
    prefs::Preferences::from(preferences).serialize()
}

#[uniffi::export]
pub fn default_preferences() -> Preferences {
    prefs::Preferences::default().into()
}

#[uniffi::export]
pub fn limits() -> Limits {
    Limits {
        min_size: prefs::MIN_SIZE,
        max_size: prefs::MAX_SIZE,
        default_line_height: prefs::LINE_HEIGHT,
        min_line_height: prefs::MIN_LINE_HEIGHT,
        max_line_height: prefs::MAX_LINE_HEIGHT,
        max_transparency: prefs::MAX_TRANSPARENCY,
        max_document_bytes: document::MAX_DOCUMENT_BYTES,
    }
}

/// Sample rate the mixer renders at (44100).
#[uniffi::export]
pub fn audio_rate() -> u32 {
    mixer::RATE
}

/// Channels per frame (2, interleaved).
#[uniffi::export]
pub fn audio_channels() -> u32 {
    mixer::CHANNELS as u32
}

/// Frames per block the host is expected to pull (220, 5 ms); any length works.
#[uniffi::export]
pub fn audio_block_frames() -> u32 {
    mixer::BLOCK as u32
}

/// The most frames the host should pull per render call (4096): the mixer's scratch space
/// is allocated for this up front, so the audio thread never allocates.
#[uniffi::export]
pub fn audio_max_block_frames() -> u32 {
    mixer::MAX_BLOCK as u32
}

// ---------------------------------------------------------------------------------------
// Highlighter

/// Scans lines one at a time, keeping the fence state at the start of every line, and
/// applies the spotlight. Mirror the host's lines with `reset` and `splice`; after an edit,
/// `scan` the changed lines and go on while `next_changed` says the state moved.
#[derive(uniffi::Object)]
pub struct Highlighter(Mutex<highlight::MarkdownHighlighter>);

#[uniffi::export]
impl Highlighter {
    #[uniffi::constructor]
    pub fn new(settings: HighlightSettings) -> Arc<Self> {
        Arc::new(Self(Mutex::new(highlight::MarkdownHighlighter::new(&settings.into()))))
    }

    /// New spotlight settings for later scans and renders; nothing is re-scanned.
    pub fn update(&self, settings: HighlightSettings) {
        self.lock().update(&settings.into());
    }

    /// A new text of `lines` lines, none scanned yet.
    pub fn reset(&self, lines: u64) {
        self.lock().reset(lines as usize);
    }

    /// Lines `first..first + removed` were replaced by `inserted` lines.
    pub fn splice(&self, first: u64, removed: u64, inserted: u64) {
        self.lock().splice(first as usize, removed as usize, inserted as usize);
    }

    pub fn line_count(&self) -> u64 {
        self.lock().line_count() as u64
    }

    /// Scan line `index` with the state its predecessor left and record the state after it.
    /// Never panics across the bridge: a failure inside is an error, so the host keeps the
    /// line's old look instead of trapping (the crash in build 72).
    pub fn scan(&self, index: u64, line: String) -> Result<ScanResult, HighlightError> {
        guarded(|| {
            let (spans, next_changed) = self.lock().scan(index as usize, &line);
            ScanResult { spans: spans_of(&line, spans), next_changed }
        })
    }

    /// Spans of line `index` under the current settings, recording nothing.
    pub fn spans(&self, index: u64, line: String) -> Result<Vec<Span>, HighlightError> {
        guarded(|| {
            let spans = self.lock().spans(index as usize, &line);
            spans_of(&line, spans)
        })
    }
}

impl Highlighter {
    /// The state, even after a failed scan: a panic leaves the lock poisoned, and every
    /// later call would fail with it.
    fn lock(&self) -> std::sync::MutexGuard<'_, highlight::MarkdownHighlighter> {
        self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

/// A highlighting call that failed inside the core (a bug, not an input the host can fix).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum HighlightError {
    Failed { message: String },
}

impl std::fmt::Display for HighlightError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let Self::Failed { message } = self;
        write!(f, "highlighting failed: {message}")
    }
}

impl std::error::Error for HighlightError {}

/// Run `f`, turning a panic into [`HighlightError`].
fn guarded<T>(f: impl FnOnce() -> T) -> Result<T, HighlightError> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).map_err(|panic| {
        let message = panic
            .downcast_ref::<&str>()
            .map(|s| s.to_string())
            .or_else(|| panic.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "panic".into());
        HighlightError::Failed { message }
    })
}

// ---------------------------------------------------------------------------------------
// Document

/// The document rules of `docs/document-safety.md` in autosave form. The host's text view
/// mirrors `text()`; every change goes through `edit` so the limit and the history see it.
#[derive(uniffi::Object)]
pub struct Document(Mutex<document::Document>);

#[uniffi::export]
impl Document {
    /// An empty document with the 20 MiB limit.
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self(Mutex::new(document::Document::default())))
    }

    /// An empty document with another limit (tests).
    #[uniffi::constructor]
    pub fn with_limit(size_limit: u64) -> Arc<Self> {
        Arc::new(Self(Mutex::new(document::Document::with_limit(size_limit))))
    }

    pub fn text(&self) -> String {
        self.0.lock().unwrap().text().to_owned()
    }

    /// Length of the text in UTF-8 bytes.
    pub fn byte_len(&self) -> u64 {
        self.0.lock().unwrap().text().len() as u64
    }

    pub fn is_dirty(&self) -> bool {
        self.0.lock().unwrap().is_dirty()
    }

    pub fn generation(&self) -> u64 {
        self.0.lock().unwrap().generation()
    }

    pub fn edits(&self) -> u64 {
        self.0.lock().unwrap().edits()
    }

    pub fn size_limit(&self) -> u64 {
        self.0.lock().unwrap().size_limit()
    }

    pub fn persistence(&self) -> Persistence {
        self.0.lock().unwrap().persistence().into()
    }

    /// Statistics of the current text.
    pub fn stats(&self) -> Stats {
        stats_of(self.0.lock().unwrap().text())
    }

    /// Number of lines (newlines plus one).
    pub fn line_count(&self) -> u64 {
        self.0.lock().unwrap().text().matches('\n').count() as u64 + 1
    }

    /// True when nothing would be lost by replacing or closing the document.
    pub fn may_discard(&self) -> bool {
        self.0.lock().unwrap().may_discard()
    }

    /// Replace the document with loaded (or empty) text: history cleared, clean, a new
    /// generation. CRLF is normalised. Refused past the inclusive limit, nothing changed.
    pub fn load(&self, text: String) -> Result<(), DocumentError> {
        self.0.lock().unwrap().load(&text).map_err(Into::into)
    }

    /// Replace bytes `start..end` with `insert`; the caret after is returned. Growing edits
    /// that would pass the limit are refused before anything changes.
    pub fn edit(&self, start: u64, end: u64, insert: String, caret_before: Position) -> Result<Position, DocumentError> {
        self.0
            .lock()
            .unwrap()
            .edit(start as usize..end as usize, &insert, caret_before.into())
            .map(Into::into)
            .map_err(Into::into)
    }

    /// Whether replacing UTF-16 units `start..end` with `insert` keeps the document within
    /// the limit (deletions always do): the gate the host applies before a change.
    pub fn edit_fits_utf16(&self, start: u64, end: u64, insert: String) -> bool {
        let d = self.0.lock().unwrap();
        let text = d.text();
        let s = utf16_to_byte(text, start as usize);
        let e = utf16_to_byte(text, end as usize).max(s);
        insert.is_empty() || document::edit_fits(text.len(), e - s, insert.len(), d.size_limit())
    }

    /// Replace UTF-16 units `start..end` with `insert`, as the host's text view reports it.
    /// The caret before is taken to be at `start`. Same refusals as `edit`.
    pub fn edit_utf16(&self, start: u64, end: u64, insert: String) -> Result<EditResult, DocumentError> {
        let mut d = self.0.lock().unwrap();
        let text = d.text();
        let s = utf16_to_byte(text, start as usize);
        let e = utf16_to_byte(text, end as usize);
        if e < s {
            return Err(DocumentError::BadRange);
        }
        let caret_before = position_at(text, s);
        let removed_lines = text[s..e].matches('\n').count() as u64;
        let inserted_lines = insert.matches('\n').count() as u64;
        let caret = d.edit(s..e, &insert, caret_before)?;
        Ok(EditResult {
            caret: caret.into(),
            caret_utf16: start + insert.encode_utf16().count() as u64,
            line: caret_before.line as u64,
            removed_lines,
            inserted_lines,
        })
    }

    /// Revert the newest step; the host applies the returned change to its view.
    pub fn undo(&self) -> Option<TextChange> {
        let mut d = self.0.lock().unwrap();
        d.undo().map(|c| TextChange::new(c, d.text()))
    }

    pub fn redo(&self) -> Option<TextChange> {
        let mut d = self.0.lock().unwrap();
        d.redo().map(|c| TextChange::new(c, d.text()))
    }

    /// End the current typing group, so the next edit starts a new undo step.
    pub fn end_group(&self) {
        self.0.lock().unwrap().end_group();
    }

    /// Whether there is a step to undo; for menu and shortcut state.
    pub fn can_undo(&self) -> bool {
        self.0.lock().unwrap().can_undo()
    }

    /// Whether there is a step to redo.
    pub fn can_redo(&self) -> bool {
        self.0.lock().unwrap().can_redo()
    }

    /// Begin a save: the generation and the exact text to write. `None` while another save
    /// is in flight.
    pub fn begin_save(&self) -> Option<SaveRequest> {
        self.0.lock().unwrap().begin_save().map(|r| SaveRequest { generation: r.generation, text: r.text })
    }

    /// The write finished; false (ignored) when `generation` is no longer the current one.
    pub fn save_succeeded(&self, generation: u64, text: String) -> bool {
        self.0.lock().unwrap().save_succeeded(generation, &text)
    }

    /// The write failed; surfaced through `persistence()` when it concerns this document.
    pub fn save_failed(&self, generation: u64, reason: String) {
        self.0.lock().unwrap().save_failed(generation, &reason);
    }

    /// Begin an Open request; any earlier outstanding one is superseded.
    pub fn start_request(&self) -> u64 {
        self.0.lock().unwrap().start_request()
    }

    pub fn request_failed(&self, request: u64) {
        self.0.lock().unwrap().request_failed(request);
    }

    /// A file finished loading for `request`: replaces the document when everything in it
    /// is persisted, otherwise the text waits for the save (`Deferred`).
    pub fn offer(&self, request: u64, text: String) -> Offer {
        self.0.lock().unwrap().offer(request, &text).into()
    }

    /// After a save completed: replace with the waiting text if nothing is unsaved any more.
    pub fn retry_pending(&self) -> Offer {
        self.0.lock().unwrap().retry_pending().into()
    }

    pub fn cancel_replace(&self) {
        self.0.lock().unwrap().cancel_replace();
    }

    pub fn has_pending_replace(&self) -> bool {
        self.0.lock().unwrap().has_pending_replace()
    }
}

// ---------------------------------------------------------------------------------------
// Audio

/// The one mixer the render callback pulls from. `AudioEngine::new` installs it; the audio
/// thread takes the lock only with `try_lock`, so it never waits on the UI thread (which
/// touches this only while creating an engine).
static MIXER: Mutex<Option<Mixer>> = Mutex::new(None);

/// Mix the next `frames` frames of interleaved stereo into `out` (`frames * 2` floats).
/// Called by the `AVAudioSourceNode` render block on the audio thread. Silence when no
/// engine exists or the mixer is momentarily held by the UI thread.
///
/// # Safety
/// `out` must point to at least `frames * 2` writable `f32`s.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hushhour_mixer_render(out: *mut f32, frames: u32) {
    if out.is_null() {
        return;
    }
    let len = frames as usize * mixer::CHANNELS;
    let slice = unsafe { std::slice::from_raw_parts_mut(out, len) };
    if let Ok(mut guard) = MIXER.try_lock()
        && let Some(mixer) = guard.as_mut()
    {
        mixer.render(slice);
        return;
    }
    slice.fill(0.0);
}

/// The application's side of the mixer: key sounds, music choice and volume. Creating one
/// replaces the mixer behind `hushhour_mixer_render`; keep a single instance.
#[derive(uniffi::Object)]
pub struct AudioEngine {
    controls: Mutex<mixer::Controls>,
}

#[uniffi::export]
impl AudioEngine {
    /// `bundled_dir` holds the tracks shipped with the app, `user_dir` the user's own.
    #[uniffi::constructor]
    pub fn new(bundled_dir: Option<String>, user_dir: String) -> Arc<Self> {
        let dirs = MusicDirs { bundled: bundled_dir.map(PathBuf::from), user: PathBuf::from(user_dir) };
        let (controls, mixer) = mixer::new(dirs);
        *MIXER.lock().unwrap() = Some(mixer);
        Arc::new(Self { controls: Mutex::new(controls) })
    }

    /// Play one key sound; silent volumes are dropped.
    pub fn play_key(&self, key: KeyKind, set: SoundSet, volume: f32) {
        self.controls.lock().unwrap().play(key.into(), set.into(), volume);
    }

    /// Switch the background music, cross-fading. `Off`, or a track that cannot be opened,
    /// fades the current music out.
    pub fn set_music(&self, music: MusicChoice) {
        self.controls.lock().unwrap().set_music(&music.into());
    }

    pub fn set_music_volume(&self, volume: f32) {
        self.controls.lock().unwrap().set_music_volume(volume);
    }

    /// All tracks for the picker: bundled first, then the user's.
    pub fn tracks(&self) -> Vec<MusicChoice> {
        self.controls.lock().unwrap().dirs().choices().into_iter().map(Into::into).collect()
    }

    /// True while a key sound or a music layer (fading included) is being rendered; the
    /// host may stop pulling once this has been false for a while.
    pub fn is_active(&self) -> bool {
        MIXER.try_lock().map(|g| g.as_ref().is_some_and(Mixer::is_active)).unwrap_or(true)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn records_round_trip_through_the_mirrors() {
        let p = default_preferences();
        assert_eq!(parse_preferences(serialize_preferences(p.clone())), p);
        assert_eq!(music_label(MusicChoice::Bundled { name: "Late cafe.ogg".into() }), "Late cafe");
        assert_eq!(built_in_music().len(), 6);
        assert_eq!(font_family(FontChoice::Serif), "Literata");
        assert_eq!(stats("Hello world. Again!".into()).sentences, 2);
        assert_eq!(sentence_at("One. Two three.".into(), 6), ByteRange { start: 5, end: 15 });
    }

    #[test]
    fn document_and_highlighter_objects_work_through_the_bridge() {
        let d = Document::with_limit(8);
        let caret = Position { line: 0, column: 0 };
        assert_eq!(d.edit(0, 0, "hello".into(), caret).unwrap(), Position { line: 0, column: 5 });
        assert_eq!(d.edit(5, 5, " world".into(), Position { line: 0, column: 5 }), Err(DocumentError::TooLarge { limit_bytes: 8 }));
        assert_eq!(d.persistence(), Persistence::Dirty);
        let save = d.begin_save().unwrap();
        assert!(d.save_succeeded(save.generation, save.text));
        assert_eq!(d.persistence(), Persistence::Clean);
        assert!(d.can_undo() && !d.can_redo());
        let change = d.undo().unwrap();
        assert_eq!(change.range, ByteRange { start: 0, end: 5 });
        assert_eq!(d.text(), "");
        assert!(!d.can_undo() && d.can_redo());

        let h = Highlighter::new(HighlightSettings {
            font: FontChoice::Serif,
            focus: FocusMode::Off,
            paragraph: ByteRange { start: 0, end: 1 },
            cursor_line: 0,
            sentence: ByteRange { start: 0, end: 0 },
        });
        h.reset(1);
        let result = h.scan(0, "# Tïtle".into()).unwrap();
        assert_eq!(result.spans[0].kind, SpanKind::Marker);
        let last = result.spans.last().unwrap();
        assert_eq!((last.end, last.utf16_end), (8, 7), "ï is two bytes, one unit");
        assert_eq!(h.line_count(), 1);
        let s = style(SpanKind::Code, false, FontChoice::Serif, Theme::Dark);
        assert_eq!(s.family, "IBM Plex Mono");
    }

    /// The crash in build 72: in Sentence spotlight the editor rescans an edited line before
    /// it refreshes the spotlight, so the sentence range still describes the old text. Typing
    /// one character before "’" (three bytes) moved the old end inside it.
    #[test]
    fn a_stale_sentence_range_never_panics_a_scan() {
        let line = "Il a dit café’";
        let h = Highlighter::new(HighlightSettings {
            font: FontChoice::Serif,
            focus: FocusMode::Sentence,
            paragraph: ByteRange { start: 0, end: 1 },
            cursor_line: 0,
            sentence: sentence_at(line.into(), line.len() as u64),
        });
        h.reset(1);
        let edited = format!("x{line}");
        let check = |spans: &[Span]| {
            assert!(spans.iter().all(|s| edited.is_char_boundary(s.start as usize) && edited.is_char_boundary(s.end as usize)));
            assert_eq!(spans.last().unwrap().end as usize, edited.len());
            assert_eq!(spans.last().unwrap().utf16_end as usize, edited.encode_utf16().count());
        };
        check(&h.scan(0, edited.clone()).unwrap().spans);
        check(&h.spans(0, edited.clone()).unwrap());
        // A range past the end of a shorter line, and one that is reversed.
        for sentence in [ByteRange { start: 40, end: 90 }, ByteRange { start: 9, end: 2 }] {
            h.update(HighlightSettings { sentence, ..HighlightSettings {
                font: FontChoice::Serif, focus: FocusMode::Sentence, paragraph: ByteRange { start: 0, end: 1 },
                cursor_line: 0, sentence: ByteRange { start: 0, end: 0 } } });
            check(&h.scan(0, edited.clone()).unwrap().spans);
        }
    }

    /// A failure inside is an error for the host, and the highlighter keeps working after it
    /// even though the panic poisoned its lock.
    #[test]
    fn a_panic_is_an_error_and_the_highlighter_survives_it() {
        let quiet = std::panic::take_hook();
        std::panic::set_hook(Box::new(|_| {}));
        assert_eq!(guarded(|| -> u8 { panic!("boom") }), Err(HighlightError::Failed { message: "boom".into() }));
        let h = Highlighter::new(HighlightSettings {
            font: FontChoice::Serif,
            focus: FocusMode::Off,
            paragraph: ByteRange { start: 0, end: 1 },
            cursor_line: 0,
            sentence: ByteRange { start: 0, end: 0 },
        });
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _held = h.0.lock().unwrap();
            panic!("while holding the lock");
        }));
        std::panic::set_hook(quiet);
        assert!(h.0.is_poisoned());
        h.reset(1);
        assert_eq!(h.scan(0, "# Title".into()).unwrap().spans[0].kind, SpanKind::Marker);
    }

    /// Random lines (ASCII and multibyte, Markdown punctuation included) with random, often
    /// stale, spotlight settings in every focus mode: no scan or render may panic, and every
    /// span lies on character boundaries and ends at the end of the line.
    #[test]
    fn random_lines_and_settings_never_panic_a_scan() {
        let mut seed = 0x2545_f491_4f6c_dd1du64;
        let mut next = |n: usize| {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            (seed % n as u64) as usize
        };
        let alphabet: Vec<char> = "abcdefghijklmnopqrstuvwxyz  .,;!?*_`#>-+[]()~=|\\0123456789éèà’“”—…🙂".chars().collect();
        for round in 0..20_000 {
            let ascii = round % 2 == 0;
            let len = next(40);
            let line: String = (0..len).map(|_| loop {
                let c = alphabet[next(alphabet.len())];
                if !ascii || c.is_ascii() { break c; }
            }).collect();
            let focus = [FocusMode::Off, FocusMode::Paragraph, FocusMode::Sentence][next(3)];
            let h = Highlighter::new(HighlightSettings {
                font: FontChoice::Mono,
                focus,
                paragraph: ByteRange { start: next(3) as u64, end: next(4) as u64 },
                cursor_line: next(3) as u64,
                sentence: ByteRange { start: next(50) as u64, end: next(50) as u64 },
            });
            h.reset(3);
            for index in 0..3u64 {
                for spans in [h.scan(index, line.clone()).unwrap().spans, h.spans(index, line.clone()).unwrap()] {
                    assert!(spans.iter().all(|s| line.is_char_boundary(s.start as usize) && line.is_char_boundary(s.end as usize)), "{line:?}");
                    assert!(spans.windows(2).all(|w| w[0].end <= w[1].start), "{line:?}");
                    if let Some(last) = spans.last() {
                        assert_eq!(last.utf16_end as usize, line.encode_utf16().count(), "{line:?}");
                    }
                }
            }
        }
    }

    #[test]
    fn the_preview_flattens_quotes_and_lists_and_keeps_lines_styles_and_links() {
        let text = "# Title\n\nSome *em* and [a link](https://example.com).\n\n> quoted\n\n1. one\n2. two\n\n- [x] done\n- [ ] open\n\n```rust\nfn x() {}\n```\n\n---\n\n<b>raw</b>\n";
        let blocks = preview(text.into());
        assert_eq!(blocks[0].kind, PreviewKind::Heading { level: 1 });
        assert_eq!(blocks[0].line, 0);
        let para = &blocks[1];
        assert_eq!(para.kind, PreviewKind::Paragraph);
        assert_eq!(para.line, 2);
        assert!(para.inlines.iter().any(|i| i.emphasis && i.text == "em"));
        assert!(para.inlines.iter().any(|i| i.link.as_deref() == Some("https://example.com") && i.text == "a link"));
        assert_eq!((blocks[2].quote_depth, blocks[2].line), (1, 4));
        assert_eq!((blocks[3].marker.as_deref(), blocks[3].list_depth), (Some("1."), 1));
        assert_eq!(blocks[4].marker.as_deref(), Some("2."));
        assert_eq!(blocks[5].marker.as_deref(), Some("☑"));
        assert_eq!(blocks[6].marker.as_deref(), Some("☐"));
        assert_eq!(blocks[7].kind, PreviewKind::Code { language: Some("rust".into()) });
        assert_eq!(blocks[7].code, "fn x() {}");
        assert_eq!(blocks[8].kind, PreviewKind::Rule);
        let raw = blocks.last().unwrap();
        let shown: String = raw.inlines.iter().map(|i| i.text.as_str()).collect();
        assert!(shown.contains("<b>"), "raw HTML stays text: {shown:?}");
    }

    #[test]
    /// As on Linux since v0.4.0, the view is not saved: every launch starts in the editor.
    fn view_modes_cycle_and_the_view_is_not_saved() {
        assert_eq!(next_view(ViewMode::Editor), ViewMode::Split);
        assert_eq!(next_view(ViewMode::Split), ViewMode::Preview);
        assert_eq!(next_view(ViewMode::Preview), ViewMode::Editor);
        let mut p = default_preferences();
        assert_eq!(p.view, ViewMode::Editor);
        p.view = ViewMode::Preview;
        let text = serialize_preferences(p.clone());
        assert!(!text.contains("view"), "{text}");
        assert_eq!(parse_preferences(text + "view = Preview\n").view, ViewMode::Editor);
    }

    #[test]
    fn the_background_round_trips_and_matches_the_core() {
        let mut p = default_preferences();
        assert_eq!(p.backdrop, Backdrop::Off, "off by default");
        p.backdrop = Backdrop::Waves;
        let text = serialize_preferences(p.clone());
        assert!(text.contains("background = Waves"), "{text}");
        assert_eq!(parse_preferences(text), p);
        assert_eq!([Backdrop::Off, Backdrop::Waves, Backdrop::Swell, Backdrop::Drift].map(backdrop_index), [0, 1, 2, 3]);
        assert_eq!(backdrop_intensity(Backdrop::Off, 10.0, 10.0, 100.0, 100.0, 7.0, 2.0), 0.0);
        let k = backdrop_intensity(Backdrop::Swell, 300.0, 799.0, 1280.0, 800.0, 7.0, 1.0);
        assert!((k - 1.2).abs() < 1e-4, "{k}");
        let timing = backdrop_timing();
        assert_eq!((timing.strength, timing.frame_interval_ms, timing.resting_interval_ms), (0.05, 66, 1000));
        assert_eq!(timing.max_secondary_fade, 0.21);
    }

    #[test]
    fn the_background_clock_rests_while_typing() {
        let mut m = backdrop_motion();
        assert_eq!((m.clock, m.calm, m.resting), (7.0, 0.0, false));
        for _ in 0..24 {
            m = backdrop_advance(m, 0.066, Some(0.1));
        }
        assert!(m.resting);
        let still = backdrop_advance(m, 0.066, Some(0.1));
        assert_eq!(still.clock, m.clock, "exactly still at rest");
        for _ in 0..70 {
            m = backdrop_advance(m, 0.066, None);
        }
        assert!(!m.resting && m.calm < 0.05);
    }

    #[test]
    fn utf16_edits_report_lines_and_offsets_the_host_can_apply() {
        let d = Document::new();
        // "a😀\nb": the emoji is one char, four bytes, two UTF-16 units.
        let r = d.edit_utf16(0, 0, "a😀\nb".into()).unwrap();
        assert_eq!((r.caret_utf16, r.line, r.removed_lines, r.inserted_lines), (5, 0, 0, 1));
        assert_eq!(d.line_count(), 2);
        assert_eq!(utf16_to_utf8("a😀\nb".into(), 3), 5);
        assert_eq!(utf16_to_utf8("a😀\nb".into(), 2), 1, "inside the pair maps to the start of the character");
        assert_eq!(utf8_to_utf16("a😀\nb".into(), 6), 4);
        // Replace the second line through UTF-16 units.
        d.end_group();
        let r = d.edit_utf16(4, 5, "cd\ne".into()).unwrap();
        assert_eq!((r.caret_utf16, r.line, r.removed_lines, r.inserted_lines), (8, 1, 0, 1));
        assert_eq!(d.text(), "a😀\ncd\ne");
        let change = d.undo().unwrap();
        assert_eq!(d.text(), "a😀\nb");
        assert_eq!(change.utf16, ByteRange { start: 4, end: 8 });
        assert_eq!(change.text, "b");
        assert_eq!((change.line, change.removed_lines, change.inserted_lines), (1, 1, 0));
        assert_eq!(change.caret_utf16, 4);
        assert_eq!(d.stats().words, 2);
        assert_eq!(d.edit_utf16(3, 2, "x".into()), Err(DocumentError::BadRange));
        let small = Document::with_limit(8);
        small.edit_utf16(0, 0, "a😀bc".into()).unwrap();
        assert!(!small.edit_fits_utf16(5, 5, "xy".into()), "7 bytes + 2 > 8");
        assert!(small.edit_fits_utf16(1, 3, "xyz".into()), "replacing the emoji (4 bytes) by 3 fits");
        assert!(small.edit_fits_utf16(0, 5, "".into()));
    }

    #[test]
    fn the_render_callback_is_silent_without_an_engine_and_mixes_with_one() {
        let mut block = vec![1.0f32; 220 * 2];
        unsafe { hushhour_mixer_render(block.as_mut_ptr(), 220) };
        assert!(block.iter().all(|s| *s == 0.0));
        let engine = AudioEngine::new(None, "/nonexistent".into());
        engine.play_key(KeyKind::Character, SoundSet::Soft, 1.0);
        let mut heard = false;
        for _ in 0..50 {
            unsafe { hushhour_mixer_render(block.as_mut_ptr(), 220) };
            heard |= block.iter().any(|s| *s != 0.0);
        }
        assert!(heard);
        unsafe { hushhour_mixer_render(std::ptr::null_mut(), 220) };
    }
}
