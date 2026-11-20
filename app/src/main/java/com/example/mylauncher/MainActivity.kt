package com.example.mylauncher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

const val MC_PACKAGE = "com.mojang.minecraftpe"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { HomeScreen() } }
    }
}

fun isMinecraftInstalled(ctx: Context): Boolean = try {
    ctx.packageManager.getPackageInfo(MC_PACKAGE, 0); true
} catch (e: PackageManager.NameNotFoundException) { false }

@Composable
fun HomeScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf("") }
    val installed = remember { isMinecraftInstalled(ctx) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("MyLauncher", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(if (installed) "Minecraft terdeteksi" else "Minecraft belum terinstall")
        Spacer(Modifier.height(24.dp))
        Button(enabled = installed, onClick = {
            val intent = ctx.packageManager.getLaunchIntentForPackage(MC_PACKAGE)
            if (intent != null) ctx.startActivity(intent) else status = "Gagal membuka game"
        }) { Text("Launch") }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(status)
        }
    }
}
