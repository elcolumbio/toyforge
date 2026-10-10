package com.kidtoy.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.VerticalAlignBottom
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kidtoy.geometry.Mesh
import com.kidtoy.model.Palette
import com.kidtoy.model.Shape
import com.kidtoy.model.ShapeType
import com.kidtoy.render.RenderScene
import com.kidtoy.render.ToyGLView

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val haptics = LocalHapticFeedback.current
    var showExport by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var showGate by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val confetti = remember { ConfettiState() }

    LaunchedEffect(vm.fx) {
        val (f, _) = vm.fx ?: return@LaunchedEffect
        when (f) {
            Fx.POP, Fx.MAGIC, Fx.CELEBRATE -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            Fx.DELETE, Fx.ERROR -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            Fx.TICK -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        Sounds.play(f)
        if (f == Fx.MAGIC || f == Fx.CELEBRATE) confetti.burst()
    }

    Box(Modifier.fillMaxSize()) {
        Canvas3D(vm)

        Row(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            ShapeShelf(vm) { showText = true }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TopBar(vm, onExport = { showExport = true }, onProjects = { showProjects = true }, onSettings = { showGate = true })
                Spacer(Modifier.weight(1f))
                BuddyBubble(vm.bubble, thinking = vm.aiState is AiState.Thinking)
                Spacer(Modifier.height(10.dp))
                MagicBar(vm)
            }
            AnimatedVisibility(
                visible = vm.selected != null,
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut(),
            ) {
                Row { Spacer(Modifier.width(16.dp)); EditPanel(vm) }
            }
        }
        ConfettiLayer(confetti)
        if (showExport) ExportDialog(vm) { showExport = false }
        if (showProjects) ProjectsDialog(vm) { showProjects = false }
        if (showText) TextDialog(vm) { showText = false }
        if (showGate) ParentGateDialog(onPass = { showGate = false; showSettings = true }, onDismiss = { showGate = false })
        if (showSettings) SettingsDialog(vm) { showSettings = false }
        if (vm.showOnboarding) Onboarding { vm.finishOnboarding(); vm.say("Tap a shape to start building! 👈", 6000) }
    }
}

@Composable
private fun Canvas3D(vm: EditorViewModel) {
    val shapes = vm.shapes
    val selected = vm.selectedId
    val warn = vm.report.floatingIds + vm.report.tinyIds
    val frame = vm.frameRequest
    val meshVersion = vm.meshVersion
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var glView by remember { mutableStateOf<ToyGLView?>(null) }
    androidx.compose.runtime.DisposableEffect(lifecycle, glView) {
        val v = glView
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> v?.onPause()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> v?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            ToyGLView(ctx, object : ToyGLView.Listener {
                override fun shapes(): List<Shape> = vm.shapes
                override fun meshFor(s: Shape): Mesh = vm.meshFor(s)
                override fun onTap(id: String?) = vm.select(id)
                override fun onDragStart(id: String) = vm.dragStart(id)
                override fun onDrag(id: String, x: Float, z: Float) = vm.dragTo(id, x, z)
                override fun onDragEnd(id: String) = vm.dragEnd(id)
            }).also { view ->
                vm.captureThumbnail = { cb -> view.renderer.requestCapture { bmp -> view.post { cb(bmp) } } }
                glView = view
            }
        },
        update = { view ->
            view.renderer.scene = RenderScene(shapes, selected, warn, vm.importedMeshes().toMap())
            if (view.tag != frame) {
                view.tag = frame
                if (shapes.isNotEmpty()) view.camera.frame(com.kidtoy.model.SceneOps.groupBounds(shapes, vm::meshFor)) else view.camera.home()
            }
            @Suppress("UNUSED_EXPRESSION") meshVersion
        },
    )
}

@Composable
private fun TopBar(vm: EditorViewModel, onExport: () -> Unit, onProjects: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        KidPanel(shape = RoundedCornerShape(30.dp)) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(ShapeType.STAR, 34.dp)
                Spacer(Modifier.width(10.dp))
                Text(vm.projectName, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Kid.Ink, maxLines = 1,
                    modifier = Modifier.widthIn(max = 260.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        KidPanel(shape = RoundedCornerShape(40.dp)) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Gap8, verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.AutoMirrored.Rounded.Undo, "Undo", vm::undo, enabled = vm.canUndo)
                RoundIconButton(Icons.AutoMirrored.Rounded.Redo, "Redo", vm::redo, enabled = vm.canRedo)
                RoundIconButton(Icons.Rounded.CenterFocusStrong, "Show my toy", { vm.requestFrame() })
                RoundIconButton(Icons.Rounded.Settings, "Grown-up settings", onSettings)
                RoundIconButton(Icons.Rounded.FolderOpen, "My toys", onProjects,
                    background = Brush.linearGradient(listOf(Color(0xFFFFF4C2), Color(0xFFFFE07A))))
                RoundIconButton(Icons.Rounded.Print, "Print", onExport, enabled = vm.shapes.isNotEmpty(),
                    background = Brush.linearGradient(listOf(Color(0xFF6FE3B4), Color(0xFF3DDC97))), tint = Color.White,
                    badge = if (vm.shapes.isNotEmpty() && !vm.report.ok) Kid.Orange else null)
            }
        }
    }
}

