package com.kidtoy.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import com.kidtoy.io.ProjectStore
import com.kidtoy.library.LibraryItem
import com.kidtoy.model.SceneOps
import java.util.Locale
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import com.kidtoy.settings.AppSettings
import com.kidtoy.text.Text3D

/** "Write a name": raised standing letters, or a name plate under the whole toy. */
@Composable
fun TextDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    KidDialog(onDismiss, "Write a name", "🔤") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(Text3D.MAX_CHARS) }, singleLine = true,
                placeholder = { Text("Mika", fontSize = 26.sp, color = Kid.Ink.copy(alpha = 0.3f)) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Black, color = Kid.Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigAction(Icons.Rounded.TextFields, "Stand-up letters", Kid.Magic, text.isNotBlank()) { vm.addText(text); onDismiss() }
                BigAction(Icons.Rounded.Badge, "Name plate under my toy", Brush.linearGradient(listOf(Kid.Orange, Kid.Pink)), text.isNotBlank()) {
                    vm.addNameplate(text); onDismiss()
                }
                BigAction(Icons.Rounded.Key, "Keychain", Brush.linearGradient(listOf(Kid.Sky, Kid.Purple)), text.isNotBlank()) {
                    vm.makeKeychain(text); onDismiss()
                }
            }
        }
    }
}

/** Simple grown-up check (a times-table question with big number buttons) in front of the settings. */
@Composable
fun ParentGateDialog(onPass: () -> Unit, onDismiss: () -> Unit) {
    val a = remember { (6..9).random() }; val b = remember { (6..9).random() }
    var answer by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    KidDialog(onDismiss, "Grown-ups only", "🔒") {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("What is $a × $b ?", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Kid.Ink)
                Text(if (answer.isEmpty()) " " else answer, fontSize = 44.sp, fontWeight = FontWeight.Black, color = if (wrong) Kid.Red else Kid.Blue)
                Text("Settings for the AI key and the printer.", fontSize = 16.sp, color = Kid.Ink.copy(alpha = 0.55f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in row) {
                            BouncyButton({
                                wrong = false
                                when (k) {
                                    "⌫" -> answer = answer.dropLast(1)
                                    "OK" -> if (answer.toIntOrNull() == a * b) onPass() else { wrong = true; answer = "" }
                                    else -> if (answer.length < 3) answer += k
                                }
                            }, size = 64.dp, background = if (k == "OK") Kid.Magic else Brush.linearGradient(listOf(Color(0xFFEFF3FF), Color(0xFFE2E8FF))),
                                shape = RoundedCornerShape(20.dp), description = k) {
                                Text(k, fontSize = 24.sp, fontWeight = FontWeight.Black, color = if (k == "OK") Color.White else Kid.Ink)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val s = vm.settings
    var key by remember { mutableStateOf("") }
    var proxy by remember { mutableStateOf(s.proxyUrl) }
    var model by remember { mutableStateOf(s.model) }
    var bed by remember { mutableStateOf(s.bedSize) }
    val saved = s.apiKey
    KidDialog(onDismiss, "Grown-up settings", "⚙️") {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Claude (Anthropic) API key", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Kid.Ink)
                Text(if (saved.isBlank()) "No key saved yet - the app uses its built-in offline ideas."
                    else "Saved (encrypted on this tablet): ${AppSettings.mask(saved)}", fontSize = 14.sp, color = Kid.Ink.copy(alpha = 0.6f))
                if (s.keystoreBroken) Text("This device's secure storage is not working, so the key cannot be saved.", color = Kid.Red, fontSize = 14.sp)
                OutlinedTextField(key, { key = it.trim() }, singleLine = true, placeholder = { Text("sk-ant-…") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth())
                Text("Or a proxy URL (your own server keeps the key)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Kid.Ink.copy(alpha = 0.7f))
                OutlinedTextField(proxy, { proxy = it.trim() }, singleLine = true, placeholder = { Text("https://…/v1/messages") },
                    shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Model:", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Kid.Ink)
                    for (m in AppSettings.MODELS) Chip(m.removePrefix("claude-"), model == m) { model = m }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Printer:", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Kid.Ink)
                    for ((size, label) in AppSettings.BEDS) Chip(label, bed == size) { bed = size }
                }
                vm.aiCheck?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Kid.Ink) }
            }
            Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BigAction(Icons.Rounded.Save, "Save", Kid.Magic, true) {
                    vm.saveAiSettings(key.ifBlank { null }, model, proxy, bed); onDismiss()
                }
                BigAction(Icons.Rounded.Wifi, "Test (tiny request)", Brush.linearGradient(listOf(Kid.Sky, Kid.Blue)), key.isNotBlank() || saved.isNotBlank() || proxy.isNotBlank()) {
                    vm.testAi(key.ifBlank { saved }, proxy)
                }
                BigAction(Icons.Rounded.Delete, "Remove key", Brush.linearGradient(listOf(Kid.Orange, Kid.Red)), saved.isNotBlank()) {
                    vm.saveAiSettings("", model, proxy, bed); onDismiss()
                }
                Text("The key is only sent to api.anthropic.com. Release builds never contain a key.",
                    fontSize = 12.sp, color = Kid.Ink.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Kid.Blue else Color(0xFFEFF3FF))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else Kid.Ink)
    }
}
