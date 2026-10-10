package com.kidtoy.ui

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kidtoy.BuildConfig
import com.kidtoy.ai.AiDesign
import com.kidtoy.ai.AiException
import com.kidtoy.ai.AnthropicToyDesigner
import com.kidtoy.ai.OfflineToyDesigner
import com.kidtoy.ai.ToyDesigner
import com.kidtoy.geometry.Mesh
import com.kidtoy.geometry.Primitives
import com.kidtoy.geometry.Solidify
import com.kidtoy.geometry.Transforms
import com.kidtoy.io.ImportException
import com.kidtoy.io.MeshImporter
import com.kidtoy.io.ProjectJson
import com.kidtoy.io.ProjectStore
import com.kidtoy.io.StlExporter
import com.kidtoy.io.ThreeMfExporter
import com.kidtoy.library.LibraryContent
import com.kidtoy.library.LibraryItem
import com.kidtoy.library.ModelLibrary
import com.kidtoy.library.StarterLibrary
import com.kidtoy.model.ImportedMeshRef
import com.kidtoy.model.Palette
import com.kidtoy.model.Project
import com.kidtoy.model.SceneOps
import com.kidtoy.model.Shape
import com.kidtoy.model.ShapeType
import com.kidtoy.model.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class Axis { X, Y, Z, ALL }

sealed interface AiState {
    data object Idle : AiState
    data class Thinking(val improving: Boolean) : AiState
}

enum class ExportFormat(val ext: String, val mime: String) { STL("stl", "model/stl"), THREE_MF("3mf", "model/3mf"), BAMBU("3mf", "model/3mf") }

/** Feedback the UI turns into haptics / sounds / confetti. */
enum class Fx { POP, TICK, DELETE, MAGIC, CELEBRATE, ERROR }

