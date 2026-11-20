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
        dst.setReadOnly()
        System.load(dst.absolutePath)
        "OK: ${f.name}"
    } catch (e: Throwable) {
        "GAGAL: ${f.name} (${e.message})"
    }

    // Load semua mod. Kalau ada yang butuh dependency (misal dobby),
    // ulangi sampai urutannya benar.
    fun loadAll(ctx: Context): List<String> {
        val logs = mutableListOf<String>()
        var pending = listMods()
        while (pending.isNotEmpty()) {
            val failed = mutableListOf<File>()
            for (f in pending) {
                val r = load(ctx, f)
                if (r.startsWith("OK")) logs += r else failed += f
            }
            if (failed.size == pending.size) {
                failed.forEach { logs += load(ctx, it) }
                break
            }
            pending = failed
        }
        return logs
    }
}