@Composable
private fun ShapeShelf(vm: EditorViewModel, onText: () -> Unit) {
    KidPanel(Modifier.fillMaxHeight().width(112.dp), RoundedCornerShape(34.dp)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            for (t in listOf(ShapeType.CUBE, ShapeType.SPHERE, ShapeType.CYLINDER, ShapeType.CONE, ShapeType.PYRAMID, ShapeType.TORUS, ShapeType.STAR, ShapeType.HEART)) {
                val c = ShapeColors[t] ?: Kid.Sky
                val bg = Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(c, Color.White, 0.88f), androidx.compose.ui.graphics.lerp(c, Color.White, 0.74f)))
                BouncyButton({ vm.addShape(t) }, size = 74.dp, shape = RoundedCornerShape(24.dp),
                    background = bg, description = "Add ${t.name.lowercase()}") {
                    ShapeIcon(t, 50.dp)
                }
            }
            BouncyButton(onText, size = 74.dp, shape = RoundedCornerShape(24.dp),
                background = Brush.verticalGradient(listOf(Color(0xFFF1E6FF), Color(0xFFE2CCFF))), description = "Add a name") {
                Text("Aa", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Kid.Purple)
            }
        }
    }
}

@Composable
private fun EditPanel(vm: EditorViewModel) {
    val sel = vm.selected
    KidPanel(Modifier.fillMaxHeight().width(244.dp), RoundedCornerShape(34.dp)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            for (row in Palette.colors.chunked(4)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in row) {
                        val isSel = sel?.color == c
                        val sc by androidx.compose.animation.core.animateFloatAsState(if (isSel) 1.18f else 1f, label = "swatch")
                        Box(
                            Modifier.size(42.dp).scale(sc).shadow(if (isSel) 6.dp else 2.dp, CircleShape)
                                .clip(CircleShape).background(Color(c))
                                .border(if (isSel) 4.dp else 2.dp, if (isSel) Kid.Ink else Color(0x22000000), CircleShape)
                                .clickable { vm.setColor(c) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val rowMod = Modifier.fillMaxWidth()
            val arr = Arrangement.SpaceEvenly
            val s = 62.dp
            Row(rowMod, horizontalArrangement = arr) {
                ToolButton(label = "Bigger", onClick = { vm.resize(Axis.ALL, 1.15f) }, repeat = true, size = s, custom = { StretchIcon("bigger") })
                ToolButton(label = "Smaller", onClick = { vm.resize(Axis.ALL, 1 / 1.15f) }, repeat = true, size = s, custom = { StretchIcon("smaller") })
                ToolButton(Icons.AutoMirrored.Rounded.RotateRight, "Spin", { vm.rotate(Axis.Y, 15f) }, tint = Kid.Blue, repeat = true, size = s)
            }
            Row(rowMod, horizontalArrangement = arr) {
                ToolButton(label = "Taller", onClick = { vm.resize(Axis.Y, 1.15f) }, repeat = true, size = s, custom = { StretchIcon("taller", color = Kid.Green) })
                ToolButton(label = "Shorter", onClick = { vm.resize(Axis.Y, 1 / 1.15f) }, repeat = true, size = s, custom = { StretchIcon("shorter", color = Kid.Green) })
                ToolButton(Icons.Rounded.Rotate90DegreesCw, "Tip over", { vm.rotate(Axis.Z, 90f) }, tint = Kid.Blue, size = s)
            }
            Row(rowMod, horizontalArrangement = arr) {
                ToolButton(label = "Wider", onClick = { vm.resize(Axis.X, 1.15f) }, repeat = true, size = s, custom = { StretchIcon("wider", color = Kid.Orange) })
                ToolButton(label = "Thinner", onClick = { vm.resize(Axis.X, 1 / 1.15f) }, repeat = true, size = s, custom = { StretchIcon("narrower", color = Kid.Orange) })
                ToolButton(Icons.Rounded.ArrowUpward, "Up", { vm.lift(5f) }, tint = Kid.Sky, repeat = true, size = s)
            }
            Row(rowMod, horizontalArrangement = arr) {
                ToolButton(label = "Fatter", onClick = { vm.resize(Axis.Z, 1.15f) }, repeat = true, size = s, custom = { StretchIcon("deeper", color = Kid.Purple) })
                ToolButton(label = "Flatter", onClick = { vm.resize(Axis.Z, 1 / 1.15f) }, repeat = true, size = s, custom = { StretchIcon("flatter", color = Kid.Purple) })
                ToolButton(Icons.Rounded.ArrowDownward, "Down", { vm.lift(-5f) }, tint = Kid.Sky, repeat = true, size = s)
            }
            Row(rowMod, horizontalArrangement = arr) {
                ToolButton(Icons.Rounded.ContentCopy, "Copy", vm::duplicate, tint = Kid.Purple, size = s)
                ToolButton(Icons.Rounded.VerticalAlignBottom, "Drop", vm::drop, tint = Kid.Sky, size = s)
                ToolButton(Icons.Rounded.DeleteForever, "Bin", vm::deleteSelected, tint = Color.White, size = s,
                    background = Brush.linearGradient(listOf(Color(0xFFFF8A8E), Kid.Red)))
            }
        }
    }
}

@Composable
private fun BuddyBubble(text: String?, thinking: Boolean) {
    AnimatedVisibility(
        visible = text != null,
        enter = scaleIn(transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)) + fadeIn(),
        exit = scaleOut(transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)) + fadeOut(),
    ) {
        val inf = rememberInfiniteTransition(label = "buddy")
        val bob by inf.animateFloat(-4f, 4f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
        val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
        Row(verticalAlignment = Alignment.Bottom) {
            Box(
                Modifier.padding(bottom = (6 + bob).dp).size(64.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(Kid.Magic),
                contentAlignment = Alignment.Center,
            ) {
                if (thinking) Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(34.dp).rotate(spin))
                else Text("🤖", fontSize = 32.sp)
            }
            Spacer(Modifier.width(8.dp))
            KidPanel(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 6.dp)) {
                Text(text ?: "", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Kid.Ink,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp).widthIn(max = 460.dp))
            }
        }
    }
}

