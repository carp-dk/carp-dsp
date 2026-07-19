#!/usr/bin/env bash
# Portability eval driver (Option B): run the HR/step pipeline in several glibc Linux
# distros under one pinned Pixi env and compare output hashes across them.
#
# Prereq on the host: Docker running (the lock + dataset are produced inside a container,
# and the final compare step uses whichever python/python3/py is on PATH).
# Run via: ./gradlew :carp.dsp.demo:evalPortability   (or directly)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HR_LIB="$HERE/../../src/jvmMain/resources/scripts/hr_lib"
RESULTS="$HERE/results"
DATASET_URL="https://zenodo.org/records/53894/files/mturkfitbit_export_4.12.16-5.12.16.zip?download=1"

# Distros to compare (glibc; add/remove freely).
IMAGES=("ubuntu:22.04" "debian:12" "fedora:40")

# ── 0. Docker must be running ────────────────────────────────────────────────
if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker not found on PATH. Install Docker and retry." >&2
  exit 3
fi
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is not running. Start Docker Desktop / the daemon and retry." >&2
  exit 3
fi

# ── 1. Assemble a build context (env + scripts + a shared dataset copy) ──────
CTX="$(mktemp -d)"
trap 'rm -rf "$CTX"' EXIT
cp "$HERE/pixi.toml" "$HERE/run_pipeline.sh" "$CTX/"
cp -r "$HR_LIB" "$CTX/hr_lib"
mkdir -p "$CTX/data"

# Shared, pinned lock + one shared dataset copy, both produced inside a container (no host
# Pixi or curl needed) and extracted via docker cp. Every distro then uses the identical
# env and byte-identical input, so the only variable is the OS.
mkdir -p "$HERE/data"
if [[ ! -f "$HERE/pixi.lock" || ! -f "$HERE/data/fitbit.zip" ]]; then
  echo ">> building locker image (generates pixi.lock + fetches dataset)"
  docker build -t carp-port-locker -f "$HERE/Dockerfile.lock" "$CTX"
  cid="$(docker create carp-port-locker)"
  [[ -f "$HERE/pixi.lock" ]]       || docker cp "$cid:/work/pixi.lock" "$HERE/pixi.lock"
  [[ -f "$HERE/data/fitbit.zip" ]] || docker cp "$cid:/work/fitbit.zip" "$HERE/data/fitbit.zip"
  docker rm "$cid" >/dev/null
fi
cp "$HERE/pixi.lock" "$CTX/"
cp "$HERE/data/fitbit.zip" "$CTX/data/fitbit.zip"

# ── 2. Build + run each distro, collect the output fingerprint ───────────────
mkdir -p "$RESULTS"; rm -f "$RESULTS"/*.hashes
for base in "${IMAGES[@]}"; do
  tag="carp-port-$(echo "$base" | tr ':/.' '___')"
  echo ">> [$base] building $tag"
  docker build --build-arg BASE="$base" -t "$tag" -f "$HERE/Dockerfile" "$CTX"
  echo ">> [$base] running"
  docker run --rm "$tag" \
    | sed -n '/=== PORTABILITY_FINGERPRINT ===/,$p' \
    | grep -E '^[0-9a-f]{64}  ' > "$RESULTS/${tag}.hashes" \
    || { echo "ERROR: no fingerprint from $base" >&2; exit 5; }
done

# ── 3. Compare across distros ────────────────────────────────────────────────
PYTHON="$(command -v python3 || command -v python || command -v py || true)"
if [[ -z "$PYTHON" ]]; then
  echo "ERROR: python not found on PATH for the comparison step." >&2
  echo "       Per-distro hashes are in $RESULTS; run compare_hashes.py manually." >&2
  exit 6
fi
"$PYTHON" "$HERE/compare_hashes.py" "$RESULTS" "${IMAGES[@]}"
