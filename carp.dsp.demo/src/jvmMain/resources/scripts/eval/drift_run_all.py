#!/usr/bin/env python3
"""Walking-speed DMO for every recording in the LabExample dataset.

Runs the Mobilise-D universal pipeline (routes each cohort to its sub-pipeline) on every
recording and prints one "datapoint,walking_speed_mps,n_wbs" line. The pipeline code is
identical across runs; only the installed mobgap version changes. Repeated runs of a fixed
version are byte-identical, so any difference between versions is attributable to the version.

  python drift_run_all.py
"""
import warnings
warnings.filterwarnings("ignore")

import mobgap
from mobgap.data import LabExampleDataset
from mobgap.pipeline import MobilisedPipelineUniversal


def main() -> None:
    ds = LabExampleDataset(reference_system="INDIP")
    print(f"# mobgap {mobgap.__version__}")
    for dp in ds:
        g = dp.group_label
        key = f"{g.cohort}-{g.participant_id}-{g.test}-{g.trial}"
        try:
            wb = MobilisedPipelineUniversal().run(dp).per_wb_parameters_
            ws = wb["walking_speed_mps"].dropna()
            val = float(ws.mean()) if len(ws) else float("nan")
            n = int(len(ws))
        except Exception:  # noqa: BLE001 - a datapoint that cannot be processed is recorded as NaN
            val, n = float("nan"), -1
        print(f"{key},{val:.12f},{n}")


if __name__ == "__main__":
    main()