private val ideas = listOf("🐉 dragon car", "🚀 rocket", "🤖 robot", "🐱 kitty", "🏰 castle", "🦄 unicorn", "⛄ snowman", "🚂 train")

@Composable
private fun MagicBar(vm: EditorViewModel) {
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val ctx = LocalContext.current
    val thinking = vm.aiState is AiState.Thinking
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) { text = said; vm.aiDesign(said) }
    }
    fun go() { focus.clearFocus(); vm.aiDesign(text) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().imePadding()) {
        AnimatedVisibility(!thinking, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            LazyRow(horizontalArrangement = Gap8, modifier = Modifier.padding(bottom = 10.dp)) {
                items(ideas) { idea ->
                    Box(
                        Modifier.shadow(4.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Color.White)
                            .clickable { text = idea.substringAfter(' '); vm.aiDesign(text) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) { Text(idea, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Kid.Ink) }
                }
            }
        }
        KidPanel(Modifier.fillMaxWidth().height(84.dp), RoundedCornerShape(42.dp)) {
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(62.dp).clip(CircleShape).background(Kid.Magic), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(if (thinking) "Building your idea…" else "What should we build?", fontSize = 22.sp,
                        color = Kid.Ink.copy(alpha = 0.4f), fontWeight = FontWeight.Bold)
                    BasicTextField(
                        value = text, onValueChange = { text = it.take(120) }, singleLine = true, enabled = !thinking,
                        textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Kid.Ink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                RoundIconButton(Icons.Rounded.Mic, "Talk", {
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "What should we build?")
                    }
                    if (i.resolveActivity(ctx.packageManager) != null) speech.launch(i) else vm.say("This tablet can't listen yet. Try typing! ⌨️")
                }, enabled = !thinking, background = Brush.linearGradient(listOf(Color(0xFFFFE3EF), Color(0xFFFFC7DE))), tint = Kid.Pink)
                Spacer(Modifier.width(8.dp))
                RoundIconButton(Icons.AutoMirrored.Rounded.Send, "Build it", { go() }, enabled = !thinking && text.isNotBlank(),
                    background = Kid.Magic, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                BouncyButton({ vm.aiImprove(text.takeIf { it.isNotBlank() }) }, size = 64.dp, shape = RoundedCornerShape(32.dp),
                    background = Kid.Sunny, enabled = !thinking && vm.shapes.isNotEmpty() && vm.designer.isOnline, description = "Make it better") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("✨", fontSize = 20.sp)
                        Text("Better", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Kid.Ink)
                    }
                }
            }
        }
        if (thinking) {
            Spacer(Modifier.height(8.dp))
            RainbowProgress()
        }
    }
}

@Composable
private fun RainbowProgress() {
    val inf = rememberInfiniteTransition(label = "rainbow")
    val x by inf.animateFloat(-0.35f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "x")
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth(0.6f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White)) {
        drawRect(Kid.Magic, topLeft = androidx.compose.ui.geometry.Offset(x * size.width, 0f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.35f, size.height))
    }
}

/** Download icon re-export for dialogs. */
val DownloadIcon = Icons.Rounded.Download
