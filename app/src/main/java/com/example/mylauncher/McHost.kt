package com.example.mylauncher

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import dalvik.system.BaseDexClassLoader
import dalvik.system.PathClassLoader
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.InvocationTargetException
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

    private fun saveReport() {
        try {
            ModLoader.root.mkdirs()
            File(ModLoader.root, "libs.txt").writeText(report.joinToString("\n"))
        } catch (_: Throwable) {}
    }

    fun launch(ctx: Context): String = try {
        val app = ctx.applicationContext
        runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }

        val info = app.packageManager.getApplicationInfo(MC_PACKAGE, 0)
        val apks = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())

        val libDir = step("extract") { extractLibs(app, apks) }
        if (!dexDone) { step("dex") { injectDex(app, apks) }; dexDone = true }
        if (!nativeDone) { step("nativePath") { addNativePath(app, libDir) }; nativeDone = true }
        if (!assetsDone) { step("assets") { addAssets(app, apks) }; assetsDone = true }
        if (!libsLoaded) {
            step("loadLibs") { loadGameLibs(libDir) }
            libsLoaded = true
        }
        step("mods") { ModLoader.loadAll(app) }
        saveReport()
        step("startActivity") {
            ctx.startActivity(
                Intent().setClassName(app.packageName, "com.mojang.minecraftpe.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        "Launch dimulai\n" + report.joinToString("\n")
    } catch (e: Throwable) {
        saveReport()
        "GAGAL ${e.message}\n" + report.joinToString("\n")
    }

    // Muat semua .so game: dependency dulu, libminecraftpe terakhir. Semua hasil dicatat.
    private fun loadGameLibs(libDir: File) {
        report.clear()
        val all = (libDir.listFiles { f -> f.extension == "so" } ?: emptyArray()).toList()
        report += "libs(${all.size}): " +
            all.joinToString(" ") { it.name.removePrefix("lib").removeSuffix(".so") }
        report += "libpairipcore ada=${all.any { it.name == "libpairipcore.so" }}"

        val main = all.firstOrNull { it.name == "libminecraftpe.so" }
            ?: throw RuntimeException("libminecraftpe.so tidak ada di folder")

        val first = listOf("libc++_shared.so", "libpairipcore.so")
        var pending = all.filter { it != main }
            .sortedBy { val i = first.indexOf(it.name); if (i >= 0) i else 99 }
        val errors = mutableMapOf<String, String>()

        while (pending.isNotEmpty()) {
            val failed = mutableListOf<File>()
            for (f in pending) {
                try {
                    System.load(f.absolutePath)
                    errors.remove(f.name)
                } catch (e: Throwable) {
                    failed += f
                    errors[f.name] = root(e).message ?: "?"
                }
            }
            if (failed.size == pending.size) break
            pending = failed
        }
        pending.forEach { report += "gagal: ${it.name} -> ${errors[it.name]}" }

        try {
            System.load(main.absolutePath)
            report += "libminecraftpe: OK"
        } catch (e: Throwable) {
            report += "libminecraftpe: GAGAL -> ${root(e).message}"
            throw RuntimeException("libminecraftpe gagal dimuat")
        }
    }

    // Ambil .so arm64 dari APK Minecraft (termasuk split) ke folder sendiri
    private fun extractLibs(ctx: Context, apks: List<String>): File {
        val out = File(ctx.filesDir, "mc/lib").apply { mkdirs() }
        for (apk in apks) ZipFile(apk).use { z ->
            z.entries().asSequence()
                .filter { it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }
                .forEach { e ->
                    val dst = File(out, File(e.name).name)
                    if (!dst.exists() || dst.length() != e.size) {
                        z.getInputStream(e).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
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
