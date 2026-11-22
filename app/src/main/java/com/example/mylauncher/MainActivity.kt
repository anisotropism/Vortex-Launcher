package com.example.mylauncher

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

const val MC_PACKAGE = "com.mojang.minecraftpe"

fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

fun requestAllFilesAccess(ctx: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        ctx.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

fun isMinecraftInstalled(ctx: Context): Boolean = try {
    ctx.packageManager.getPackageInfo(MC_PACKAGE, 0); true
} catch (e: PackageManager.NameNotFoundException) { false }

// Simpan crash Java ke /sdcard/vortex/crash.txt
fun installCrashHandler() {
    val prev = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        try {
            ModLoader.root.mkdirs()
            File(ModLoader.root, "crash.txt")
                .writeText("Thread: ${t.name}\n" + android.util.Log.getStackTraceString(e))
        } catch (_: Throwable) {}
        prev?.uncaughtException(t, e)
    }
}

fun reasonName(r: Int): String = when (r) {
    ApplicationExitInfo.REASON_CRASH -> "CRASH (Java)"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
    ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (dibunuh sinyal)"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
    ApplicationExitInfo.REASON_ANR -> "ANR"
    ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
    else -> "LAINNYA ($r)"
}

fun printableRuns(b: ByteArray, min: Int = 6): List<String> {
    val res = mutableListOf<String>()
    val sb = StringBuilder()
    for (x in b) {
        val c = x.toInt() and 0xFF
        if (c in 32..126) sb.append(c.toChar())
        else {
            if (sb.length >= min) res += sb.toString()
            sb.setLength(0)
        }
    }
    if (sb.length >= min) res += sb.toString()
    return res
}

fun readCrashLog(ctx: Context): List<String> {
    val out = mutableListOf<String>()

    // 1. Crash Java yang sempat tercatat
    val cf = File(ModLoader.root, "crash.txt")
    if (cf.exists()) {
        out += "== crash.txt =="
        out += cf.readLines().take(25)
    }

    // 2. Alasan proses mati menurut Android (Android 11+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val list = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 3)
            if (list.isEmpty()) out += "Belum ada riwayat proses mati."
            list.forEachIndexed { i, r ->
                out += "== exit #$i: ${reasonName(r.reason)} status=${r.status} ${r.description ?: ""}"
                if (i == 0) {
                    try {
                        val bytes = r.traceInputStream?.use { it.readBytes() }
                        if (bytes != null && bytes.isNotEmpty()) {
                            try {
                                ModLoader.root.mkdirs()
                                File(ModLoader.root, "tombstone.bin").writeBytes(bytes)
                            } catch (_: Throwable) {}
                            val keys = listOf(".so", "SIG", "abort", "Check failed", "Fatal", "Exception", "pairip")
                            val hits = printableRuns(bytes)
                                .filter { s -> keys.any { s.contains(it, ignoreCase = true) } }
                                .distinct()
                                .take(25)
                            out += if (hits.isEmpty()) listOf("(trace ada, tapi tidak ada teks penting)") else hits
                        } else out += "(tidak ada trace)"
                    } catch (e: Throwable) {
                        out += "(gagal baca trace: ${e.message})"
                    }
                }
            }
        } catch (e: Throwable) {
            out += "Gagal baca exit info: ${e.message}"
        }
    } else {
        out += "Android < 11: exit info tidak tersedia."
    }

    // 3. Cadangan: logcat (sering kosong di MIUI/HyperOS)
    try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", "1500"))
        val full = p.inputStream.bufferedReader().readText()
        val keys = listOf("FATAL", "Fatal signal", "UnsatisfiedLink", "Caused by", "SIGSEGV", "SIGABRT", "dlopen")
        val prefix = Regex("^\\S+\\s+\\S+\\s+\\d+\\s+\\d+\\s+")
        val hits = full.lineSequence()
            .filter { l -> keys.any { l.contains(it, ignoreCase = true) } }
            .map { it.replace(prefix, "") }
            .toList().takeLast(10)
        if (hits.isNotEmpty()) { out += "== logcat =="; out += hits }
    } catch (_: Throwable) {}

    return out
}

class MainActivity : ComponentActivity() {
    private var access by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        setContent { MaterialTheme { HomeScreen(access) } }
    }

    override fun onResume() {
        super.onResume()
        access = hasAllFilesAccess()
        if (access) { ModLoader.modsDir(); ModLoader.gameDir() }
    }
}

@Composable
fun HomeScreen(access: Boolean) {
    val ctx = LocalContext.current
    val installed = remember { isMinecraftInstalled(ctx) }
    var mods by remember { mutableStateOf<List<File>>(emptyList()) }
    val logs = remember { mutableStateListOf<String>() }

    LaunchedEffect(access) { if (access) mods = ModLoader.listMods() }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Vortex", style = MaterialTheme.typography.headlineMedium)
        Text(if (installed) "Minecraft terdeteksi" else "Minecraft belum terinstall")
        Spacer(Modifier.height(12.dp))

        if (!access) {
            Text("Butuh izin akses semua file untuk folder /sdcard/vortex")
            Button(onClick = { requestAllFilesAccess(ctx) }) { Text("Beri izin") }
        } else {
            Text("Folder mod: /sdcard/vortex/mods", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = installed, onClick = {
                    logs.add(0, McHost.launch(ctx))
                }) { Text("Launch") }
                OutlinedButton(onClick = { mods = ModLoader.listMods() }) { Text("Refresh") }
                OutlinedButton(onClick = {
                    logs.addAll(0, ModLoader.loadAll(ctx))
                }) { Text("Load all") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    logs.clear()
                    logs.addAll(readCrashLog(ctx))
                }) { Text("Log") }
                OutlinedButton(onClick = { logs.clear() }) { Text("Bersihkan") }
            }
            Spacer(Modifier.height(12.dp))
            Text("Mods (${mods.size})", style = MaterialTheme.typography.titleMedium)

            LazyColumn(Modifier.weight(1f)) {
                items(mods) { f ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(f.name, Modifier.weight(1f))
                        Button(onClick = { logs.add(0, ModLoader.load(ctx, f)) }) { Text("Load") }
                    }
                }
            }
            if (logs.isNotEmpty()) {
                HorizontalDivider()
                Column(
                    Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                ) {
                    logs.take(80).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
