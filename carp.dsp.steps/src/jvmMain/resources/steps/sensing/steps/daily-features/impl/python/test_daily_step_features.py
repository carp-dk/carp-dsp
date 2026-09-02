"""Unit tests for sensing.steps.daily-features.

Run from the step directory:
    python -m pytest impl/python -q
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pandas as pd

SCRIPT = Path(__file__).parent / "daily_step_features.py"


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


def day(steps: list, participant: str = "p01") -> pd.DataFrame:
    return pd.DataFrame({
        "participant_id": [participant] * len(steps),
        "timestamp": [f"2024-01-01 {i:02d}:00:00" for i in range(len(steps))],
        "steps": steps,
    })


def test_produces_one_row_per_participant_day(tmp_path: Path) -> None:
    frame = pd.concat([day([10, 20]), day([30, 40], participant="p02")])

    result = features(tmp_path, frame)

    assert len(result) == 2
    assert set(result["participant_id"]) == {"p01", "p02"}


def test_totals_the_day(tmp_path: Path) -> None:
    assert features(tmp_path, day([100, 200])).iloc[0]["total_steps"] == 300


def test_active_intervals_counts_intervals_at_or_above_the_threshold(tmp_path: Path) -> None:
    """The threshold is inclusive, matching hr-activity-contrast.

    The middle interval sits exactly on the default of 100. The two steps apply
    the same rule to the same signal, so an interval on the boundary has to count
    as active in both or in neither.
    """
    assert features(tmp_path, day([0, 100, 500])).iloc[0]["active_intervals"] == 2


def test_the_active_threshold_is_configurable(tmp_path: Path) -> None:
    frame = day([50, 150, 500])

    assert features(tmp_path, frame)["active_intervals"].iloc[0] == 2
    assert features(tmp_path, frame, "--active-threshold", "400")["active_intervals"].iloc[0] == 1


def test_observed_step_intervals_separates_a_quiet_day_from_an_unrecorded_one(tmp_path: Path) -> None:
    """The two days this step must not conflate.

    A full day spent sitting still and a day the device was barely worn both
    report a low `total_steps` and zero `active_intervals`. `observed_step_intervals`
    is the only field that tells them apart, which is why it is reported rather
    than used internally as a filter.
    """
    quiet = features(tmp_path, day([0] * 24))
    barely_worn = features(tmp_path, day([0, 0, pd.NA] + [pd.NA] * 21))

    assert quiet.iloc[0]["observed_step_intervals"] == 24
    assert barely_worn.iloc[0]["observed_step_intervals"] == 2
    assert quiet.iloc[0]["active_intervals"] == barely_worn.iloc[0]["active_intervals"] == 0


def test_a_day_with_no_step_data_reports_empty_not_zero(tmp_path: Path) -> None:
    """Zero states the participant did not move; the truth is nothing was recorded.

    `sensing.steps.clean` emits empty intervals rather than zeros precisely so
    that difference survives; this is where it would otherwise be discarded.
    Only `observed_step_intervals` can honestly be zero on such a day.
    """
    result = features(tmp_path, day([pd.NA, pd.NA, pd.NA]))

    assert pd.isna(result.iloc[0]["total_steps"])
    assert pd.isna(result.iloc[0]["active_intervals"])
    assert result.iloc[0]["observed_step_intervals"] == 0


def test_a_partly_observed_day_still_totals_what_was_seen(tmp_path: Path) -> None:
    result = features(tmp_path, day([500, pd.NA, 300]))

    assert result.iloc[0]["total_steps"] == 800
    assert result.iloc[0]["active_intervals"] == 2
    assert result.iloc[0]["observed_step_intervals"] == 2


def test_data_off_the_declared_grid_is_rejected(tmp_path: Path) -> None:
    frame = pd.DataFrame({
        "participant_id": ["p01"] * 3,
        "timestamp": ["2024-01-01 08:00:00", "2024-01-01 08:30:00", "2024-01-01 09:00:00"],
        "steps": [200, 200, 200],
    })

    result = run(tmp_path, frame)

    assert result.returncode != 0
    assert "not on a 1h grid" in result.stderr


def test_a_finer_grid_is_accepted_when_declared(tmp_path: Path) -> None:
    frame = pd.DataFrame({
        "participant_id": ["p01"] * 3,
        "timestamp": ["2024-01-01 08:00:00", "2024-01-01 08:30:00", "2024-01-01 09:00:00"],
        "steps": [200, 200, 200],
    })

    assert features(tmp_path, frame, "--interval", "30min").iloc[0]["active_intervals"] == 3


def test_missing_intervals_do_not_break_the_grid_check(tmp_path: Path) -> None:
    """A cleaned series is legitimately missing intervals; the gaps stay multiples."""
    frame = pd.DataFrame({
        "participant_id": ["p01"] * 3,
        "timestamp": ["2024-01-01 08:00:00", "2024-01-01 11:00:00", "2024-01-02 07:00:00"],
        "steps": [200, 200, 200],
    })

    assert len(features(tmp_path, frame)) == 2


def test_reproduces_the_reference_fixture(tmp_path: Path) -> None:
    step_dir = Path(__file__).resolve().parents[2]
    frame = pd.read_csv(step_dir / "reference" / "input.csv")

    result = features(tmp_path, frame)
    expected = pd.read_csv(step_dir / "reference" / "expected.csv")

    pd.testing.assert_frame_equal(result, expected, check_dtype=False, atol=1e-6)
