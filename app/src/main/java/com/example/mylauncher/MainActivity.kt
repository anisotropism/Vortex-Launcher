package com.example.mylauncher

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

class MainActivity : ComponentActivity() {
    private var access by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                logs.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
