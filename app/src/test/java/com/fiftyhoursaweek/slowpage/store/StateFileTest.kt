package com.fiftyhoursaweek.slowpage.store

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class StateFileTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aWrittenFileReadsBackAndLeavesNoTemporaryFile() {
        val file = File(tmp.root, "state/recents")
        assertNull(StateFile.write(file, "hello".toByteArray()))
        assertArrayEquals("hello".toByteArray(), StateFile.read(file))
        assertEquals(listOf("recents"), file.parentFile!!.list()!!.toList())
    }

    @Test fun aMissingFileReadsAsNothing() {
        assertNull(StateFile.read(File(tmp.root, "absent")))
    }

    @Test fun anOversizedFileIsIgnoredAndOneAtTheCapIsRead() {
        val at = File(tmp.root, "at").apply { writeBytes(ByteArray(StateFile.READ_LIMIT)) }
        val over = File(tmp.root, "over").apply { writeBytes(ByteArray(StateFile.READ_LIMIT + 1)) }
        assertNotNull(StateFile.read(at))
        assertNull(StateFile.read(over))
    }

    @Test fun aWriteFailureIsReturnedAndTheOldFileIsKept() {
        val file = File(tmp.root, "prefs").apply { writeText("old") }
        tmp.root.setWritable(false)
        try {
            assertNotNull(StateFile.write(file, "new".toByteArray()))
        } finally {
            tmp.root.setWritable(true)
        }
        assertEquals("old", file.readText())
    }

    @Test fun aBoundedReadStopsAtTheLimitWhateverTheStreamHolds() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int { served++; return 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill('a'.code.toByte(), off, off + len); served += len; return len
            }
        }
        assertNull(readAtMost(endless, 1000))
        assertEquals(1001L, served)
        assertEquals(1000, readAtMost(ByteArrayInputStream(ByteArray(1000)), 1000)!!.size)
    }
}
