#!/usr/bin/env bash
# Runs inside each distro container: execute the HR/step pipeline (text-only steps) under
# the pinned Pixi env, then print a filename-keyed SHA-256 fingerprint of the outputs.
# The framework's orchestration is pure-JVM and OS-independent (shown same-machine in the
# overhead eval), so this isolates the numeric/env layer where cross-OS divergence would
# appear. Outputs are CSV/JSON only (no plots) to avoid font/renderer differences.
set -euo pipefail

WORK=/work
OUT=/tmp/out
mkdir -p "$OUT"

PY=(pixi run --manifest-path "$WORK/pixi.toml" python)
LIB="$WORK/hr_lib"
CACHE="$WORK/data/fitbit.zip"   # pre-seeded by the build so every distro reads identical input

# load -> daily-features -> summarise  (the wf-minimal-summary chain, pandas only)
"${PY[@]}" "$LIB/load_hr_steps.py"  --output "$OUT/hr-steps.csv" --cache "$CACHE" --participants 8
"${PY[@]}" "$LIB/daily_features.py" --input "$OUT/hr-steps.csv"  --output "$OUT/daily-features.csv"
"${PY[@]}" "$LIB/summarise.py"      --input "$OUT/daily-features.csv" --output "$OUT/summary.json"

echo "=== PORTABILITY_FINGERPRINT ==="
find "$OUT" -type f \( -name '*.csv' -o -name '*.json' \) | sort | while read -r f; do
  printf '%s  %s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$(basename "$f")"
done
