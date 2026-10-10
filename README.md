# ToyForge – design toys, print them

A native Android tablet app (Kotlin + Jetpack Compose + OpenGL ES 2.0) that lets children
build toys from chunky 3D shapes, get help from an AI "Toy Buddy" (Claude), and export a
printable STL / 3MF.

Sideload build: `./gradlew assembleRelease` gives a signed APK when a keystore is configured (Android 8.0+).

## What a kid can do

| | |
|---|---|
| **Add shapes** | Left shelf: cube, ball, cylinder, cone, pyramid, donut, star, heart. Tap = a shape pops onto the plate. If a part is selected, the new one is stacked on top of it (overlapping 2 mm so it prints as one piece). |
| **Move** | Drag a shape with one finger, it slides over the build plate and snaps to a 5 mm grid on release. |
| **Look around** | One finger on empty space = orbit, pinch = zoom, two fingers = pan, double-tap empty = reset view. |
| **Edit** | Right panel (appears when something is selected): 11 colours, bigger/smaller, taller/shorter, wider/thinner, fatter/flatter, spin 15°, tip over 90°, up/down, drop (falls onto the part below), copy, bin. Hold a button to repeat. |
| **Undo / redo** | Top bar, 100 steps. Repeated taps on the same tool are merged into one step. |
| **AI** | Bottom "magic bar": type or speak an idea ("a dragon car") or tap an idea chip → Claude returns a JSON list of shapes that is placed on the plate. **✨ Better** sends the current scene and gets an improved version back. |
| **Print** | Printer button → checklist (on the plate, nothing floating, no tiny bits, fits the printer, size in mm) + a "printer recipe" (supports? brim?) → **Send to Bambu / Orca** (share sheet, Bambu Studio/OrcaSlicer project 3MF), **Save on this tablet** (`Download/ToyForge/`), or **Share STL** for any other slicer. Parts that would float or are too thin blink red in 3D, and the printer button gets an orange dot. |
| **Names** | "Aa" tile: type a name (e.g. *Mika*) → **Stand-up letters** (raised 3D letters, placed on the selected part) or **Name plate under my toy** (the toy is lifted onto a plate, the name lies on its front strip). Letters are real solids (Lilita One font, OFL) fused with the toy. |
| **Grown-ups** | ⚙️ in the top bar → times-table gate → Claude API key (stored encrypted on the tablet), optional proxy URL, model (sonnet / haiku), printer bed (X1/P1/A1 256 mm or A1 mini 180 mm), "Test" button (one tiny haiku request). |
| **My toys** | Save (with thumbnail), open, new, delete. Autosave on every change. "Start from…" starter toys (car, rocket, robot, snowman, house, cat). Open an STL/OBJ from device storage and add shapes to remix it. |

Everything kid-facing is icon-first, big (≥ 56 dp touch targets, most 64–92 dp), animated
(spring "squish" on buttons, pop-in of new shapes, pulsing selection ring, confetti), has haptics
and tiny synthesised sound effects, and uses very few words.

## Build

Requirements: JDK 17+ (tested with OpenJDK 21), Android SDK platform 35 + build-tools 35.

```bash
cd app                       # this folder
cat > local.properties <<EOF2
sdk.dir=/path/to/android-sdk
ANTHROPIC_API_KEY=sk-ant-...        # optional, DEV ONLY (see below)
ANTHROPIC_MODEL=claude-sonnet-5-5   # optional, default claude-sonnet-5-5
# AI_PROXY_URL=https://your-backend.example.com   # optional, takes precedence over the key
EOF2
./gradlew testDebugUnitTest   # unit tests
./gradlew assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # signed if KEYSTORE_PROPERTIES is set (never committed)
```

* `AI_MOCK=true` in `local.properties` (debug only) makes the real Claude client talk to a scripted fake
  Messages API (`ai/MockAnthropicTransport.kt`: "dragon car", "blue alien", "make it better") – used for demos and emulator runs.
