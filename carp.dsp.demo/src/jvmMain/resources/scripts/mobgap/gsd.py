#!/usr/bin/env python3
# --- eval timing (paper Fig D): prints script self-time to stderr ---
import atexit as _ae, sys as _sys, time as _time
_EVAL_T0 = _time.perf_counter()
_ae.register(lambda: print(f"EVAL_SCRIPT_SECONDS={_time.perf_counter() - _EVAL_T0:.3f}", file=_sys.stderr))
# --------------------------------------------------------------------
"""
Step 2: Gait Sequence Detection
===============================

Runs the Mobilise-D gait sequence detector for the participant's cohort (GsdIonescu for
impaired walking, GsdIluz for regular walking) on the full recording.

Output: gs_list.csv - gs_id (index), start, end (recording-level samples)

Usage:
    python gsd.py --imu-data <imu.csv> --config <config.json> --output <gs_list.csv>
"""

import argparse
from pathlib import Path

from _mobilised import action_kwargs, load_config, pipeline, read_csv


def parse_args():
    parser = argparse.ArgumentParser(description="Gait Sequence Detection (Mobilise-D)")
    parser.add_argument("--imu-data", required=True, help="Path to IMU CSV file")
    parser.add_argument("--config", required=True, help="Path to config JSON")
    parser.add_argument("--output", required=True, help="Path for gs_list output CSV")
    return parser.parse_args()


def main():
    args = parse_args()
    config = load_config(args.config)
    imu = read_csv(args.imu_data, index_col=0)
    detector = pipeline(config).gait_sequence_detection
    gs_list = detector.clone().detect(imu, **action_kwargs(config)).gs_list_

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    gs_list.to_csv(out)
    print(f"[GSD] {type(detector).__name__}: {len(gs_list)} gait sequences -> {out}")


if __name__ == "__main__":
    main()
