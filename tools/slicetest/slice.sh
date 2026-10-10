#!/bin/bash
# Slice a ToyForge export with the OrcaSlicer CLI (test only, never bundled). Usage: slice.sh file.(stl|3mf) outdir
# Multi-colour Bambu 3MFs: OrcaSlicer 2.4.x's CLI fails with "Grouping error: PLA can not be placed in the right nozzle"
# when --load-filaments gets 2+ profiles (issue #12426). Workaround used here: embed machine/process/filament settings
# (one PLA per colour) as Metadata/project_settings.config in a test copy and slice without --load-*.
set -e
D=$(cd "$(dirname "$0")" && pwd)
ORCA=${ORCA:-$HOME/tools/orca/squashfs-root/AppRun}
[ -f "$D/machine.json" ] || { python3 "$D/flatten.py" machine "Bambu Lab P1S 0.4 nozzle" "$D/machine.json"; python3 "$D/flatten.py" process "0.20mm Standard @BBL X1C" "$D/process.json"; python3 "$D/flatten.py" filament "Bambu PLA Basic @BBL X1C" "$D/filament.json"; }
mkdir -p "$2"
colours=0
if [[ "$1" == *.3mf ]]; then colours=$(unzip -p "$1" Metadata/toyforge.config 2>/dev/null | grep -o 'filament_colours" value="[^"]*' | tr ';' '\n' | wc -l); fi
if [ "$colours" -gt 1 ]; then
  python3 "$D/embed_project.py" "$1" "$2/with-project.3mf" "$D/machine.json" "$D/process.json" "$D/filament.json"
  "$ORCA" --slice 0 --outputdir "$2" "$2/with-project.3mf"
else
  "$ORCA" --load-settings "$D/machine.json;$D/process.json" --load-filaments "$D/filament.json" --slice 0 --outputdir "$2" "$1"
fi
cat "$2/result.json"