* Release signing: put `KEYSTORE_PROPERTIES=/path/to/keystore.properties` in `local.properties`
  (keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Keystores are never committed; without one the release APK is unsigned.
  **Keep a backup** – updates of the sideloaded app must be signed with the same key.

### Getting AI on the tablet (no key in the APK)

Release APKs contain no key. A parent opens ⚙️ (times-table gate) and pastes their Claude key; it is stored with
`EncryptedSharedPreferences` (AES-256-GCM, master key in the Android Keystore) and only sent to `api.anthropic.com`.
Better for many devices: run a small proxy that holds the key and enter its URL instead.

Without a key the app still works: the magic bar falls back to an **offline designer** that picks the
closest starter toy (clearly labelled "Offline magic"), and "✨ Better" is disabled.

### ⚠️ API key – never ship it

`ANTHROPIC_API_KEY` from `local.properties` is compiled into `BuildConfig` of **debug builds only**
(release builds force it to `""`). Anything inside an APK can be extracted, so a key in the app is
fine for a personal test tablet, never for the Play Store. For release:

1. Run a tiny backend that holds the key, authenticates the device/parent account, rate-limits,
   logs, and forwards `POST /v1/messages` to Anthropic (it can also pin the model and the system prompt).
2. Set `AI_PROXY_URL` – `AnthropicToyDesigner` then talks to the proxy without a key. Or write another
   `ToyDesigner` implementation; the UI only knows the interface.

## Architecture

```
com.kidtoy
├─ model/        Shape, Vec3, Project (kotlinx.serialization), Palette
│                SceneOps: keep-on-plate (settle), group placement, printability check
├─ geometry/     Mat4 (pure Kotlin), Mesh/MeshBuilder, Primitives (closed unit meshes),
│                Transforms (model matrix, cached world bounds), Csg (BSP, port of csg.js – MIT),
│                Solidify (scene → one watertight export mesh)
├─ io/           StlExporter (binary), ThreeMfExporter (colours via basematerials),
│                MeshImporter (binary/ASCII STL, OBJ), ProjectStore/ProjectJson
├─ ai/           ToyDesigner (interface) ─┬─ AnthropicToyDesigner (Messages API or proxy, HttpTransport for tests)
│                                         └─ OfflineToyDesigner (starter templates)
│                AiShapeParser (tolerant JSON → validated, clamped shapes), KidSafePrompt, Templates
├─ library/      ModelLibrary (interface), StarterLibrary (offline), RemoteModelLibraryStub
├─ render/       ToyGLView (GLSurfaceView + gestures), ToyRenderer (GLES 2.0), OrbitCamera, Picking
└─ ui/           EditorViewModel (state, undo/redo, AI, import/export, autosave), EditorScreen,
                 Dialogs (print, my toys), Kit (bouncy buttons, panels), ShapeIcons, Fun (confetti, sounds)
```

* **Units & axes**: millimetres, Y up in the app, plate = 200 × 200 mm at y = 0. Every primitive is a
  closed mesh in the unit box, `scale` = size in mm. Exports convert to Z-up (`(x,y,z) → (x,−z,y)`,
  a proper rotation, so winding stays outward).
* **Keep on plate**: after every edit `SceneOps.settle` lifts the part so its lowest *real vertex*
  (not just a bounding box corner) is ≥ 0 and pushes it back inside the plate.
* **Export pipeline** (`Solidify`): transform parts → group parts whose boxes touch → CSG-union each
  group → weld vertices (1 µm) → repair T-junctions → close sliver holes → **validate** (every edge
  shared by exactly two opposite half-edges, volume between the largest part and the sum of parts).
  If a fused group fails validation it is exported as separate closed, overlapping shells instead
  (all slicers merge overlapping shells). So the output is always made of closed, outward-facing
  shells; the tests check this on every starter toy and on 25 random piles of rotated shapes.
  Time budget 12 s, after which remaining groups are exported as shells.
* **AI contract**: the model must reply with
  `{"name","message","shapes":[{"type","position":[x,y,z],"size":[w,h,d],"rotation":[rx,ry,rz],"color":"#RRGGBB"}]}`.
  The parser tolerates code fences / prose / bare arrays / aliases (`ball`, `wheel`, `scale`, `center`…),
  drops unknown types (e.g. anything weapon-like the model might invent), clamps sizes to 2–180 mm and
  positions to the plate, caps at 40 shapes, and the app then grounds and centres the group.
  The system prompt (`KidSafePrompt`) contains the safety rules (no weapons/violence/adult content,
  gentle redirection, no personal data, ignore prompt-injection in the child's text) and the
  printability rules (overlap ≥ 2 mm, flat base, ≥ 4 mm features).

## Printing on a Bambu Lab printer (findings)

* **What we export:** a Bambu Studio / OrcaSlicer flavoured 3MF project (`io/BambuProject.kt`): production-extension
  layout (`3D/3dmodel.model` + `3D/Objects/object_1.model`), `Metadata/model_settings.config` with **one object made
  of one closed part per colour** (part *k* → filament slot *k*, so AMS users get colours), the object centred on the
  chosen bed, a plate thumbnail, and the print recipe as **object-level settings**: `layer_height 0.2`, `wall_loops 3`,
  `sparse_infill_density 15%`, `sparse_infill_pattern gyroid`, `enable_support` + `support_type tree(auto)` when the
  advisor finds > 25 mm² of steep overhang, `brim_type outer_only` / 5 mm for small or tall-thin footprints.
  No `project_settings.config` on purpose: the slicer keeps *your* printer, plate and PLA profile, so a kid's file can
  never switch the machine profile. PLA is whatever filament is loaded in the slicer.
* **Verified on the box with OrcaSlicer 2.4.2 CLI** (`tools/slicetest/slice.sh`, not bundled): the e2e alien STL and
  3MF both slice to G-code with a P1S 0.4 + 0.20 mm Standard + Bambu PLA Basic profile. The 3MF's object settings
  are applied (tree supports generated; the bare STL gets none and Orca warns about floating regions).
  Multi-filament CLI slicing with `--load-filaments` fails with "Grouping error: PLA can not be placed in the right
  nozzle" (OrcaSlicer CLI bug, issue #12426). `tools/slicetest/slice.sh` works around it for tests only: it embeds
  machine/process/one-PLA-per-colour settings as `Metadata/project_settings.config` in a copy and slices without
  `--load-*` -> Success, 5 colours, supports from our object overrides. The app itself never embeds printer settings.
* **Bambu Handy cannot open or slice local files** (it is not a slicer; it only prints MakerWorld models via
  cloud slicing). So the realistic flow is: *Send to Bambu / Orca* → share sheet (Drive, Nearby Share, e-mail,
  LocalSend…) → open on the computer in Bambu Studio / OrcaSlicer → Print (LAN or cloud).
* **In-app slicing:** not realistic right now. OrcaSlicer / PrusaSlicer / Bambu Studio (libslic3r) are AGPL-3.0 –
  bundling would make the whole app AGPL and needs a large C++/NDK port; CuraEngine is AGPL too. Options for later:
  (a) a home "slicer box" (Orca CLI on a PC/NAS/Raspberry Pi) behind a tiny HTTP endpoint that slices and sends to the
  printer in LAN mode, (b) uploading to MakerWorld for cloud slicing (public/semi-public, needs a full print profile).

### Renderer choice

The research doc (`../RESEARCH.md`) suggests three.js-in-WebView or SceneView/Filament. I went with a
small custom **OpenGL ES 2.0** renderer because it had zero dependencies, builds offline, is
fully native, and the scene is simple (lit primitives, plate, grid, blob shadows, selection ring).
Picking is exact (ray vs. triangles). If we later need PBR materials, glTF assets or textures,
SceneView (Apache-2.0) can replace `render/` without touching the model, geometry or UI layers.

### Licences of reused code

* csg.js algorithm (Evan Wallace, MIT) – ported in `geometry/Csg.kt` (plus our local-clipping `unionAll`).
* Lilita One font (Juan Montoreano, SIL OFL 1.1) – `assets/fonts/`, licence text alongside.
* AndroidX / Compose / kotlinx (Apache-2.0). No GPL code.

## Tests

`./gradlew testDebugUnitTest` – 36 JVM tests:
* `PrintPerfTest` – instant recipe (~50 ms) agrees with the exact one; fusing respects its wall-clock budget and
  falls back to closed separate shells.
* `AttachmentTest` – every part of the e2e alien overlaps a neighbour by real volume (CSG), ears hold on to the head.

* `GeometryTest` – every primitive is closed, outward and has the right volume; CSG fusing is watertight.
* `TemplateSolidTest` – every starter toy and 25 random piles export as closed shells.
* `ExportImportTest` – binary STL layout, normals vs. winding, Z-up, signed volume, STL round-trip,
  ASCII STL + OBJ import, 3MF package structure, project JSON round-trip, keep-on-plate, printability.
* `AiParsingTest` – strict JSON, tolerant parsing, clamping, garbage rejection, request format
  (URL, `x-api-key`, `anthropic-version: 2023-06-01`, model, system prompt) against a fake transport,
  kid-friendly error mapping, mock transport end-to-end.
* `LibraryTest` – Smithsonian Open Access parsing (CC0 + 3D only), zipped OBJ import.
* `TextTest` – words incl. umlauts/digits/holes become watertight letters; nameplate fuses with letters.
* `E2eAlienTest` – the whole kid story on the JVM: "blue alien with big ears" via the mocked Claude client,
  bigger ears, recolour, move, "Mika" nameplate, STL + Bambu 3MF export, every shell watertight; files in `app/build/e2e/`.

## Not done yet / next

* Museum (Smithsonian CC0 3D) search works; downloads fail because `3d-api.si.edu` currently serves an expired TLS
  certificate (we do not bypass TLS). Poly Pizza needs an API key.
* Backend proxy for the AI (required before any public release), privacy policy.
* On-canvas handles (drag to stretch/rotate), multi-select / grouping, holes (CSG difference).
* CSG: the local-clipping union fuses all starter toys; on complex toys a few small parts touching a crack stay as
  separate closed shells overlapping the body (the e2e alien: 9 of 22 parts). Slicers merge those automatically.
  Manifold (Apache-2.0, NDK) would make every union exact.
* Thumbnails are captured from the live view; the camera is not reframed for them.

## Print modes and estimates
The print dialog has two modes. "One colour (fast)" is the default: a single body and no AMS. "Colourful (AMS)" merges tiny decorations into their host and caps the toy at 4 colours. Each mode shows a time, grams and colour-swap estimate calibrated against the OrcaSlicer 2.4.2 CLI (see PROGRESS.md, round 4, for the table). Toys estimated at over 3 h get a "Make it smaller" offer. A joint check finds thin limb joints, and "Make stronger" sinks the limb up to 15% of its size into its neighbour.

## License

MIT, see `LICENSE`. Third-party notices (csg.js port, Lilita One font): `THIRD_PARTY_LICENSES.md`.
