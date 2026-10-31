//! The document rules of `docs/document-safety.md` in the hub (HushHour v0.2.2), as one
//! platform-independent state machine for an autosaving port: the size limit on every growing
//! edit and on load (rule 5), undo and redo through the shared [`History`] with groups that end
//! at save boundaries (rule 6), dirtiness against the saved revision with save results applied
//! only to the generation they were taken from (rule 2), one save in flight (rule 3), late
//! results ignored (rule 7), and no replacement of text that is not yet persisted (rule 1 in
//! its autosave form: the question becomes "wait for the save", a failed save blocks).
//!
//! The host's text view mirrors [`Document::text`]; every change goes through
//! [`Document::edit`] so the limit and the history see it. Offsets are UTF-8 byte offsets;
//! the host converts from its own (UTF-16) ranges. The tests mirror the rules, and port the
//! Linux `fn selftest`.

use std::ops::Range;

use hushhour_core::history::{History, Position, position_at};

/// Documents larger than this are refused rather than loaded into the editor.
pub const MAX_DOCUMENT_BYTES: u64 = 20 * 1024 * 1024;

/// Whether replacing `selected` bytes of a `current`-byte document with `added` bytes
/// keeps the document within `limit`. Overflow counts as too big.
pub fn edit_fits(current: usize, selected: usize, added: usize, limit: u64) -> bool {
    let kept = current.saturating_sub(selected) as u64;
    kept.checked_add(added as u64).is_some_and(|total| total <= limit)
}

/// Why an edit or a load was not applied.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Refused {
    /// The result would exceed the limit; nothing changed. Deletions are never refused.
    TooLarge { limit_bytes: u64 },
    /// The range is reversed, past the end or not on character boundaries.
    BadRange,
}

/// A change the host must apply to its text view: `range` in the text as it was, replaced
/// by `text`, then the caret at `caret`. The line counts and the UTF-16 length let a host
/// with a line table and UTF-16 ranges (UIKit) update them without scanning the text.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Change {
    pub range: Range<usize>,
    pub text: String,
    pub caret: Position,
    /// The line `range` starts on.
    pub line: usize,
    /// Newlines inside `range` (lines that disappear) and inside `text` (lines that appear).
    pub removed_lines: usize,
    pub inserted_lines: usize,
    /// UTF-16 length of the removed text.
    pub removed_utf16: usize,
}

/// What a save writes: the text and the generation it was taken from.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SaveRequest {
    pub generation: u64,
    pub text: String,
}

/// Where the text stands against the file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Persistence {
    /// Everything is on disk: New, Open and Close may proceed.
    Clean,
    /// Edited since the last save and no save running: the host should save, then retry.
    Dirty,
    /// A save is in flight: wait for its result.
    Saving,
    /// The last save failed; the text is only in memory. Shown to the user, blocks transitions
    /// until a later save succeeds.
    Failed(String),
}

/// What became of a finished Open request.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Offer {
    /// Superseded by a later request, replaced meanwhile, or over the limit.
    Ignored,
    /// The document holds text that is not yet persisted: the loaded text waits inside until
    /// [`Document::retry_pending`] after a save, or [`Document::cancel_replace`].
    Deferred,
    /// The document was replaced.
    Replaced,
}

#[derive(Debug)]
pub struct Document {
    text: String,
    /// The text as last loaded or saved; the document is dirty when `text` differs from it.
    saved_text: String,
    history: History,
    dirty: bool,
    /// Bumped whenever the document is replaced; results of older requests are ignored.
    generation: u64,
    /// Bumped on every edit, undo and redo; with `generation` it names one exact revision.
    edits: u64,
    /// Each Open gets an id; only the latest outstanding one may replace the document.
    next_request: u64,
    open_request: Option<u64>,
    /// Loaded text waiting for the unsaved text to be persisted.
    pending_open: Option<String>,
    saving: bool,
    save_error: Option<String>,
    /// `MAX_DOCUMENT_BYTES`, smaller in tests.
    size_limit: u64,
}

impl Default for Document {
    fn default() -> Self {
        Self::with_limit(MAX_DOCUMENT_BYTES)
    }
}

impl Document {
    pub fn with_limit(size_limit: u64) -> Self {
        Self {
            text: String::new(),
            saved_text: String::new(),
            history: History::default(),
            dirty: false,
            generation: 0,
            edits: 0,
            next_request: 0,
            open_request: None,
            pending_open: None,
            saving: false,
            save_error: None,
            size_limit,
        }
    }

