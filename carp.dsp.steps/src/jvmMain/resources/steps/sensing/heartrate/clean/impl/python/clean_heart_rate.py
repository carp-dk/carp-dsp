#!/usr/bin/env python3
"""sensing.heartrate.clean - quality-control a heart rate series.

Drops physiologically implausible values, removes duplicate timestamps, resamples
each participant onto a regular grid, and interpolates short gaps. Consumes and
produces `dk.cachet.carp.heartrate`.

Inputs:
    --input     CSV with participant_id, timestamp, heart_rate_bpm
Outputs:
    --output    CSV participant_id, timestamp, heart_rate_bpm

Optional:
    --min-bpm / --max-bpm    plausible range, default 30-220
    --interval               resampling interval, default 1h
    --gap-limit              consecutive gaps to interpolate, default 3
"""
from __future__ import annotations

import argparse

import pandas as pd

COLUMNS = ["participant_id", "timestamp", "heart_rate_bpm"]

DEFAULT_MIN_BPM = 30.0
DEFAULT_MAX_BPM = 220.0
DEFAULT_INTERVAL = "1h"
DEFAULT_GAP_LIMIT = 3
DEFAULT_DROP_GAPS = False


def clean(
    frame: pd.DataFrame,
    min_bpm: float = DEFAULT_MIN_BPM,
    max_bpm: float = DEFAULT_MAX_BPM,
    interval: str = DEFAULT_INTERVAL,
    gap_limit: int = DEFAULT_GAP_LIMIT,
    drop_gaps: bool = DEFAULT_DROP_GAPS,
) -> pd.DataFrame:
    """Clean a heart rate series.

    Steps, in order: mask values outside the plausible range, reduce duplicate
    timestamps per participant to their first non-missing reading, resample onto
    a regular grid taking the mean within each bucket, then linearly interpolate
    runs of at most `gap_limit` empty buckets. Buckets still empty after that are
    emitted empty rather than filled or removed, so an outage is visible in the
    output rather than absent from it.

    Args:
        frame: raw samples with participant_id, timestamp, heart_rate_bpm.
        min_bpm: lowest plausible heart rate, inclusive.
        max_bpm: highest plausible heart rate, inclusive.
        interval: pandas offset alias for the output grid.
        gap_limit: how many consecutive empty buckets to interpolate across.
            Zero disables interpolation, leaving every gap as a hole.
        drop_gaps: remove intervals that are still empty instead of emitting
            them. Loses the distinction between "no reading" and "not in the
            study period", so it is off by default.

    Returns:
        Cleaned samples with the same columns (participant_id, timestamp,
        heart_rate_bpm), sorted by participant then time.
    """
    frame = frame.copy()
    frame["timestamp"] = pd.to_datetime(frame["timestamp"])
    frame["heart_rate_bpm"] = pd.to_numeric(frame["heart_rate_bpm"], errors="coerce")

    frame.loc[~frame["heart_rate_bpm"].between(min_bpm, max_bpm), "heart_rate_bpm"] = float("nan")

    cleaned = []
    for participant, group in frame.groupby("participant_id", sort=True):
        series = group.set_index("timestamp").sort_index()
        # First *non-missing* reading per timestamp. Keeping the first reading
        # outright would let a masked duplicate hide a good one recorded at the
        # same instant.
        series = series[["heart_rate_bpm"]].groupby(level=0).first()

        resampled = series.resample(interval).mean()
        # A limit of zero means "fill nothing". pandas rejects limit=0, so the
        # case is handled here rather than passed through.
        if gap_limit > 0:
            resampled["heart_rate_bpm"] = resampled["heart_rate_bpm"].interpolate(
                method="linear", limit=gap_limit
            )
        if drop_gaps:
            resampled = resampled.dropna(subset=["heart_rate_bpm"])

        resampled["participant_id"] = participant
        cleaned.append(resampled.reset_index())

    if not cleaned:
        return pd.DataFrame(columns=COLUMNS)

    return pd.concat(cleaned, ignore_index=True)[COLUMNS]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="raw heart rate CSV")
    parser.add_argument("--output", required=True, help="cleaned heart rate CSV")
    parser.add_argument("--min-bpm", type=float, default=DEFAULT_MIN_BPM)
    parser.add_argument("--max-bpm", type=float, default=DEFAULT_MAX_BPM)
    parser.add_argument("--interval", default=DEFAULT_INTERVAL)
    parser.add_argument("--gap-limit", type=int, default=DEFAULT_GAP_LIMIT)
    parser.add_argument(
        "--drop-gaps",
        action="store_true",
        help="remove intervals still empty after interpolation instead of emitting them",
    )
    args = parser.parse_args()

    result = clean(
        pd.read_csv(args.input),
        min_bpm=args.min_bpm,
        max_bpm=args.max_bpm,
        interval=args.interval,
        gap_limit=args.gap_limit,
        drop_gaps=args.drop_gaps,
    )
    result.to_csv(args.output, index=False)


if __name__ == "__main__":
    main()
