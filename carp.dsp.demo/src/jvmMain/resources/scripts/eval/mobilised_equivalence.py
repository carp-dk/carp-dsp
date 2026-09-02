#!/usr/bin/env python3
"""
Mobilise-D equivalence check.
=============================

The claim under test is:

    the CARP-DSP workflow expresses the Mobilise-D pipeline,
    invoking the same validated library, and produces the same outcomes on the same recording.

This script compares two things, and both matter:

  1. ALGORITHM CHAIN -- the concrete algorithm objects mobgap's own
     MobilisedPipeline* configures, against the ones the workflow's steps
     instantiate. If these differ, the pipelines are not the same and no
     numeric agreement would make them so.

  2. AGGREGATED DMOs -- mobgap's packaged pipeline run end to end on the same
     LabExampleDataset datapoint, against the CARP-DSP workflow's
     aggregate step output.

Usage
-----
    pixi run python mobilised_equivalence.py \
        --workflow-output <dsp-output>/.../aggregated_dmos.csv \
        [--cohort MS] [--participant-id 001] [--test Test11] [--trial Trial1] \
        [--pipeline universal|healthy|impaired] \
        [--results-dir ../../../../../eval_results]

Writes mobilised-equivalence.{txt,csv,tex} into --results-dir.

NOTE: the defaults mirror scripts/mobgap/import_data.py, so the reference run
and the workflow run see the identical recording. If you change the demo's
selection, you must also change it here too or pass the flags.
"""

import argparse
import json
import sys
from pathlib import Path

# PRIMARY picks the subset worth putting in the paper's table.
PRIMARY = (
    "walking_speed_mps__avg",
    "stride_length_m__avg",
    "cadence_spm__avg",
    "stride_duration_s__avg",
    "wb_all__count",
    "total_walking_duration_min",
)

# The algorithm blocks a GenericMobilisedPipeline is built from.
BLOCKS = [
    "gait_sequence_detection",
    "initial_contact_detection",
    "laterality_classification",
    "cadence_calculation",
    "stride_length_calculation",
    "walking_speed_calculation",
    "turn_detection",
    "stride_selection",
    "wba",
    "dmo_aggregation",
]

# What the CARP-DSP workflow steps instantiate, read off
# resources/scripts/mobgap/*.py. Kept here so the comparison is explicit and
# reviewable; update if the step scripts change.
WORKFLOW_CHAIN = {
    "gait_sequence_detection":  "GsdIluz",
    "initial_contact_detection": "IcdShinImproved",
    "laterality_classification": "LrcUllrich",
    "cadence_calculation":       "CadFromIc",
    "stride_length_calculation": "SlZijlstra",
    "walking_speed_calculation": "WsNaive",
    "turn_detection":            "TdElGohary",
    "stride_selection":          "StrideSelection",
    "wba":                       "WbAssembly",
    "dmo_aggregation":           "MobilisedAggregator",
}


def parse_args():
    p = argparse.ArgumentParser(description="Mobilise-D equivalence check")
    p.add_argument("--workflow-output", required=True,
                   help="CARP-DSP aggregate step output CSV")
    p.add_argument("--cohort", default="MS")
    p.add_argument("--participant-id", default="001")
    p.add_argument("--test", default="Test11")
    p.add_argument("--trial", default="Trial1")
    p.add_argument("--pipeline", default="universal",
                   choices=["universal", "healthy", "impaired"],
                   help="Which packaged mobgap pipeline to use as reference. "
                        "'universal' selects by cohort, as Mobilise-D intends.")
    p.add_argument("--results-dir", default="eval_results")
    p.add_argument("--tolerance", type=float, default=1e-9,
                   help="Relative tolerance for calling a DMO identical")
    return p.parse_args()


def block_name(obj):
    if obj is None:
        return "-"
    return type(obj).__name__


