//! User preferences and their `key = value` text format.
//!
//! Mirrors `src/prefs.rs` of the Linux app (HushHour v0.4.2, 88c40d1), which stays outside the
//! shared crate: the schema, defaults, clamps and the parse/serialise round trip are the same
//! text; the Linux file paths, the iced `Font` and the disk I/O are not here. The host stores
//! the serialised text in a state file. Keep in step when Ink changes the schema.

use std::fmt;

use crate::highlight::FocusMode;
use crate::palette::ThemeKind;
use hushhour_core::backdrop::Pattern;
use hushhour_core::music::Music;

pub const MIN_SIZE: f32 = 12.0;
pub const MAX_SIZE: f32 = 40.0;
/// Default body line height, relative to the font size.
pub const LINE_HEIGHT: f32 = 1.55;
pub const MIN_LINE_HEIGHT: f32 = 1.2;
/// The page never goes fully invisible.
pub const MAX_TRANSPARENCY: f32 = 0.9;
pub const MAX_LINE_HEIGHT: f32 = 2.2;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum FontKind {
    #[default]
    Mono,
    Sans,
    Serif,
}

impl FontKind {
    pub const ALL: [Self; 3] = [Self::Mono, Self::Sans, Self::Serif];

    pub fn family(self) -> &'static str {
        match self {
            Self::Mono => "IBM Plex Mono",
            Self::Sans => "IBM Plex Sans",
            Self::Serif => "Literata",
        }
    }

    pub fn next(self) -> Self {
        match self {
            Self::Mono => Self::Sans,
            Self::Sans => Self::Serif,
            Self::Serif => Self::Mono,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Mono => "Mono",
            Self::Sans => "Sans",
            Self::Serif => "Serif",
        }
    }

    /// Average width of a character of running text in em, measured from the
    /// font files; the column is 66 of these wide.
    pub fn char_em(self) -> f32 {
        match self {
            Self::Mono => 0.6,
            Self::Sans => 0.5,
            Self::Serif => 0.5,
        }
    }
}

impl fmt::Display for FontKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

/// Which set of key samples to play.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum SoundSet {
    #[default]
    Soft,
    Keyboard,
}

impl SoundSet {
    pub const ALL: [Self; 2] = [Self::Soft, Self::Keyboard];

    pub fn label(self) -> &'static str {
        match self {
            Self::Soft => "Soft",
            Self::Keyboard => "Keyboard",
        }
    }
}

impl fmt::Display for SoundSet {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

/// The animated background behind the page (`background` in the file).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Backdrop {
    #[default]
    Off,
    Waves,
    Swell,
    Drift,
}

impl Backdrop {
    pub const ALL: [Self; 4] = [Self::Off, Self::Waves, Self::Swell, Self::Drift];

    pub fn label(self) -> &'static str {
        match self {
            Self::Off => "Off",
            Self::Waves => "Waves",
            Self::Swell => "Swell",
            Self::Drift => "Drift",
        }
    }

    pub fn pattern(self) -> Option<Pattern> {
        match self {
            Self::Off => None,
            Self::Waves => Some(Pattern::Waves),
            Self::Swell => Some(Pattern::Swell),
            Self::Drift => Some(Pattern::Drift),
        }
    }
}

impl fmt::Display for Backdrop {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

/// What the window shows: the editor, the editor beside a live preview, or the preview alone.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ViewMode {
    #[default]
    Editor,
    Split,
    Preview,
}

impl ViewMode {
    pub const ALL: [Self; 3] = [Self::Editor, Self::Split, Self::Preview];

    pub fn label(self) -> &'static str {
        match self {
            Self::Editor => "Editor",
            Self::Split => "Split",
            Self::Preview => "Preview",
        }
    }

    pub fn next(self) -> Self {
        match self {
            Self::Editor => Self::Split,
            Self::Split => Self::Preview,
            Self::Preview => Self::Editor,
        }
    }

    pub fn shows_editor(self) -> bool {
        self != Self::Preview
    }

    pub fn shows_preview(self) -> bool {
        self != Self::Editor
    }
}

