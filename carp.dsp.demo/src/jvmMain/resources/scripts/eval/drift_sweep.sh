#!/usr/bin/env bash
# Run the same walking-speed pipeline under several mobgap versions and collect
# the DMO into eval_results/drift-results.csv. Each version is installed into the active
# environment in turn (heavy scientific deps are cached, so only the mobgap wheel changes).
#
#   bash drift_sweep.sh
#
# Reproducibility note: this deliberately varies an unpinned dependency. Under CARP-DSP the
# environment is pinned by a Pixi lock and its hash is recorded in the execution report, so a
# pinned re-run returns the same value on every version and any change is attributable.
set -u
OUT="${1:-drift-results.csv}"
VERSIONS=("0.7.0" "0.8.0" "0.9.0" "0.10.0" "0.11.0" "1.0.0" "1.2.0")
echo "version,walking_speed_mps" > "$OUT"
for V in "${VERSIONS[@]}"; do
  pip install "mobgap==$V" --break-system-packages --quiet 2>/dev/null
  WS=$(python3 drift_run.py 2>/dev/null | awk '/mean_ws_mps/ {print $2}')
  echo "$V,$WS" >> "$OUT"
  echo "mobgap $V -> $WS"
done
