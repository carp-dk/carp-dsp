#!/usr/bin/env python3
"""
Step 2: Initial Contact Detection + Laterality + Turn Detection
================================================================
CARP-DSP pipeline step — mobgap demo.

For each gait sequence from step 1:
  - Detects initial contacts (IcdShinImproved)
  - Classifies left/right laterality (LrcUllrich)
  - Detects turns (TdElGohary)

All output sample indices are recording-level (gs.start offset applied).

Usage:
    python step2_icd.py --imu-data <csv> --gs-list <csv> --config <json>
                        --ic-list <csv> --turn-list <csv>

Inputs:
    --imu-data   Full recording IMU CSV (from generate_sample_data.py)
    --gs-list    Gait sequence CSV (from step1_gsd.py)
    --config     Config JSON

Outputs:
    --ic-list    CSV: gs_id, ic_id, ic (recording-level sample), lr_label
    --turn-list  CSV: gs_id, turn_id, start, end, duration_s, direction
                 start/end are recording-level sample indices.

NOTE on offsets:
    ICD runs on per-GS slices. The 'ic' values from ICD are local to the GS slice.
    This script adds gs.start to convert them to recording-level indices.
    Step 3 will subtract gs.start again to get local indices for algorithm calls.
"""

import argparse
import json
import sys
from pathlib import Path

import pandas as pd


def parse_args():
    parser = argparse.ArgumentParser(description="ICD + Laterality + Turn Detection")
    parser.add_argument("--imu-data", required=True, help="Path to IMU CSV file")
    parser.add_argument("--gs-list", required=True, help="Path to gs_list CSV (from step 1)")
    parser.add_argument("--config", required=True, help="Path to config JSON")
    parser.add_argument("--ic-list", required=True, help="Output path for ic_list CSV")
    parser.add_argument("--turn-list", required=True, help="Output path for turn_list CSV")
    return parser.parse_args()


def main():
    args = parse_args()

    try:
        from mobgap.initial_contacts import IcdShinImproved
        from mobgap.laterality import LrcUllrich
        from mobgap.turning import TdElGohary
    except ImportError:
        print("ERROR: mobgap not installed. Run: pip install mobgap")
        sys.exit(1)

    # --- Load inputs ---
    print(f"[ICD] Reading IMU data from: {args.imu_data}")
    imu_data = pd.read_csv(args.imu_data, index_col=0)
    print(f"[ICD] Loaded {len(imu_data)} samples")

    print(f"[ICD] Reading GS list from: {args.gs_list}")
    gs_list = pd.read_csv(args.gs_list, index_col=0)
    print(f"[ICD] {len(gs_list)} gait sequences to process")

    with open(args.config) as f:
        config = json.load(f)
    sampling_rate_hz = float(config["sampling_rate_hz"])
    print(f"[ICD] sampling_rate_hz={sampling_rate_hz}")

    # --- Process each GS ---
    all_ic_rows = []
    all_turn_rows = []

    icd = IcdShinImproved()
    lrc = LrcUllrich()
    turn_det = TdElGohary()

    for gs_id, gs in gs_list.iterrows():
        gs_start = int(gs["start"])
        gs_end = int(gs["end"])
        gs_data = imu_data.iloc[gs_start:gs_end]

        if len(gs_data) < 10:
            print(f"[ICD] GS {gs_id}: too short ({len(gs_data)} samples), skipping")
            continue

        # Initial contact detection (local indices within gs_data)
        icd_run = icd.clone().detect(gs_data, sampling_rate_hz=sampling_rate_hz)
        ic_local = icd_run.ic_list_

        if ic_local.empty:
            print(f"[ICD] GS {gs_id}: no ICs detected, skipping")
            continue

        # Laterality classification
        lrc_run = lrc.clone().predict(gs_data, ic_local, sampling_rate_hz=sampling_rate_hz)
        ic_lr_local = lrc_run.ic_lr_list_

        # Turn detection (runs on original GS, not refined)
        turn_run = turn_det.clone().detect(gs_data, sampling_rate_hz=sampling_rate_hz)
        turn_local = turn_run.turn_list_

        # Convert local indices to recording-level and collect
        for ic_id, row in ic_lr_local.iterrows():
            all_ic_rows.append({
                "gs_id": gs_id,
                "ic_id": ic_id,
                "ic": int(row["ic"]) + gs_start,   # recording-level
                "lr_label": row["lr_label"],
            })

        for turn_id, row in turn_local.iterrows():
            all_turn_rows.append({
                "gs_id": gs_id,
                "turn_id": turn_id,
                "start": int(row["start"]) + gs_start,  # recording-level
                "end": int(row["end"]) + gs_start,
                "duration_s": float(row.get("duration_s", 0.0)),
                "direction": row.get("direction", ""),
            })

        n_ic = len(ic_lr_local)
        n_turns = len(turn_local)
        print(f"[ICD] GS {gs_id} (samples {gs_start}–{gs_end}): {n_ic} ICs, {n_turns} turns")

    # --- Write outputs ---
    ic_df = pd.DataFrame(all_ic_rows)
    turn_df = pd.DataFrame(all_turn_rows)

    ic_path = Path(args.ic_list)
    ic_path.parent.mkdir(parents=True, exist_ok=True)
    ic_df.to_csv(ic_path, index=False)

    turn_path = Path(args.turn_list)
    turn_path.parent.mkdir(parents=True, exist_ok=True)
    turn_df.to_csv(turn_path, index=False)

    print(f"[ICD] Total ICs: {len(ic_df)}")
    print(f"[ICD] Total turns: {len(turn_df)}")
    print(f"[ICD] ic_list written: {ic_path}")
    print(f"[ICD] turn_list written: {turn_path}")


if __name__ == "__main__":
    main()
