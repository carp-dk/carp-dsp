"""Unit tests for analysis.activity.detect-anomaly.

Run from the step directory:
    python -m pytest impl/python -q
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pandas as pd

SCRIPT = Path(__file__).parent / "detect_anomaly.py"


def detect(tmp: Path, frame: pd.DataFrame, *args: str) -> pd.DataFrame:
    src, out = tmp / "in.csv", tmp / "out.csv"
    frame.to_csv(src, index=False)
    result = subprocess.run(
        [sys.executable, str(SCRIPT), "--input", str(src), "--output", str(out), *args],
        capture_output=True, text=True, check=False,
    )
    assert result.returncode == 0, result.stderr
    return pd.read_csv(out)


FLAGGING = ("--min-days", "6")


def days(total_steps: list[int], resting: list[float], participant: str = "p01") -> pd.DataFrame:
    return pd.DataFrame({
        "participant_id": [participant] * len(total_steps),
        "date": [f"2024-01-{i + 1:02d}" for i in range(len(total_steps))],
        "mean_hr": [70.0] * len(total_steps),
        "resting_hr": resting,
        "peak_hr": [120.0] * len(total_steps),
        "total_steps": total_steps,
        "active_intervals": [5] * len(total_steps),
    })


def test_flags_a_day_well_below_the_participants_usual_activity(tmp_path: Path) -> None:
    # Five typical days and one near-inactive day.
    result = detect(tmp_path, days([10000] * 5 + [100], [60.0] * 6), *FLAGGING)

    assert result.iloc[-1]["low_activity"] == True  # noqa: E712 - must not be missing
    assert result.iloc[-1]["flagged"] == True  # noqa: E712
    assert result["low_activity"].notna().all()


def test_does_not_flag_a_consistent_participant(tmp_path: Path) -> None:
    result = detect(tmp_path, days([10000, 10200, 9800, 10100, 9900, 10050], [60.0] * 6), *FLAGGING)

    assert result["flagged"].notna().all()
    assert not result["flagged"].any()


def test_baselines_are_per_participant(tmp_path: Path) -> None:
    """A low-stepping participant is judged against their own norm, not the cohort's."""
    active = days([10000] * 6, [60.0] * 6)
    sedentary = days([500, 520, 480, 510, 495, 505], [60.0] * 6, participant="p02")

    result = detect(tmp_path, pd.concat([active, sedentary]), *FLAGGING)

    quiet = result.query("participant_id == 'p02'")
    assert quiet["low_activity"].notna().all()
    assert not quiet["low_activity"].any()


def test_leaves_every_flag_empty_below_the_minimum_days(tmp_path: Path) -> None:
    """Empty, not False: there were not enough days to decide, which is not a finding.
    """
    result = detect(tmp_path, days([10000] * 5 + [1], [60.0] * 6), "--min-days", "10")

    assert result[["low_activity", "elevated_resting_hr", "flagged"]].isna().all().all()


def test_the_minimum_cannot_be_set_below_the_floor(tmp_path: Path) -> None:
    """Under six days `elevated_resting_hr` cannot fire whatever the data says.
    """
    frame = days([10000] * 4 + [1], [60.0] * 4 + [200.0])

    result = detect(tmp_path, frame, "--min-days", "1")

    assert result["elevated_resting_hr"].isna().all()


def test_reports_a_row_for_every_input_day(tmp_path: Path) -> None:
    result = detect(tmp_path, days([1000, 2000, 3000], [60.0] * 3))

    assert len(result) == 3
    assert list(result.columns) == [
        "participant_id", "date", "low_activity", "elevated_resting_hr", "flagged",
    ]


def test_the_floor_follows_the_multiplier(tmp_path: Path) -> None:
    frame = days([10000] * 5 + [10000], [60.0] * 5 + [200.0])

    at_two = detect(tmp_path, frame, "--min-days", "6", "--hr-sd-multiplier", "2.0")
    at_three = detect(tmp_path, frame, "--min-days", "6", "--hr-sd-multiplier", "3.0")

    assert at_two["elevated_resting_hr"].notna().all()
    assert at_two.iloc[-1]["elevated_resting_hr"] == True  # noqa: E712
    assert at_three["elevated_resting_hr"].isna().all()


def test_a_stricter_multiplier_flags_less(tmp_path: Path) -> None:
    resting = [58.0, 59.0, 60.0, 60.0, 61.0, 62.0, 59.0, 61.0, 60.0, 62.5]
    frame = days([10000] * 10, resting)

    lenient = detect(tmp_path, frame, "--min-days", "10", "--hr-sd-multiplier", "1.5")
    strict = detect(tmp_path, frame, "--min-days", "10", "--hr-sd-multiplier", "2.0")

    assert lenient.iloc[-1]["elevated_resting_hr"] == True  # noqa: E712
    assert strict.iloc[-1]["elevated_resting_hr"] == False  # noqa: E712


def test_the_steps_fraction_is_configurable(tmp_path: Path) -> None:
    """A day at 60% of the median is unremarkable by default and low at 0.7."""
    frame = days([10000] * 5 + [6000], [60.0] * 6)

    assert detect(tmp_path, frame, *FLAGGING).iloc[-1]["low_activity"] == False  # noqa: E712
    strict = detect(tmp_path, frame, *FLAGGING, "--steps-median-fraction", "0.7")
    assert strict.iloc[-1]["low_activity"] == True  # noqa: E712


def test_a_non_positive_multiplier_is_rejected(tmp_path: Path) -> None:
    src, out = tmp_path / "in.csv", tmp_path / "out.csv"
    days([10000] * 6, [60.0] * 6).to_csv(src, index=False)

    result = subprocess.run(
        [sys.executable, str(SCRIPT), "--input", str(src), "--output", str(out),
         "--hr-sd-multiplier", "0"],
        capture_output=True, text=True, check=False,
    )

    assert result.returncode != 0
    assert "must be positive" in result.stderr


def test_reproduces_the_reference_fixture(tmp_path: Path) -> None:
    """The fixture exercises both flags, not just the undecided path."""
    step_dir = Path(__file__).resolve().parents[2]
    frame = pd.read_csv(step_dir / "reference" / "input.csv")

    result = detect(tmp_path, frame)
    expected = pd.read_csv(step_dir / "reference" / "expected.csv")

    assert result["flagged"].any(), "the fixture must exercise the flags"
    pd.testing.assert_frame_equal(result, expected, check_dtype=False)
