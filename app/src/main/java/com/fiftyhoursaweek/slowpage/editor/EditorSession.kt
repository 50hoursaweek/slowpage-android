package com.fiftyhoursaweek.slowpage.editor

import com.fiftyhoursaweek.slowpage.store.DocumentStore
import com.fiftyhoursaweek.slowpage.store.StoreError
import uniffi.hushhour.Document
import uniffi.hushhour.DocumentException
import uniffi.hushhour.EditResult
import uniffi.hushhour.Persistence
import uniffi.hushhour.Stats
import uniffi.hushhour.TextChange

/**
 * One open document: the core `Document` (limit, history, dirtiness, one save in flight,
 * results applied to their generation) over a file in the [DocumentStore]. The editor mirrors
 * [text]; every change goes through [edit] (or [change]), undo and redo come back from the
 * core's history (E12). [save] blocks and may run on any thread; saves are serialised, so
 * onStop's synchronous save waits for an autosave in flight.
 */
class EditorSession private constructor(
    val name: String,
    private val store: DocumentStore,
    private val document: Document,
) : AutoCloseable {
    private val saveLock = Any()

    /** Held while the document is used off the main thread or closed, so a late job never reaches a closed one. */
    private val closeLock = Any()
    private var closed = false

    /**
     * The text as the core holds it, copied out each time: not kept, as the editor already
     * holds it and a 20 MiB document would be held twice (round 1, BLOCKING 3). Typing never
     * needs it; keep it off the main thread for a large document.
     */
    val text: String
        get() = document.text()

    sealed interface Change {
        /** Applied; [result] says which lines changed (null when nothing did). */
        data class Applied(val result: EditResult?) : Change
        /** Refused before anything changed; the editor keeps the old text. */
        data class Refused(val reason: String) : Change
    }

    /** [text] for a job off the main thread that may outlive the document: null once closed. */
    fun textUnlessClosed(): String? = synchronized(closeLock) { if (closed) null else document.text() }

    /**
     * Whether replacing UTF-16 units `start until end` with [insert] keeps the document within
     * the limit. Asked on every key: when even the largest growth the insert could bring (three
     * UTF-8 bytes a unit) fits, the core is not asked, as it walks the text to the offset.
     */
    fun fits(start: Int, end: Int, insert: String): Boolean {
        if (document.byteLen().toLong() + 3L * insert.length <= document.sizeLimit().toLong()) return true
        return document.editFitsUtf16(start.toULong(), end.toULong(), insert)
    }

    /** Replace UTF-16 units `start until end` with [insert], as the editor reports it. */
    fun edit(start: Int, end: Int, insert: String): Change {
        if (!fits(start, end, insert)) return Change.Refused(tooLarge())
        return try {
            Change.Applied(document.editUtf16(start.toULong(), end.toULong(), insert))
        } catch (e: DocumentException) {
            Change.Refused(e.message ?: "The change was refused.")
        }
    }

    /** Make the text [new], as one range edit. */
    fun change(new: String): Change {
        val edit = diff(text, new) ?: return Change.Applied(null)
        return edit(edit.start, edit.end, edit.insert)
    }

    /** The editor and the core disagree (a change that bypassed the gate): the editor's [new] text wins. */
    fun resync(new: String) {
        if (change(new) is Change.Refused) document.load(new)
    }

    fun undo(): TextChange? = document.undo()
    fun redo(): TextChange? = document.redo()
    fun canUndo() = document.canUndo()
    fun canRedo() = document.canRedo()

    fun stats(): Stats = document.stats()

    /** [stats] for a job that may outlive the document (it counts the whole text, off the main thread): null once closed. */
    fun statsUnlessClosed(): Stats? = synchronized(closeLock) { if (closed) null else document.stats() }

    /** The revision a preview was parsed from (V5): a load changes the generation, an edit the count. */
    fun revision(): Pair<ULong, ULong> = document.generation() to document.edits()

    fun persistence(): Persistence = document.persistence()

    /** The core's own copy, for tests of the mirror. */
    internal fun coreText(): String = document.text()

    fun isDirty() = document.isDirty()

    /** Write the text if it is not on disk yet; returns the state after. Blocking. */
    fun save(): Persistence = synchronized(saveLock) {
        if (!document.isDirty()) return document.persistence()
        val request = document.beginSave() ?: return document.persistence()
        try {
            store.write(name, request.text)
            document.saveSucceeded(request.generation, request.text)
        } catch (e: StoreError) {
            document.saveFailed(request.generation, e.message ?: e.toString())
        }
        document.persistence()
    }

    fun limitMiB() = document.sizeLimit().toLong() / (1024 * 1024)

    private fun tooLarge() = "This change would make the document larger than ${limitMiB()} MiB."

    override fun close() = synchronized(closeLock) {
        if (closed) return
        closed = true
        document.close()
    }

    companion object {
        /** Read [name] from the store and load it into a new core document. Blocking. */
        fun open(store: DocumentStore, name: String): EditorSession {
            val text = store.read(name)
            val document = Document()
            try {
                document.load(text)
            } catch (e: DocumentException) {
                document.close()
                throw e
            }
            return EditorSession(name, store, document)
        }
    }
}
