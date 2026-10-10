# ToyForge progress log

## State at 10:47 (Oct 10)
Done & committed: text 3D (TextTest), Bambu 3MF exporter + PrintAdvisor + PrintPackager, NameTag, E2eAlienTest (JVM),
local-clipping CSG unionAll (templates all fuse; alien e2e ~7.5 s JVM), on-demand GL rendering.
Box notes: emulator ATD needs `adb root; adb shell setprop debug.hwui.drawing_enabled 1` or Compose UI is invisible.
Memory: never run emulator (4.2 GB) + gradle together if free < 3 GB. Use -Pkotlin.compiler.execution.strategy=in-process.
OrcaSlicer 2.4.2 CLI extracted at ~/tools/orca/squashfs-root/AppRun (deps installed).

## Next
1. build green + commit  2. Orca CLI slice test  3. UI: text dialog + print flow  4. settings (parent gate, encrypted key)
5. release signing -> /workspace/kidtoy/dist/KidToy.apk  6. emulator e2e screenshots  7. README

## 10:55 Orca CLI slicing test (step 2) DONE
- Build green, 32 JVM tests pass.
- tools/slicetest/slice.sh (flattens BBL P1S + 0.20mm Standard + PLA Basic profiles, runs Orca 2.4.2 CLI).
- mika-alien.stl -> Success, 433 layers, 86.6 mm, warning "floating regions" (no supports in STL path).
- mika-alien.3mf (Bambu project) -> Success with 1 filament, 75 g; object overrides applied: 383 support
  features (tree(auto)) vs 0 for STL. Multi-filament CLI slice fails with "Grouping error: PLA can not be
  placed in the right nozzle" = known upstream Orca CLI bug (OrcaSlicer issue #12426, also fails for STL? no:
  STL+2 filaments OK because only slot 1 used). Desktop GUI multi-colour not verifiable here.

## 11:10 UI step 3+4 DONE (committed)
- Shelf "Aa" -> TextDialog (stand-up letters / name plate). TopBar gear -> ParentGateDialog (times table) -> SettingsDialog
  (EncryptedSharedPreferences: key, proxy, model sonnet/haiku, bed 256/180, "Test" = 1 tiny haiku request).
- Print dialog: "Send to Bambu / Orca" (project 3MF share), "Save on this tablet" (3MF), "Share STL"; recipe text.
Next: release signing + dist APK, emulator e2e screenshots, README.

## 11:15 Release step 5 DONE
- Keystore /workspace/kidtoy/keystore/toyforge-release.jks, passwords in keystore/keystore.properties (chmod 600, not in git).
- /workspace/kidtoy/dist/KidToy.apk = signed release (v2, CN=ToyForge), minSdk 26, no key strings.
Next: emulator e2e screenshots (AI_MOCK debug build), README.

## 11:25 README + render done; emulator booted (1536M), mock APK installed. Doing e2e screenshots.
- tools/render3mf.py -> screenshots/e2e-08-final-render.png

## 11:50 – emulator e2e screenshots
- Saved screenshots/e2e-01..10 (start, idea, AI alien, select ear, bigger ear, purple belly, hand drag with floating-part warning (then undone), name dialog "Mika", nameplate, print dialog). Final render renamed to e2e-12-final-render.png.
- OPEN ISSUE: on the TCG emulator (no KVM, ~20-50x slower) the print dialog stayed on "Thinking about supports…" for more than 10 min. The JVM test runs the same pipeline in 7.5 s. Cause: the Solidify time budget is only checked between union attempts, not inside Csg.unionAll.
  Next fix: pass the deadline into Csg.unionAll / BSP clip and fall back to separate shells (fuse=false) when it is exceeded. Also show the recipe from a quick bbox/overhang analysis before the fused solid is ready.
- Committed tools/render3mf.py (a3221a7).

