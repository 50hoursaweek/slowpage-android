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

    @Test fun aTrackIsCopiedUnderAFreeNameKeepingItsType() {
        val bytes = ByteArray(300_000) { it.toByte() }
        assertEquals("Song.mp3", MusicFiles.import(context, "Song.mp3", bytes.inputStream()))
        assertEquals("a second with the same name", "Song 2.mp3", MusicFiles.import(context, "Song.mp3", bytes.inputStream()))
        assertEquals("the type is kept, in lower case", "Rain on: glass.ogg".replace(':', '-'), MusicFiles.import(context, "Rain on: glass.OGG", bytes.inputStream()))
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
}
