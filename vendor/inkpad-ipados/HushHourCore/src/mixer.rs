//! Key sounds and background music, mixed into 44.1 kHz stereo blocks.
//!
//! The Linux app keeps its mixer in `src/sound.rs`, tied to its ALSA loop; this is the same
//! design without a device (voices, music layers with their fades, the sample table and the
//! WAV decoder follow `src/sound.rs` of HushHour v0.2.2). On iPadOS the host's audio callback
//! (an `AVAudioSourceNode`) pulls each block with [`Mixer::render`]. Commands travel from
//! [`Controls`] over a channel, so the render side never waits on the UI thread, and sources
//! are built on the caller's side (`FileSource::open` touches the file system and spawns its
//! decoder thread).

use std::sync::mpsc::{self, Receiver, Sender, TryRecvError};

use hushhour_core::music::{Music, MusicDirs, Source};
use hushhour_core::rng::Rng;

use crate::prefs::SoundSet;

pub use hushhour_core::dsp::RATE;
pub const CHANNELS: usize = 2;
/// Frames per block the host is expected to pull: 5 ms. `render` accepts any length.
pub const BLOCK: usize = 220;
/// The largest block the host should pull per call: the music scratch buffer is allocated
/// for this many frames up front, so a render never allocates on the audio thread.
pub const MAX_BLOCK: usize = 4096;

/// The key samples for each [`SoundSet`], in the order of `SoundSet::ALL`.
/// CC0 recordings from unicaegames' Keyboard Soundpack #1 (see assets/sounds/README.md).
const SAMPLES: [&[&[u8]]; 2] = [
    &[
        include_bytes!("../assets/sounds/soft/key1.wav"),
        include_bytes!("../assets/sounds/soft/key2.wav"),
        include_bytes!("../assets/sounds/soft/key3.wav"),
        include_bytes!("../assets/sounds/soft/key4.wav"),
        include_bytes!("../assets/sounds/soft/key5.wav"),
        include_bytes!("../assets/sounds/soft/key6.wav"),
    ],
    &[
        include_bytes!("../assets/sounds/keyboard/key1.wav"),
        include_bytes!("../assets/sounds/keyboard/key2.wav"),
        include_bytes!("../assets/sounds/keyboard/key3.wav"),
        include_bytes!("../assets/sounds/keyboard/key4.wav"),
        include_bytes!("../assets/sounds/keyboard/key5.wav"),
        include_bytes!("../assets/sounds/keyboard/key6.wav"),
        include_bytes!("../assets/sounds/keyboard/key7.wav"),
        include_bytes!("../assets/sounds/keyboard/key8.wav"),
    ],
];

fn set_index(set: SoundSet) -> usize {
    SoundSet::ALL.iter().position(|s| *s == set).unwrap_or(0)
}

/// Decode a 16-bit PCM WAV (mono or stereo) into mono f32 samples in -1..=1.
fn decode_wav(bytes: &[u8]) -> Vec<f32> {
    let u16_at = |i: usize| u16::from_le_bytes([bytes[i], bytes[i + 1]]);
    let u32_at = |i: usize| u32::from_le_bytes([bytes[i], bytes[i + 1], bytes[i + 2], bytes[i + 3]]) as usize;
    let mut channels = 1usize;
    let mut i = 12;
    while i + 8 <= bytes.len() {
        let id = &bytes[i..i + 4];
        let len = u32_at(i + 4);
        let body = i + 8;
        if id == b"fmt " {
            channels = u16_at(body + 2).max(1) as usize;
        } else if id == b"data" {
            let end = (body + len).min(bytes.len());
            return bytes[body..end]
                .chunks_exact(2 * channels)
                .map(|frame| {
                    frame.as_chunks::<2>().0.iter().map(|s| i16::from_le_bytes(*s) as f32 / 32768.0).sum::<f32>()
                        / channels as f32
                })
                .collect();
        }
        i = body + len + (len & 1);
    }
    Vec::new()
}

/// What kind of key was pressed; each gets a slightly different sound.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Key {
    Character,
    Enter,
    Backspace,
}

struct Play {
    key: Key,
    set: SoundSet,
    /// 0..=1, as set in the preferences.
    volume: f32,
}

enum Command {
    Key(Play),
    /// A new music source, or `None` to fade everything out.
    Music(Option<Box<dyn Source>>),
    MusicVolume(f32),
}

/// One key sample being played.
struct Voice {
    set: usize,
    sample: usize,
    /// Position in the sample, in frames; fractional because of the rate variation.
    position: f32,
    rate: f32,
    gain: f32,
}

impl Voice {
    fn finished(&self, samples: &[Vec<Vec<f32>>]) -> bool {
        self.position as usize + 1 >= samples[self.set][self.sample].len()
    }
}

/// A music source with its current gain, ramped per block for click-free fades.
struct Layer {
    source: Box<dyn Source>,
    gain: f32,
    target: f32,
    /// The current selection; a retiring layer (replaced or stopped) has this false.
    /// Muting lowers the target to zero but keeps the layer, so it can come back.
    active: bool,
    /// Gain change per frame while fading in and out.
    in_step: f32,
    out_step: f32,
}

