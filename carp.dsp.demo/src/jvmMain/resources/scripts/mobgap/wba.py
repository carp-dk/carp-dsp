#!/usr/bin/env python3
"""
Step 4: Walking Bout Assembly (WBA)
=====================================
CARP-DSP pipeline step — mobgap demo.

Converts ICs to strides, interpolates per-second parameters down to per-stride,
applies stride selection rules, then assembles valid walking bouts.

Usage:
    python step4_wba.py --ic-list <csv> --per-sec-params <csv> --config <json>
                         --stride-list <csv> --wb-params <csv>

Inputs:
    --ic-list        ic_list CSV (from step 2) — recording-level ic values
    --per-sec-params per_sec_params CSV (from step 3) — recording-level sec_center
    --config         Config JSON

Outputs:
    --stride-list    CSV: s_id, gs_id, start, end, lr_label, stride_duration_s,
                          cadence_spm, stride_length_m, walking_speed_mps, wb_id
    --wb-params      CSV: wb_id, start, end, n_strides, stride_duration_s,
                          cadence_spm, stride_length_m, walking_speed_mps

TRICKY: The multi-index (gs_id, s_id) must be flattened to a string "gs_id_s_id"
before StrideSelection and WbAssembly are called. Passing a multi-index causes
silent mis-assembly. See D9 design doc for details.
"""

import argparse
import json
import sys
from pathlib import Path

import pandas as pd


def parse_args():
    parser = argparse.ArgumentParser(description="Walking Bout Assembly")
    parser.add_argument("--ic-list", required=True)
    parser.add_argument("--per-sec-params", required=True)
    parser.add_argument("--config", required=True)
    parser.add_argument("--stride-list", required=True, help="Output CSV for stride list")
    parser.add_argument("--wb-params", required=True, help="Output CSV for WB parameters")
    return parser.parse_args()


