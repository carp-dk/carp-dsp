#!/usr/bin/env python3
"""Compute one walking-speed DMO.

Runs the Mobilise-D healthy-cohort gait pipeline on a fixed example recording and prints a
key digital mobility outcome (mean walking speed). The pipeline code is identical across
runs; only the installed mobgap version changes. Sweeping the version (see drift_sweep.sh)
shows the DMO drift that an unpinned environment silently introduces -- the failure mode
CARP-DSP prevents by pinning the environment and recording its hash in the execution report.

  python drift_run.py            # uses the currently installed mobgap
"""
import warnings
warnings.filterwarnings("ignore")

import mobgap
from mobgap.data import LabExampleDataset
from mobgap.pipeline import MobilisedPipelineHealthy


def main() -> None:
    ds = LabExampleDataset(reference_system="INDIP")
    dp = ds.get_subset(
        cohort="HA", participant_id="001", test="Test5", trial="Trial1"
    )[0]
    wb = MobilisedPipelineHealthy().run(dp).per_wb_parameters_
    ws = wb["walking_speed_mps"].dropna()
    print(f"mobgap {mobgap.__version__}")
    print(f"mean_ws_mps {ws.mean():.12f}")
    print(f"stride_len_mean {wb['stride_length_m'].dropna().mean():.12f}")
    print(f"cadence_mean {wb['cadence_spm'].dropna().mean():.12f}")


if __name__ == "__main__":
    main()
