#!/usr/bin/env python3
"""
Step 3: Per-Second Parameter Estimation (Cadence, Stride Length, Walking Speed)
=================================================================================
CARP-DSP pipeline step — mobgap demo.

For each gait sequence, refines the GS to the first-IC → last-IC window,
then estimates cadence, stride length, and walking speed at per-second resolution.

Usage:
    python step3_per_sec.py --imu-data <csv> --gs-list <csv> --ic-list <csv>
                             --config <json> --output <csv>

Inputs:
    --imu-data   Full recording IMU CSV
    --gs-list    gs_list CSV (from step 1)
    --ic-list    ic_list CSV (from step 2) — ic values are recording-level
    --config     Config JSON

Outputs:
    --output     CSV: gs_id, sec_center, cadence_spm, stride_length_m, walking_speed_mps
                 sec_center is recording-level (seconds from recording start).

NOTE on offset handling:
    - ic values in ic_list are recording-level (step 2 applied gs.start offset).
    - This script converts back to local (GS-relative) before calling algorithms.
    - The 'refined GS' is trimmed to [first_ic, last_ic] within each GS.
    - sec_center output is offset back to recording level before saving.
"""

import argparse
import json
import sys
from pathlib import Path

import pandas as pd


def parse_args():
    parser = argparse.ArgumentParser(description="Per-second cadence, stride length, walking speed")
    parser.add_argument("--imu-data", required=True)
    parser.add_argument("--gs-list", required=True)
    parser.add_argument("--ic-list", required=True)
    parser.add_argument("--config", required=True)
    parser.add_argument("--output", required=True, help="Output CSV path for per_sec_params")
    return parser.parse_args()


def main():
    args = parse_args()

    try:
        from mobgap.cadence import CadFromIc
        from mobgap.stride_length import SlZijlstra
        from mobgap.walking_speed import WsNaive
    except ImportError:
        print("ERROR: mobgap not installed. Run: pip install mobgap")
        sys.exit(1)

    # --- Load inputs ---
    print(f"[PER-SEC] Reading IMU data: {args.imu_data}")
    imu_data = pd.read_csv(args.imu_data, index_col=0)

    print(f"[PER-SEC] Reading GS list: {args.gs_list}")
    gs_list = pd.read_csv(args.gs_list, index_col=0)

    print(f"[PER-SEC] Reading IC list: {args.ic_list}")
    ic_list = pd.read_csv(args.ic_list)  # columns: gs_id, ic_id, ic, lr_label

    with open(args.config) as f:
        config = json.load(f)
    sampling_rate_hz = float(config["sampling_rate_hz"])
    height_m = float(config["height_m"])
    print(f"[PER-SEC] sampling_rate_hz={sampling_rate_hz}, height_m={height_m}")

    all_rows = []

    cad_algo = CadFromIc()
    sl_algo = SlZijlstra()
    ws_algo = WsNaive()

    for gs_id, gs in gs_list.iterrows():
        gs_start = int(gs["start"])
        gs_end = int(gs["end"])
        gs_data = imu_data.iloc[gs_start:gs_end]

        # Get ICs for this GS, convert from recording-level to GS-local
        gs_ics = ic_list[ic_list["gs_id"] == gs_id].copy()
        if gs_ics.empty:
            print(f"[PER-SEC] GS {gs_id}: no ICs, skipping")
            continue

        gs_ics["ic_local"] = gs_ics["ic"] - gs_start  # local to gs_data

        # Determine refined GS: trimmed to [first_ic, last_ic]
        refined_start_local = int(gs_ics["ic_local"].min())
        refined_end_local = int(gs_ics["ic_local"].max())

        if refined_end_local <= refined_start_local:
            print(f"[PER-SEC] GS {gs_id}: degenerate refined GS, skipping")
            continue

        refined_gs_data = gs_data.iloc[refined_start_local:refined_end_local]

        # ICs relative to refined GS start (as required by algorithms)
        ic_in_refined = gs_ics["ic_local"] - refined_start_local
        # Build the IC DataFrame that algorithms expect: index=ic_id, column='ic'
        ic_df_for_algo = pd.DataFrame(
            {"ic": ic_in_refined.values},
            index=pd.Index(gs_ics["ic_id"].values, name="ic_id"),
        )
        # Also need lr_label for full compatibility (some algos use it)
        ic_df_for_algo["lr_label"] = gs_ics["lr_label"].values

        try:
            # Cadence
            cad = cad_algo.clone().calculate(
                refined_gs_data,
                initial_contacts=ic_df_for_algo,
                sampling_rate_hz=sampling_rate_hz,
            )
            cad_per_sec = cad.cadence_per_sec_.copy()

            # Stride length
            sl = sl_algo.clone().calculate(
                refined_gs_data,
                initial_contacts=ic_df_for_algo,
                sampling_rate_hz=sampling_rate_hz,
                sensor_height_m=height_m,
            )
            sl_per_sec = sl.stride_length_per_sec_.copy()

            # Walking speed
            ws = ws_algo.clone().calculate(
                refined_gs_data,
                initial_contacts=ic_df_for_algo,
                cadence_per_sec=cad_per_sec,
                stride_length_per_sec=sl_per_sec,
                sampling_rate_hz=sampling_rate_hz,
            )
            ws_per_sec = ws.walking_speed_per_sec_.copy()

        except Exception as e:
            print(f"[PER-SEC] GS {gs_id}: algorithm error — {e}, skipping")
            continue

        # Combine and offset sec_center to recording level.
        # cad_per_sec.index contains GS-local sec_center_samples (integer samples
        # relative to refined_gs_data start). We offset to recording-level for both
        # the seconds column and a sec_center_samples column — the latter is what
        # naive_sec_paras_to_regions needs (must match the IC coordinate system).
        recording_offset_samples = gs_start + refined_start_local
        sec_center_samples_rec = (cad_per_sec.index.values + recording_offset_samples).astype(int)
        recording_offset_s = recording_offset_samples / sampling_rate_hz

        combined = pd.concat([cad_per_sec, sl_per_sec, ws_per_sec], axis=1)
        combined["sec_center_samples"] = sec_center_samples_rec  # recording-level integer
        combined.index = combined.index + recording_offset_s    # recording-level seconds
        combined.index.name = "sec_center"
        combined["gs_id"] = gs_id
        combined = combined.reset_index()

        all_rows.append(combined)

        n_sec = len(combined)
        print(
            f"[PER-SEC] GS {gs_id} (samples {gs_start}–{gs_end}): "
            f"{n_sec} seconds, cad={cad_per_sec['cadence_spm'].mean():.1f} spm, "
            f"sl={sl_per_sec['stride_length_m'].mean():.2f} m, "
            f"ws={ws_per_sec['walking_speed_mps'].mean():.2f} m/s"
        )

    if not all_rows:
        print("[PER-SEC] ERROR: no per-second results produced")
        sys.exit(1)

    result_df = pd.concat(all_rows, ignore_index=True)
    # Reorder columns
    result_df = result_df[["gs_id", "sec_center", "sec_center_samples", "cadence_spm", "stride_length_m", "walking_speed_mps"]]

    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    result_df.to_csv(out_path, index=False)

    print(f"[PER-SEC] Total rows: {len(result_df)}")
    print(f"[PER-SEC] Output written: {out_path}")


if __name__ == "__main__":
    main()
