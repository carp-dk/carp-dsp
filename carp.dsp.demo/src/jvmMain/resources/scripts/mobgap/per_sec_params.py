#!/usr/bin/env python3
# --- eval timing (paper Fig D): prints script self-time to stderr ---
import atexit as _ae, sys as _sys, time as _time
_EVAL_T0 = _time.perf_counter()
_ae.register(lambda: print(f"EVAL_SCRIPT_SECONDS={_time.perf_counter() - _EVAL_T0:.3f}", file=_sys.stderr))
# --------------------------------------------------------------------
"""
Step 4: Per-Second Parameter Estimation (Cadence, Stride Length, Walking Speed)
===============================================================================

Per gait sequence, as the Mobilise-D pipeline does: refines the sequence to its first and
last initial contact, then estimates cadence (CadFromIcDetector), stride length
(SlZijlstra, from the sensor height) and walking speed (WsNaive) per second.

Output: per_sec_params.csv - gs_id, sec_center_samples (index), cadence_spm,
        stride_length_m, walking_speed_mps

Usage:
    python per_sec_params.py --imu-data <imu.csv> --gs-list <gs_list.csv>
        --ic-list <ic_list.csv> --config <config.json> --output <per_sec_params.csv>
"""

import argparse
from pathlib import Path

import pandas as pd

from _mobilised import action_kwargs, load_config, pipeline, read_csv


def parse_args():
    parser = argparse.ArgumentParser(description="Per-second gait parameters (Mobilise-D)")
    parser.add_argument("--imu-data", required=True)
    parser.add_argument("--gs-list", required=True)
    parser.add_argument("--ic-list", required=True)
    parser.add_argument("--config", required=True)
    parser.add_argument("--output", required=True, help="Output CSV path for per_sec_params")
    return parser.parse_args()


def main():
    args = parse_args()
    from mobgap.initial_contacts import refine_gs
    from mobgap.pipeline import GsIterator
    from mobgap.pipeline._gs_iterator import FullPipelinePerGsResult

    config = load_config(args.config)
    kwargs = action_kwargs(config)
    pipe = pipeline(config)
    imu = read_csv(args.imu_data, index_col=0)
    gs_list = read_csv(args.gs_list, index_col=0)
    ic_list = read_csv(args.ic_list, index_col=[0, 1])
    has_ics = set(ic_list.index.get_level_values(0))

    # The second half of the pipeline's per-gait-sequence loop (GenericMobilisedPipeline._run_per_gs).
    iterator = GsIterator[FullPipelinePerGsResult]()
    for (gs, _), _r in iterator.iterate(imu, gs_list):
        ics = ic_list.loc[[gs.id]].droplevel(0) if gs.id in has_ics else ic_list.iloc[0:0].droplevel(0)
        ics = ics.assign(ic=ics["ic"] - gs.start)  # back to sequence-local samples
        if len(ics) < 2:
            continue
        refined_gs, refined_ics = refine_gs(ics)
        with iterator.subregion(refined_gs) as ((_, data), rr):
            cad = pipe.cadence_calculation.clone().calculate(data, initial_contacts=refined_ics, **kwargs)
            rr.cadence_per_sec = cad.cadence_per_sec_
            sl = pipe.stride_length_calculation.clone().calculate(data, initial_contacts=refined_ics, **kwargs)
            rr.stride_length_per_sec = sl.stride_length_per_sec_
            rr.walking_speed_per_sec = pipe.walking_speed_calculation.clone().calculate(
                data,
                initial_contacts=refined_ics,
                cadence_per_sec=cad.cadence_per_sec_,
                stride_length_per_sec=sl.stride_length_per_sec_,
                **kwargs,
            ).walking_speed_per_sec_
    results = iterator.results_

    per_sec = pd.concat(
        [results.cadence_per_sec, results.stride_length_per_sec, results.walking_speed_per_sec], axis=1
    ).reset_index("r_gs_id", drop=True)
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    per_sec.to_csv(out)
    print(f"[PER-SEC] {type(pipe.cadence_calculation).__name__}: {len(per_sec)} seconds -> {out}")


if __name__ == "__main__":
    main()
