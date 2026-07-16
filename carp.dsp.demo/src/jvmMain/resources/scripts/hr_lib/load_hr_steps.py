#!/usr/bin/env python3
"""Library step: load-hr-steps.

Fetch the open Fitbit dataset (Furberg et al., Zenodo 53894, CC BY 4.0), and build an
hourly heart-rate + step-count table. Joins hourly step counts with heart-rate readings
aggregated to the hour, one row per (participant, hour).

Output type: hr-steps-csv  [participant_id, timestamp, heart_rate_bpm, steps]

Reused by: wf-activity-summary, wf-anomaly-report, wf-minimal-summary.
"""
import argparse
import io
import pathlib
import urllib.request
import zipfile

import pandas as pd

ZENODO_ZIP = ("https://zenodo.org/records/53894/files/"
              "mturkfitbit_export_4.12.16-5.12.16.zip?download=1")
STEPS_CSV = "Fitabase Data 4.12.16-5.12.16/hourlySteps_merged.csv"
HR_CSV = "Fitabase Data 4.12.16-5.12.16/heartrate_seconds_merged.csv"


def _load_zip(cache: pathlib.Path) -> zipfile.ZipFile:
    cache.parent.mkdir(parents=True, exist_ok=True)
    if not cache.exists():
        print(f"[load-hr-steps] downloading {ZENODO_ZIP}")
        urllib.request.urlretrieve(ZENODO_ZIP, cache)
    return zipfile.ZipFile(cache)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", required=True, help="hr-steps-csv output path")
    ap.add_argument("--cache", default=".cache/fitbit_53894.zip", help="dataset zip cache")
    ap.add_argument("--participants", type=int, default=8, help="limit to first N participants")
    args = ap.parse_args()

    zf = _load_zip(pathlib.Path(args.cache))
    steps = pd.read_csv(io.BytesIO(zf.read(STEPS_CSV)))
    hr = pd.read_csv(io.BytesIO(zf.read(HR_CSV)))

    steps["timestamp"] = pd.to_datetime(steps["ActivityHour"])
    steps = steps.rename(columns={"Id": "participant_id", "StepTotal": "steps"})

    hr["timestamp"] = pd.to_datetime(hr["Time"]).dt.floor("h")
    hr = (hr.rename(columns={"Id": "participant_id", "Value": "heart_rate_bpm"})
            .groupby(["participant_id", "timestamp"], as_index=False)["heart_rate_bpm"].mean())

    df = steps.merge(hr, on=["participant_id", "timestamp"], how="inner")
    keep = sorted(df["participant_id"].unique())[: args.participants]
    df = df[df["participant_id"].isin(keep)]
    df = df[["participant_id", "timestamp", "heart_rate_bpm", "steps"]].sort_values(
        ["participant_id", "timestamp"])

    df.to_csv(args.output, index=False)
    print(f"[load-hr-steps] wrote {len(df)} rows, {df['participant_id'].nunique()} participants "
          f"-> {args.output}")


if __name__ == "__main__":
    main()
