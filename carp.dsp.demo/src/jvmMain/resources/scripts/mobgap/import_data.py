#!/usr/bin/env python3
"""
Step 1: Import IMU Data from LabExampleDataset
===============================================
CARP-DSP pipeline step — mobgap demo.

Downloads mobgap's built-in LabExampleDataset (if not already cached),
selects a single trial, converts to body frame, and writes the flat files
that all downstream steps expect.

Usage:
    python step1_import.py --imu-data <csv> --config <json>
                           [--cohort <str>] [--participant-id <str>]
                           [--test <str>] [--trial <str>]

Outputs:
    --imu-data   CSV: columns acc_x, acc_y, acc_z, gyr_x, gyr_y, gyr_z
                      one row per sample, index = sample number
    --config     JSON: sampling_rate_hz, cohort, height_m, measurement_condition
"""

import argparse
import json
import sys
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser(
        description="Import LabExampleDataset trial to flat CSV + config JSON"
    )
    parser.add_argument("--imu-data", required=True, help="Output path for imu_data CSV")
    parser.add_argument("--config", required=True, help="Output path for config JSON")
    parser.add_argument("--cohort", default="MS", help="Cohort: 'MS' (impaired) or 'HA' (healthy). Default: MS")
    parser.add_argument("--participant-id", default="001", help="Participant ID. Default: 001")
    parser.add_argument("--test", default="Test11", help="Test name. Default: Test11")
    parser.add_argument("--trial", default="Trial1", help="Trial name. Default: Trial1")
    return parser.parse_args()


def main():
    args = parse_args()

    try:
        from mobgap.data import LabExampleDataset
        from mobgap.utils.conversions import to_body_frame
    except ImportError:
        print("ERROR: mobgap not installed. Run: pip install mobgap")
        sys.exit(1)

    print(f"[IMPORT] Loading LabExampleDataset — cohort={args.cohort}, "
          f"participant={args.participant_id}, test={args.test}, trial={args.trial}")
    print("[IMPORT] (First run will download sample data — requires internet access)")

    try:
        dataset = LabExampleDataset(reference_system="INDIP")
        trial = dataset.get_subset(
            cohort=args.cohort,
            participant_id=args.participant_id,
            test=args.test,
            trial=args.trial,
        )[0]
    except Exception as e:
        print(f"ERROR: Could not load dataset — {e}")
        sys.exit(1)

    imu_data = to_body_frame(trial.data_ss)
    sampling_rate_hz = float(trial.sampling_rate_hz)
    participant_metadata = trial.participant_metadata
    measurement_condition = trial.recording_metadata["measurement_condition"]

    print(f"[IMPORT] Loaded {len(imu_data)} samples at {sampling_rate_hz} Hz")
    print(f"[IMPORT] Columns: {list(imu_data.columns)}")
    print(f"[IMPORT] Duration: {len(imu_data) / sampling_rate_hz:.1f} s")

    # Write IMU data
    imu_path = Path(args.imu_data)
    imu_path.parent.mkdir(parents=True, exist_ok=True)
    imu_data.to_csv(imu_path)
    print(f"[IMPORT] imu_data written: {imu_path}")

    # Write config
    config = {
        "sampling_rate_hz": sampling_rate_hz,
        "cohort": participant_metadata["cohort"],
        "height_m": float(participant_metadata["height_m"]),
        "measurement_condition": measurement_condition,
    }
    config_path = Path(args.config)
    config_path.parent.mkdir(parents=True, exist_ok=True)
    with open(config_path, "w") as f:
        json.dump(config, f, indent=2)
    print(f"[IMPORT] config written: {config_path}")
    print(f"[IMPORT] Config: {json.dumps(config)}")


if __name__ == "__main__":
    main()
