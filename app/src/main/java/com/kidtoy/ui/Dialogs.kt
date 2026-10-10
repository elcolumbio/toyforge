package com.kidtoy.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

@Composable
internal fun KidDialog(onDismiss: () -> Unit, title: String, emoji: String, widthFraction: Float = 0.72f, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        KidPanel(Modifier.fillMaxWidth(widthFraction), RoundedCornerShape(36.dp)) {
            Column(Modifier.padding(horizontal = 28.dp, vertical = 22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(emoji, fontSize = 40.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(title, fontSize = 32.sp, fontWeight = FontWeight.Black, color = Kid.Ink, modifier = Modifier.weight(1f))
                    RoundIconButton(Icons.Rounded.Close, "Close", onDismiss, size = 56.dp)
                }
                Spacer(Modifier.height(18.dp))
                content()
            }
        }
    }
}

@Composable
fun ExportDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val r = vm.report
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(vm.shapes) { vm.preparePrint() }
    KidDialog(onDismiss, "Ready to print?", "🖨️", widthFraction = 0.9f) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // 1) checks + recipe
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Check(SceneOps.Issue.NOT_ON_PLATE !in r.issues, "Sits on the plate", "Nothing touches the plate!")
                Check(SceneOps.Issue.FLOATING !in r.issues, "Everything is connected", "Some parts are floating (they blink red)")
                Check(SceneOps.Issue.TINY !in r.issues, "No tiny bits", "Some parts are too thin to print")
                Check(SceneOps.Issue.TOO_BIG !in r.issues, "Fits the printer", "Too big for the printer")
                Check(vm.weakJoints == 0, "Strong joints", "Thin joints could snap", action = if (vm.weakJoints > 0) "Make stronger" else null) {
                    vm.strengthenJoints()
                }
                val plan = vm.printPlan
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0x145B6CFF)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Printer recipe", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Kid.Blue)
                    if (plan == null) Text("Thinking about supports…", fontSize = 15.sp, color = Kid.Ink.copy(alpha = 0.6f))
                    else plan.kidSummary().forEach { Text("• $it", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Kid.Ink.copy(alpha = 0.75f)) }
                    if (plan != null) Text(String.format(Locale.US, "%.1f mm layers · %d walls · %d%% %s infill · PLA · %.0f × %.0f × %.0f mm",
                        plan.layerHeight, plan.wallLoops, plan.infillPercent, plan.infillPattern, r.size.x, r.size.z, r.size.y),
                        fontSize = 12.sp, color = Kid.Ink.copy(alpha = 0.5f))
                    if (plan != null && vm.printRefining) Text("Double-checking the details…", fontSize = 12.sp, color = Kid.Ink.copy(alpha = 0.45f))
                }
            }
            // 2) how to print: mode, time, size suggestion, send
            Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val opts = vm.printOptions
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (mode in com.kidtoy.io.PrintMode.values()) {
                        ModeCard(Modifier.weight(1f), mode, opts?.get(mode), vm.printMode == mode) { vm.printMode = mode }
                    }
                }
                opts?.get(vm.printMode)?.let { o ->
                    if (o.merges.isNotEmpty()) Text("To save colour swaps: " + o.merges.distinct().joinToString(", ") {
                        "${com.kidtoy.io.PrintPackager.colourName(it.first)} → ${com.kidtoy.io.PrintPackager.colourName(it.second)}" },
                        fontSize = 12.sp, color = Kid.Ink.copy(alpha = 0.55f))
                    com.kidtoy.io.PrintEstimate.shrinkSuggestion(o.estimate)?.let { (f, e) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(Color(0x33FF9F1C)).clickable { vm.shrinkToy(f) }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text("🐇", fontSize = 26.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Make it smaller to print faster", fontSize = 16.sp, fontWeight = FontWeight.Black, color = Kid.Ink)
                                Text("${(f * 100).toInt()}% size → ${e.kidTime()}, ${e.grams} g", fontSize = 13.sp, color = Kid.Ink.copy(alpha = 0.65f))
                            }
                        }
                    }
                }
                if (vm.exporting) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(52.dp)) {
                        CircularProgressIndicator(color = Kid.Purple, strokeWidth = 5.dp, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("Getting it ready…", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Kid.Ink)
                    }
                }
                BigAction(Icons.Rounded.Share, "Send to Bambu / Orca", Kid.Magic, !vm.exporting) {
                    vm.share(ExportFormat.BAMBU) { ctx.startActivity(it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigAction(Icons.Rounded.Download, "Save", Brush.linearGradient(listOf(Color(0xFF6FE3B4), Kid.Green)), !vm.exporting) {
                        vm.saveToDownloads(ExportFormat.BAMBU)
                    }
                    BigAction(Icons.Rounded.Share, "STL", Brush.linearGradient(listOf(Kid.Sky, Kid.Blue)), !vm.exporting) {
                        vm.share(ExportFormat.STL) { ctx.startActivity(it) }
                    }
                }
                Text("Grown-ups: open the .3mf in OrcaSlicer or Bambu Studio, check the plate, press Print.",
                    fontSize = 12.sp, color = Kid.Ink.copy(alpha = 0.55f))
                vm.lastExport?.let { Text("Last saved: $it", fontSize = 12.sp, color = Kid.Ink.copy(alpha = 0.5f)) }
            }
        }
    }
}

