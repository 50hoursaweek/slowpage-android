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

    /** The text as the core holds it, copied out only when asked for (typing never needs it). */
    @Volatile private var cached: String? = null
    val text: String
        get() = cached ?: document.text().also { cached = it }

    sealed interface Change {
        /** Applied; [result] says which lines changed (null when nothing did). */
        data class Applied(val result: EditResult?) : Change
        /** Refused before anything changed; the editor keeps the old text. */
        data class Refused(val reason: String) : Change
    }

    /** Whether replacing UTF-16 units `start until end` with [insert] keeps the document within the limit. */
    fun fits(start: Int, end: Int, insert: String) = document.editFitsUtf16(start.toULong(), end.toULong(), insert)

    /** Replace UTF-16 units `start until end` with [insert], as the editor reports it. */
    fun edit(start: Int, end: Int, insert: String): Change {
        if (!fits(start, end, insert)) return Change.Refused(tooLarge())
        return try {
            val result = document.editUtf16(start.toULong(), end.toULong(), insert)
            cached = null
            Change.Applied(result)
        } catch (e: DocumentException) {
            cached = null
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
        cached = null
    }

    fun undo(): TextChange? = document.undo().also { cached = null }
    fun redo(): TextChange? = document.redo().also { cached = null }
    fun canUndo() = document.canUndo()
    fun canRedo() = document.canRedo()

    fun stats(): Stats = document.stats()

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

    override fun close() = document.close()

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