    pub fn text(&self) -> &str {
        &self.text
    }

    pub fn is_dirty(&self) -> bool {
        self.dirty
    }

    pub fn generation(&self) -> u64 {
        self.generation
    }

    pub fn edits(&self) -> u64 {
        self.edits
    }

    pub fn size_limit(&self) -> u64 {
        self.size_limit
    }

    pub fn persistence(&self) -> Persistence {
        if self.saving {
            Persistence::Saving
        } else if let Some(e) = &self.save_error {
            Persistence::Failed(e.clone())
        } else if self.dirty {
            Persistence::Dirty
        } else {
            Persistence::Clean
        }
    }

    /// True when nothing would be lost by replacing or closing the document.
    pub fn may_discard(&self) -> bool {
        self.persistence() == Persistence::Clean
    }

    /// Replace the document with loaded (or empty, for New) text: history cleared, clean,
    /// a new generation, outstanding requests and parked text forgotten. CRLF is normalised.
    /// Refused, with nothing changed, past the limit, which is inclusive like the edit guard
    /// so a document the editor built always reopens.
    pub fn load(&mut self, text: &str) -> Result<(), Refused> {
        let text = text.replace("\r\n", "\n");
        if text.len() as u64 > self.size_limit {
            return Err(Refused::TooLarge { limit_bytes: self.size_limit });
        }
        self.saved_text = text.clone();
        self.text = text;
        self.history.clear();
        self.dirty = false;
        self.generation += 1;
        self.edits = 0;
        self.open_request = None;
        self.pending_open = None;
        self.save_error = None;
        Ok(())
    }

    /// Replace `range` with `insert`, recording the step. `caret_before` is where the caret
    /// was (the history restores it on undo); the caret after is returned. Growing edits
    /// that would pass the limit are refused before anything changes.
    pub fn edit(&mut self, range: Range<usize>, insert: &str, caret_before: Position) -> Result<Position, Refused> {
        if range.start > range.end
            || range.end > self.text.len()
            || !self.text.is_char_boundary(range.start)
            || !self.text.is_char_boundary(range.end)
        {
            return Err(Refused::BadRange);
        }
        if !insert.is_empty() && !edit_fits(self.text.len(), range.len(), insert.len(), self.size_limit) {
            return Err(Refused::TooLarge { limit_bytes: self.size_limit });
        }
        if range.is_empty() && insert.is_empty() {
            return Ok(caret_before);
        }
        let mut new = String::with_capacity(self.text.len() - range.len() + insert.len());
        new.push_str(&self.text[..range.start]);
        new.push_str(insert);
        new.push_str(&self.text[range.end..]);
        let caret_after = position_at(&new, range.start + insert.len());
        self.history.record(&self.text, &new, caret_before, caret_after);
        self.text = new;
        self.edits += 1;
        self.update_dirty();
        Ok(caret_after)
    }

    /// Revert the newest step; the host applies the returned change to its view.
    pub fn undo(&mut self) -> Option<Change> {
        let d = self.history.undo()?;
        Some(self.apply(d.offset, d.inserted.len(), d.removed, d.cursor_before))
    }

    pub fn redo(&mut self) -> Option<Change> {
        let d = self.history.redo()?;
        Some(self.apply(d.offset, d.removed.len(), d.inserted, d.cursor_after))
    }

    fn apply(&mut self, offset: usize, remove_len: usize, insert: String, caret: Position) -> Change {
        let range = offset..offset + remove_len;
        let line = self.text[..offset].matches('\n').count();
        let removed = &self.text[range.clone()];
        let removed_lines = removed.matches('\n').count();
        let removed_utf16 = removed.encode_utf16().count();
        let inserted_lines = insert.matches('\n').count();
        self.text.replace_range(range.clone(), &insert);
        self.edits += 1;
        self.update_dirty();
        Change { range, text: insert, caret, line, removed_lines, inserted_lines, removed_utf16 }
    }

    /// End the current typing group, so the next edit starts a new undo step.
    pub fn end_group(&mut self) {
        self.history.end_group();
    }

    /// Whether there is a step to undo; for menu and shortcut state.
    pub fn can_undo(&self) -> bool {
        self.history.can_undo()
    }

    /// Whether there is a step to redo.
    pub fn can_redo(&self) -> bool {
        self.history.can_redo()
    }

