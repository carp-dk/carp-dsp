#!/usr/bin/env python3
"""
Step 1: Gait Sequence Detection (GSD)
======================================
CARP-DSP pipeline step — mobgap demo.

Runs GsdIluz on the full IMU recording and produces a list of gait sequences
(start/end sample indices) for downstream processing.

Usage:
    python step1_gsd.py --imu-data <csv> --config <json> --output <csv>

Inputs:
    --imu-data   CSV with columns: acc_x, acc_y, acc_z, gyr_x, gyr_y, gyr_z
    --config     JSON with: sampling_rate_hz, cohort, height_m, measurement_condition

Outputs:
    --output     CSV with columns: gs_id (index), start (int), end (int)
                 All values are recording-level sample indices.
"""

import argparse
import json
import sys
from pathlib import Path

import pandas as pd


def parse_args():
    parser = argparse.ArgumentParser(description="Gait Sequence Detection (GsdIluz)")
    parser.add_argument("--imu-data", required=True, help="Path to IMU CSV file")
    parser.add_argument("--config", required=True, help="Path to config JSON")
    parser.add_argument("--output", required=True, help="Path for gs_list output CSV")
    return parser.parse_args()


def main():
    args = parse_args()

    try:
        from mobgap.gait_sequences import GsdIluz
    except ImportError:
        print("ERROR: mobgap not installed. Run: pip install mobgap")
        sys.exit(1)

    # --- Load inputs ---
    imu_path = Path(args.imu_data)
    if not imu_path.exists():
        print(f"ERROR: IMU data file not found: {imu_path}")
        sys.exit(1)

    config_path = Path(args.config)
    if not config_path.exists():
        print(f"ERROR: Config file not found: {config_path}")
        sys.exit(1)

    print(f"[GSD] Reading IMU data from: {imu_path}")
    imu_data = pd.read_csv(imu_path, index_col=0)
    print(f"[GSD] Loaded {len(imu_data)} samples, columns: {list(imu_data.columns)}")

    with open(config_path) as f:
        config = json.load(f)
    sampling_rate_hz = float(config["sampling_rate_hz"])
    print(f"[GSD] Config: sampling_rate_hz={sampling_rate_hz}, cohort={config.get('cohort')}")

    # --- Run GSD ---
    participant_metadata = {
        k: config[k] for k in ("cohort", "height_m") if k in config
    }

    gsd = GsdIluz()
    gsd.detect(imu_data, sampling_rate_hz=sampling_rate_hz, **participant_metadata)
    gs_list = gsd.gs_list_

    print(f"[GSD] Detected {len(gs_list)} gait sequences")
    if not gs_list.empty:
        total_samples = (gs_list["end"] - gs_list["start"]).sum()
        total_sec = total_samples / sampling_rate_hz
        print(f"[GSD] Total gait time: {total_sec:.1f} s across {len(gs_list)} sequences")

    # --- Write output ---
    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    gs_list.to_csv(out_path)
    print(f"[GSD] Output written: {out_path}")
    if not gs_list.empty:
        print(f"[GSD] First GS: start={gs_list.iloc[0]['start']}, end={gs_list.iloc[0]['end']}")


if __name__ == "__main__":
    main()