/** CSG budget for the exact print solid; past it the remaining parts are exported as separate closed shells. */
private const val FUSE_BUDGET_MS = 8_000L

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private data class Snapshot(val shapes: List<Shape>, val name: String)

    var shapes by mutableStateOf<List<Shape>>(emptyList()); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var projectName by mutableStateOf("My Toy"); private set
    private var projectFile: String = newFileName()

    /** Imported meshes; never removed during a session so undo can bring them back. */
    private val meshes = HashMap<String, Mesh>()
    private val meshRefs = HashMap<String, ImportedMeshRef>()
    var meshVersion by mutableIntStateOf(0); private set

    private val undo = ArrayDeque<Snapshot>()
    private val redo = ArrayDeque<Snapshot>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    private var lastUndoKey: String? = null
    private var lastUndoAt = 0L

    var aiState by mutableStateOf<AiState>(AiState.Idle); private set
    /** Toy Buddy speech bubble. */
    var bubble by mutableStateOf<String?>(null); private set
    private var bubbleJob: Job? = null
    var fx by mutableStateOf<Pair<Fx, Long>?>(null); private set

    var exporting by mutableStateOf(false); private set
    var lastExport by mutableStateOf<String?>(null); private set

    val report by derivedStateOf { SceneOps.check(shapes, ::meshFor) }
    val selected: Shape? get() = shapes.firstOrNull { it.id == selectedId }

    private val store = ProjectStore(File(app.filesDir, "projects"))
    val library: ModelLibrary = StarterLibrary()
    val museum: ModelLibrary = com.kidtoy.library.SmithsonianLibrary(BuildConfig.SI_API_KEY.ifBlank { "DEMO_KEY" })

    /** Encrypted prefs: creating the Keystore master key is slow (seconds on emulators), so never on the main thread first. */
    val settings by lazy { com.kidtoy.settings.AppSettings(app) }

    /**
     * Who designs toys, in priority order: mock (debug demos) > parent's settings (proxy, then on-device key)
     * > debug build key from local.properties > offline templates. Release builds never contain a key.
     */
    var designer: ToyDesigner = if (BuildConfig.AI_MOCK) AnthropicToyDesigner("mock-key", com.kidtoy.settings.AppSettings.MODELS.first(), transport = com.kidtoy.ai.MockAnthropicTransport()) else OfflineToyDesigner(); private set
    /** "online" when Claude is wired up, "offline" for the built-in templates. */
    var aiMode by mutableStateOf(if (designer is OfflineToyDesigner) "offline" else "online"); private set
    /** False until the font, settings and autosaved toy are loaded (start-up work runs off the main thread). */
    var ready by mutableStateOf(false); private set
    /** First-run tour (3 cards); remembered in plain prefs (nothing secret). */
    var showOnboarding by mutableStateOf(false); private set
    private val uiPrefs get() = getApplication<Application>().getSharedPreferences("toyforge_ui", android.content.Context.MODE_PRIVATE)
    fun finishOnboarding() {
        showOnboarding = false
        viewModelScope.launch(Dispatchers.IO) { uiPrefs.edit().putBoolean("onboarded", true).apply() }
        fire(Fx.CELEBRATE)
    }

    private fun makeDesigner(): ToyDesigner {
        val model = settings.model
        return when {
            BuildConfig.AI_MOCK -> AnthropicToyDesigner("mock-key", model, transport = com.kidtoy.ai.MockAnthropicTransport())
            settings.proxyUrl.isNotBlank() -> AnthropicToyDesigner("", model, settings.proxyUrl)
            settings.apiKey.isNotBlank() -> AnthropicToyDesigner(settings.apiKey, model)
            BuildConfig.AI_PROXY_URL.isNotBlank() -> AnthropicToyDesigner("", BuildConfig.ANTHROPIC_MODEL, BuildConfig.AI_PROXY_URL)
            BuildConfig.ANTHROPIC_API_KEY.isNotBlank() -> AnthropicToyDesigner(BuildConfig.ANTHROPIC_API_KEY, BuildConfig.ANTHROPIC_MODEL)
            else -> OfflineToyDesigner()
        }
    }

    fun saveAiSettings(key: String?, model: String, proxy: String, bed: Int) {
        if (key != null) settings.apiKey = key
        settings.model = model; settings.proxyUrl = proxy; settings.bedSize = bed
        designer = makeDesigner()
        aiMode = if (designer is OfflineToyDesigner) "offline" else "online"
    }

    var aiCheck by mutableStateOf<String?>(null); private set

    /** One tiny real request with the cheap model so a parent can see the key works. */
    fun testAi(key: String, proxy: String) {
        aiCheck = "Checking…"
        viewModelScope.launch {
            aiCheck = try {
                val d = if (proxy.isNotBlank()) AnthropicToyDesigner("", "claude-haiku-5-5", proxy) else AnthropicToyDesigner(key.trim(), "claude-haiku-5-5")
                val r = d.design("a small red ball")
                "✅ Works! Claude built \"${r.name}\" with ${r.shapes.size} parts."
            } catch (e: Exception) {
                "❌ " + ((e as? com.kidtoy.ai.AiException)?.kidMessage ?: e.message ?: "Did not work")
            }
        }
    }

    /** Captures the 3D view for project thumbnails; installed by the GL view. */
    var captureThumbnail: (((Bitmap) -> Unit) -> Unit)? = null
    /** Asks the camera to frame the toy (after AI builds / loads). */
    var frameRequest by mutableIntStateOf(0); private set
    fun requestFrame() { frameRequest++ }

    init {
        viewModelScope.launch {
            // Everything slow happens on background threads, in this order: the font first (so 3D text in the
            // autosave never meshes as a placeholder cube), then encrypted settings, then the autosaved toy.
            val loaded = withContext(Dispatchers.IO) {
                if (com.kidtoy.text.Text3D.font == null) runCatching {
                    com.kidtoy.text.Text3D.font = com.kidtoy.text.TrueTypeFont(app.assets.open("fonts/LilitaOne-Regular.ttf").use { it.readBytes() })
                }
                val firstRun = !uiPrefs.getBoolean("onboarded", false)
                withContext(Dispatchers.Main) { showOnboarding = firstRun }
                val d = runCatching { makeDesigner() }.getOrElse { OfflineToyDesigner() }
                val f = store.autosaveFile()
                val p = if (f.exists()) runCatching { store.load(f) }.getOrNull() else null
                // warm the mesh cache (text outlines, imported models) so the first frame is cheap
                p?.shapes?.forEach { s -> if (s.type == ShapeType.TEXT) runCatching { s.text?.let { com.kidtoy.text.Text3D.build(it) } } }
                d to p
            }
            designer = loaded.first
            aiMode = if (designer is OfflineToyDesigner) "offline" else "online"
            // a kid who started building during the (slow) start-up keeps their toy
            if (shapes.isEmpty()) loaded.second?.let { applyProject(it, fileName = null) }
            ready = true
            if (pendingSave) { pendingSave = false; changed() }
            if (shapes.isEmpty() && !showOnboarding) say("Hi! Tap a shape to start building! 👈", 6000)
        }
    }

    fun meshFor(s: Shape): Mesh = com.kidtoy.geometry.ShapeMeshes.of(s, meshes)
    fun importedMeshes(): Map<String, Mesh> = meshes

    // ---------- feedback ----------
    fun say(text: String, ms: Long = 4500) {
        bubble = text
        bubbleJob?.cancel()
        bubbleJob = viewModelScope.launch { delay(ms); bubble = null }
    }
    private fun fire(f: Fx) { fx = f to SystemClock.uptimeMillis() }

    // ---------- history ----------
    private fun pushUndo(key: String? = null) {
        val now = SystemClock.uptimeMillis()
        if (key != null && key == lastUndoKey && now - lastUndoAt < 1200) { lastUndoAt = now; return }
        lastUndoKey = key; lastUndoAt = now
        undo.addLast(Snapshot(shapes, projectName))
        if (undo.size > 100) undo.removeFirst()
        redo.clear()
        refreshHistory()
    }
    private fun refreshHistory() { canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty() }

    fun undo() {
        val s = undo.removeLastOrNull() ?: return
        redo.addLast(Snapshot(shapes, projectName))
        shapes = s.shapes; projectName = s.name
        if (selectedId != null && shapes.none { it.id == selectedId }) selectedId = null
        lastUndoKey = null; refreshHistory(); changed(); fire(Fx.TICK)
    }

    fun redo() {
        val s = redo.removeLastOrNull() ?: return
        undo.addLast(Snapshot(shapes, projectName))
        shapes = s.shapes; projectName = s.name
        if (selectedId != null && shapes.none { it.id == selectedId }) selectedId = null
        lastUndoKey = null; refreshHistory(); changed(); fire(Fx.TICK)
    }

    private var autosaveJob: Job? = null
    private var pendingSave = false
    private fun changed() {
        if (!ready) { pendingSave = true; return } // the old autosave isn't loaded yet: don't overwrite it
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(800)
            val p = currentProject()
            withContext(Dispatchers.IO) { runCatching { store.save(p, ProjectStore.AUTOSAVE) } }
        }
    }

    /**
     * Called when the app goes to the background (onStop): writes a pending autosave right away, so a
     * process kill right after the last edit can't lose it. Runs on its own scope so it survives the VM.
     */
    fun flushAutosave() {
        val pending = autosaveJob?.isActive == true
        if (!pending || !ready) return
        autosaveJob?.cancel()
        val p = currentProject()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { store.save(p, ProjectStore.AUTOSAVE) } }
    }

    private fun update(id: String, undoKey: String?, f: (Shape) -> Shape) {
        val i = shapes.indexOfFirst { it.id == id }
        if (i < 0) return
        pushUndo(undoKey?.let { "$it:$id" })
        val s = SceneOps.settle(f(shapes[i]), meshFor(shapes[i]))
        shapes = shapes.toMutableList().also { it[i] = s }
        changed()
    }

    // ---------- editing ----------
    fun select(id: String?) {
        if (id != selectedId) { selectedId = id; if (id != null) fire(Fx.TICK) }
    }

    private var colorCursor = 0

    fun addShape(type: ShapeType) {
        val size = when (type) {
            ShapeType.CUBE -> Vec3(30f, 30f, 30f)
            ShapeType.SPHERE -> Vec3(30f, 30f, 30f)
            ShapeType.CYLINDER -> Vec3(28f, 30f, 28f)
            ShapeType.CONE -> Vec3(30f, 36f, 30f)
            ShapeType.TORUS -> Vec3(44f, 44f, 44f)
            ShapeType.STAR -> Vec3(40f, 40f, 12f)
            ShapeType.HEART -> Vec3(40f, 40f, 12f)
            ShapeType.PYRAMID -> Vec3(36f, 30f, 36f)
            ShapeType.MESH -> Vec3(60f, 60f, 60f)
            ShapeType.TEXT -> Vec3(60f, 18f, 6f)
        }
        val color = Palette.colors[colorCursor++ % 8]
        val base = Shape(UUID.randomUUID().toString(), type, Vec3(0f, size.y / 2, 0f), size, color = color)
        val mesh = meshFor(base)
        val sel = selected
        val placed = if (sel != null) {
            // stack on top of the selected part, overlapping a little so it prints as one piece
            val b = Transforms.bounds(sel, meshFor(sel))
            val nb = Transforms.bounds(base, mesh)
            base.copy(position = Vec3((b[0] + b[3]) / 2, base.position.y + (b[4] - nb[1]) - 2f, (b[2] + b[5]) / 2))
        } else freeSpot(base, mesh)
        pushUndo()
        val s = SceneOps.settle(placed, mesh)
        shapes = shapes + s
        selectedId = s.id
        changed(); fire(Fx.POP)
        if (shapes.size == 1) say("Drag it to move. Use the buttons to paint and stretch! 🎨")
    }

    private fun freeSpot(base: Shape, mesh: Mesh): Shape {
        val existing = shapes.map { Transforms.bounds(it, meshFor(it)) }
        val nb = Transforms.bounds(base, mesh)
        val r = maxOf(nb[3] - nb[0], nb[5] - nb[2]) / 2 + 4
        for (ring in 0..6) {
            val steps = if (ring == 0) 1 else ring * 8
            for (k in 0 until steps) {
                val a = 2 * Math.PI * k / steps
                val x = SceneOps.snap((ring * 2 * r * kotlin.math.cos(a)).toFloat())
                val z = SceneOps.snap((ring * 2 * r * kotlin.math.sin(a)).toFloat())
                if (kotlin.math.abs(x) + r > SceneOps.PLATE_HALF || kotlin.math.abs(z) + r > SceneOps.PLATE_HALF) continue
                val cand = floatArrayOf(x - r, 0f, z - r, x + r, 1000f, z + r)
                if (existing.none { Solidify.overlap(it, cand, 0f) }) return base.copy(position = Vec3(x, base.position.y, z))
            }
        }
        return base
    }

    // ---------- 3D text ----------
    /** Standing raised letters, placed like a new shape (on top of the selected part, or in a free spot). */
    fun addText(text: String) {
        val color = selected?.color?.let { c -> Palette.colors.firstOrNull { it != c && it != Palette.colors[9] } } ?: Palette.colors[colorCursor++ % 8]
        val base = com.kidtoy.model.NameTag.letters(text, color) ?: run { say("Type some letters first ✏️"); return }
        val mesh = meshFor(base)
        val sel = selected
        val placed = if (sel != null) {
            val b = Transforms.bounds(sel, meshFor(sel)); val nb = Transforms.bounds(base, mesh)
            base.copy(position = Vec3((b[0] + b[3]) / 2, base.position.y + (b[4] - nb[1]) - 2f, (b[2] + b[5]) / 2))
        } else freeSpot(base, mesh)
        pushUndo()
        val s = SceneOps.settle(placed, mesh)
        shapes = shapes + s; selectedId = s.id
        changed(); fire(Fx.POP)
        say("\"${s.text}\" in 3D letters! ✨")
    }

    /** Puts the whole toy on a base plate with the name lying on the front strip. */
    fun addNameplate(text: String) {
        val letters = Palette.colors[5]
        val result = com.kidtoy.model.NameTag.nameplate(shapes, ::meshFor, text, letters, Palette.colors[9])
            ?: run { say("Type some letters first ✏️"); return }
        pushUndo()
        shapes = result; selectedId = null
        changed(); fire(Fx.CELEBRATE); frameRequest++
        say("A name plate for ${com.kidtoy.text.Text3D.clean(text)}! 🏷️")
    }

    /** Starts a fresh keychain with the name on it (the old toy stays one undo away). */
    fun makeKeychain(text: String) {
        val result = com.kidtoy.model.NameTag.keychain(text, Palette.colors[9], Palette.colors[6])
            ?: run { say("Type some letters first ✏️"); return }
        pushUndo()
        shapes = result; selectedId = null
        projectName = "${com.kidtoy.text.Text3D.clean(text)}'s keychain".take(30)
        changed(); fire(Fx.CELEBRATE); frameRequest++
        say("A keychain for ${com.kidtoy.text.Text3D.clean(text)}! 🔑")
    }

    fun setColor(c: Int) { selectedId?.let { id -> update(id, "color") { it.copy(color = c) }; fire(Fx.TICK) } }

    fun resize(axis: Axis, factor: Float) {
        val id = selectedId ?: return
        update(id, "size$axis") { s ->
            fun g(v: Float) = (v * factor).coerceIn(3f, 190f)
            val ns = when (axis) {
                Axis.ALL -> if (s.scale.max() * factor > 190f || s.scale.min() * factor < 3f) s.scale else s.scale * factor
                Axis.X -> s.scale.copy(x = g(s.scale.x))
                Axis.Y -> s.scale.copy(y = g(s.scale.y))
                Axis.Z -> s.scale.copy(z = g(s.scale.z))
            }
            // keep the bottom where it was so parts don't sink into or jump off the plate
            val before = Transforms.bounds(s, meshFor(s))
            val after = Transforms.bounds(s.copy(scale = ns), meshFor(s))
            s.copy(scale = ns, position = s.position.copy(y = s.position.y + (before[1] - after[1])))
        }
        fire(Fx.TICK)
    }

    fun rotate(axis: Axis, deg: Float) {
        val id = selectedId ?: return
        update(id, "rot$axis") { s ->
            val r = when (axis) {
                Axis.X -> s.rotation.copy(x = (s.rotation.x + deg) % 360f)
                Axis.Y -> s.rotation.copy(y = (s.rotation.y + deg) % 360f)
                else -> s.rotation.copy(z = (s.rotation.z + deg) % 360f)
            }
            val before = Transforms.bounds(s, meshFor(s))
            val after = Transforms.bounds(s.copy(rotation = r), meshFor(s))
            s.copy(rotation = r, position = s.position.copy(y = s.position.y + (before[1] - after[1])))
        }
        fire(Fx.TICK)
    }

    fun lift(dy: Float) {
        val id = selectedId ?: return
        update(id, "lift") { it.copy(position = it.position.copy(y = it.position.y + dy)) }
        fire(Fx.TICK)
    }

    /** Drops the selected part straight down until it rests on the plate or on another part. */
    fun drop() {
        val id = selectedId ?: return
        val s = shapes.first { it.id == id }
        val b = Transforms.bounds(s, meshFor(s))
        var floor = 0f
        for (o in shapes) if (o.id != id) {
            val ob = Transforms.bounds(o, meshFor(o))
            val overlapXZ = ob[0] < b[3] && b[0] < ob[3] && ob[2] < b[5] && b[2] < ob[5]
            if (overlapXZ && ob[4] <= b[1] + 0.5f) floor = maxOf(floor, ob[4] - 1.5f)
        }
        update(id, null) { it.copy(position = it.position.copy(y = it.position.y - (b[1] - floor))) }
        fire(Fx.POP)
    }

    fun duplicate() {
        val s = selected ?: return
        pushUndo()
        val b = Transforms.bounds(s, meshFor(s))
        val copy = SceneOps.settle(s.copy(id = UUID.randomUUID().toString(),
            position = s.position + Vec3(SceneOps.snap((b[3] - b[0]) + 5f), 0f, 0f)), meshFor(s))
        shapes = shapes + copy
        selectedId = copy.id
        changed(); fire(Fx.POP)
    }

    fun deleteSelected() {
        val id = selectedId ?: return
        pushUndo()
        shapes = shapes.filter { it.id != id }
        selectedId = null
        changed(); fire(Fx.DELETE)
    }

    fun clearAll() {
        if (shapes.isEmpty()) return
        pushUndo()
        shapes = emptyList(); selectedId = null
        changed(); fire(Fx.DELETE)
        say("Fresh start! (Oops? Press undo ↩)")
    }

    // ---------- direct manipulation from the 3D view ----------
    private var dragStartPushed = false
    fun dragStart(id: String) { select(id); dragStartPushed = false }
    fun dragTo(id: String, x: Float, z: Float) {
        if (!dragStartPushed) { pushUndo(); dragStartPushed = true }
        val i = shapes.indexOfFirst { it.id == id }
        if (i < 0) return
        val s = shapes[i]
        shapes = shapes.toMutableList().also { it[i] = SceneOps.settle(s.copy(position = s.position.copy(x = x, z = z)), meshFor(s)) }
    }
    fun dragEnd(id: String) {
        val i = shapes.indexOfFirst { it.id == id }
        if (i < 0) return
        val s = shapes[i]
        shapes = shapes.toMutableList().also {
            it[i] = SceneOps.settle(s.copy(position = s.position.copy(x = SceneOps.snap(s.position.x), z = SceneOps.snap(s.position.z))), meshFor(s))
        }
        changed(); fire(Fx.TICK)
    }

    // ---------- AI ----------
    fun aiDesign(idea: String) {
        if (idea.isBlank() || aiState != AiState.Idle) return
        aiState = AiState.Thinking(improving = false)
        say("Hmm, let me think… 🤔", 60_000)
        viewModelScope.launch {
            try {
                applyDesign(designer.design(idea.trim()), keepImported = true)
            } catch (e: AiException) {
                say(e.kidMessage, 6000); fire(Fx.ERROR)
            } catch (e: Exception) {
                say("Oops, something went wrong. Try again!", 6000); fire(Fx.ERROR)
            } finally { aiState = AiState.Idle }
        }
    }

    fun aiImprove(wish: String?) {
        if (aiState != AiState.Idle) return
        val editable = shapes.filter { it.type != ShapeType.MESH }
        if (editable.isEmpty()) { say("Add some shapes first, then I can help! ✨"); return }
        aiState = AiState.Thinking(improving = true)
        say("Ooh, let me add some magic… ✨", 60_000)
        val imported = shapes.filter { it.type == ShapeType.MESH }
        val importedBounds = if (imported.isEmpty()) null else SceneOps.groupBounds(imported, ::meshFor)
        viewModelScope.launch {
            try {
                val d = designer.improve(projectName, editable, wish?.takeIf { it.isNotBlank() }, importedBounds)
                applyDesign(d, keepImported = true, recentre = false)
            } catch (e: AiException) {
                say(e.kidMessage, 6000); fire(Fx.ERROR)
            } catch (e: Exception) {
                say("Oops, something went wrong. Try again!", 6000); fire(Fx.ERROR)
            } finally { aiState = AiState.Idle }
        }
    }

    private fun applyDesign(d: AiDesign, keepImported: Boolean, recentre: Boolean = true) {
        pushUndo()
        val kept = if (keepImported) shapes.filter { it.type == ShapeType.MESH } else emptyList()
        val placed = if (recentre) SceneOps.placeGroup(d.shapes, ::meshFor)
        else d.shapes.map { SceneOps.settle(it, meshFor(it)) }.let { list ->
            // keep the improved toy grounded as a whole
            val minY = SceneOps.groupBounds(list, ::meshFor)[1]
            if (minY > 0.5f) list.map { it.copy(position = it.position - Vec3(0f, minY, 0f)) } else list
        }
        shapes = kept + placed
        selectedId = null
        if (d.name.isNotBlank() && (projectName == "My Toy" || recentre)) projectName = d.name
        changed(); fire(Fx.MAGIC); frameRequest++
        say(d.message.ifBlank { "Ta-da! ✨" }, 7000)
    }

    // ---------- import ----------
    fun importModel(uri: Uri, displayName: String) {
        viewModelScope.launch {
            try {
                val (mesh, name, ref) = withContext(Dispatchers.Default) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { MeshImporter.readLimited(it) }
                        ?: throw ImportException("Can't open file")
                    val m = MeshImporter.read(displayName, bytes)
                    val n = displayName.substringBeforeLast('.')
                    Triple(m, n, ProjectJson.meshToRef(UUID.randomUUID().toString(), n, m))
                }
                addImportedMesh(mesh, name, ref)
            } catch (e: ImportException) {
                say("I can't open that file 😕 (${e.message})", 6000); fire(Fx.ERROR)
            } catch (e: OutOfMemoryError) {
                say("That model is too big for me 😕", 6000); fire(Fx.ERROR)
            } catch (e: Exception) {
                say("I can't open that file 😕", 6000); fire(Fx.ERROR)
            }
        }
    }

    fun addImportedMesh(mesh: Mesh, name: String, ref: ImportedMeshRef? = null) {
        val id = ref?.id ?: UUID.randomUUID().toString()
        meshes[id] = mesh
        meshRefs[id] = ref ?: ProjectJson.meshToRef(id, name, mesh)
        meshVersion++
        val e = MeshImporter.extents(mesh)
        val size = 70f
        val s = Shape(UUID.randomUUID().toString(), ShapeType.MESH, Vec3(0f, e[1] * size / 2, 0f), Vec3(size, size, size),
            color = Palette.colors[4], meshId = id)
        pushUndo()
        val placed = SceneOps.settle(freeSpot(s, mesh), mesh)
        shapes = shapes + placed
        selectedId = placed.id
        changed(); fire(Fx.MAGIC); frameRequest++
        say("Here is $name! Add shapes to remix it! 🧩")
    }

    // ---------- library ----------
    suspend fun starters(): List<LibraryItem> = library.search("")
    fun loadStarter(item: LibraryItem) {
        viewModelScope.launch {
            when (val c = library.fetch(item)) {
                is LibraryContent.Shapes -> applyDesign(AiDesign(item.title, "Here's a ${item.title}! Make it yours! 🎨", c.shapes), keepImported = false)
                is LibraryContent.MeshFile -> addImportedMesh(MeshImporter.read(c.fileName, c.bytes), item.title)
            }
        }
    }

    var museumBusy by mutableStateOf(false); private set
    /** The museum shelf is only offered while its file host works (see SmithsonianLibrary.isAvailable). */
    var museumOpen by mutableStateOf(false); private set
    fun checkMuseum() { viewModelScope.launch { museumOpen = runCatching { museum.isAvailable() }.getOrDefault(false) } }

    suspend fun searchMuseum(q: String): List<LibraryItem> = try {
        museumBusy = true
        museum.search(q)
    } catch (e: com.kidtoy.library.LibraryException) {
        say(e.kidMessage, 6000); emptyList()
    } catch (e: Exception) {
        say("I can't reach the museum right now.", 6000); emptyList()
    } finally { museumBusy = false }

    fun loadFromMuseum(item: LibraryItem) {
        viewModelScope.launch {
            museumBusy = true
            say("Fetching ${item.title} from the museum… 🏛️", 60_000)
            try {
                val c = museum.fetch(item) as LibraryContent.MeshFile
                val mesh = withContext(Dispatchers.Default) { MeshImporter.read(c.fileName, c.bytes) }
                addImportedMesh(mesh, item.title)
            } catch (e: com.kidtoy.library.LibraryException) {
                say(e.kidMessage, 6000); fire(Fx.ERROR)
            } catch (e: Exception) {
                say("That one didn't work. Try another! 🙂", 6000); fire(Fx.ERROR)
            } finally { museumBusy = false }
        }
    }

    // ---------- projects ----------
    private fun currentProject() = Project(
        name = projectName,
        shapes = shapes,
        meshes = shapes.mapNotNull { it.meshId }.distinct().mapNotNull { meshRefs[it] },
        savedAt = System.currentTimeMillis(),
    )

    private fun applyProject(p: Project, fileName: String?) {
        meshes.clear(); meshRefs.clear()
        for (r in p.meshes) { meshes[r.id] = ProjectJson.refToMesh(r); meshRefs[r.id] = r }
        meshVersion++
        shapes = p.shapes; projectName = p.name; selectedId = null
        if (fileName != null) projectFile = fileName
        undo.clear(); redo.clear(); refreshHistory()
        frameRequest++
    }

    fun rename(name: String) { if (name.isNotBlank()) { pushUndo(); projectName = name.take(30); changed() } }

    fun saveProject() {
        val p = currentProject()
        val file = projectFile
        val capture = captureThumbnail
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.save(p, file) }
            capture?.invoke { bmp ->
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        val w = 480; val h = (bmp.height * w / bmp.width.coerceAtLeast(1)).coerceAtLeast(1)
                        File(store.thumbDir(), file.removeSuffix(".json") + ".png").outputStream().use {
                            Bitmap.createScaledBitmap(bmp, w, h, true).compress(Bitmap.CompressFormat.PNG, 90, it)
                        }
                    }
                }
            }
            say("Saved! 💾"); fire(Fx.CELEBRATE)
        }
    }

    suspend fun listProjects(): List<ProjectStore.Entry> = withContext(Dispatchers.IO) { store.list() }
    fun thumbnailFor(e: ProjectStore.Entry): File = File(store.thumbDir(), e.file.name.removeSuffix(".json") + ".png")

    fun openProject(e: ProjectStore.Entry) {
        viewModelScope.launch {
            val p = withContext(Dispatchers.IO) { store.load(e.file) }
            applyProject(p, e.file.name)
            changed(); fire(Fx.POP)
            say("Welcome back to ${p.name}! 👋")
        }
    }

    fun deleteProject(e: ProjectStore.Entry) {
        viewModelScope.launch(Dispatchers.IO) { store.delete(e.file); thumbnailFor(e).delete() }
    }

    fun newProject() {
        pushUndo()
        shapes = emptyList(); selectedId = null; projectName = "My Toy"; projectFile = newFileName()
        changed(); frameRequest++
        say("A brand new toy! Tap a shape to start 👈")
    }

    private fun newFileName() = "toy-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"

    // ---------- export ----------
    private var quickCache: Pair<List<Shape>, com.kidtoy.io.PrintPackage>? = null
    private var fullJob: Pair<List<Shape>, kotlinx.coroutines.Deferred<com.kidtoy.io.PrintPackage>>? = null
    /** The print recipe for the current toy (null while being worked out). */
    var printPlan by mutableStateOf<com.kidtoy.io.PrintPlan?>(null); private set
    /** Print mode chosen in the dialog ("One colour (fast)" by default: no AMS, no swaps). */
    var printMode by mutableStateOf(com.kidtoy.io.PrintMode.ONE_COLOUR)
    /** Both print modes with their estimates (null while being worked out). */
    var printOptions by mutableStateOf<Map<com.kidtoy.io.PrintMode, com.kidtoy.io.PrintOption>?>(null); private set
    /** Parts that hang on by a thin neck (see [com.kidtoy.geometry.Joints]). */
    var weakJoints by mutableStateOf(0); private set

    /** Sinks thinly attached parts (with everything hanging on them) a little into their neighbour. */
    fun strengthenJoints() {
        val (fixed, moved) = com.kidtoy.geometry.Joints.strengthen(shapes, ::meshFor)
        if (moved == 0) return
        pushUndo(); shapes = fixed; changed(); fire(Fx.CELEBRATE)
        say(if (moved == 1) "Glued one part on tighter 💪" else "Glued $moved parts on tighter 💪")
    }

    /** Scales the whole toy by [f] around its bottom centre (it stays on the plate). */
    fun shrinkToy(f: Float) {
        if (shapes.isEmpty() || f <= 0f || f >= 1f) return
        val b = shapes.map { com.kidtoy.geometry.Transforms.bounds(it, meshFor(it)) }
        val c = com.kidtoy.model.Vec3((b.minOf { it[0] } + b.maxOf { it[3] }) / 2, b.minOf { it[1] }, (b.minOf { it[2] } + b.maxOf { it[5] }) / 2)
        pushUndo()
        shapes = shapes.map { it.copy(position = c + (it.position - c) * f, scale = it.scale * f) }
        changed(); fire(Fx.TICK); frameRequest++
        say("Smaller toy, faster print! 🐢➡️🐇")
    }

    /** True while the instant recipe is shown and the exact one (from the fused solid) is still being worked out. */
    var printRefining by mutableStateOf(false); private set
    private var thumbPng: ByteArray? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun doneValue(d: kotlinx.coroutines.Deferred<com.kidtoy.io.PrintPackage>) = d.getCompleted()

    private suspend fun quickPackage(): com.kidtoy.io.PrintPackage {
        val snapshot = shapes
        quickCache?.let { if (it.first === snapshot) return it.second }
        val pkg = withContext(Dispatchers.Default) { com.kidtoy.io.PrintPackager.quick(snapshot, ::meshFor) }
        quickCache = snapshot to pkg
        return pkg
    }

    /** Fused package; started once per scene version and always finishes (CSG has a time budget). */
    private fun fullPackage(quick: com.kidtoy.io.PrintPackage?): kotlinx.coroutines.Deferred<com.kidtoy.io.PrintPackage> {
        val snapshot = shapes
        fullJob?.let { if (it.first === snapshot) return it.second }
        val job = viewModelScope.async(Dispatchers.Default) {
            com.kidtoy.io.PrintPackager.build(snapshot, ::meshFor, timeBudgetMs = FUSE_BUDGET_MS, quickPkg = quick)
        }
        fullJob = snapshot to job
        return job
    }

    /** Called when the print dialog opens: instant recipe first, exact one in the background, plus a thumbnail. */
    fun preparePrint() {
        val snapshot = shapes
        val done = fullJob?.takeIf { it.first === snapshot && it.second.isCompleted }?.second?.let { doneValue(it) }
        printPlan = done?.plan ?: quickCache?.takeIf { it.first === snapshot }?.second?.plan
        printRefining = done == null
        captureThumbnail?.invoke { bmp ->
            viewModelScope.launch(Dispatchers.Default) {
                val side = minOf(bmp.width, bmp.height)
                val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
                val small = Bitmap.createScaledBitmap(sq, 256, 256, true)
                thumbPng = java.io.ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            }
        }
        printOptions = (done ?: quickCache?.takeIf { it.first === snapshot }?.second)?.options
        if (snapshot.isNotEmpty()) viewModelScope.launch {
            val w = withContext(Dispatchers.Default) { com.kidtoy.geometry.Joints.weak(snapshot, ::meshFor).size }
            if (shapes === snapshot) weakJoints = w
        } else weakJoints = 0
        if (snapshot.isEmpty() || done != null) { printRefining = false; return }
        viewModelScope.launch {
            val quick = quickPackage()
            if (shapes === snapshot) printOptions = quick.options
            if (shapes === snapshot && printRefining) printPlan = quick.plan
            val full = fullPackage(quick).await()
            if (shapes === snapshot) { printPlan = full.plan; printRefining = false }
        }
    }

    private suspend fun buildExport(format: ExportFormat): ByteArray {
        val quick = quickPackage()
        // Bambu parts are shells anyway; only the recipe benefits from the fused solid, so use it if it is ready.
        val ready = fullJob?.takeIf { it.first === shapes && it.second.isCompleted }?.second?.let { doneValue(it) }
        val pkg = if (format == ExportFormat.BAMBU) (ready ?: quick) else fullPackage(quick).await()
        return withContext(Dispatchers.Default) {
            when (format) {
                ExportFormat.STL -> StlExporter.toBytes(pkg.solid.mesh, projectName)
                ExportFormat.THREE_MF -> java.io.ByteArrayOutputStream().also { ThreeMfExporter.write(pkg.solid.mesh, it, projectName) }.toByteArray()
                ExportFormat.BAMBU -> com.kidtoy.io.BambuProjectExporter.toBytes(pkg.options.getValue(printMode).parts, pkg.plan, projectName, settings.bedSize.toFloat(), thumbPng)
            }
        }
    }

    private fun exportName(format: ExportFormat): String {
        val safe = projectName.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifBlank { "toy" }
        val tag = if (format == ExportFormat.BAMBU) (if (printMode == com.kidtoy.io.PrintMode.ONE_COLOUR) "-bambu-1colour" else "-bambu-ams") else ""
        return "ToyForge-$safe$tag-" + SimpleDateFormat("HHmmss", Locale.US).format(Date()) + "." + format.ext
    }

    fun saveToDownloads(format: ExportFormat) {
        if (shapes.isEmpty() || exporting) return
        exporting = true
        viewModelScope.launch {
            try {
                val bytes = buildExport(format)
                val name = exportName(format)
                val where = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    if (android.os.Build.VERSION.SDK_INT < 29) {
                        // Android 8/9: no MediaStore.Downloads; use the app's own Downloads folder (no permission needed)
                        val dir = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "ToyForge").apply { mkdirs() }
                        File(dir, name).writeBytes(bytes)
                        return@withContext dir.absolutePath + "/" + name
                    }
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, format.mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/ToyForge")
                    }
                    val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
                    app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    "Download/ToyForge/$name"
                }
                lastExport = where
                say("Saved for printing! 🎉 Look in Downloads › ToyForge", 7000); fire(Fx.CELEBRATE)
            } catch (e: Exception) {
                say("Saving didn't work 😕", 6000); fire(Fx.ERROR)
            } finally { exporting = false }
        }
    }

    fun share(format: ExportFormat, launch: (Intent) -> Unit) {
        if (shapes.isEmpty() || exporting) return
        exporting = true
        viewModelScope.launch {
            try {
                val bytes = buildExport(format)
                val app = getApplication<Application>()
                val file = withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    File(dir, exportName(format)).apply { writeBytes(bytes) }
                }
                val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = format.mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, projectName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                launch(Intent.createChooser(send, projectName))
            } catch (e: Exception) {
                say("Sharing didn't work 😕", 6000); fire(Fx.ERROR)
            } finally { exporting = false }
        }
    }
}