@Composable
private fun ModeCard(modifier: Modifier, mode: com.kidtoy.io.PrintMode, o: com.kidtoy.io.PrintOption?, selected: Boolean, onClick: () -> Unit) {
    val colours = o?.parts?.map { Color(it.color) } ?: emptyList()
    Column(modifier.clip(RoundedCornerShape(20.dp))
        .background(if (selected) Color(0x225B6CFF) else Color(0x0A000000))
        .border(if (selected) 3.dp else 1.dp, if (selected) Kid.Blue else Color(0x22000000), RoundedCornerShape(20.dp))
        .clickable(onClick = onClick).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(mode.kidLabel, fontSize = 16.sp, fontWeight = FontWeight.Black, color = Kid.Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            colours.take(4).forEach { Box(Modifier.size(16.dp).clip(androidx.compose.foundation.shape.CircleShape).background(it)
                .border(1.dp, Color(0x33000000), androidx.compose.foundation.shape.CircleShape)) }
        }
        if (o == null) Text("…", fontSize = 14.sp, color = Kid.Ink.copy(alpha = 0.5f))
        else {
            Text("⏱ ${o.estimate.kidTime()}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Kid.Ink.copy(alpha = 0.8f))
            Text("${o.estimate.grams} g" + if (o.estimate.colourChanges > 0) " · ${o.estimate.colourChanges} swaps" else " · no swaps",
                fontSize = 12.sp, color = Kid.Ink.copy(alpha = 0.55f))
        }
    }
}

@Composable
private fun Check(ok: Boolean, good: String, bad: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (ok) Color(0x223DDC97) else Color(0x33FF9F1C)).padding(horizontal = 12.dp, vertical = 9.dp)) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = if (ok) Kid.Green else Kid.Orange, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Text(if (ok) good else bad, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Kid.Ink, modifier = Modifier.weight(1f))
        if (action != null) Text(action, fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color.White,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Kid.Orange).clickable(onClick = onAction)
                .padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

@Composable
internal fun androidx.compose.foundation.layout.RowScope.BigAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Brush, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.weight(1f)) { BigActionButton(icon, label, bg, enabled, onClick) }
}

@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.BigAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Brush, enabled: Boolean, onClick: () -> Unit) {
    BigActionButton(icon, label, bg, enabled, onClick)
}

@Composable
internal fun BigActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Brush, enabled: Boolean, onClick: () -> Unit) {
    BouncyButton(onClick, Modifier.fillMaxWidth(), size = 64.dp, background = bg, shape = RoundedCornerShape(32.dp), enabled = enabled, description = label) {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 19.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
    }
}

