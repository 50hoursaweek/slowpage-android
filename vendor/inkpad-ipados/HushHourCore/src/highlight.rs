//! The spotlight layer over the shared Markdown scanner: which spans are dimmed (everything
//! but the current paragraph or sentence), and colour and face for each span. The Linux app
//! does the same in its `src/highlight.rs` for iced, which re-scans from the first changed
//! line down. Here the host edits lines in place, so the fence state at the start of every
//! line is kept and spliced as lines come and go; a change is re-scanned only until the
//! states converge again. `Kind`, `tokenize`, `split_at` and `sentence_at` come from
//! `hushhour-core`.

use std::ops::Range;

pub use hushhour_core::markdown::{Kind, sentence_at};
use hushhour_core::markdown::{Fence, split_at, tokenize};

use crate::palette::{Palette, Rgb, mix};

pub const MONO_FAMILY: &str = "IBM Plex Mono";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum FocusMode {
    #[default]
    Off,
    Paragraph,
    Sentence,
}

impl FocusMode {
    pub const ALL: [Self; 3] = [Self::Off, Self::Paragraph, Self::Sentence];

    pub fn next(self) -> Self {
        match self {
            Self::Off => Self::Paragraph,
            Self::Paragraph => Self::Sentence,
            Self::Sentence => Self::Off,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Off => "Off",
            Self::Paragraph => "Paragraph",
            Self::Sentence => "Sentence",
        }
    }
}

impl std::fmt::Display for FocusMode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.label())
    }
}

/// Everything the highlighter needs to know about the editor state.
#[derive(Debug, Clone, PartialEq)]
pub struct Settings {
    pub family: &'static str,
    pub focus: FocusMode,
    /// Lines belonging to the paragraph under the cursor.
    pub paragraph: Range<usize>,
    pub cursor_line: usize,
    /// Byte range of the sentence under the cursor, within `cursor_line`.
    pub sentence: Range<usize>,
}