impl Layer {
    fn new(source: Box<dyn Source>, target: f32) -> Self {
        let (fade_in, fade_out) = source.fade_seconds();
        Self {
            source,
            gain: 0.0,
            target,
            active: true,
            in_step: 1.0 / (fade_in * RATE as f32),
            out_step: 1.0 / (fade_out * RATE as f32),
        }
    }

    /// Advance the fade by `frames`; false once a retiring layer has faded out or its source has ended.
    fn step(&mut self, frames: usize) -> bool {
        let frames = frames as f32;
        if self.gain < self.target {
            self.gain = (self.gain + self.in_step * frames).min(self.target);
        } else if self.gain > self.target {
            self.gain = (self.gain - self.out_step * frames).max(self.target);
        }
        if self.source.finished() {
            return false;
        }
        self.active || self.gain > 0.0
    }
}

/// The application's side: sends commands to the [`Mixer`]. Dropping it leaves the mixer
/// playing out what it has.
pub struct Controls {
    tx: Sender<Command>,
    dirs: MusicDirs,
}

impl Controls {
    pub fn play(&self, key: Key, set: SoundSet, volume: f32) {
        if volume > 0.0 {
            let _ = self.tx.send(Command::Key(Play { key, set, volume }));
        }
    }

    /// Switch the background music, cross-fading from whatever plays now. `Off`, or a track
    /// that cannot be opened, fades the current music out.
    pub fn set_music(&self, music: &Music) {
        let _ = self.tx.send(Command::Music(music.source(&self.dirs)));
    }

    pub fn set_music_volume(&self, volume: f32) {
        let _ = self.tx.send(Command::MusicVolume(volume));
    }

    pub fn dirs(&self) -> &MusicDirs {
        &self.dirs
    }

    pub fn set_dirs(&mut self, dirs: MusicDirs) {
        self.dirs = dirs;
    }
}

/// The audio side: owned by the output callback, which calls [`Mixer::render`] per block.
pub struct Mixer {
    rx: Receiver<Command>,
    samples: Vec<Vec<Vec<f32>>>,
    rng: Rng,
    voices: Vec<Voice>,
    last_sample: usize,
    /// The playing layer plus any layers still fading out.
    layers: Vec<Layer>,
    music_volume: f32,
    scratch: Vec<f32>,
}

/// Create a connected pair. `dirs` is where tracks are looked up.
pub fn new(dirs: MusicDirs) -> (Controls, Mixer) {
    let (tx, rx) = mpsc::channel();
    let samples = SAMPLES.iter().map(|set| set.iter().map(|bytes| decode_wav(bytes)).collect()).collect();
    let mixer = Mixer {
        rx,
        samples,
        rng: Rng::new(),
        voices: Vec::with_capacity(16),
        last_sample: usize::MAX,
        layers: Vec::with_capacity(4),
        music_volume: 0.5,
        scratch: vec![0.0; MAX_BLOCK * CHANNELS],
    };
    (Controls { tx, dirs }, mixer)
}

impl Mixer {
    fn drain_commands(&mut self) {
        loop {
            match self.rx.try_recv() {
                Ok(Command::Key(play)) => self.start_voice(play),
                Ok(Command::Music(source)) => {
                    for layer in &mut self.layers {
                        layer.active = false;
                        layer.target = 0.0;
                    }
                    if let Some(source) = source {
                        self.layers.push(Layer::new(source, self.music_volume * self.music_volume));
                    }
                }
                Ok(Command::MusicVolume(v)) => {
                    self.music_volume = v.clamp(0.0, 1.0);
                    if let Some(layer) = self.layers.iter_mut().find(|l| l.active) {
                        layer.target = self.music_volume * self.music_volume;
                    }
                }
                Err(TryRecvError::Empty | TryRecvError::Disconnected) => return,
            }
        }
    }

    fn start_voice(&mut self, play: Play) {
        // Never repeat the previous sample, so runs of keys do not sound mechanical.
        let set = set_index(play.set);
        let count = self.samples[set].len();
        let mut sample = (self.rng.next_u64() % count as u64) as usize;
        if sample == self.last_sample {
            sample = (sample + 1) % count;
        }
        self.last_sample = sample;
        let (rate, gain) = match play.key {
            Key::Character => (self.rng.range(0.94, 1.06), self.rng.range(0.8, 1.0)),
            Key::Enter => (self.rng.range(0.82, 0.88), 1.0),
            Key::Backspace => (self.rng.range(1.04, 1.12), self.rng.range(0.6, 0.75)),
        };
        // Square the volume so the slider feels linear in loudness.
        self.voices.push(Voice { set, sample, position: 0.0, rate, gain: gain * play.volume * play.volume });
    }