    /// Begin a save (autosave included): the generation and the exact text to write. `None`
    /// while another save is in flight (rule 3). Edits made during the save undo separately.
    pub fn begin_save(&mut self) -> Option<SaveRequest> {
        if self.saving {
            return None;
        }
        self.saving = true;
        self.history.end_group();
        Some(SaveRequest { generation: self.generation, text: self.text.clone() })
    }

    /// The write finished. Ignored (false) when `generation` is not the current one: the
    /// result belongs to a document that has since been replaced. Otherwise `text` becomes
    /// the saved revision, and the document is clean only if that is what it holds now.
    pub fn save_succeeded(&mut self, generation: u64, text: &str) -> bool {
        self.saving = false;
        if generation != self.generation {
            return false;
        }
        self.saved_text = text.to_owned();
        self.save_error = None;
        self.history.end_group();
        self.update_dirty();
        true
    }

    /// The write failed. Remembered (and surfaced through [`Persistence::Failed`]) when it
    /// concerns the current document; a later successful save clears it.
    pub fn save_failed(&mut self, generation: u64, reason: &str) {
        self.saving = false;
        if generation == self.generation {
            self.save_error = Some(reason.to_owned());
        }
    }

    /// Begin an Open request; any earlier outstanding one is superseded.
    pub fn start_request(&mut self) -> u64 {
        self.next_request += 1;
        self.open_request = Some(self.next_request);
        self.next_request
    }

    /// The request was cancelled or its file unreadable.
    pub fn request_failed(&mut self, request: u64) {
        if self.open_request == Some(request) {
            self.open_request = None;
        }
    }

    /// A file finished loading for `request`. Replaces the document when everything in it is
    /// persisted; otherwise the text waits ([`Offer::Deferred`]) for the save to finish.
    pub fn offer(&mut self, request: u64, text: &str) -> Offer {
        if self.open_request != Some(request) {
            return Offer::Ignored;
        }
        self.open_request = None;
        if !self.may_discard() {
            self.pending_open = Some(text.to_owned());
            return Offer::Deferred;
        }
        match self.load(text) {
            Ok(()) => Offer::Replaced,
            // Checked by the host before offering; a text past the limit is never kept.
            Err(_) => Offer::Ignored,
        }
    }

    /// After a save completed: replace with the waiting text if nothing is unsaved any more.
    pub fn retry_pending(&mut self) -> Offer {
        let Some(text) = self.pending_open.as_deref() else { return Offer::Ignored };
        if !self.may_discard() {
            return Offer::Deferred;
        }
        let text = text.to_owned();
        match self.load(&text) {
            Ok(()) => Offer::Replaced,
            Err(_) => {
                self.pending_open = None;
                Offer::Ignored
            }
        }
    }

    /// The user gave up on the waiting file (or chose to keep the current text).
    pub fn cancel_replace(&mut self) {
        self.pending_open = None;
    }

    pub fn has_pending_replace(&self) -> bool {
        self.pending_open.is_some()
    }