impl Default for Settings {
    fn default() -> Self {
        Self { family: MONO_FAMILY, focus: FocusMode::Off, paragraph: 0..1, cursor_line: 0, sentence: 0..0 }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Highlight {
    pub kind: Kind,
    pub dim: bool,
    pub family: &'static str,
}

/// The fence state at the start of a line: unknown until the line before it was scanned.
type State = Option<Option<Fence>>;

/// Scans lines one at a time with the fence state their predecessor left, and applies the
/// spotlight. The host owns the line texts; this keeps one state per line.
#[derive(Debug)]
pub struct MarkdownHighlighter {
    settings: Settings,
    /// `states[i]` is the state at the start of line `i`; the last entry is the state after
    /// the last line. Line 0 always starts outside a fence.
    states: Vec<State>,
}

impl MarkdownHighlighter {
    pub fn new(settings: &Settings) -> Self {
        Self { settings: settings.clone(), states: vec![Some(None)] }
    }

    /// New spotlight settings: later scans use them. Nothing is re-scanned; the host
    /// re-renders the lines whose dimming changed.
    pub fn update(&mut self, new_settings: &Settings) {
        self.settings = new_settings.clone();
    }

    pub fn settings(&self) -> &Settings {
        &self.settings
    }

    pub fn line_count(&self) -> usize {
        self.states.len() - 1
    }

    /// A new text of `lines` lines, none scanned yet.
    pub fn reset(&mut self, lines: usize) {
        self.states = vec![None; lines + 1];
        self.states[0] = Some(None);
    }

    /// Lines `first..first + removed` were replaced by `inserted` lines. The state at the
    /// start of `first` still holds; the states of the new lines are unknown until scanned,
    /// and the state after them is kept for [`Self::scan`] to compare against.
    pub fn splice(&mut self, first: usize, removed: usize, inserted: usize) {
        let first = first.min(self.line_count());
        let removed = removed.min(self.line_count() - first);
        self.states.splice(first + 1..first + 1 + removed, std::iter::repeat_n(None, inserted));
    }

    fn state_at(&self, index: usize) -> Option<Fence> {
        self.states.get(index).copied().flatten().unwrap_or(None)
    }

    /// Scan line `index` (whose predecessors were scanned), record the state it leaves and
    /// say whether that differs from what the next line was last scanned with: while it
    /// does, the host must scan on, because the following lines render differently now.
    pub fn scan(&mut self, index: usize, line: &str) -> (Vec<(Range<usize>, Highlight)>, bool) {
        let mut state = self.state_at(index);
        let spans = tokenize(line, &mut state);
        let next = index + 1;
        if next >= self.states.len() {
            self.states.resize(next + 1, None);
        }
        let changed = self.states[next] != Some(state);
        self.states[next] = Some(state);
        (self.decorate(index, line, spans), changed)
    }

    /// Spans of line `index` under the current settings, without recording anything: for
    /// re-rendering a line whose text did not change.
    pub fn spans(&self, index: usize, line: &str) -> Vec<(Range<usize>, Highlight)> {
        let mut state = self.state_at(index);
        let spans = tokenize(line, &mut state);
        self.decorate(index, line, spans)
    }

    fn decorate(&self, index: usize, line: &str, mut spans: Vec<(Range<usize>, Kind)>) -> Vec<(Range<usize>, Highlight)> {
        let family = self.settings.family;
        let dim_whole = match self.settings.focus {
            FocusMode::Off => false,
            FocusMode::Paragraph => !self.settings.paragraph.contains(&index),
            FocusMode::Sentence => index != self.settings.cursor_line,
        };

        if self.settings.focus == FocusMode::Sentence && index == self.settings.cursor_line {
            // The range can describe the line before an edit the host has not yet followed
            // with new settings (crash in iPad build 72): keep it inside this line, ordered
            // and on character boundaries, so no span can cut a character.
            let start = floor_char_boundary(line, self.settings.sentence.start);
            let s = start..floor_char_boundary(line, self.settings.sentence.end).max(start);
            spans = split_at(spans, &[s.start, s.end]);
            return spans
                .into_iter()
                .map(|(r, kind)| {
                    let inside = r.start >= s.start && r.end <= s.end;
                    (r, Highlight { kind, dim: !inside, family })
                })
                .collect();
        }

        spans.into_iter().map(|(r, kind)| (r, Highlight { kind, dim: dim_whole, family })).collect()
    }
}

/// The largest character boundary of `line` at or before `index` (the line's end if past it).
fn floor_char_boundary(line: &str, index: usize) -> usize {
    let mut i = index.min(line.len());
    while !line.is_char_boundary(i) {
        i -= 1;
    }
    i
}

/// How a highlighted span is drawn: colour, and which face of which family.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Style {
    pub color: Rgb,
    /// Code spans use the mono family whatever the body font.
    pub family: &'static str,
    pub bold: bool,
    pub italic: bool,
}

/// Map a highlight to colour and face for the theme's palette. Same mixes as the Linux app.
pub fn style(h: &Highlight, p: &Palette) -> Style {
    let (bg, fg) = (p.background, p.text);

    let base = match h.kind {
        Kind::Marker | Kind::ListMarker => mix(fg, bg, 0.55),
        Kind::Url => mix(fg, bg, 0.45),
        Kind::Link => p.primary,
        _ => fg,
    };
    let color = if h.dim { mix(base, bg, 0.72) } else { base };

    let family = match h.kind {
        Kind::Code | Kind::CodeBlock => MONO_FAMILY,
        _ => h.family,
    };
    let bold = matches!(h.kind, Kind::Heading | Kind::Strong | Kind::StrongEmphasis);
    let italic = matches!(h.kind, Kind::Emphasis | Kind::StrongEmphasis);

    Style { color, family, bold, italic }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn first_kind(h: &mut MarkdownHighlighter, index: usize, line: &str) -> (Option<Kind>, bool) {
        let (spans, changed) = h.scan(index, line);
        (spans.first().map(|(_, hl)| hl.kind), changed)
    }

    #[test]
    fn fence_state_is_kept_per_line_and_scanning_stops_when_it_converges() {
        let mut h = MarkdownHighlighter::new(&Settings::default());
        let lines = ["```rust", "let x = 1;", "```", "after", "~~~", "inside tilde", "```", "still inside"];
        h.reset(lines.len());
        let kinds: Vec<_> = lines.iter().enumerate().map(|(i, l)| first_kind(&mut h, i, l).0).collect();
        assert_eq!(kinds[1], Some(Kind::CodeBlock));
        assert_eq!(kinds[3], Some(Kind::Text));
        assert_eq!(kinds[7], Some(Kind::CodeBlock), "a backtick fence does not close a tilde block");
        assert_eq!(h.line_count(), lines.len());

        // Re-scanning an unchanged line changes nothing after it.
        assert_eq!(first_kind(&mut h, 3, "after"), (Some(Kind::Text), false));
        assert_eq!(h.spans(5, "inside tilde")[0].1.kind, Kind::CodeBlock);

        // Inserting a plain line: the line itself is new (nothing recorded after it), the
        // line after it scans the same as before, so scanning stops there.
        h.splice(4, 0, 1);
        assert_eq!(h.line_count(), lines.len() + 1);
        assert_eq!(first_kind(&mut h, 4, "plain"), (Some(Kind::Text), true));
        assert_eq!(first_kind(&mut h, 5, "~~~"), (Some(Kind::Marker), false));

        // Deleting the opening fence changes every line down to the next fence and beyond.
        h.splice(0, 1, 0);
        assert_eq!(first_kind(&mut h, 0, "let x = 1;"), (Some(Kind::Text), true));
        let (spans, changed) = h.scan(1, "```");
        assert_eq!(spans[0].1.kind, Kind::Marker);
        assert!(changed, "now an opening fence");
        assert_eq!(first_kind(&mut h, 2, "after"), (Some(Kind::CodeBlock), true));
    }

    #[test]
    fn paragraph_focus_dims_other_lines_whole() {
        let settings = Settings { focus: FocusMode::Paragraph, paragraph: 1..3, ..Settings::default() };
        let mut h = MarkdownHighlighter::new(&settings);
        h.reset(4);
        assert!(h.scan(0, "# Title").0.iter().all(|(_, hl)| hl.dim));
        assert!(h.scan(1, "in the paragraph").0.iter().all(|(_, hl)| !hl.dim));
        assert!(h.scan(2, "still in").0.iter().all(|(_, hl)| !hl.dim));
        assert!(h.scan(3, "out again").0.iter().all(|(_, hl)| hl.dim));
    }

    #[test]
    fn spotlight_dims_outside_the_sentence() {
        let line = "First one. Second here! Third?";
        let sentence = sentence_at(line, 12);
        assert_eq!(sentence, 11..23);
        let settings = Settings { focus: FocusMode::Sentence, cursor_line: 0, sentence, ..Settings::default() };
        let mut h = MarkdownHighlighter::new(&settings);
        h.reset(2);
        let (spans, _) = h.scan(0, line);
        assert!(spans.iter().all(|(r, hl)| hl.dim == !(r.start >= 11 && r.end <= 23)), "{spans:?}");
        assert!(spans.iter().any(|(_, hl)| !hl.dim) && spans.iter().any(|(_, hl)| hl.dim));
        // Other lines are dimmed whole; new settings apply to the next render of a line.
        assert!(h.scan(1, "Another line.").0.iter().all(|(_, hl)| hl.dim));
        h.update(&Settings::default());
        assert!(h.spans(1, "Another line.").iter().all(|(_, hl)| !hl.dim));
    }

    #[test]
    fn style_uses_mono_for_code_and_dims_towards_the_background() {
        let p = crate::palette::ThemeKind::Light.palette();
        let code = style(&Highlight { kind: Kind::Code, dim: false, family: "Literata" }, &p);
        assert_eq!(code.family, MONO_FAMILY);
        let plain = style(&Highlight { kind: Kind::Text, dim: false, family: "Literata" }, &p);
        let dimmed = style(&Highlight { kind: Kind::Text, dim: true, family: "Literata" }, &p);
        assert_eq!(plain.family, "Literata");
        assert_eq!(plain.color, p.text);
        assert!(dimmed.color.r > plain.color.r, "dimmed text moves towards the light background");
        assert!(style(&Highlight { kind: Kind::Heading, dim: false, family: "Literata" }, &p).bold);
        let both = style(&Highlight { kind: Kind::StrongEmphasis, dim: false, family: "Literata" }, &p);
        assert!(both.bold && both.italic);
        assert_eq!(style(&Highlight { kind: Kind::Link, dim: false, family: "Literata" }, &p).color, p.primary);
    }
}