@Composable
fun ProjectsDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    var showMuseum by remember { mutableStateOf(false) }
    if (showMuseum) { MuseumDialog(vm, onDismiss); return }
    var entries by remember { mutableStateOf<List<ProjectStore.Entry>>(emptyList()) }
    var starters by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    val ctx = LocalContext.current
    LaunchedEffect(refresh) { entries = vm.listProjects(); starters = vm.starters() }
    LaunchedEffect(Unit) { vm.checkMuseum() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "model.stl"
            vm.importModel(uri, name); onDismiss()
        }
    }
    KidDialog(onDismiss, "My toys", "🧸") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigAction(Icons.Rounded.Save, "Save my toy", Brush.linearGradient(listOf(Color(0xFF6FE3B4), Kid.Green)), true) {
                vm.saveProject(); refresh++
            }
            BigAction(Icons.Rounded.Add, "New toy", Kid.Sunny, true) { vm.newProject(); onDismiss() }
            BigAction(Icons.Rounded.FileOpen, "Open 3D file", Brush.linearGradient(listOf(Kid.Sky, Kid.Blue)), true) {
                picker.launch(arrayOf("*/*"))
            }
            if (vm.museumOpen) BigAction(Icons.Rounded.Search, "Museum", Kid.Magic, true) { showMuseum = true }
        }
        Spacer(Modifier.height(16.dp))
        Text("Start from…", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Kid.Ink.copy(alpha = 0.6f))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (s in starters) {
                Column(
                    Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xFFF1F5FF)).clickable { vm.loadStarter(s); onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.emoji, fontSize = 36.sp)
                    Text(s.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Kid.Ink)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Saved", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Kid.Ink.copy(alpha = 0.6f))
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Text("Nothing saved yet. Press “Save my toy”! 💾", fontSize = 18.sp, color = Kid.Ink.copy(alpha = 0.5f), modifier = Modifier.padding(12.dp))
        } else {
            LazyVerticalGrid(GridCells.Adaptive(170.dp), Modifier.height(260.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(entries, key = { it.file.name }) { e ->
                    val thumb = remember(e.file.name, refresh) {
                        vm.thumbnailFor(e).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
                    }
                    Box(Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xFFF1F5FF)).clickable { vm.openProject(e); onDismiss() }) {
                        Column {
                            Box(Modifier.fillMaxWidth().aspectRatio(1.5f).background(Color(0xFFDDEBFF)), contentAlignment = Alignment.Center) {
                                if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                else Text("🧸", fontSize = 40.sp)
                            }
                            Text(e.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Kid.Ink, maxLines = 1,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                        Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(40.dp).clip(CircleShape).background(Color.White)
                            .clickable { vm.deleteProject(e); refresh++ }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Delete, "Delete", tint = Kid.Red, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun MuseumDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("dinosaur") }
    var results by remember { mutableStateOf<List<LibraryItem>?>(null) }
    var searchCount by remember { mutableStateOf(0) }
    LaunchedEffect(searchCount) { if (searchCount > 0) results = vm.searchMuseum(query) }
    KidDialog(onDismiss, "Museum shelf", "🏛️") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KidPanel(Modifier.weight(1f).height(64.dp), RoundedCornerShape(32.dp)) {
                Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                    androidx.compose.foundation.text.BasicTextField(query, { query = it.take(40) }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Kid.Ink),
                        modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.width(12.dp))
            RoundIconButton(Icons.Rounded.Search, "Search", { searchCount++ }, enabled = !vm.museumBusy, size = 64.dp, background = Kid.Magic, tint = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text("Real objects scanned by the Smithsonian museum (free to use, CC0).", fontSize = 14.sp, color = Kid.Ink.copy(alpha = 0.55f))
        Spacer(Modifier.height(12.dp))
        when {
            vm.museumBusy -> CircularProgressIndicator(color = Kid.Purple, modifier = Modifier.size(48.dp))
            results == null -> Text("Type something and press search 🔍", fontSize = 18.sp, color = Kid.Ink.copy(alpha = 0.5f))
            results!!.isEmpty() -> Text("Nothing found. Try “dinosaur” or “shell”!", fontSize = 18.sp, color = Kid.Ink.copy(alpha = 0.5f))
            else -> LazyVerticalGrid(GridCells.Adaptive(200.dp), Modifier.height(280.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(results!!, key = { it.id }) { item ->
                    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFFF1F5FF)).clickable { vm.loadFromMuseum(item); onDismiss() }
                        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.emoji, fontSize = 30.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Kid.Ink, maxLines = 2)
                    }
                }
            }
        }
    }
}