## 11:35 – round 3, step 1: print recipe hang fixed (a72b481)
- Profiled the alien on the JVM: fuse took 4.8 s, and 2.8 s of that was weld/T-junction repair (quadratic). Now a long-key grid plus x-sorted candidates: 1.5 s total.
- Csg.withDeadline/tick: the BSP loops throw CsgTimeout past the budget, and Solidify falls back to closed separate shells (multi-body).
- PrintPackager.quick: shells only plus a recipe estimate that ignores downward faces buried in other parts. About 110 ms on the JVM, with the same supports/brim decision as the exact one.
- VM: the dialog shows the quick recipe instantly ("Double-checking the details…"), then the exact one. The fuse budget is 8 s, so it always finishes. Bambu export uses the shells, so no waiting.
- Tests 34/34 (new: PrintPerfTest; AlienScene helper shared with E2eAlienTest).

## 11:20 – step 2: multi-colour 3MF slices in the Orca CLI
- Root cause (Orca 2.4.2 source): with 2+ --load-filaments, check_filament_printable_after_group gets a wrong nozzle map (issue #12426, still open after the segfault fix). It is the CLI path, not our 3MF.
- Workaround (test only, tools/slicetest/embed_project.py): embed project_settings.config (P1S + 0.20 Standard + 5x Bambu PLA Basic with our colours, prime tower at 15,120) and slice without --load-*. The default tower position (15,220) runs off the bed, which gives error -102.
- Result: Success, 433 layers, 5 filaments used (79.8/35.5/20.2/15.9/16.5 g), 361 colour changes, 430 support features (object override applied; global enable_support=0). About 13 h on a P1S/AMS.
- The app still does not embed printer/filament settings, on purpose: the user's slicer profile wins.

## 11:30 – step 3: glyphs and ears verified
- Glyph outlines plus cap triangulation dumped (GlyphDumpTest, tools/render_glyphs.py, screenshots/e2e-14-glyphs-mika.png). The i (stem plus separate dot) and the a counters are correct; no contour bug.
- Cause of the "odd i": in the low oblique render the 1.1 mm i-dot gap was hidden behind 3 mm tall letter walls (plus aliasing). Fixes: letter relief 3 to 2 mm (10 layers), tracking 0.04 to 0.06 em, and the renderer now supersamples 2x. New renders: e2e-12-final-render, e2e-13-nameplate-closeup (top), e2e-13b-nameplate-angle.
- AttachmentTest: real volume overlaps (CSG). Each big ear overlaps the head by about 120 mm3 (plus about 526 mm3 with its pink inside), and all 22 parts form one connected piece. Feet to plate: 66 mm3 per leg cylinder. Tests 36/36.

## 11:55 – step 4: emulator recapture + release APK
- Found on the emulator: the budget did not cover weld/repair, so the fuse ran for minutes. Now the whole fused pass is under the wall-clock deadline (ticks in weld/T-junction/edge checks). On timeout the instant shells are reused, with no repeated work. The instant package welds each part once (~50 ms JVM).
- TCG emulator (no KVM, very slow): the recipe now finishes (about 2 min in total under TCG, versus never before). Note: ATD only redraws the dialog on input, so screenshots are taken after a tap.
- Screens: e2e-10-print-recipe, e2e-11-share-sheet (ATD has no share targets: "No apps can perform this action"), e2e-11b-device-3mf-thumbnail, e2e-15-parent-gate, e2e-16-settings, e2e-17-settings-key-masked.
- 3MF exported ON the emulator, pulled via run-as, sliced with the Orca CLI: Success, 452 layers, 5 filaments, 446 support features.
- Encrypted prefs verified: a fake key was saved, the prefs file holds only encrypted names/values, and "FAKE" is not found.
- dist/KidToy.apk rebuilt: signed (same cert db392a1a…6d11), minSdk 26 / target 35, empty key, AI_MOCK=false.

## Round 4 (smart print defaults)
- Joints (geometry/Joints.kt): a joint tree from the biggest part. Neck = largest cross-section of the overlap, sampled with exact inside tests in each part's local frame. Need = max(28 mm2, 0.25 x group volume^(2/3)), checked for limbs only (groups < 25% of the toy). Alien: exactly the two big ears are weak (neck 44 mm2 vs 67 needed). strengthen() moves each ear plus its pink inside 2.0 mm towards the head, after which none are weak. JointsTest.
- Print modes (io/PrintModes.kt): ONE_COLOUR is the default (single body, 1 filament). COLOURFUL merges tiny decorations (< 0.2% of the toy, inside a host) into the host and caps colours at 4 by merging the closest colours. Alien: glints go white to dark, purple to blue, giving 4 colours.
- Estimate calibrated against Orca CLI 2.4.2 (P1S, 0.20 Standard, PLA Basic), least squares over 4 one-colour slices plus the 4-colour swap cost:
  min = 1.827*g + 0.0806*layers + 1.75*swaps; g = 1.24e-3*(0.886*A + 0.16*(V - 0.886*A)) + 0.289*swaps
  | model | Orca | estimate |
  | alien 1 colour | 2h49 / 73.3 g / 0 | 2h48 / 73 g / 0 |
  | alien colourful (4) | 8h31 / 129.7 g / 195 | 8h37 / 130 g / 199 |
  | alien 70% 1c | 1h22 / 31.6 g | 1h22 / 31 g |
  | alien 50% 1c | 45 min / 14.7 g | 44 min / 14 g |
  | 20 mm cube | 16 min / 4.3 g | 15 min / 3 g |
  | alien 60% colourful (out of sample) | 4h20 / 54.1 g / 116 | 4h33 / 57 g / 121 |
  (old 5-colour, unmerged: 12h59 / 178 g / 361 swaps)
- Shrink offer when > 3 h: the biggest size (>= 50%) that hits 2.5 h, or at least 45% faster.
- Dialog: mode cards (colour dots, time, g, swaps), merge note, make-it-smaller row, "Strong joints" check with a "Make stronger" button, recomputed whenever the toy changes. Tests 39/39.
- Device (ATD emulator, mock build): dialog shows 1 colour about 3 h / 69 g / no swaps; colourful about 8 h / 119 g / 171 swaps; after Make stronger: Strong joints, shrink offer 60% to about 4 h / 50 g. Screenshots e2e-18, e2e-19. Release APK rebuilt.

## Round 5 (bug hunt + kid features)
- Lint: 0 errors, 8 warnings (dependency updates, fixed orientation, monochrome icon). Tests: 48/48 (39 before).
- Start-up: font, encrypted settings (Keystore master key), autosave load and text meshing now run off the main thread, in order (the font loads first so autosaved 3D text never meshes as a cube). A fresh install on the ATD emulator shows no ANR dialog. The earlier "Process system isn't responding" dialog came from system_server under TCG, not from the app.
- Autosave: written straight away on onStop (survives process death). It is never written before the old autosave is loaded, and a toy started during start-up is kept.
- Import: bounded read (60 MB), OOM caught, NaN/degenerate triangles dropped, binary STL with a "solid" header or trailing bytes, broken OBJ gives a kid message; 200k-triangle STL in 114 ms (ImportRobustnessTest, 7 tests). Mesh encoding moved off the main thread.
- Museum: the Smithsonian file host 3d-api.si.edu serves an expired certificate (CN=ids.si.edu, notAfter 2026-10-09 23:59 GMT); search on api.si.edu works. No alternate host. The shelf is hidden while the TLS probe fails; TLS is never bypassed. ModelLibrary.isAvailable() defaults to true.
- Keychain template (Aa → Keychain): tag + key ring + raised name, one flat piece, 67x24x6 mm; ~12 min / 5 g one colour, ~19 min / 6 g / 4 swaps colourful, no supports. Device and JVM give the same numbers for the same scene, so the round-4 mismatch was a different scene (saved project vs the test's rebuilt alien), not a code path difference.
- First-run onboarding: 3 icon-led cards, skippable, remembered.
- Dialog redraw quirk: not reproduced this round (the print dialog drew its estimates without a tap).
- Repo: pure MIT LICENSE, THIRD_PARTY_LICENSES.md (csg.js, Lilita One), example name changed to "Mika", history reset to one commit. CI workflow is ready but not pushed: the gh token lacks the `workflow` scope.

## TODO (Florian, via GitHub web UI)
- Add CI: copy `docs/ci.yml.example` to `.github/workflows/ci.yml` (builds + runs unit tests on push, no secrets).