    fn update_dirty(&mut self) {
        self.dirty = self.text != self.saved_text;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn caret_at_end(d: &Document) -> Position {
        position_at(d.text(), d.text().len())
    }

    /// Type at the end of the document, one character at a time, like key presses.
    fn type_str(d: &mut Document, s: &str) {
        for c in s.chars() {
            let end = d.text().len();
            let caret = caret_at_end(d);
            d.edit(end..end, c.encode_utf8(&mut [0; 4]), caret).expect("edit fits");
        }
    }

    fn backspace(d: &mut Document) {
        let end = d.text().len();
        let start = d.text()[..end].char_indices().next_back().map_or(0, |(i, _)| i);
        let caret = caret_at_end(d);
        d.edit(start..end, "", caret).unwrap();
    }

    /// A complete autosave round trip of the current text.
    fn autosave(d: &mut Document) {
        let req = d.begin_save().expect("no save in flight");
        assert!(d.save_succeeded(req.generation, &req.text));
    }

    /// Rule 5 (V07): the paste limit counts what the paste replaces.
    #[test]
    fn paste_limit_accounts_for_the_selection() {
        let limit = MAX_DOCUMENT_BYTES as usize;
        let fits = |current, selected, added| edit_fits(current, selected, added, MAX_DOCUMENT_BYTES);
        assert!(fits(limit, limit, limit), "replacing a full document with one of equal size");
        assert!(fits(limit - 10, 0, 10));
        assert!(!fits(limit - 9, 0, 10));
        assert!(fits(100, 60, limit - 40));
        assert!(!fits(100, 60, limit - 39));
        assert!(!fits(usize::MAX, 0, usize::MAX), "no overflow");
    }

    /// Rule 5.
    #[test]
    fn edits_cannot_grow_the_document_past_the_limit() {
        let mut d = Document::with_limit(10);
        d.load("0123456789").unwrap();
        let caret = caret_at_end(&d);
        assert_eq!(d.edit(10..10, "x", caret), Err(Refused::TooLarge { limit_bytes: 10 }));
        assert_eq!(d.text(), "0123456789");
        assert!(!d.is_dirty(), "a refused edit changes nothing");
        // Replacing a selection with text of the same size fits.
        assert!(d.edit(0..5, "abcde", Position::default()).is_ok());
        assert_eq!(d.text(), "abcde56789");
        assert!(d.edit(0..5, "abcdef", Position::default()).is_err());
        // Deleting always works, and after it there is room again.
        assert!(d.edit(0..5, "", Position::default()).is_ok());
        assert!(d.edit(0..0, "pq", Position::default()).is_ok());
        assert_eq!(d.text(), "pq56789");
    }

    /// Rule 5.
    #[test]
    fn the_open_limit_is_inclusive() {
        let mut d = Document::with_limit(10);
        assert!(d.load("0123456789").is_ok());
        assert_eq!(d.load("0123456789a"), Err(Refused::TooLarge { limit_bytes: 10 }));
        assert_eq!(d.text(), "0123456789", "a refused load leaves the document alone");
        assert_eq!(d.generation(), 1);
    }

    /// Port of `fn selftest` in the Linux `main.rs`, in the same order, with the "Don't save"
    /// steps in their autosave form.
    #[test]
    fn selftest() {
        let mut d = Document::default();
        d.load("Hello world.\nSecond line.").unwrap();
        type_str(&mut d, " More");
        assert_eq!(d.text(), "Hello world.\nSecond line. More", "typed");
        type_str(&mut d, "\n");
        assert_eq!(d.text(), "Hello world.\nSecond line. More\n", "enter");
        type_str(&mut d, "New");
        assert_eq!(d.text(), "Hello world.\nSecond line. More\nNew", "typed 2");

        assert!(d.undo().is_some());
        assert_eq!(d.text(), "Hello world.\nSecond line. More\n", "undo word");
        assert!(d.undo().is_some());
        assert_eq!(d.text(), "Hello world.\nSecond line. More", "undo enter");
        assert!(d.undo().is_some());
        assert_eq!(d.text(), "Hello world.\nSecond line.", "undo ' More'");
        assert!(d.undo().is_none(), "undo at bottom is a no-op");
        assert!(!d.can_undo() && d.can_redo(), "menu state at the bottom of the history");
        assert_eq!(d.text(), "Hello world.\nSecond line.");
        assert!(d.redo().is_some());
        assert_eq!(d.text(), "Hello world.\nSecond line. More", "redo 1");
        assert!(d.redo().is_some());
        assert_eq!(d.text(), "Hello world.\nSecond line. More\n", "redo 2");
        let change = d.redo().unwrap();
        assert_eq!(d.text(), "Hello world.\nSecond line. More\nNew", "redo 3");
        assert_eq!((change.caret.line, change.caret.column), (2, 3), "cursor after redo");
        assert!(d.redo().is_none());

        // Replace a selection, then undo it.
        d.edit(6..11, "X", Position { line: 0, column: 6 }).unwrap();
        assert_eq!(d.text(), "Hello X.\nSecond line. More\nNew", "replace selection");
        let change = d.undo().unwrap();
        assert_eq!(d.text(), "Hello world.\nSecond line. More\nNew", "undo replace");
        assert_eq!(
            change,
            Change {
                range: 6..7,
                text: "world".into(),
                caret: Position { line: 0, column: 6 },
                line: 0,
                removed_lines: 0,
                inserted_lines: 0,
                removed_utf16: 1,
            }
        );
        assert!(d.redo().is_some());
        assert_eq!(d.text(), "Hello X.\nSecond line. More\nNew", "redo replace");

        // Backspace coalescing and multi-byte text.
        type_str(&mut d, "è!");
        assert_eq!(d.text(), "Hello X.\nSecond line. More\nNewè!", "unicode typed");
        backspace(&mut d);
        assert_eq!(d.text(), "Hello X.\nSecond line. More\nNewè", "bs 1");
        backspace(&mut d);
        assert_eq!(d.text(), "Hello X.\nSecond line. More\nNew", "bs 2");
        assert!(d.undo().is_some());
        assert_eq!(d.text(), "Hello X.\nSecond line. More\nNewè!", "undo both backspaces");

        // Unsaved text must never be discarded: New, Open and Close wait for the autosave.
        assert_eq!(d.persistence(), Persistence::Dirty);
        assert!(!d.may_discard());
        assert!(!d.text().is_empty(), "document was lost");
        // A stale save result (older generation) must be ignored.
        assert!(!d.save_succeeded(d.generation() + 100, ""), "stale save result was applied");
        assert!(d.is_dirty());
        // A save of older text must not mark newer text clean.
        assert!(d.save_succeeded(d.generation(), "older"));
        assert!(d.is_dirty(), "save of older text marked newer text clean");
        // A save of the current text makes it clean; undo past it makes it dirty again;
        // redo back to it makes it clean.
        autosave(&mut d);
        assert!(!d.is_dirty(), "save of current text left the document dirty");
        assert!(d.undo().is_some());
        assert_eq!(d.text(), "Hello X.\nSecond line. More\nNew", "undo past save");
        assert!(d.is_dirty(), "undo past the saved revision is not dirty");
        assert!(d.redo().is_some());
        assert_eq!(d.text(), "Hello X.\nSecond line. More\nNewè!", "redo to saved");
        assert!(!d.is_dirty(), "redo back to the saved revision is still dirty");
        // A file that finished loading while the document is dirty must wait, not replace.
        type_str(&mut d, "!");
        let request = d.start_request();
        assert_eq!(d.offer(request, "other"), Offer::Deferred, "late Open result replaced an edited document");
        assert_ne!(d.text(), "other");
        assert!(d.has_pending_replace());
        d.cancel_replace();
        assert!(!d.has_pending_replace());
        assert_eq!(d.text(), "Hello X.\nSecond line. More\nNewè!!");
    }

    /// Rule 1 in its autosave form.
    #[test]
    fn unsaved_text_defers_a_replacement_until_persisted() {
        let mut d = Document::default();
        d.load("a").unwrap();
        type_str(&mut d, "b");
        let request = d.start_request();
        assert_eq!(d.offer(request, "new"), Offer::Deferred);
        assert_eq!(d.text(), "ab");
        assert_eq!(d.retry_pending(), Offer::Deferred, "still dirty");
        autosave(&mut d);
        assert_eq!(d.retry_pending(), Offer::Replaced);
        assert_eq!(d.text(), "new");
        assert!(!d.has_pending_replace());
        assert_eq!(d.retry_pending(), Offer::Ignored, "nothing waiting");
        // Clean documents are replaced at once.
        let request = d.start_request();
        assert_eq!(d.offer(request, "newer"), Offer::Replaced);
        assert_eq!(d.text(), "newer");
    }

    /// Rules 2 and 7.
    #[test]
    fn a_save_result_applies_only_to_its_generation() {
        let mut d = Document::default();
        d.load("one").unwrap();
        type_str(&mut d, "!");
        let req = d.begin_save().unwrap();
        // The document is replaced while the write runs.
        d.load("two").unwrap();
        assert!(!d.save_succeeded(req.generation, &req.text), "result of a replaced document");
        assert_eq!(d.text(), "two");
        assert!(!d.is_dirty());
        assert_eq!(d.persistence(), Persistence::Clean, "the stale result still ends the in-flight save");
        // A failure for an old generation is not pinned on the new document either.
        let req = d.begin_save().unwrap();
        d.load("three").unwrap();
        d.save_failed(req.generation, "disk full");
        assert_eq!(d.persistence(), Persistence::Clean);
    }

    /// Rules 2 and 6.
    #[test]
    fn typing_during_a_save_leaves_the_document_dirty_and_starts_a_new_undo_step() {
        let mut d = Document::default();
        d.load("").unwrap();
        type_str(&mut d, "ab");
        let req = d.begin_save().unwrap();
        assert_eq!(d.persistence(), Persistence::Saving);
        type_str(&mut d, "cd");
        assert!(d.save_succeeded(req.generation, &req.text));
        assert!(d.is_dirty(), "edited since the snapshot: never silently clean");
        assert!(d.undo().is_some());
        assert_eq!(d.text(), "ab", "the edits after the snapshot are one step");
        assert!(!d.is_dirty());
        assert!(d.undo().is_some());
        assert_eq!(d.text(), "");
    }

    /// Rule 3.
    #[test]
    fn one_save_in_flight() {
        let mut d = Document::default();
        d.load("x").unwrap();
        type_str(&mut d, "y");
        let req = d.begin_save().unwrap();
        assert!(d.begin_save().is_none(), "a second save is refused while one runs");
        assert!(d.save_succeeded(req.generation, &req.text));
        assert!(d.begin_save().is_some(), "and allowed again once it finished");
        d.save_failed(d.generation(), "boom");
        assert!(d.begin_save().is_some(), "a failure also frees the slot");
    }

    /// Autosave: a failed save must surface and block destructive transitions.
    #[test]
    fn a_failed_autosave_surfaces_and_blocks_transitions() {
        let mut d = Document::default();
        d.load("keep").unwrap();
        type_str(&mut d, " me");
        let req = d.begin_save().unwrap();
        d.save_failed(req.generation, "no space left");
        assert_eq!(d.persistence(), Persistence::Failed("no space left".into()));
        assert!(!d.may_discard());
        let request = d.start_request();
        assert_eq!(d.offer(request, "other"), Offer::Deferred);
        assert_eq!(d.text(), "keep me");
        // The next successful save clears the failure and lets the replacement through.
        autosave(&mut d);
        assert_eq!(d.persistence(), Persistence::Clean);
        assert_eq!(d.retry_pending(), Offer::Replaced);
        assert_eq!(d.text(), "other");
    }

    /// Rule 7.
    #[test]
    fn only_the_latest_open_request_can_replace() {
        let mut d = Document::default();
        d.load("base").unwrap();
        let old = d.start_request();
        let newer = d.start_request();
        assert_eq!(d.offer(old, "stale"), Offer::Ignored);
        assert_eq!(d.text(), "base");
        assert_eq!(d.offer(newer, "fresh"), Offer::Replaced);
        assert_eq!(d.offer(newer, "again"), Offer::Ignored, "a request delivers once");
        // A cancelled request delivers nothing, and loading forgets outstanding requests.
        let request = d.start_request();
        d.request_failed(request);
        assert_eq!(d.offer(request, "late"), Offer::Ignored);
        let request = d.start_request();
        d.load("replaced by New").unwrap();
        assert_eq!(d.offer(request, "late"), Offer::Ignored);
        assert_eq!(d.text(), "replaced by New");
    }

    /// Autosave: undo history is intact across autosaves; counters name the revision.
    #[test]
    fn undo_history_survives_autosaves() {
        let mut d = Document::default();
        d.load("").unwrap();
        type_str(&mut d, "one");
        autosave(&mut d);
        type_str(&mut d, " two");
        autosave(&mut d);
        type_str(&mut d, " three");
        assert_eq!((d.generation(), d.edits()), (1, 13));
        autosave(&mut d);
        assert!(!d.is_dirty());
        assert!(d.undo().is_some());
        assert_eq!(d.text(), "one two");
        assert!(d.undo().is_some());
        assert_eq!(d.text(), "one");
        assert!(d.undo().is_some());
        assert_eq!(d.text(), "");
        assert!(d.undo().is_none());
        assert_eq!(d.edits(), 16, "undo counts as an edit");
        assert!(d.redo().is_some() && d.redo().is_some() && d.redo().is_some());
        assert_eq!(d.text(), "one two three");
        assert!(!d.is_dirty(), "back at the saved revision");
    }

    #[test]
    fn bad_ranges_are_refused_and_crlf_is_normalised() {
        let mut d = Document::default();
        d.load("a\r\nè").unwrap();
        assert_eq!(d.text(), "a\nè");
        assert_eq!(d.edit(3..3, "x", Position::default()), Err(Refused::BadRange), "inside è");
        assert_eq!(d.edit(Range { start: 2, end: 1 }, "x", Position::default()), Err(Refused::BadRange), "reversed");
        assert_eq!(d.edit(0..9, "x", Position::default()), Err(Refused::BadRange));
        assert!(!d.is_dirty());
    }
}
