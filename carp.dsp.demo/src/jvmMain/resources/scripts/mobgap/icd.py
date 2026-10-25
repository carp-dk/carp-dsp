#!/usr/bin/env python3
"""
Step 3: Initial Contact Detection + Laterality + Turn Detection
================================================================

Per gait sequence, as the Mobilise-D pipeline does: detects initial contacts (IcdIonescu),
classifies left/right (LrcUllrich, Mobilise-D model) and detects turns (TdElGohary).

Outputs (recording-level samples):
  - ic_list.csv   : gs_id, step_id (index), ic, lr_label
  - turn_list.csv : gs_id, turn_id (index), start, end, ...

Usage:
    python icd.py --imu-data <imu.csv> --gs-list <gs_list.csv> --config <config.json>
                  --ic-list <ic_list.csv> --turn-list <turn_list.csv>
"""

import argparse
from pathlib import Path

from _mobilised import action_kwargs, load_config, pipeline, read_csv


def parse_args():
    parser = argparse.ArgumentParser(description="ICD + Laterality + Turn Detection (Mobilise-D)")
    parser.add_argument("--imu-data", required=True, help="Path to IMU CSV file")
    parser.add_argument("--gs-list", required=True, help="Path to gs_list CSV (from gsd)")
    parser.add_argument("--config", required=True, help="Path to config JSON")
    parser.add_argument("--ic-list", required=True, help="Output path for ic_list CSV")
    parser.add_argument("--turn-list", required=True, help="Output path for turn_list CSV")
    return parser.parse_args()


def main():
    args = parse_args()
    from mobgap.pipeline import GsIterator
    from mobgap.pipeline._gs_iterator import FullPipelinePerGsResult

    config = load_config(args.config)
    kwargs = action_kwargs(config)
    pipe = pipeline(config)
    imu = read_csv(args.imu_data, index_col=0)
    gs_list = read_csv(args.gs_list, index_col=0)

    # The first half of the pipeline's per-gait-sequence loop (GenericMobilisedPipeline._run_per_gs).
    iterator = GsIterator[FullPipelinePerGsResult]()
    for (_, gs_data), r in iterator.iterate(imu, gs_list):
        ics = pipe.initial_contact_detection.clone().detect(gs_data, **kwargs).ic_list_
        r.ic_list = pipe.laterality_classification.clone().predict(gs_data, ics, **kwargs).ic_lr_list_
        if pipe.turn_detection:
            r.turn_list = pipe.turn_detection.clone().detect(gs_data, **kwargs).turn_list_
    results = iterator.results_

    for path, frame in ((args.ic_list, results.ic_list), (args.turn_list, results.turn_list)):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        frame.to_csv(path)
    print(f"[ICD] {type(pipe.initial_contact_detection).__name__}: {len(results.ic_list)} ICs, "
          f"{len(results.turn_list)} turns over {len(gs_list)} gait sequences")


if __name__ == "__main__":
    main()