    /// Mix the next `out.len() / CHANNELS` frames of interleaved stereo into `out`, in -1..=1.
    pub fn render(&mut self, out: &mut [f32]) {
        self.drain_commands();
        out.fill(0.0);
        let frames = out.len() / CHANNELS;
        if frames == 0 {
            return;
        }
        if self.scratch.len() < out.len() {
            // Only if the host ignores MAX_BLOCK; allocates once, never on a normal pull.
            self.scratch.resize(out.len(), 0.0);
        }
        let music_block = &mut self.scratch[..frames * CHANNELS];
        // Music layers, each ramping linearly across the block towards its target gain.
        self.layers.retain_mut(|layer| {
            let from = layer.gain;
            let alive = layer.step(frames);
            layer.source.fill(music_block);
            for (i, frame) in music_block.chunks(CHANNELS).enumerate() {
                let g = from + (layer.gain - from) * i as f32 / frames as f32;
                out[i * CHANNELS] += frame[0] * g;
                out[i * CHANNELS + 1] += frame[1] * g;
            }
            alive
        });
        // Key sounds, mono, placed in the centre.
        for voice in &mut self.voices {
            let data = &self.samples[voice.set][voice.sample];
            for frame in out.chunks_mut(CHANNELS) {
                let i = voice.position as usize;
                if i + 1 >= data.len() {
                    break;
                }
                let frac = voice.position - i as f32;
                let s = (data[i] * (1.0 - frac) + data[i + 1] * frac) * voice.gain;
                frame[0] += s;
                frame[1] += s;
                voice.position += voice.rate;
            }
        }
        let samples = &self.samples;
        self.voices.retain(|v| !v.finished(samples));
        for s in out.iter_mut() {
            *s = s.clamp(-1.0, 1.0);
        }
    }

    /// True while a key sound or a music layer (fading included) is being rendered; the
    /// host may stop pulling once this has been false for a while.
    pub fn is_active(&self) -> bool {
        !self.voices.is_empty() || !self.layers.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn pair() -> (Controls, Mixer) {
        new(MusicDirs { bundled: None, user: PathBuf::from("/nonexistent") })
    }

    fn peak(block: &[f32]) -> f32 {
        block.iter().fold(0.0f32, |m, s| m.max(s.abs()))
    }

    #[test]
    fn samples_decode() {
        for set in SAMPLES {
            for bytes in set {
                let pcm = decode_wav(bytes);
                assert!(pcm.len() > 1000, "sample too short: {}", pcm.len());
                assert!(peak(&pcm) <= 1.0 && peak(&pcm) > 0.05);
            }
        }
    }

    #[test]
    fn a_key_sounds_and_then_the_mixer_goes_quiet() {
        let (controls, mut mixer) = pair();
        let mut block = vec![0.0f32; BLOCK * CHANNELS];
        mixer.render(&mut block);
        assert_eq!(peak(&block), 0.0);
        assert!(!mixer.is_active());

        controls.play(Key::Character, SoundSet::Soft, 0.8);
        let mut heard = false;
        let mut blocks = 0;
        while blocks < 400 {
            mixer.render(&mut block);
            blocks += 1;
            if peak(&block) > 0.0 {
                heard = true;
            }
            if !mixer.is_active() {
                break;
            }
        }
        assert!(heard, "the key sample was never audible");
        assert!(!mixer.is_active(), "a key sample must end within two seconds");
        assert!(block.iter().all(|s| (-1.0..=1.0).contains(s)));
    }

    #[test]
    fn silent_keys_are_dropped() {
        let (controls, mut mixer) = pair();
        controls.play(Key::Enter, SoundSet::Keyboard, 0.0);
        let mut block = vec![0.0f32; BLOCK * CHANNELS];
        mixer.render(&mut block);
        assert!(!mixer.is_active());
    }

    #[test]
    fn music_fades_in_and_out() {
        let (controls, mut mixer) = pair();
        let mut block = vec![0.0f32; BLOCK * CHANNELS];
        controls.set_music_volume(1.0);
        controls.set_music(&Music::Drone);
        let second = RATE as usize / BLOCK;
        // The first block starts the fade from zero; after two seconds the drone is up.
        mixer.render(&mut block);
        let early = peak(&block);
        for _ in 0..2 * second {
            mixer.render(&mut block);
        }
        let late = peak(&block);
        assert!(mixer.is_active());
        assert!(late > early, "fade in: {early} -> {late}");
        assert!(late > 0.01, "drone must be audible at full volume: {late}");

        controls.set_music(&Music::Off);
        for _ in 0..3 * second {
            mixer.render(&mut block);
            if !mixer.is_active() {
                break;
            }
        }
        assert!(!mixer.is_active(), "the layer must retire after its fade-out");
    }

    #[test]
    fn render_accepts_any_block_length() {
        let (controls, mut mixer) = pair();
        assert_eq!(mixer.scratch.len(), MAX_BLOCK * CHANNELS, "allocated up front for the largest block");
        controls.set_music(&Music::Rain);
        let mut largest = vec![0.0f32; MAX_BLOCK * CHANNELS];
        mixer.render(&mut largest);
        assert_eq!(mixer.scratch.len(), MAX_BLOCK * CHANNELS, "no growth for a block within MAX_BLOCK");
        let mut big = vec![0.0f32; 1024 * CHANNELS];
        let mut small = vec![0.0f32; 64 * CHANNELS];
        mixer.render(&mut big);
        mixer.render(&mut small);
        mixer.render(&mut []);
        assert!(mixer.is_active());
    }
}
