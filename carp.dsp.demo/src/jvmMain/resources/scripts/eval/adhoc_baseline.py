#!/usr/bin/env python3
"""Ad-hoc baseline for the error-detection eval.

This is the "no framework" comparison baseline: eight mobgap steps wired together as a
plain linear script, the typical way they would chain them by hand. There is no
author/plan time validation - so every fault can only surface at run time,
and only after some portion of the pipeline has already used compute.
This harness injects the same five faults as the CARP-DSP eval and records, for each,
how many steps ran before the pipeline halted.

Contrast with the CARP-DSP (ErrorDetectionEval.kt): CARP-DSP catches all five at plan time,
0 / 8 steps executed.

Runnable faults (measured) execute the real scripts and stop at the first failure:
    type-mismatch     wrong-typed file handed to the final consumer  -> fails at step 8
    missing-producer  an upstream producer step is omitted           -> fails at step 3
    missing-output    a producer writes its result under a wrong name -> fails at step 3
Structural faults (not separately measured - determined by the injection point):
    cycle             linear script has no valid schedule            -> 0 steps runnable
    missing-env       environment never provisioned                  -> import error at step 1

Usage:
    pixi run python adhoc_baseline.py            # inside the mobgap pixi env
    python adhoc_baseline.py --python "pixi run python"
    python adhoc_baseline.py --dry-run           # emit the CSV from documented values only

Writes eval_results/error-detection-adhoc.csv (schema matches the CARP-DSP eval) and appends a
report to eval_results/error-detection.txt.
"""
from __future__ import annotations
import argparse
import csv
import pathlib
import shlex
import shutil
import subprocess
import sys
import tempfile
import time

HERE = pathlib.Path(__file__).resolve().parent
SCRIPTS = HERE.parent / "mobgap"          # resources/scripts/mobgap
PROJECT_ROOT = HERE.parents[4]            # .../carp.dsp.demo (matches the Kotlin evalResultsDir)
RESULTS = PROJECT_ROOT / "eval_results"
STEP_COUNT = 8


def step_cmd(name: str, wd: pathlib.Path) -> list[str]:
    """Argv for one mobgap step, wired by file convention inside `wd` (no framework)."""
    p = {k: str(wd / v) for k, v in {
        "imu": "imu_data.csv", "cfg": "config.json", "gs": "gs_list.csv",
        "ic": "ic_list.csv", "turn": "turn_list.csv", "persec": "per_sec_params.csv",
        "stride": "stride_list.csv", "wb": "wb_params.csv", "agg": "aggregated_dmos.csv",
        "wbpng": "wb_params.png", "aggpng": "aggregated_dmos.png",
    }.items()}
    s = lambda f: str(SCRIPTS / f)
    return {
        "import-data": [s("import_data.py"), "--imu-data", p["imu"], "--config", p["cfg"]],
        "gsd": [s("gsd.py"), "--imu-data", p["imu"], "--config", p["cfg"], "--output", p["gs"]],
        "icd": [s("icd.py"), "--imu-data", p["imu"], "--gs-list", p["gs"], "--config", p["cfg"],
                "--ic-list", p["ic"], "--turn-list", p["turn"]],
        "per-sec-params": [s("per_sec_params.py"), "--imu-data", p["imu"], "--gs-list", p["gs"],
                           "--ic-list", p["ic"], "--config", p["cfg"], "--output", p["persec"]],
        "wba": [s("wba.py"), "--ic-list", p["ic"], "--per-sec-params", p["persec"], "--config", p["cfg"],
                "--stride-list", p["stride"], "--wb-params", p["wb"]],
        "aggregate": [s("aggregate.py"), "--wb-params", p["wb"], "--config", p["cfg"], "--output", p["agg"]],
        "plot-wb-params": [s("plot_wb_params.py"), "--wb-params", p["wb"], "--output", p["wbpng"]],
        "plot-aggregated-dmos": [s("plot_aggregated_dmos.py"), "--aggregated-dmos", p["agg"], "--output", p["aggpng"]],
    }[name]


ORDER = ["import-data", "gsd", "icd", "per-sec-params", "wba", "aggregate",
         "plot-wb-params", "plot-aggregated-dmos"]


def run_step(name: str, wd: pathlib.Path, python: list[str],
             skip: set[str], corrupt: dict, delete_after: dict) -> tuple[bool, float]:
    """Run one step; return (success, wall_seconds). A skipped step takes 0 s.
      skip          - omit a producer step entirely (researcher forgot to add it)
      corrupt       - rewrite a file to the wrong type just before the step consumes it
      delete_after  - remove a file after a step succeeds (producer wrote a wrong name)
    """
    if name in skip:
        print(f"    [skip] {name} (injected: producer omitted)")
        return True, 0.0  # a skipped producer "succeeds" from the researcher's point of view
    for path, kind in corrupt.get(name, {}).items():
        if kind == "json":
            (wd / path).write_text('{"walking_speed_mps": 1.0, "note": "wrong type - JSON not CSV"}\n')
            print(f"    [inject] overwrote {path} with JSON before {name}")
    cmd = python + step_cmd(name, wd)
    t0 = time.perf_counter()
    r = subprocess.run(cmd, capture_output=True, text=True)
    elapsed = time.perf_counter() - t0
    if r.returncode != 0:
        tail = (r.stderr or r.stdout).strip().splitlines()[-1:] or [""]
        print(f"    [FAIL] {name} ({elapsed:.1f}s): {tail[0]}")
        return False, elapsed
    for path in delete_after.get(name, []):
        (wd / path).unlink(missing_ok=True)
        print(f"    [inject] removed {path} after {name} (producer output undeclared)")
    print(f"    [ok]   {name} ({elapsed:.1f}s)")
    return True, elapsed


