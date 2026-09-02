"""Unit tests for sensing.heartrate.daily-features.

Run from the step directory:
    python -m pytest impl/python -q
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pandas as pd

SCRIPT = Path(__file__).parent / "daily_hr_features.py"


def run(tmp: Path, frame: pd.DataFrame, *extra: str) -> subprocess.CompletedProcess:
    src, out = tmp / "in.csv", tmp / "out.csv"
    frame.to_csv(src, index=False)
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--input", str(src), "--output", str(out), *extra],
        capture_output=True, text=True, check=False,
    )


def features(tmp: Path, frame: pd.DataFrame, *extra: str) -> pd.DataFrame:
    result = run(tmp, frame, *extra)
    assert result.returncode == 0, result.stderr
    return pd.read_csv(tmp / "out.csv")


def day(hrs: list, participant: str = "p01") -> pd.DataFrame:
    return pd.DataFrame({
        "participant_id": [participant] * len(hrs),
        "timestamp": [f"2024-01-01 {i:02d}:00:00" for i in range(len(hrs))],
        "heart_rate_bpm": hrs,
    })


def test_produces_one_row_per_participant_day(tmp_path: Path) -> None:
    frame = pd.concat([day([60, 70]), day([80, 90], participant="p02")])

    result = features(tmp_path, frame)

    assert len(result) == 2
    assert set(result["participant_id"]) == {"p01", "p02"}


def test_aggregates_the_day(tmp_path: Path) -> None:
    row = features(tmp_path, day([60, 80])).iloc[0]

    assert row["mean_hr"] == 70.0
    assert row["peak_hr"] == 80.0


def test_resting_hr_is_the_low_end_of_the_day(tmp_path: Path) -> None:
    row = features(tmp_path, day([50, 60, 70, 120])).iloc[0]

    assert row["resting_hr"] < row["mean_hr"] < row["peak_hr"]


def test_the_resting_quantile_is_configurable(tmp_path: Path) -> None:
    """At quantile 0.5 the reported `resting_hr` is the median, by definition."""
    result = features(tmp_path, day([50, 60, 70, 120]), "--resting-quantile", "0.5")

    assert result.iloc[0]["resting_hr"] == 65.0


def test_a_resting_quantile_outside_zero_to_one_is_rejected(tmp_path: Path) -> None:
    result = run(tmp_path, day([60, 70]), "--resting-quantile", "5")

    assert result.returncode != 0
    assert "between 0 and 1" in result.stderr


def test_observed_hr_intervals_says_how_much_the_percentile_rests_on(tmp_path: Path) -> None:
    """A percentile over three intervals is not comparable to one over twenty-four.

    Both days report a `resting_hr` and nothing else in the output says which is
    which, so the count is reported alongside rather than used as a filter.
    """
    full = features(tmp_path, day([60] * 24))
    sparse = features(tmp_path, day([60, 62, 64] + [pd.NA] * 21))

    assert full.iloc[0]["observed_hr_intervals"] == 24
    assert sparse.iloc[0]["observed_hr_intervals"] == 3


def test_a_day_with_no_heart_rate_reports_empty_features(tmp_path: Path) -> None:
    result = features(tmp_path, day([pd.NA, pd.NA, pd.NA]))

    assert pd.isna(result.iloc[0]["mean_hr"])
    assert pd.isna(result.iloc[0]["resting_hr"])
    assert result.iloc[0]["observed_hr_intervals"] == 0


def test_data_off_the_declared_grid_is_rejected(tmp_path: Path) -> None:
    frame = pd.DataFrame({
        "participant_id": ["p01"] * 3,
        "timestamp": ["2024-01-01 08:00:00", "2024-01-01 08:30:00", "2024-01-01 09:00:00"],
        "heart_rate_bpm": [60.0, 62.0, 64.0],
    })

    result = run(tmp_path, frame)

    assert result.returncode != 0
    assert "not on a 1h grid" in result.stderr


def test_a_finer_grid_is_accepted_when_declared(tmp_path: Path) -> None:
    frame = pd.DataFrame({
        "participant_id": ["p01"] * 3,
        "timestamp": ["2024-01-01 08:00:00", "2024-01-01 08:30:00", "2024-01-01 09:00:00"],
        "heart_rate_bpm": [60.0, 62.0, 64.0],
    })

    assert features(tmp_path, frame, "--interval", "30min").iloc[0]["observed_hr_intervals"] == 3


def test_missing_intervals_do_not_break_the_grid_check(tmp_path: Path) -> None:
    frame = pd.DataFrame({
        "participant_id": ["p01"] * 3,
        "timestamp": ["2024-01-01 08:00:00", "2024-01-01 11:00:00", "2024-01-02 07:00:00"],
        "heart_rate_bpm": [60.0, 62.0, 64.0],
    })

    assert len(features(tmp_path, frame)) == 2


def test_reproduces_the_reference_fixture(tmp_path: Path) -> None:
    step_dir = Path(__file__).resolve().parents[2]
    frame = pd.read_csv(step_dir / "reference" / "input.csv")

    result = features(tmp_path, frame)
    expected = pd.read_csv(step_dir / "reference" / "expected.csv")

    pd.testing.assert_frame_equal(result, expected, check_dtype=False, atol=1e-6)
