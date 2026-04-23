#!/usr/bin/env python3
"""
Step 5: DMO Aggregation
========================
CARP-DSP pipeline step — mobgap demo.

Applies Mobilise-D DMO thresholds to filter walking bouts by clinical validity,
then aggregates the remaining bouts into a single-row summary per recording.

Usage:
    python step5_aggregate.py --wb-params <csv> --config <json> --output <csv>

Inputs:
    --wb-params   WB parameters CSV (from step 4)
    --config      Config JSON — must include: cohort, height_m, measurement_condition

Outputs:
    --output      Single-row CSV with aggregated DMOs (mean walking speed, cadence,
                  stride length, stride duration, plus WB-level counts and stats).
"""

import argparse
import json
import sys
from pathlib import Path

import pandas as pd


def parse_args():
    parser = argparse.ArgumentParser(description="DMO Aggregation (Mobilise-D)")
    parser.add_argument("--wb-params", required=True, help="WB parameters CSV (from step 4)")
    parser.add_argument("--config", required=True, help="Config JSON")
    parser.add_argument("--output", required=True, help="Output CSV for aggregated DMOs")
    return parser.parse_args()


def main():
    args = parse_args()

    try:
        from mobgap.aggregation import (
            MobilisedAggregator,
            apply_thresholds,
            get_mobilised_dmo_thresholds,
        )
    except ImportError:
        print("ERROR: mobgap not installed. Run: pip install mobgap")
        sys.exit(1)

    # --- Load inputs ---
    print(f"[AGG] Reading WB parameters: {args.wb_params}")
    wb_params = pd.read_csv(args.wb_params)

    if "wb_id" not in wb_params.columns:
        print("[AGG] WARNING: wb_id missing in wb_params; synthesizing wb_id from row order")
        wb_params = wb_params.reset_index(drop=True)
        wb_params.insert(0, "wb_id", wb_params.index.astype(int))

    if wb_params["wb_id"].isna().any():
        print("[AGG] ERROR: wb_id contains null values")
        sys.exit(1)
    if wb_params["wb_id"].duplicated().any():
        print("[AGG] ERROR: wb_id must be unique per walking bout")
        sys.exit(1)

    # Keep a canonical index for both thresholding and aggregation.
    wb_params = wb_params.set_index("wb_id", drop=True)

    if wb_params.empty:
        print("[AGG] ERROR: wb_params is empty — no walking bouts to aggregate")
        sys.exit(1)

    with open(args.config) as f:
        config = json.load(f)

    cohort = config["cohort"]
    height_m = float(config["height_m"])
    measurement_condition = config["measurement_condition"]

    print(f"[AGG] {len(wb_params)} walking bouts")
    print(f"[AGG] cohort={cohort}, height_m={height_m}, condition={measurement_condition}")

    required_dmo_cols = {"walking_speed_mps", "stride_duration_s", "cadence_spm", "stride_length_m"}
    missing_dmo = sorted(required_dmo_cols - set(wb_params.columns))
    if missing_dmo:
        print(
            "[AGG] ERROR: wb_params is missing required DMO columns: "
            + ", ".join(missing_dmo)
        )
        sys.exit(1)

    # --- Apply DMO thresholds ---
    thresholds = get_mobilised_dmo_thresholds()

    try:
        wb_mask = apply_thresholds(
            wb_params,
            thresholds,
            cohort=cohort,
            height_m=height_m,
            measurement_condition=measurement_condition,
        )
        if not wb_mask.index.equals(wb_params.index):
            missing = len(wb_params.index.difference(wb_mask.index))
            extra = len(wb_mask.index.difference(wb_params.index))
            print(
                "[AGG] WARNING: threshold mask index mismatch "
                f"(missing={missing}, extra={extra}); reindexing to wb_params"
            )
            wb_mask = wb_mask.reindex(wb_params.index)
        print(f"[AGG] Threshold mask applied — {wb_mask.notna().any(axis=1).sum()} WBs have at least one check")
    except Exception as e:
        print(f"[AGG] WARNING: apply_thresholds failed ({e}), proceeding without masking")
        wb_mask = None

    # --- Aggregate ---
    agg = MobilisedAggregator(
        **MobilisedAggregator.PredefinedParameters.single_recording
    )

    try:
        agg_result = agg.aggregate(
            wb_params,
            wb_dmos_mask=wb_mask,
        ).aggregated_data_
    except Exception as e:
        print(f"[AGG] ERROR during aggregation: {e}")
        sys.exit(1)

    # --- Write output ---
    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    agg_result.reset_index().to_csv(out_path, index=False)

    print(f"[AGG] Aggregated DMOs written: {out_path}")
    print(f"[AGG] Result shape: {agg_result.shape}")

    # Log key DMO values
    for col in ["walking_speed_mps", "cadence_spm", "stride_length_m", "stride_duration_s"]:
        if col in agg_result.columns:
            val = agg_result[col].iloc[0]
            print(f"[AGG]   {col}: {val:.4f}")


if __name__ == "__main__":
    main()
