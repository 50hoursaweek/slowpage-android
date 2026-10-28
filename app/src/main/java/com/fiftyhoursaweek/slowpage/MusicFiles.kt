package com.fiftyhoursaweek.slowpage

import android.content.Context
import java.io.File

/**
 * The core opens music by file path (hushhour-core `music.rs`), and APK assets are not paths.
 * The bundled OGG loops are copied once into app-private storage; a stamp with the version
 * code makes an update copy them again. User tracks will be copied into [userDir] on import.
 */
object MusicFiles {
    private const val ASSET_DIR = "music"

    fun bundledDir(context: Context) = File(context.filesDir, "music/bundled")

    fun userDir(context: Context) = File(context.filesDir, "music/user").apply { mkdirs() }

    /** Copies the bundled tracks if this version has not done so yet; returns the directory. */
    fun install(context: Context): File {
        val dir = bundledDir(context)
        val stamp = File(dir, ".installed")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
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
}
