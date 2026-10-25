#!/usr/bin/env python3
# --- eval timing (paper Fig D): prints script self-time to stderr ---
import atexit as _ae, sys as _sys, time as _time
_EVAL_T0 = _time.perf_counter()
_ae.register(lambda: print(f"EVAL_SCRIPT_SECONDS={_time.perf_counter() - _EVAL_T0:.3f}", file=_sys.stderr))
# --------------------------------------------------------------------
"""
Step 1: Import IMU Data from LabExampleDataset
==============================================

Downloads mobgap's built-in LabExampleDataset (if not already cached), picks one trial
(lower-back sensor, INDIP reference system), and writes:
  - imu_data.csv : IMU signal in body frame (acc_*, gyr_*), one row per sample
  - config.json  : sampling rate plus the participant and recording metadata the
                   Mobilise-D algorithms use (cohort, height, sensor height, ...)

Usage:
    python import_data.py --imu-data <out.csv> --config <out.json>
        [--cohort MS] [--participant-id 001] [--test Test11] [--trial Trial1]
"""

import argparse
import json
import sys
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser(description="Import LabExampleDataset trial to flat CSV + config JSON")
    parser.add_argument("--imu-data", required=True, help="Output path for imu_data CSV")
    parser.add_argument("--config", required=True, help="Output path for config JSON")
    parser.add_argument("--cohort", default="MS", help="Cohort: 'MS' (impaired) or 'HA' (healthy). Default: MS")
    parser.add_argument("--participant-id", default="001", help="Participant ID. Default: 001")
    parser.add_argument("--test", default="Test11", help="Test name. Default: Test11")
    parser.add_argument("--trial", default="Trial1", help="Trial name. Default: Trial1")
    return parser.parse_args()


def main():
    args = parse_args()
    from mobgap.data import LabExampleDataset
    from mobgap.utils.conversions import to_body_frame

    print(f"[IMPORT] LabExampleDataset {args.cohort}/{args.participant_id}/{args.test}/{args.trial}")
    try:
        dataset = LabExampleDataset(reference_system="INDIP")
        trial = dataset.get_subset(
            cohort=args.cohort, participant_id=args.participant_id, test=args.test, trial=args.trial
        )[0]
    except Exception as e:
        print(f"ERROR: Could not load dataset - {e}")
        sys.exit(1)

    imu_data = to_body_frame(trial.data_ss)
    imu_path = Path(args.imu_data)
    imu_path.parent.mkdir(parents=True, exist_ok=True)
    imu_data.to_csv(imu_path)
    print(f"[IMPORT] {len(imu_data)} samples at {trial.sampling_rate_hz} Hz -> {imu_path}")

    # The recording identifier is a tuple naming the trial; no algorithm reads it.
    recording = {k: v for k, v in trial.recording_metadata.items() if k != "recording_identifier"}
    config = {
        "sampling_rate_hz": float(trial.sampling_rate_hz),
        "participant": dict(trial.participant_metadata),
        "recording": recording,
    }
    config_path = Path(args.config)
    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_path.write_text(json.dumps(config, indent=2))
    print(f"[IMPORT] config -> {config_path}")


if __name__ == "__main__":
    main()
