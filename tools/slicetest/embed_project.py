#!/usr/bin/env python3
"""TEST ONLY: add a full Metadata/project_settings.config (machine + process + one PLA profile per colour) to a
ToyForge Bambu 3MF, so the OrcaSlicer CLI can slice it without --load-filaments (workaround for Orca issue #12426).
The app itself does not embed this, so Bambu Studio / Orca keep the user's own printer and filaments.
usage: embed_project.py in.3mf out.3mf machine.json process.json filament.json"""
import json, re, sys, zipfile
src, dst, mach, proc, fil = sys.argv[1:6]
z = zipfile.ZipFile(src)
colours = re.search(r'filament_colours" value="([^"]+)"', z.read("Metadata/toyforge.config").decode()).group(1).split(";")
n = len(colours)
cfg = {}
for p in (mach, proc):
    cfg.update({k: v for k, v in json.load(open(p)).items() if k not in ("type", "name", "from", "instantiation", "inherits")})
f = json.load(open(fil))
for k, v in f.items():
    if k in ("type", "name", "from", "instantiation", "inherits", "compatible_printers", "compatible_printers_condition"): continue
    cfg[k] = [v[0]] * n if isinstance(v, list) and v else v
cfg["filament_colour"] = colours
cfg["filament_settings_id"] = [f["name"]] * n
cfg["filament_map"] = ["1"] * n
cfg["print_settings_id"] = json.load(open(proc))["name"]
cfg["printer_settings_id"] = json.load(open(mach))["name"]
cfg["version"] = "2.4.2.0"
# prime tower left of the toy (the default 15,220 runs off a 256 mm bed with 5 colours)
cfg["wipe_tower_x"] = ["15"]; cfg["wipe_tower_y"] = ["120"]
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as out:
    for i in z.infolist():
        out.writestr(i, z.read(i.filename))
    out.writestr("Metadata/project_settings.config", json.dumps(cfg, indent=1))
print(f"{dst}: {n} filaments {colours}")
