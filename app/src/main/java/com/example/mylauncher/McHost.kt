package com.example.mylauncher

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import dalvik.system.BaseDexClassLoader
import dalvik.system.PathClassLoader
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipFile

object McHost {
    private var dexDone = false
    private var nativeDone = false
    private var assetsDone = false
    private var libsLoaded = false
    private val report = mutableListOf<String>()

    private fun root(e: Throwable): Throwable {
        var t = e
        while (true) {
            val next = if (t is InvocationTargetException) t.targetException else t.cause
            if (next == null || next === t) return t
            t = next
        }
    }

    private inline fun <T> step(name: String, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        val r = root(e)
        val where = r.stackTrace.take(3).joinToString(" < ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        throw RuntimeException("[$name] ${r.javaClass.simpleName}: ${r.message} @ $where")
    }

    // Tulis ke /sdcard/vortex/libs.txt dan paksa tersimpan sebelum lanjut
    private fun progress(msg: String) {
        report += msg
        try {
            ModLoader.root.mkdirs()
            FileOutputStream(File(ModLoader.root, "libs.txt"), true).use { o ->
                o.write((msg + "\n").toByteArray())
                o.fd.sync()
            }
        } catch (_: Throwable) {}
    }

    fun launch(ctx: Context): String = try {
        val app = ctx.applicationContext
        runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        report.clear()
        runCatching { File(ModLoader.root, "libs.txt").delete() }

        val info = app.packageManager.getApplicationInfo(MC_PACKAGE, 0)
        val apks = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
        progress("android ${android.os.Build.VERSION.SDK_INT}, apk: ${apks.size} file")

        val libDir = step("extract") { extractLibs(app, apks) }
        if (!dexDone) { step("dex") { injectDex(app, apks) }; dexDone = true; progress("dex ok") }
        if (!nativeDone) { step("nativePath") { addNativePath(app, libDir) }; nativeDone = true; progress("nativePath ok") }
        if (!assetsDone) { step("assets") { addAssets(app, apks) }; assetsDone = true; progress("assets ok") }
        if (!libsLoaded) {
            step("loadLibs") { loadGameLibs(libDir) }
            libsLoaded = true
        }
        step("mods") { ModLoader.loadAll(app) }
        progress("mods ok, buka activity game")
        step("startActivity") {
            ctx.startActivity(
                Intent().setClassName(app.packageName, "com.mojang.minecraftpe.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        "Launch dimulai\n" + report.takeLast(6).joinToString("\n")
    } catch (e: Throwable) {
        progress("GAGAL ${e.message}")
        "GAGAL ${e.message}"
    }

    // Baca daftar dependency (DT_NEEDED) dari file ELF 64-bit
    private fun neededOf(f: File): List<String> = try {
        RandomAccessFile(f, "r").use { r ->
            val le = ByteOrder.LITTLE_ENDIAN
            val h = ByteArray(64); r.readFully(h)
            val hb = ByteBuffer.wrap(h).order(le)
            val phoff = hb.getLong(0x20)
            val phentsize = hb.getShort(0x36).toInt() and 0xFFFF
            val phnum = hb.getShort(0x38).toInt() and 0xFFFF
            val ph = ByteArray(phentsize * phnum)
            r.seek(phoff); r.readFully(ph)
            val pb = ByteBuffer.wrap(ph).order(le)

            class Seg(val off: Long, val vaddr: Long, val size: Long)
            val loads = mutableListOf<Seg>()
            var dyn: Seg? = null
            for (i in 0 until phnum) {
                val b = i * phentsize
                val type = pb.getInt(b)
                val seg = Seg(pb.getLong(b + 8), pb.getLong(b + 16), pb.getLong(b + 32))
                if (type == 1) loads += seg
                if (type == 2) dyn = seg
            }
            val d = dyn ?: return emptyList()
            val db = ByteArray(d.size.toInt()); r.seek(d.off); r.readFully(db)
            val dbb = ByteBuffer.wrap(db).order(le)
            val needIdx = mutableListOf<Long>()
            var strtab = 0L
            var p = 0
            while (p + 16 <= db.size) {
                val tag = dbb.getLong(p); val v = dbb.getLong(p + 8)
                if (tag == 0L) break
                if (tag == 1L) needIdx += v
                if (tag == 5L) strtab = v
                p += 16
            }
            val seg = loads.firstOrNull { strtab >= it.vaddr && strtab < it.vaddr + it.size }
                ?: return emptyList()
            val strOff = strtab - seg.vaddr + seg.off
            needIdx.map { idx ->
                r.seek(strOff + idx)
                val sb = StringBuilder()
                while (true) {
                    val c = r.read()
                    if (c <= 0) break
                    sb.append(c.toChar())
                }
                sb.toString()
            }
        }
    } catch (e: Throwable) {
        emptyList()
    }

    // Muat semua .so sesuai urutan dependency aslinya (dependency dulu), semuanya dicatat
    private fun loadGameLibs(libDir: File) {
        val all = (libDir.listFiles { f -> f.extension == "so" } ?: emptyArray()).toList()
        val byName = all.associateBy { it.name }
        progress("libs(${all.size})")
        all.sortedBy { it.name }.forEach { f ->
            progress("${f.name} butuh: ${neededOf(f).joinToString(" ")}")
        }

        val main = byName["libminecraftpe.so"]
            ?: throw RuntimeException("libminecraftpe.so tidak ada di folder")

        val done = mutableSetOf<String>()
        fun loadRec(f: File) {
            if (!done.add(f.name)) return
            neededOf(f).forEach { n -> byName[n]?.let { loadRec(it) } }
            progress("load ${f.name} (${f.length()} byte)")
            try {
                System.load(f.absolutePath)
                progress("  ok")
            } catch (e: Throwable) {
                progress("  gagal: ${root(e).message}")
                if (f == main) throw RuntimeException("libminecraftpe gagal dimuat")
            }
        }

        loadRec(main)
        all.forEach { loadRec(it) } // sisanya kalau ada yang belum terpanggil
    }

    private fun crcOf(f: File): Long {
        val c = CRC32()
        f.inputStream().use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                c.update(buf, 0, n)
            }
        }
        return c.value
    }

    // Ambil .so arm64 dari APK Minecraft (termasuk split), cek CRC supaya tidak ada file rusak
    private fun extractLibs(ctx: Context, apks: List<String>): File {
        val out = File(ctx.filesDir, "mc/lib").apply { mkdirs() }
        for (apk in apks) ZipFile(apk).use { z ->
            z.entries().asSequence()
                .filter { it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }
                .forEach { e ->
                    val dst = File(out, File(e.name).name)
                    val ok = dst.exists() && dst.length() == e.size &&
                        (e.crc == -1L || crcOf(dst) == e.crc)
                    if (!ok) {
                        dst.delete()
                        z.getInputStream(e).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                        if (e.crc != -1L && crcOf(dst) != e.crc) {
                            throw RuntimeException("CRC tidak cocok setelah extract: ${dst.name}")
                        }
                        progress("extract ulang: ${dst.name}")
                    }
                }
        }
        return out
    }

    private fun pathList(cl: ClassLoader): Any {
        val f = BaseDexClassLoader::class.java.getDeclaredField("pathList")
        f.isAccessible = true
        return f.get(cl)!!
    }

    // Gabungkan dex Minecraft ke class loader Vortex
    private fun injectDex(ctx: Context, apks: List<String>) {
        val mc = PathClassLoader(apks.joinToString(File.pathSeparator), null)
        val ours = pathList(ctx.classLoader)
        val theirs = pathList(mc)
        val f = ours.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }
        val a = f.get(ours) as Array<*>
        val b = f.get(theirs) as Array<*>
        val merged = java.lang.reflect.Array.newInstance(a.javaClass.componentType!!, a.size + b.size)
        System.arraycopy(a, 0, merged, 0, a.size)
        System.arraycopy(b, 0, merged, a.size, b.size)
        f.set(ours, merged)
    }

    // Supaya NativeActivity bisa menemukan libminecraftpe lewat class loader
    private fun addNativePath(ctx: Context, dir: File) {
        val pl = pathList(ctx.classLoader)
        val m = pl.javaClass.getDeclaredMethod("addNativePath", java.util.Collection::class.java)
        m.isAccessible = true
        try {
            m.invoke(pl, listOf(dir.absolutePath))
        } catch (e1: Throwable) {
            try {
                m.invoke(pl, listOf(dir))
            } catch (e2: Throwable) {
                throw RuntimeException(
                    "addNativePath String: ${root(e1).message} || File: ${root(e2).message}"
                )
            }
        }
    }

    // Asset game ada di APK Minecraft
    private fun addAssets(ctx: Context, apks: List<String>) {
        val m = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
        apks.forEach { m.invoke(ctx.assets, it) }
    }
}
