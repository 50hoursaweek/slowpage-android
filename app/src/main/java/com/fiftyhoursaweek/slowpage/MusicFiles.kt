package com.fiftyhoursaweek.slowpage

import android.content.Context
import android.os.Build
import com.fiftyhoursaweek.slowpage.store.Names
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The core opens music by file path (hushhour-core `music.rs`), and APK assets are not paths.
 * The bundled OGG loops are copied once into app-private storage; a stamp with the version
 * code makes an update copy them again. User tracks are copied into [userDir] on import (A3).
 */
object MusicFiles {
    private const val ASSET_DIR = "music"

    fun bundledDir(context: Context) = File(context.filesDir, "music/bundled")

    fun userDir(context: Context) = File(context.filesDir, "music/user").apply { mkdirs() }

    /** Copies the bundled tracks if this version has not done so yet; returns the directory. */
    fun install(context: Context): File {
        val dir = bundledDir(context)
        val stamp = File(dir, ".installed")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() else @Suppress("DEPRECATION") info.versionCode.toString()
        if (stamp.isFile && stamp.readText() == version) return dir

        dir.mkdirs()
        val names = context.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".ogg") }
        for (name in names) {
            // Write to a temporary name and rename, so a killed copy never leaves a short track.
            val partial = File(dir, "$name.partial")
            context.assets.open("$ASSET_DIR/$name").use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            check(partial.renameTo(File(dir, name))) { "could not install $name" }
        }
        dir.listFiles { f -> f.name.endsWith(".ogg") && f.name !in names }?.forEach { it.delete() }
        stamp.writeText(version)
        return dir
    }

    /** The types the core decodes (`music.rs`, `scan_music_dir`). */
    val PLAYABLE = setOf("mp3", "ogg", "oga", "flac", "wav")
    /** A user track is copied whole; this keeps a wrong pick from filling the tablet. */
    const val IMPORT_LIMIT_BYTES = 200L * 1024 * 1024

    class ImportError(message: String) : Exception(message)

    /**
     * Copy a picked music file into [userDir] under a free name, keeping its extension; returns
     * the name. Refuses a type the core cannot play and a file over [IMPORT_LIMIT_BYTES]. Written
     * under a temporary name and renamed, so a half-copied track never shows in the list. Blocking.
     */
    fun import(context: Context, displayName: String?, input: InputStream): String {
        val (rawBase, rawExt) = Names.split(displayName?.trim().orEmpty())
        val ext = rawExt.lowercase()
        if (ext !in PLAYABLE) throw ImportError("Slowpage plays MP3, OGG, FLAC and WAV files.")
        val base = Names.forImport(rawBase).first
        val dir = userDir(context)
        val temp = File.createTempFile(".import.", ".partial", dir)
        try {
            var total = 0L
            temp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > IMPORT_LIMIT_BYTES) throw ImportError("The file is larger than ${IMPORT_LIMIT_BYTES / (1024 * 1024)} MiB.")
                    out.write(buffer, 0, n)
                }
                out.fd.sync()
            }
            synchronized(this) {
                val name = generateSequence(1) { it + 1 }.map { Names.numbered(base, ext, it) }.first { !File(dir, it).exists() }
                if (!temp.renameTo(File(dir, name))) throw IOException("could not store $name")
                return name
            }
        } finally {
            temp.delete()
        }
    }

    /** Remove temporary files a killed import left behind. */
    fun removeLeftovers(context: Context) {
        userDir(context).listFiles { f -> f.name.endsWith(".partial") }?.forEach { it.delete() }
    }
}
