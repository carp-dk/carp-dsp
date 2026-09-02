"""Unit tests for analysis.activity.hr-activity-contrast.

Run from the step directory:
    python -m pytest impl/python -q
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
from hr_activity_contrast import hr_activity_contrast  # noqa: E402

SCRIPT = Path(__file__).parent / "hr_activity_contrast.py"
STEP_DIR = Path(__file__).resolve().parents[2]


def intervals(hr: list, steps: list, participant: str = "p01", day: str = "2024-01-01"):
    return pd.DataFrame({
        "participant_id": [participant] * len(hr),
        "timestamp": [f"{day} {i:02d}:00:00" for i in range(len(hr))],
        "heart_rate_bpm": hr,
        "steps": steps,
    })


def test_contrasts_active_against_inactive_heart_rate() -> None:
    result = hr_activity_contrast(intervals([100, 100, 60, 60], [500, 500, 0, 0]))

    row = result.iloc[0]
    assert row["active_hr"] == 100.0
    assert row["inactive_hr"] == 60.0
    assert row["hr_activity_gap"] == 40.0
    assert row["active_intervals"] == 2
    assert row["inactive_intervals"] == 2


def test_threshold_decides_which_intervals_are_active() -> None:
    frame = intervals([100, 60], [150, 0])

    strict = hr_activity_contrast(frame, active_threshold=200)
    lenient = hr_activity_contrast(frame, active_threshold=100)

    assert strict.iloc[0]["active_intervals"] == 0
    assert lenient.iloc[0]["active_intervals"] == 1


def test_gap_is_empty_when_a_day_has_no_active_intervals() -> None:
    """No activity is no evidence about the contrast, which is not a contrast of zero."""
    result = hr_activity_contrast(intervals([60, 62], [0, 0]))

    assert pd.isna(result.iloc[0]["hr_activity_gap"])
    assert pd.isna(result.iloc[0]["active_hr"])


def test_gap_is_empty_when_a_day_has_no_inactive_intervals() -> None:
    result = hr_activity_contrast(intervals([100, 102], [500, 500]))

    assert pd.isna(result.iloc[0]["hr_activity_gap"])


def test_masked_readings_join_neither_group() -> None:
    """An upstream step that masks a bad value must not have it counted as inactive."""
    frame = intervals([100, 60, None], [500, 0, 0])

    result = hr_activity_contrast(frame)

    row = result.iloc[0]
    assert row["active_intervals"] == 1
    assert row["inactive_intervals"] == 1


def test_a_missing_step_count_also_excludes_the_interval() -> None:
    frame = intervals([100, 60, 70], [500, 0, None])

    result = hr_activity_contrast(frame)

    assert result.iloc[0]["inactive_intervals"] == 1


def test_reports_one_row_per_participant_day() -> None:
    a = intervals([100, 60], [500, 0])
    b = intervals([100, 60], [500, 0], day="2024-01-02")
    other = intervals([90, 70], [500, 0], participant="p02")

    result = hr_activity_contrast(pd.concat([a, b, other]))

    assert len(result) == 3
    assert list(result.columns) == [
        "participant_id", "date", "active_hr", "inactive_hr", "hr_activity_gap",
        "active_intervals", "inactive_intervals",
    ]


def test_reproduces_the_reference_fixture(tmp_path: Path) -> None:
    """The fixture every implementation of this step must reproduce.
    """
    out = tmp_path / "out.csv"
    result = subprocess.run(
        [sys.executable, str(SCRIPT),
         "--input", str(STEP_DIR / "reference" / "input.csv"),
         "--output", str(out)],
        capture_output=True, text=True, check=False,
    )

    assert result.returncode == 0, result.stderr
    expected = pd.read_csv(STEP_DIR / "reference" / "expected.csv")
    pd.testing.assert_frame_equal(pd.read_csv(out), expected, rtol=1e-6)