def main():
    args = parse_args()

    try:
        from mobgap.laterality import strides_list_from_ic_lr_list
        from mobgap.utils.interpolation import naive_sec_paras_to_regions
        from mobgap.utils.df_operations import create_multi_groupby
        from mobgap.wba import StrideSelection, WbAssembly
    except ImportError:
        print("ERROR: mobgap not installed. Run: pip install mobgap")
        sys.exit(1)

    # --- Load inputs ---
    print(f"[WBA] Reading IC list: {args.ic_list}")
    ic_list = pd.read_csv(args.ic_list)

    print(f"[WBA] Reading per-sec params: {args.per_sec_params}")
    per_sec = pd.read_csv(args.per_sec_params)

    with open(args.config) as f:
        config = json.load(f)
    sampling_rate_hz = float(config["sampling_rate_hz"])
    print(f"[WBA] sampling_rate_hz={sampling_rate_hz}")
    print(f"[WBA] {len(ic_list)} ICs, {len(per_sec)} per-sec rows across "
          f"{ic_list['gs_id'].nunique()} GS")

    # --- Reconstruct multi-indexed IC list expected by strides_list_from_ic_lr_list ---
    # Build a multi-index (gs_id, ic_id) DataFrame matching what GsIterator would produce
    ic_multi = ic_list.set_index(["gs_id", "ic_id"])[["ic", "lr_label"]]

    # Build per-sec multi-index DataFrame.
    # naive_sec_paras_to_regions requires a "sec_center_samples" index level
    # (integer, recording-level) that matches the coordinate system of IC start/end.
    per_sec_multi = per_sec.set_index(["gs_id", "sec_center_samples"])[
        ["cadence_spm", "stride_length_m", "walking_speed_mps"]
    ]

    # --- Build stride list from ICs (per GS) ---
    stride_list_parts = []
    for gs_id, gs_ics in ic_multi.groupby(level="gs_id"):
        gs_ics_single = gs_ics.droplevel("gs_id")
        strides = strides_list_from_ic_lr_list(gs_ics_single)
        strides["gs_id"] = gs_id
        stride_list_parts.append(strides)

    if not stride_list_parts:
        print("[WBA] ERROR: no strides could be built from IC list")
        sys.exit(1)

    stride_list = pd.concat(stride_list_parts)
    stride_list["stride_duration_s"] = (
        (stride_list["end"] - stride_list["start"]) / sampling_rate_hz
    )
    print(f"[WBA] Built {len(stride_list)} initial strides")

    # Reset to multi-index (gs_id, s_id) for interpolation
    stride_list = stride_list.reset_index().rename(columns={"index": "s_id"})
    stride_list = stride_list.set_index(["gs_id", "s_id"])

    # --- Interpolate per-second params to per-stride ---
    # NOTE: mobgap return shape differs across versions. Some versions return only
    # interpolated parameter columns, others include stride columns too.
    # Drop overlaps before joining so both shapes are handled safely.
    per_sec_interp = create_multi_groupby(
        stride_list,
        per_sec_multi,
        "gs_id",
        group_keys=False,
    ).apply(naive_sec_paras_to_regions, sampling_rate_hz=sampling_rate_hz)

    overlap_cols = stride_list.columns.intersection(per_sec_interp.columns)
    if len(overlap_cols) > 0:
        print(
            "[WBA] Dropping overlapping columns from interpolated parameters: "
            f"{', '.join(overlap_cols)}"
        )
        per_sec_interp = per_sec_interp.drop(columns=list(overlap_cols), errors="ignore")

    stride_list_with_paras = stride_list.join(per_sec_interp, how="left")
    print(f"[WBA] Interpolated per-sec params to {len(stride_list_with_paras)} strides")

    # --- TRICKY: Flatten multi-index before WB assembly ---
    # WbAssembly requires a flat string s_id index, not a (gs_id, s_id) tuple.
    flat_index = pd.Index(
        ["_".join(str(e) for e in idx) for idx in stride_list_with_paras.index],
        name="s_id",
    )
    stride_flat = (
        stride_list_with_paras
        .reset_index("gs_id")
        .rename(columns={"gs_id": "original_gs_id"})
        .set_index(flat_index)
    )

    # --- Stride selection + WB assembly ---
    ss = StrideSelection()
    ss_result = ss.filter(stride_flat, sampling_rate_hz=sampling_rate_hz)
    print(f"[WBA] After stride selection: {len(ss_result.filtered_stride_list_)} strides")

    # Rebuild raw ic_list as multi-index for WbAssembly (it needs it for IC-level validation)
    raw_ic_for_wba = ic_multi

    wba = WbAssembly()
    wba.assemble(
        ss_result.filtered_stride_list_,
        raw_initial_contacts=raw_ic_for_wba,
        sampling_rate_hz=sampling_rate_hz,
    )

    final_strides = wba.annotated_stride_list_
    wb_meta = wba.wb_meta_parameters_

    print(f"[WBA] Assembled {wb_meta['wb_id'].nunique() if 'wb_id' in wb_meta.columns else len(wb_meta)} walking bouts")
    print(f"[WBA] Final strides in valid WBs: {len(final_strides)}")

    # --- Build WB-level parameter summary ---
    params_to_agg = ["stride_duration_s", "cadence_spm", "stride_length_m", "walking_speed_mps"]

    # Normalize wb_id placement (column vs index) for version compatibility.
    strides_for_agg = final_strides.copy()
    if "wb_id" not in strides_for_agg.columns and "wb_id" in strides_for_agg.index.names:
        strides_for_agg = strides_for_agg.reset_index(level="wb_id")

    wb_params = wb_meta.drop(columns=["rule_obj"], errors="ignore").copy()
    if "wb_id" not in wb_params.columns:
        if "wb_id" in wb_params.index.names:
            wb_params = wb_params.reset_index(level="wb_id")
        else:
            wb_params = wb_params.reset_index(drop=False)
            if "index" in wb_params.columns and "wb_id" not in wb_params.columns:
                wb_params = wb_params.rename(columns={"index": "wb_id"})

    available = [c for c in params_to_agg if c in strides_for_agg.columns]
    if "wb_id" in strides_for_agg.columns:
        wb_group = strides_for_agg.groupby("wb_id")

        # Recompute robust bout-level timing/count fields from stride-level data.
        wb_counts = wb_group.agg(
            start=("start", "min"),
            end=("end", "max"),
            n_strides=("start", "count"),
        )
        wb_counts["duration_s"] = (wb_counts["end"] - wb_counts["start"]) / sampling_rate_hz

        wb_params = wb_counts.join(
            wb_params.set_index("wb_id"),
            how="left",
            rsuffix="_meta",
        )

        if available:
            wb_means = wb_group[available].mean()
            wb_params = wb_params.join(wb_means, how="left")

        wb_params = wb_params.reset_index()

    # Keep canonical columns first to stabilize downstream loading.
    canonical_prefix = [
        "wb_id",
        "start",
        "end",
        "n_strides",
        "duration_s",
        "n_raw_initial_contacts",
        "rule_name",
    ]
    canonical = canonical_prefix + params_to_agg
    ordered = [c for c in canonical if c in wb_params.columns] + [
        c for c in wb_params.columns if c not in canonical
    ]
    wb_params = wb_params[ordered]

    # --- Write outputs ---
    stride_path = Path(args.stride_list)
    stride_path.parent.mkdir(parents=True, exist_ok=True)
    final_strides.reset_index().to_csv(stride_path, index=False)

    wb_path = Path(args.wb_params)
    wb_path.parent.mkdir(parents=True, exist_ok=True)
    wb_params.to_csv(wb_path, index=False)

    print(f"[WBA] stride_list written: {stride_path}")
    print(f"[WBA] wb_params written: {wb_path}")
    if not wb_params.empty:
        ws_col = "walking_speed_mps"
        if ws_col in wb_params.columns:
            mean_ws = wb_params[ws_col].mean()
            print(f"[WBA] Mean walking speed across WBs: {mean_ws:.3f} m/s")


if __name__ == "__main__":
    main()
