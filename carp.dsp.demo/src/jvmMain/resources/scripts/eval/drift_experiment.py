#!/usr/bin/env python3
"""Dependency-drift experiment driver (Fig B / Table tab:drift).

Sweeps several released versions of the analysis library (mobgap) over the whole LabExample
dataset, holding the input data and the pipeline code fixed, and measures how the walking-speed
DMO drifts when the dependency is left unpinned. Then it aggregates and plots the result.

  python drift_experiment.py --out ../../eval_results --fig <paper>/images/fig-dependency-drift

Requires pixi on PATH (https://pixi.sh). Outputs (into --out): drift-per-datapoint.csv,
drift-summary.{txt,csv}, drift-table.tex. The pixi project (manifest, lock, envs) lives under
--work-dir (default: a temp dir, never inside the repo).
"""
import argparse
import pathlib
import platform
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
DEFAULT_VERSIONS = ["0.7.0", "0.8.0", "0.9.0", "0.10.0", "0.11.0", "1.0.0", "1.2.0"]
BASELINE = "0.10.0"  # pre-drift reference


def env_name(version: str) -> str:
    # pixi environment names allow only lowercase letters, numbers, and dashes.
    return "v" + version.replace(".", "-")


def current_platform() -> str:
    system = platform.system()
    mach = platform.machine().lower()
    if system == "Darwin":
        return "osx-arm64" if mach in ("arm64", "aarch64") else "osx-64"
    if system == "Windows":
        return "win-64"
    return "linux-aarch64" if mach in ("aarch64", "arm64") else "linux-64"


def write_manifest(work_dir: pathlib.Path, versions: list, py_version: str) -> None:
    lines = [
        "[project]",
        'name = "carp-drift"',
        'channels = ["conda-forge"]',
        f'platforms = ["{current_platform()}"]',
        "",
        "[dependencies]",
        f'python = "{py_version}.*"',
        "",
    ]
    for v in versions:
        lines += [f"[feature.{env_name(v)}.pypi-dependencies]", f'mobgap = "=={v}"', ""]
    # A small env for aggregating and plotting, so those steps never depend on the caller's
    # Python having matplotlib installed.
    lines += ["[feature.plot.dependencies]", 'matplotlib = "*"', ""]
    lines += ["[environments]"]
    lines += [f'{env_name(v)} = ["{env_name(v)}"]' for v in versions]
    lines += ['plot = ["plot"]']
    (work_dir / "pixi.toml").write_text("\n".join(lines) + "\n")


def run_version(pixi: str, work_dir: pathlib.Path, version: str, runner: pathlib.Path):
    """Run the dataset under one mobgap version; returns {datapoint: speed} or None on failure."""
    r = subprocess.run([pixi, "run", "-e", env_name(version), "python", str(runner)],
                       cwd=str(work_dir), capture_output=True, text=True)
    if r.returncode != 0:
        tail = (r.stderr or r.stdout or "").strip().splitlines()[-1:] or [""]
        print(f"[drift]   pixi could not build/run mobgap {version}: {tail[0]}")
        return None
    speeds = {}
    for line in r.stdout.splitlines():
        if line.startswith("#") or not line.strip():
            continue
        key, ws, _n = line.split(",")
        speeds[key] = ws
    return speeds


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(HERE / "../../eval_results"))
    ap.add_argument("--fig", default=None, help="figure path stem (no extension)")
    ap.add_argument("--work-dir", default=str(pathlib.Path(tempfile.gettempdir()) / "carp-drift-pixi"))
    ap.add_argument("--py-version", default="3.11", help="conda-forge Python for every env (default 3.11)")
    ap.add_argument("--versions", nargs="+", default=DEFAULT_VERSIONS)
    args = ap.parse_args()

    pixi = shutil.which("pixi")
    if not pixi:
        print("[drift] pixi not found on PATH. Install it from https://pixi.sh and re-run.")
        sys.exit(2)

    out = pathlib.Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    work_dir = pathlib.Path(args.work_dir).resolve()
    work_dir.mkdir(parents=True, exist_ok=True)
    write_manifest(work_dir, args.versions, args.py_version)
    print(f"[drift] pixi project: {work_dir} (Python {args.py_version}, {current_platform()})")

    runner = HERE / "drift_run_all.py"
    per_version = {}
    for v in args.versions:
        print(f"[drift] setting up + running mobgap {v} (pixi provisions Python + packages) ...", flush=True)
        res = run_version(pixi, work_dir, v, runner)
        if res is not None:
            per_version[v] = res

    if BASELINE not in per_version:
        print(f"[drift] NOTE: baseline mobgap {BASELINE} did not resolve; comparison uses the earliest that did.")
    if len(per_version) < 2:
        print(f"[drift] Only {len(per_version)} version(s) ran; need at least two to show drift. Aborting.")
        sys.exit(2)

    order = [v for v in args.versions if v in per_version]
    keys = list(next(iter(per_version.values())))
    csv_path = out / "drift-per-datapoint.csv"
    with open(csv_path, "w") as f:
        f.write("version,cohort,participant,test,trial,datapoint,walking_speed_mps,n_wbs\n")
        for v in order:
            for k in keys:
                cohort, part, test, trial = k.split("-")
                f.write(f"{v},{cohort},{part},{test},{trial},{k},{per_version[v][k]},\n")
    print(f"[drift] wrote {csv_path}")

    # Aggregate + plot inside the pixi 'plot' env (has matplotlib), so this never depends on the
    # caller's Python. analyze_drift needs only the stdlib; plot_drift needs matplotlib.
    subprocess.run([pixi, "run", "-e", "plot", "python", str(HERE / "analyze_drift.py"),
                    str(csv_path), "--out", str(out)], cwd=str(work_dir), check=True)
    if args.fig:
        r = subprocess.run([pixi, "run", "-e", "plot", "python", str(HERE / "plot_drift.py"),
                            str(csv_path), args.fig], cwd=str(work_dir))
        if r.returncode != 0:
            print("[drift] plot step failed (figure not written); data + table are still available.")


if __name__ == "__main__":
    main()
