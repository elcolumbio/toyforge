#!/bin/bash
# Slice every model in app/build/calib with the OrcaSlicer CLI and print time / grams / colour changes (test only).
D=$(cd "$(dirname "$0")" && pwd)
for f in "${1:-$D/../../app/build/calib}"/*.3mf; do
  n=$(basename "$f" .3mf); o=/tmp/calib-$n; rm -rf "$o"
  "$D/slice.sh" "$f" "$o" > "$o.log" 2>&1
  g="$o/plate_1.gcode"
  if [ -f "$g" ]; then
    t=$(grep -m1 "total estimated time" "$g" | sed 's/.*total estimated time: //')
    grams=$(grep -m1 "filament used \[g\]" "$g" | sed 's/.*= //')
    ch=$(grep -cE "^M620 S[0-9]{1,2}A?$|^M620 S[0-9]{1,2}A? " "$g")
    layers=$(grep -m1 "total layer number" "$g" | sed 's/.*: //')
    echo "$n | time $t | grams $grams | M620 loads $ch | layers $layers"
  else echo "$n | FAILED $(grep -o '"error_string"[^,]*' $o/result.json)"; fi
done
