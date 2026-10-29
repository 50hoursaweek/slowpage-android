package com.fiftyhoursaweek.slowpage.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Small state files (preferences, the recent list), kept as on iPad and Linux (S2): written
 * atomically (a temporary file in the same folder, synced, renamed over the target), read with
 * a hard cap of 64 KiB whatever the file's size says, and a write failure is a value the caller
 * shows, never a crash and never silence.
 */
object StateFile {
    /** The most a state file is ever read. */
    const val READ_LIMIT = 64 * 1024

    /** The file's bytes, at most [READ_LIMIT]; null when it does not exist, cannot be read or is too large. */
    fun read(file: File): ByteArray? = try {
        file.inputStream().use { readAtMost(it, READ_LIMIT.toLong()) }
    } catch (_: IOException) {
        null
    }

    /** Write [bytes] atomically; the error is returned for the caller to show. */
    fun write(file: File, bytes: ByteArray): IOException? = try {
        writeAtomically(file, bytes)
        null
    } catch (e: IOException) {
        e
    }
}

/**
 * At most [limit] bytes of [input], reading one byte more to tell; null when there are more.
 * The read is bounded whatever any metadata says (D8).
 */
fun readAtMost(input: InputStream, limit: Long): ByteArray? {
    require(limit in 0..Int.MAX_VALUE - 1L)
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (total <= limit) {
        val want = minOf(buffer.size.toLong(), limit + 1 - total).toInt()
        val n = input.read(buffer, 0, want)
        if (n < 0) break
        out.write(buffer, 0, n)
        total += n
    }
    return if (total > limit) null else out.toByteArray()
}

/**
 * Write [bytes] to [target] through a temporary file in the same folder: written, synced to
 * disk, then renamed over the target, so a reader sees the old bytes or the new ones, never a
 * part. The temporary file is removed when anything fails.
 */
@Throws(IOException::class)
fun writeAtomically(target: File, bytes: ByteArray) {
    val folder = target.absoluteFile.parentFile ?: throw IOException("no folder for $target")
    if (!folder.isDirectory && !folder.mkdirs()) throw IOException("cannot create $folder")
    val temp = File.createTempFile(".${target.name}.", ".partial", folder)
    try {
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!temp.renameTo(target)) throw IOException("cannot replace ${target.name}")
    } catch (e: IOException) {
        temp.delete()
        throw e
    }
}