def main():
    args = parse_args()

    try:
        import pandas as pd
        from mobgap import __version__ as mobgap_version
        from mobgap.data import LabExampleDataset
        from mobgap.pipeline import (
            MobilisedPipelineHealthy,
            MobilisedPipelineImpaired,
            MobilisedPipelineUniversal,
        )
    except ImportError as e:
        print(f"[EQ] ERROR: mobgap/pandas not available: {e}", file=sys.stderr)
        print("[EQ] Run inside the demo's pixi environment.", file=sys.stderr)
        return 2

    # ---------- reference run: mobgap's own packaged pipeline ----------
    dataset = LabExampleDataset(reference_system="INDIP")
    datapoint = dataset.get_subset(
        cohort=args.cohort,
        participant_id=args.participant_id,
        test=args.test,
        trial=args.trial,
    )

    if args.pipeline == "healthy":
        ref = MobilisedPipelineHealthy()
    elif args.pipeline == "impaired":
        ref = MobilisedPipelineImpaired()
    else:
        ref = MobilisedPipelineUniversal()

    print(f"[EQ] mobgap {mobgap_version}; reference pipeline "
          f"{type(ref).__name__}; datapoint "
          f"{args.cohort}/{args.participant_id}/{args.test}/{args.trial}")

    ref = ref.run(datapoint)

    # MobilisedPipelineUniversal delegates to the pipeline it selected.
    inner = getattr(ref, "pipeline_", ref)
    ref_chain = {b: block_name(getattr(inner, b, None)) for b in BLOCKS}

    ref_agg = ref.aggregated_parameters_
    if ref_agg is None:
        print("[EQ] ERROR: reference pipeline produced no aggregated "
              "parameters for this datapoint.", file=sys.stderr)
        return 3
    ref_row = ref_agg.reset_index().iloc[0]

    # ---------- the workflow's own output ----------
    wf_path = Path(args.workflow_output)
    if not wf_path.is_file():
        print(f"[EQ] ERROR: workflow output not found: {wf_path}", file=sys.stderr)
        print("[EQ] Run the mobgap demo first, then point --workflow-output "
              "at its aggregate step output.", file=sys.stderr)
        return 4
    wf_row = pd.read_csv(wf_path).iloc[0]

    # ---------- 1. algorithm chain ----------
    chain_rows, chain_same = [], True
    for b in BLOCKS:
        r = ref_chain.get(b, "-")
        w = WORKFLOW_CHAIN.get(b, "-")
        same = (r == w)
        if not same:
            chain_same = False
        chain_rows.append((b, r, w, same))

    # ---------- 2. aggregated DMOs ----------
    # Compare every numeric column both frames carry. A column present in one
    # and not the other is itself a difference and is reported as such.
    ref_cols = set(ref_row.index)
    wf_cols = set(wf_row.index)
    shared = [c for c in ref_row.index if c in wf_cols]
    only_ref = sorted(ref_cols - wf_cols - {"index"})
    only_wf = sorted(wf_cols - ref_cols - {"index"})

    dmo_rows, dmo_same = [], True
    for c in shared:
        if c == "index":
            continue
        try:
            rv, wv = float(ref_row[c]), float(wf_row[c])
        except (TypeError, ValueError):
            if str(ref_row[c]) != str(wf_row[c]):
                dmo_same = False
                dmo_rows.append((c, ref_row[c], wf_row[c], None, "differs"))
            continue
        import math
        if math.isnan(rv) and math.isnan(wv):
            dmo_rows.append((c, rv, wv, 0.0, "identical")); continue
        diff = abs(rv - wv)
        rel = diff / abs(rv) if rv else (0.0 if diff == 0 else float("inf"))
        ok = rel <= args.tolerance
        if not ok:
            dmo_same = False
        dmo_rows.append((c, rv, wv, rel, "identical" if ok else "differs"))

    if only_ref or only_wf:
        dmo_same = False

    # ---------- report ----------
    out = Path(args.results_dir)
    out.mkdir(parents=True, exist_ok=True)

    lines = []
    lines.append("Mobilise-D equivalence check")
    lines.append("=" * 60)
    lines.append(f"mobgap version      : {mobgap_version}")
    lines.append(f"reference pipeline  : {type(ref).__name__}"
                 + (f" -> {type(inner).__name__}" if inner is not ref else ""))
    lines.append(f"datapoint           : {args.cohort}/{args.participant_id}"
                 f"/{args.test}/{args.trial}")
    lines.append(f"workflow output     : {wf_path}")
    lines.append("")
    lines.append("1. Algorithm chain")
    lines.append(f"{'block':<28}{'mobgap reference':<24}{'CARP-DSP workflow':<24}same")
    for b, r, w, s in chain_rows:
        lines.append(f"{b:<28}{r:<24}{w:<24}{'yes' if s else 'NO'}")
    lines.append(f"  -> chains {'MATCH' if chain_same else 'DIFFER'}")
    lines.append("")
    lines.append("2. Aggregated DMOs")
    lines.append(f"{'dmo':<24}{'reference':>16}{'workflow':>16}{'rel.diff':>14}  verdict")
    for c, rv, wv, rel, verdict in dmo_rows:
        rs = f"{rv:16.9g}" if isinstance(rv, float) else f"{'-':>16}"
        ws = f"{wv:16.9g}" if isinstance(wv, float) else f"{'-':>16}"
        ds = f"{rel:14.3g}" if isinstance(rel, float) else f"{'-':>14}"
        lines.append(f"{c:<24}{rs}{ws}{ds}  {verdict}")
    if only_ref:
        lines.append(f"  columns only in reference : {', '.join(only_ref)}")
    if only_wf:
        lines.append(f"  columns only in workflow  : {', '.join(only_wf)}")
    lines.append(f"  -> {len(dmo_rows)} shared columns compared; "
                 f"DMOs {'MATCH' if dmo_same else 'DIFFER'} "
                 f"(tolerance {args.tolerance:g})")
    lines.append("")
    lines.append("VERDICT: " + (
        "the workflow expresses the same algorithm chain and reproduces the "
        "reference pipeline's outcomes."
        if chain_same and dmo_same else
        "see the rows marked NO / differs above."))

    txt = "\n".join(lines)
    print(txt)
    (out / "mobilised-equivalence.txt").write_text(txt + "\n", encoding="utf-8")

    import csv as _csv
    with (out / "mobilised-equivalence.csv").open("w", newline="", encoding="utf-8") as fh:
        w = _csv.writer(fh)
        w.writerow(["kind", "name", "reference", "workflow", "rel_diff", "verdict"])
        for b, r, wn, s in chain_rows:
            w.writerow(["block", b, r, wn, "", "same" if s else "differs"])
        for c, rv, wv, rel, verdict in dmo_rows:
            w.writerow(["dmo", c, rv, wv, rel, verdict])

    tex = ["% generated by mobilised_equivalence.py -- do not edit",
           r"\begin{tabular}{@{}l r r l@{}}", r"\toprule",
           r"DMO & Mobilise-D reference & CARP-DSP workflow & Agreement \\", r"\midrule"]
    for c, rv, wv, rel, verdict in dmo_rows:
        if not isinstance(rv, float):
            continue
        if not any(c.endswith(p) or c == p for p in PRIMARY):
            continue
        mark = r"identical" if verdict == "identical" else f"{rel:.2g} rel."
        safe = c.replace("_", "\\_")
        tex.append("\\texttt{" + safe + "} & "
                   + f"{rv:.6g} & {wv:.6g} & {mark}" + " \\\\")
    tex += [r"\bottomrule", r"\end{tabular}"]
    (out / "mobilised-equivalence.tex").write_text("\n".join(tex) + "\n", encoding="utf-8")

    print(f"\n[EQ] wrote mobilised-equivalence.{{txt,csv,tex}} to {out}")
    return 0 if (chain_same and dmo_same) else 1


if __name__ == "__main__":
    sys.exit(main())
