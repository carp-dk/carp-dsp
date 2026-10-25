#!/usr/bin/env python3
# --- eval timing (paper Fig D): prints script self-time to stderr ---
import atexit as _ae, sys as _sys, time as _time
_EVAL_T0 = _time.perf_counter()
_ae.register(lambda: print(f"EVAL_SCRIPT_SECONDS={_time.perf_counter() - _EVAL_T0:.3f}", file=_sys.stderr))
# --------------------------------------------------------------------
"""
Step 6: DMO Thresholds + Aggregation
====================================

As the Mobilise-D pipeline does: masks walking-bout parameters outside the Mobilise-D
validity thresholds for the participant's cohort, height and measurement condition, then
aggregates the bouts into one row of digital mobility outcomes (MobilisedAggregator).

Output: aggregated_dmos.csv - one row (wb_all__count, total_walking_duration_min, ...)

Usage:
    python aggregate.py --wb-params <wb_params.csv> --config <config.json> --output <dmos.csv>
"""

import argparse
from pathlib import Path

from _mobilised import load_config, pipeline, read_csv


def parse_args():
    parser = argparse.ArgumentParser(description="DMO Aggregation (Mobilise-D)")
    parser.add_argument("--wb-params", required=True, help="WB parameters CSV (from wba)")
    parser.add_argument("--config", required=True, help="Config JSON")
    parser.add_argument("--output", required=True, help="Output CSV for aggregated DMOs")
    return parser.parse_args()


def main():
    args = parse_args()
    from mobgap.aggregation import apply_thresholds

    config = load_config(args.config)
    pipe = pipeline(config)
    participant = config["participant"]
    per_wb = read_csv(args.wb_params, index_col=0)

    mask = None
    if pipe.dmo_thresholds is not None:
        mask = apply_thresholds(
            per_wb,
            pipe.dmo_thresholds,
            cohort=participant["cohort"],
            height_m=participant["height_m"],
            measurement_condition=config["recording"]["measurement_condition"],
        )
    dmos = pipe.dmo_aggregation.clone().aggregate(per_wb, wb_dmos_mask=mask).aggregated_data_

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    dmos.to_csv(out, index=False)
    print(f"[AGG] {len(per_wb)} walking bouts -> {len(dmos.columns)} DMOs -> {out}")


if __name__ == "__main__":
    main()
