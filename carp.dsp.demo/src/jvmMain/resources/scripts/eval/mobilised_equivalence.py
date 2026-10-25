#!/usr/bin/env python3
"""
Mobilise-D equivalence check.
=============================

Claim under test: the CARP-DSP gait workflow is the official Mobilise-D pipeline, split into
steps, and gives the same results as mobgap's own ``MobilisedPipelineUniversal``.

Runs ``MobilisedPipelineUniversal`` on the recording the workflow imported and compares, step
by step, the workflow's outputs with the pipeline's own intermediates: gait sequences,
initial contacts, per-second parameters, per-walking-bout parameters and aggregated DMOs.
A stage is identical when its numbers are bit-for-bit equal (NaN equal to NaN).

Usage (inside the gait workflow's pixi environment):
    python mobilised_equivalence.py --run-root <CARP-DSP run folder> --output <result.csv>
        [--cohort MS] [--participant-id 001] [--test Test11] [--trial Trial1]

The recording flags must match the workflow's import-data arguments.
"""

import argparse
import csv
import sys
import warnings
from pathlib import Path

import pandas as pd

# Output file of each compared stage (relative to the step's outputs/ folder),
# its index columns, and the pipeline attribute holding the reference.
STAGES = [
    ("gait_sequences", "gs-list-csv.csv", [0], "gs_list_"),
    ("initial_contacts", "ic-list-csv.csv", [0, 1], "raw_ic_list_"),
    ("per_second", "per-sec-params-csv.csv", [0, 1], "raw_per_sec_parameters_"),
    ("walking_bouts", "wb-params-csv.csv", [0], "per_wb_parameters_"),
    ("dmos", "aggregated-dmos-csv.csv", None, "aggregated_parameters_"),
]


def parse_args():
    p = argparse.ArgumentParser(description="Mobilise-D equivalence check")
    p.add_argument("--run-root", required=True, help="CARP-DSP run folder (holds steps/)")
    p.add_argument("--output", required=True, help="CSV to write the per-stage result to")
    p.add_argument("--cohort", default="MS")
    p.add_argument("--participant-id", default="001")
    p.add_argument("--test", default="Test11")
    p.add_argument("--trial", default="Trial1")
    return p.parse_args()


def compare(mine: pd.DataFrame, ref: pd.DataFrame):
    """Returns (identical, max absolute difference) over the numeric cells of two frames."""
    mine = mine.reset_index(drop=True)
    ref = ref.reset_index(drop=True)
    if list(mine.columns) != list(ref.columns) or mine.shape != ref.shape:
        return False, None
    a = mine.select_dtypes("number").to_numpy(dtype=float)
    b = ref.select_dtypes("number").to_numpy(dtype=float)
    same_text = mine.select_dtypes(exclude="number").astype(str).equals(ref.select_dtypes(exclude="number").astype(str))
    both_nan = pd.isna(a) & pd.isna(b)
    diff = abs(a - b)
    diff[both_nan] = 0.0
    max_diff = float(diff.max()) if diff.size else 0.0
    return same_text and max_diff == 0.0 and (pd.isna(a) == pd.isna(b)).all(), max_diff


def main():
    args = parse_args()
    warnings.filterwarnings("ignore")
    from mobgap import __version__ as mobgap_version
    from mobgap.data import LabExampleDataset
    from mobgap.pipeline import MobilisedPipelineUniversal

    datapoint = LabExampleDataset(reference_system="INDIP").get_subset(
        cohort=args.cohort, participant_id=args.participant_id, test=args.test, trial=args.trial
    )
    universal = MobilisedPipelineUniversal().run(datapoint)
    ref = universal.pipeline_
    print(f"[EQ] mobgap {mobgap_version}; {args.cohort}/{args.participant_id}/{args.test}/{args.trial} "
          f"-> {type(ref).__name__}")

    outputs = {f.name: f for f in Path(args.run_root).glob("steps/*/outputs/*.csv")}
    rows = []
    for stage, file_name, index_cols, attribute in STAGES:
        if file_name not in outputs:
            print(f"[EQ] ERROR: {file_name} not found under {args.run_root}/steps", file=sys.stderr)
            return 2
        mine = pd.read_csv(outputs[file_name], index_col=index_cols, float_precision="round_trip")
        identical, max_diff = compare(mine, getattr(ref, attribute))
        rows.append([args.cohort, type(ref).__name__, mobgap_version, stage, len(mine), identical,
                     "" if max_diff is None else max_diff])
        print(f"[EQ]   {stage:<17} {len(mine):>4} rows  {'identical' if identical else 'DIFFERS'}"
              + ("" if identical or max_diff is None else f"  (max diff {max_diff:.3g})"))

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["cohort", "pipeline", "mobgap", "stage", "rows", "identical", "max_abs_diff"])
        w.writerows(rows)
    return 0 if all(r[5] for r in rows) else 1


if __name__ == "__main__":
    sys.exit(main())