impl fmt::Display for ViewMode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

/// Everything the user can change from the menu.
#[derive(Debug, Clone, PartialEq)]
pub struct Preferences {
    pub theme: ThemeKind,
    pub font: FontKind,
    pub size: f32,
    pub line_height: f32,
    pub focus: FocusMode,
    pub typewriter: bool,
    /// Not saved: every launch starts in the editor.
    pub view: ViewMode,
    pub typing_sound: bool,
    pub sound_set: SoundSet,
    /// 0..=1.
    pub sound_volume: f32,
    /// Show the welcome dialog at launch until the user opts out.
    pub show_welcome: bool,
    pub frost: bool,
    pub backdrop: Backdrop,
    /// 0..=0.9: how see-through the page is when frosted.
    pub frost_transparency: f32,
    /// 0..=1: how heavily the backdrop is frosted, where the compositor can.
    pub frost_thickness: f32,
    /// Not saved: the app always starts silent.
    pub music: Music,
    /// 0..=1.
    pub music_volume: f32,
}

impl Default for Preferences {
    fn default() -> Self {
        Self {
            theme: ThemeKind::Light,
            font: FontKind::Mono,
            size: 18.0,
            line_height: LINE_HEIGHT,
            focus: FocusMode::Off,
            typewriter: false,
            view: ViewMode::Editor,
            typing_sound: true,
            sound_set: SoundSet::Soft,
            sound_volume: 0.6,
            show_welcome: true,
            frost: false,
            backdrop: Backdrop::Off,
            frost_transparency: 0.35,
            frost_thickness: 0.6,
            music: Music::Off,
            music_volume: 0.5,
        }
    }
}

impl Preferences {
    /// Read the `key = value` text, falling back to defaults for anything missing or malformed.
    pub fn parse(text: &str) -> Self {
        let mut prefs = Self::default();
        for line in text.lines() {
            let Some((key, value)) = line.split_once('=') else { continue };
            let value = value.trim();
            match key.trim() {
                "theme" => prefs.theme = pick(&[ThemeKind::Light, ThemeKind::Sepia, ThemeKind::Dark], value).unwrap_or(prefs.theme),
                "font" => prefs.font = pick(&FontKind::ALL, value).unwrap_or(prefs.font),
                "size" => prefs.size = parse_clamped(value, MIN_SIZE, MAX_SIZE).unwrap_or(prefs.size),
                "line_height" => {
                    prefs.line_height = parse_clamped(value, MIN_LINE_HEIGHT, MAX_LINE_HEIGHT).unwrap_or(prefs.line_height)
                }
                "focus" => {
                    prefs.focus = pick(&[FocusMode::Off, FocusMode::Paragraph, FocusMode::Sentence], value)
                        .unwrap_or(prefs.focus)
                }
                "typewriter" => prefs.typewriter = parse_bool(value).unwrap_or(prefs.typewriter),
                "typing_sound" => prefs.typing_sound = parse_bool(value).unwrap_or(prefs.typing_sound),
                "sound_set" => prefs.sound_set = pick(&SoundSet::ALL, value).unwrap_or(prefs.sound_set),
                "sound_volume" => prefs.sound_volume = parse_clamped(value, 0.0, 1.0).unwrap_or(prefs.sound_volume),
                "music_volume" => prefs.music_volume = parse_clamped(value, 0.0, 1.0).unwrap_or(prefs.music_volume),
                "show_welcome" => prefs.show_welcome = parse_bool(value).unwrap_or(prefs.show_welcome),
                "frost" => prefs.frost = parse_bool(value).unwrap_or(prefs.frost),
                "background" => prefs.backdrop = pick(&Backdrop::ALL, value).unwrap_or(prefs.backdrop),
                "frost_transparency" => prefs.frost_transparency = parse_clamped(value, 0.0, MAX_TRANSPARENCY).unwrap_or(prefs.frost_transparency),
                "frost_thickness" => prefs.frost_thickness = parse_clamped(value, 0.0, 1.0).unwrap_or(prefs.frost_thickness),
                _ => {}
            }
        }
        prefs
    }

