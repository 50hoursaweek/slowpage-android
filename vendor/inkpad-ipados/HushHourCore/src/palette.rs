//! Light, sepia and dark colour schemes for the page.
//!
//! The values of `src/palette.rs` in the Linux app (HushHour v0.2.2, 1607492), which stays
//! outside the shared crate because it is iced colours there; here they are plain [`Rgb`] in a
//! [`Palette`]. Keep in step when Ink changes a colour.

/// A colour with components in `0.0..=1.0`, like iced's `Color` without alpha.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct Rgb {
    pub r: f32,
    pub g: f32,
    pub b: f32,
}

/// The six colours a theme is made of, as in iced's `Palette`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Palette {
    pub background: Rgb,
    pub text: Rgb,
    pub primary: Rgb,
    pub success: Rgb,
    pub warning: Rgb,
    pub danger: Rgb,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ThemeKind {
    #[default]
    Light,
    Sepia,
    Dark,
}

impl ThemeKind {
    pub const ALL: [Self; 3] = [Self::Light, Self::Sepia, Self::Dark];

    pub fn next(self) -> Self {
        match self {
            Self::Light => Self::Sepia,
            Self::Sepia => Self::Dark,
            Self::Dark => Self::Light,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Light => "Light",
            Self::Sepia => "Sepia",
            Self::Dark => "Dark",
        }
    }

    pub fn palette(self) -> Palette {
        match self {
            Self::Light => Palette {
                background: rgb(0xF7, 0xF7, 0xF7),
                text: rgb(0x1A, 0x1A, 0x1A),
                primary: rgb(0x1D, 0x99, 0xF3),
                success: rgb(0x2E, 0x7D, 0x32),
                warning: rgb(0xED, 0x6C, 0x02),
                danger: rgb(0xD3, 0x2F, 0x2F),
            },
            Self::Sepia => Palette {
                background: rgb(0xF4, 0xEC, 0xD8),
                text: rgb(0x3B, 0x30, 0x24),
                primary: rgb(0xB0, 0x6B, 0x2A),
                success: rgb(0x5B, 0x7A, 0x3A),
                warning: rgb(0xB8, 0x6E, 0x1E),
                danger: rgb(0xA8, 0x3A, 0x2E),
            },
            Self::Dark => Palette {
                background: rgb(0x1B, 0x1B, 0x1B),
                text: rgb(0xCC, 0xCC, 0xCC),
                primary: rgb(0x1D, 0x99, 0xF3),
                success: rgb(0x66, 0xBB, 0x6A),
                warning: rgb(0xFF, 0xA7, 0x26),
                danger: rgb(0xEF, 0x53, 0x50),
            },
        }
    }
}

impl std::fmt::Display for ThemeKind {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.label())
    }
}

const fn rgb(r: u8, g: u8, b: u8) -> Rgb {
    Rgb { r: r as f32 / 255.0, g: g as f32 / 255.0, b: b as f32 / 255.0 }
}

/// Blend `fg` towards `bg` by `t` (0 = fg, 1 = bg). Used for dimmed text and markers.
pub fn mix(fg: Rgb, bg: Rgb, t: f32) -> Rgb {
    Rgb { r: fg.r + (bg.r - fg.r) * t, g: fg.g + (bg.g - fg.g) * t, b: fg.b + (bg.b - fg.b) * t }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mix_endpoints_and_midpoint() {
        let (a, b) = (rgb(0, 0, 0), rgb(255, 255, 255));
        assert_eq!(mix(a, b, 0.0), a);
        assert_eq!(mix(a, b, 1.0), b);
        assert!((mix(a, b, 0.5).r - 0.5).abs() < 1e-6);
    }

    #[test]
    fn themes_differ_and_cycle() {
        assert_ne!(ThemeKind::Light.palette(), ThemeKind::Dark.palette());
        assert_eq!(ThemeKind::Light.next().next().next(), ThemeKind::Light);
    }
}
