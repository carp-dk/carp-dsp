#!/usr/bin/env python3
# --- eval timing (paper Fig D): prints script self-time to stderr ---
import atexit as _ae, sys as _sys, time as _time
_EVAL_T0 = _time.perf_counter()
_ae.register(lambda: print(f"EVAL_SCRIPT_SECONDS={_time.perf_counter() - _EVAL_T0:.3f}", file=_sys.stderr))
# --------------------------------------------------------------------
"""
Step 5: Stride Selection + Walking Bout Assembly
================================================

As the Mobilise-D pipeline does: builds strides from the initial contacts, interpolates the
per-second parameters to strides, filters them (StrideSelection), assembles walking bouts
(WbAssembly) and averages the stride parameters per bout.

Outputs:
  - stride_list.csv : strides in valid walking bouts, with their parameters
  - wb_params.csv   : one row per walking bout (wb_id, start, end, duration, means, ...)

Usage:
    python wba.py --ic-list <ic_list.csv> --per-sec-params <per_sec.csv> --config <config.json>
                  --stride-list <stride_list.csv> --wb-params <wb_params.csv>
"""

import argparse
from pathlib import Path

import pandas as pd

from _mobilised import load_config, pipeline, read_csv


def parse_args():
    parser = argparse.ArgumentParser(description="Stride selection + WB assembly (Mobilise-D)")
    parser.add_argument("--ic-list", required=True)
    parser.add_argument("--per-sec-params", required=True)
    parser.add_argument("--config", required=True)
    parser.add_argument("--stride-list", required=True, help="Output CSV for stride list")
    parser.add_argument("--wb-params", required=True, help="Output CSV for WB parameters")
    return parser.parse_args()


def main():
    args = parse_args()
    config = load_config(args.config)
    pipe = pipeline(config)
    fs = config["sampling_rate_hz"]
    ic_list = read_csv(args.ic_list, index_col=[0, 1])
    per_sec = read_csv(args.per_sec_params, index_col=[0, 1])

    # The pipeline's own steps after the per-gait-sequence loop (GenericMobilisedPipeline.run).
    strides = pipe._sec_to_stride(per_sec, ic_list, fs)
    flat_index = pd.Index(["_".join(str(e) for e in s_id) for s_id in strides.index], name="s_id")
    strides = strides.reset_index("gs_id").rename(columns={"gs_id": "original_gs_id"})
    strides.index = flat_index

    selected = pipe.stride_selection.clone().filter(strides, sampling_rate_hz=fs)
    wba = pipe.wba.clone().assemble(selected.filtered_stride_list_, raw_initial_contacts=ic_list, sampling_rate_hz=fs)
    per_wb = pipe._aggregate_per_wb(wba.annotated_stride_list_, wba.wb_meta_parameters_)

    for path, frame in ((args.stride_list, wba.annotated_stride_list_), (args.wb_params, per_wb)):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        frame.to_csv(path)
    print(f"[WBA] {len(selected.filtered_stride_list_)} strides selected, {len(per_wb)} walking bouts")


if __name__ == "__main__":
    main()