    /// The text `parse` reads back; `music` and `view` are not saved (the app always starts
    /// silent, in the editor).
    pub fn serialize(&self) -> String {
        format!(
            "theme = {}\nfont = {}\nsize = {}\nline_height = {:.2}\nfocus = {}\ntypewriter = {}\ntyping_sound = {}\nsound_set = {}\nsound_volume = {:.2}\nmusic_volume = {:.2}\nshow_welcome = {}\nfrost = {}\nbackground = {}\nfrost_transparency = {:.2}\nfrost_thickness = {:.2}\n",
            self.theme.label(),
            self.font.label(),
            self.size,
            self.line_height,
            self.focus.label(),
            self.typewriter,
            self.typing_sound,
            self.sound_set.label(),
            self.sound_volume,
            self.music_volume,
            self.show_welcome,
            self.frost,
            self.backdrop.label(),
            self.frost_transparency,
            self.frost_thickness,
        )
    }
}

/// `true`/`false`; anything else is treated as absent so the default applies.
fn parse_bool(value: &str) -> Option<bool> {
    match value {
        "true" => Some(true),
        "false" => Some(false),
        _ => None,
    }
}

/// Match `value` against the labels of `options`, case-insensitively.
fn pick<T: Copy + fmt::Display>(options: &[T], value: &str) -> Option<T> {
    options.iter().copied().find(|o| o.to_string().eq_ignore_ascii_case(value))
}

fn parse_clamped(value: &str, min: f32, max: f32) -> Option<f32> {
    let v: f32 = value.parse().ok()?;
    v.is_finite().then(|| v.clamp(min, max))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let prefs = Preferences {
            theme: ThemeKind::Dark,
            font: FontKind::Serif,
            size: 21.0,
            line_height: 1.8,
            focus: FocusMode::Sentence,
            typewriter: true,
            view: ViewMode::Editor,
            typing_sound: false,
            sound_set: SoundSet::Keyboard,
            sound_volume: 0.25,
            show_welcome: false,
            frost: true,
            backdrop: Backdrop::Swell,
            frost_transparency: 0.5,
            frost_thickness: 0.25,
            music: Music::Off,
            music_volume: 0.35,
        };
        assert_eq!(Preferences::parse(&prefs.serialize()), prefs);
    }

    #[test]
    fn malformed_values_fall_back_to_defaults() {
        let text = "theme = neon\nsize = huge\nline_height = 9\nfocus=paragraph\nbogus = 1\ntyping_sound = yes\n";
        let prefs = Preferences::parse(text);
        assert_eq!(prefs.theme, ThemeKind::Light);
        assert!(prefs.typing_sound, "a malformed boolean must keep the default");
        assert_eq!(prefs.size, 18.0);
        assert_eq!(prefs.line_height, MAX_LINE_HEIGHT);
        assert_eq!(prefs.focus, FocusMode::Paragraph);
    }

    #[test]
    fn the_view_is_not_saved_and_the_background_is() {
        let mut prefs = Preferences { backdrop: Backdrop::Waves, ..Preferences::default() };
        prefs.view = ViewMode::Preview;
        let text = prefs.serialize();
        assert!(!text.contains("view"), "{text}");
        assert!(text.contains("background = Waves\n"), "{text}");
        let back = Preferences::parse(&format!("{text}view = Preview\n"));
        assert_eq!(back.view, ViewMode::Editor, "an old file's view is ignored");
        assert_eq!(back.backdrop, Backdrop::Waves);
        assert_eq!(Preferences::parse("background = drift").backdrop, Backdrop::Drift);
        assert_eq!(Preferences::parse("background = sparkles").backdrop, Backdrop::Off);
    }
}
