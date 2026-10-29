package com.fiftyhoursaweek.slowpage.editor

import com.fiftyhoursaweek.slowpage.store.DocumentStore
import com.fiftyhoursaweek.slowpage.store.StoreError
import uniffi.hushhour.Document
import uniffi.hushhour.DocumentException
import uniffi.hushhour.Persistence

/**
 * One open document: the core `Document` (limit, history, dirtiness, one save in flight,
 * results applied to their generation) over a file in the [DocumentStore]. The text field
 * mirrors [text]; every change goes through [change]. [save] blocks and may run on any
 * thread; saves are serialised, so onStop's synchronous save waits for an autosave in flight.
 */
class EditorSession private constructor(
    val name: String,
    private val store: DocumentStore,
    private val document: Document,
) : AutoCloseable {
    private val saveLock = Any()

    /** The text as the core holds it; the field shows exactly this. */
    var text: String = document.text()
        private set

    sealed interface Change {
        /** Applied; the field shows [text], the core's text after the edit. */
        data class Applied(val text: String) : Change
        /** Refused before anything changed; the field keeps the old text. */
        data class Refused(val reason: String) : Change
    }

    /** Apply the field's new value [new] as one range edit. */
    fun change(new: String): Change {
        val edit = diff(text, new) ?: return Change.Applied(text)
        val start = edit.start.toULong()
        val end = edit.end.toULong()
        if (!document.editFitsUtf16(start, end, edit.insert)) {
            return Change.Refused("This change would make the document larger than ${limitMiB()} MiB.")
        }
        return try {
            document.editUtf16(start, end, edit.insert)
            text = new
            Change.Applied(text)
        } catch (e: DocumentException) {
            text = document.text()
            Change.Refused(e.message ?: "The change was refused.")
        }
    }

    fun persistence(): Persistence = document.persistence()

    /** The core's own copy, for tests of the mirror ([text] must always equal it). */
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

    private fun limitMiB() = document.sizeLimit().toLong() / (1024 * 1024)

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
