package com.example.mylauncher

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import dalvik.system.BaseDexClassLoader
import dalvik.system.PathClassLoader
import java.io.File
import java.util.zip.ZipFile

object McHost {
    private var prepared = false

    fun launch(ctx: Context): String = try {
        val app = ctx.applicationContext
        val info = app.packageManager.getApplicationInfo(MC_PACKAGE, 0)
        val apks = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())

        if (!prepared) {
            val libDir = extractLibs(app, apks)
            injectDex(app, apks)
            addNativePath(app, libDir)
            addAssets(app, apks)
            for (n in listOf("libc++_shared.so", "libfmod.so", "libminecraftpe.so")) {
                System.load(File(libDir, n).absolutePath)
            }
            prepared = true
        }
        ModLoader.loadAll(app)
        ctx.startActivity(
            Intent().setClassName(app.packageName, "com.mojang.minecraftpe.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        "Launch dimulai"
    } catch (e: Throwable) {
        "GAGAL: ${e.javaClass.simpleName}: ${e.message}"
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
        m.invoke(pl, listOf(dir))
    }

    // Asset game ada di APK Minecraft
    private fun addAssets(ctx: Context, apks: List<String>) {
        val m = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
        apks.forEach { m.invoke(ctx.assets, it) }
    }
}
