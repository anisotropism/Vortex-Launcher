package com.example.mylauncher

import android.content.Context
import android.os.Environment
import java.io.File

object ModLoader {
    val root get() = File(Environment.getExternalStorageDirectory(), "vortex")
    fun modsDir() = File(root, "mods").apply { mkdirs() }
    fun gameDir() = File(root, "games/com.mojang").apply { mkdirs() }

    fun listMods(): List<File> =
        modsDir().listFiles { f -> f.extension == "so" }?.sortedBy { it.name } ?: emptyList()

    fun load(ctx: Context, f: File): String = try {
        // Android tidak mengizinkan load langsung dari sdcard, jadi salin dulu
        val dst = File(ctx.codeCacheDir, f.name)
        dst.delete()
        f.copyTo(dst)
        dst.setReadOnly() // wajib di Android 14+
        System.load(dst.absolutePath)
        "OK: ${f.name}"
    } catch (e: Throwable) {
        "GAGAL: ${f.name} (${e.message})"
    }
}