def run_scenario(python: list[str], skip=None, corrupt=None, delete_after=None) -> tuple[int, float]:
    """Run the linear pipeline, stop at first real failure.
    Returns (#steps completed, wall seconds of compute wasted before the failure).
    Wasted time counts only steps that ran to completion (the doomed step's partial
    run is excluded, keeping steps_executed and wasted_seconds consistent)."""
    skip, corrupt, delete_after = skip or set(), corrupt or {}, delete_after or {}
    wd = pathlib.Path(tempfile.mkdtemp(prefix="adhoc_"))
    completed, wasted_s = 0, 0.0
    try:
        for name in ORDER:
            ok, secs = run_step(name, wd, python, skip, corrupt, delete_after)
            if not ok:
                return completed, wasted_s
            if name not in skip:
                completed += 1
                wasted_s += secs
    finally:
        shutil.rmtree(wd, ignore_errors=True)
    return completed, wasted_s


def measured_faults(python: list[str]) -> dict:
    """Run the three schedulable faults for real; return {fault: (steps, wasted_seconds)}."""
    return {
        # feed a JSON file to the final CSV consumer -> crashes at step 8 (7 already ran)
        "type-mismatch": run_scenario(python, corrupt={"plot-aggregated-dmos": {"aggregated_dmos.csv": "json"}}),
        # omit the gsd producer -> icd (step 3) fails needing gs_list.csv (1 ran: import)
        "missing-producer": run_scenario(python, skip={"gsd"}),
        # gsd runs but its output is removed (wrong name) -> icd fails (2 ran: import, gsd)
        "missing-output": run_scenario(python, delete_after={"gsd": ["gs_list.csv"]}),
    }


FIXED = {
    # a cyclic dependency has no valid linear order: the hand-written script gets stuck at
    # authoring - the first step already needs an output produced downstream.
    "cycle": dict(steps=0, measured=False, outcome="unschedulable",
                  note="linear script has no valid topological order"),
    # the pixi environment is never provisioned: the very first step fails on import.
    "missing-env": dict(steps=0, measured=False, outcome="runtime_env_error",
                        note="mobgap import fails when the environment is absent"),
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--python", default="python", help="interpreter, e.g. 'pixi run python'")
    ap.add_argument("--dry-run", action="store_true", help="emit CSV from documented values, no execution")
    args = ap.parse_args()
    python = shlex.split(args.python)

    rows = []
    if args.dry_run:
        # Documented steps-executed by injection position (see module docstring). No timing
        # is measured in dry-run, so wasted_seconds is left blank and the plot falls back to
        # a steps axis. Use a real run (with pixi) for the wall-clock figure.
        measured = {"type-mismatch": (7, None), "missing-producer": (1, None), "missing-output": (2, None)}
        pipeline_s = None
        measured_flag = False
    else:
        # One clean run first: the full-pipeline wall time is the reference length for the
        # figure's "never reached" track (measured on this machine, not hardcoded).
        clean_steps, pipeline_s = run_scenario(python)
        print(f"  clean pipeline: {clean_steps}/{STEP_COUNT} steps in {pipeline_s:.1f}s")
        measured = measured_faults(python)
        measured_flag = True

    def secs_cell(v):
        return "" if v is None else round(v, 2)

    for fault, (steps, secs) in measured.items():
        rows.append(dict(fault=fault, arm="adhoc", detected=True, detection_stage="runtime",
                         steps_total=STEP_COUNT, steps_executed=steps,
                         wasted_fraction=round(steps / STEP_COUNT, 3),
                         wasted_seconds=secs_cell(secs), pipeline_seconds=secs_cell(pipeline_s),
                         outcome=("runtime_crash" if steps > 0 else "runtime_error"),
                         measured=measured_flag))
    for fault, d in FIXED.items():
        # cycle / missing-env burn no step compute (they fail before or at launch)
        rows.append(dict(fault=fault, arm="adhoc", detected=True, detection_stage="runtime",
                         steps_total=STEP_COUNT, steps_executed=d["steps"],
                         wasted_fraction=round(d["steps"] / STEP_COUNT, 3),
                         wasted_seconds=0.0, pipeline_seconds=secs_cell(pipeline_s),
                         outcome=d["outcome"], measured=d["measured"]))

    RESULTS.mkdir(parents=True, exist_ok=True)
    out = RESULTS / "error-detection-adhoc.csv"
    with out.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    summary = ["", "=" * 70, f"Error-detection eval (ad-hoc arm) - {time.strftime('%Y-%m-%d %H:%M:%S')}",
               f"{'fault':18}{'steps':10}{'wasted %':10}{'wasted s':10}outcome"]
    for r in rows:
        ws = r["wasted_seconds"]
        ws_str = f"{ws:<10.1f}" if isinstance(ws, (int, float)) else f"{'n/a':<10}"
        summary.append(f"{r['fault']:18}{str(r['steps_executed'])+'/'+str(STEP_COUNT):10}"
                       f"{r['wasted_fraction']*100:<10.1f}{ws_str}{r['outcome']}")
    summary += ["Static detection by ad-hoc pipeline: 0 / 5 (all faults surface only at runtime).", "=" * 70]
    text = "\n".join(summary) + "\n"
    print(text)
    (RESULTS / "error-detection.txt").open("a").write(text)
    print(f"Wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
