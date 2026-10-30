package com.fiftyhoursaweek.slowpage.audio

import com.fiftyhoursaweek.slowpage.MusicFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** A3: an imported track is copied into the app's music folder, where the core reads it by path. */
@RunWith(RobolectricTestRunner::class)
class MusicImportTest {
    private val context = RuntimeEnvironment.getApplication()

    /** [size] bytes that start as a file of that kind does. */
    private fun track(head: String, size: Int = 300_000) = ByteArray(size) { it.toByte() }.also { b ->
        head.forEachIndexed { i, c -> b[i] = c.code.toByte() }
    }

    @Test fun aTrackIsCopiedUnderAFreeNameKeepingItsType() {
        val bytes = track("ID3")
        assertEquals("Song.mp3", MusicFiles.import(context, "Song.mp3", bytes.inputStream()))
        assertEquals("a second with the same name", "Song 2.mp3", MusicFiles.import(context, "Song.mp3", bytes.inputStream()))
        assertEquals("the type is kept, in lower case", "Rain on: glass.ogg".replace(':', '-'), MusicFiles.import(context, "Rain on: glass.OGG", track("OggS").inputStream()))
        val dir = MusicFiles.userDir(context)
        assertTrue(File(dir, "Song.mp3").readBytes().contentEquals(bytes))
        assertEquals("nothing half written left", emptyList<String>(), dir.list()!!.filter { it.endsWith(".partial") })
    }

    @Test fun aTypeTheCoreCannotPlayIsRefused() {
        for (name in listOf("notes.md", "voice.m4a", "noextension", null)) {
            try {
                MusicFiles.import(context, name, ByteArray(10).inputStream())
                fail("$name was imported")
            } catch (e: MusicFiles.ImportError) {
                assertTrue(e.message!!.contains("MP3, OGG, FLAC and WAV"))
            }
        }
        assertEquals(emptyList<String>(), MusicFiles.userDir(context).list()!!.toList())
    }

    @Test fun aFileThatIsNotAudioIsRefusedWhateverItsName() {
        // Codex phase 5, MINOR 3: a renamed document, an empty file.
        for (bytes in listOf("# Notes\n".toByteArray(), ByteArray(0), track("RIFF....AVI "))) {
            try {
                MusicFiles.import(context, "Song.mp3", bytes.inputStream())
                fail("imported")
            } catch (e: MusicFiles.ImportError) {
                assertTrue(e.message!!.contains("not an MP3"))
            }
        }
        for (head in listOf("ID3", "OggS", "fLaC", "RIFF....WAVE")) {
            assertTrue(head, MusicFiles.import(context, "t.wav", track(head, 64).inputStream()).endsWith(".wav"))
        }
        // An MP3 without a tag starts with a frame header.
        val frame = track("").also { it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte() }
        MusicFiles.import(context, "Frame.mp3", frame.inputStream())
        assertEquals("nothing left of the refused ones", 5, MusicFiles.userDir(context).list()!!.size)
    }

    @Test fun aFileOverTheLimitIsRefusedAndNothingIsKept() {
        val limit = 100_000L
        MusicFiles.import(context, "Fits.ogg", track("OggS", limit.toInt()).inputStream(), limit)
        try {
            MusicFiles.import(context, "Big.ogg", track("OggS", limit.toInt() + 1).inputStream(), limit)
            fail("imported")
        } catch (e: MusicFiles.ImportError) {
            assertTrue(e.message!!.contains("larger than"))
        }
        assertEquals(listOf("Fits.ogg"), MusicFiles.userDir(context).list()!!.toList())
    }

    @Test fun theBundledLoopsAreListedWithoutCopyingThem() {
        // Codex phase 5, MINOR 4.
        val names = MusicFiles.bundledNames(context)
        assertEquals(6, names.size)
        assertEquals(names.sortedBy { it.lowercase() }, names)
        assertTrue(!MusicFiles.bundledDir(context).exists())
    }
}
