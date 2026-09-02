"""Unit tests for core.reshape.join-tables.

Run from the step directory:
    python -m pytest impl/python -q
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pandas as pd

SCRIPT = Path(__file__).parent / "join_tables.py"
STEP_DIR = Path(__file__).resolve().parents[2]

FIXTURE_ARGS = [
    "--left-rename", "Id:participant_id,ActivityHour:timestamp,StepTotal:steps",
    "--right-rename", "Id:participant_id,Time:timestamp,Value:heart_rate_bpm",
    "--right-resample", "timestamp:1h",
    "--right-agg", "heart_rate_bpm:mean",
    "--on", "participant_id,timestamp",
    "--how", "inner",
]


def join(tmp: Path, left: pd.DataFrame, right: pd.DataFrame, *args: str) -> pd.DataFrame:
    lp, rp, out = tmp / "left.csv", tmp / "right.csv", tmp / "out.csv"
    left.to_csv(lp, index=False)
    right.to_csv(rp, index=False)
    result = subprocess.run(
        [sys.executable, str(SCRIPT), "--left", str(lp), "--right", str(rp),
         "--output", str(out), *args],
        capture_output=True, text=True, check=False,
    )
    assert result.returncode == 0, result.stderr
    return pd.read_csv(out)


def test_inner_join_keeps_only_matching_keys(tmp_path: Path) -> None:
    left = pd.DataFrame({"id": [1, 2, 3], "a": ["x", "y", "z"]})
    right = pd.DataFrame({"id": [2, 3, 4], "b": ["p", "q", "r"]})

    joined = join(tmp_path, left, right, "--on", "id", "--how", "inner")

    assert sorted(joined["id"]) == [2, 3]


def test_left_join_keeps_unmatched_rows(tmp_path: Path) -> None:
    left = pd.DataFrame({"id": [1, 2], "a": ["x", "y"]})
    right = pd.DataFrame({"id": [2], "b": ["p"]})

    joined = join(tmp_path, left, right, "--on", "id", "--how", "left")

    assert sorted(joined["id"]) == [1, 2]
    assert joined.loc[joined["id"] == 1, "b"].isna().all()


def test_renames_columns_before_joining(tmp_path: Path) -> None:
    left = pd.DataFrame({"Id": [1], "StepTotal": [100]})
    right = pd.DataFrame({"Id": [1], "Value": [60]})

    joined = join(
        tmp_path, left, right,
        "--left-rename", "Id:participant_id,StepTotal:steps",
        "--right-rename", "Id:participant_id,Value:heart_rate_bpm",
        "--on", "participant_id",
    )

    assert list(joined.columns) == ["participant_id", "steps", "heart_rate_bpm"]


def test_resamples_one_side_onto_a_coarser_grid(tmp_path: Path) -> None:
    """A per-minute series must be reducible to the hourly grid it is joined against."""
    left = pd.DataFrame({
        "participant_id": [1, 1],
        "timestamp": ["2024-01-01 08:00:00", "2024-01-01 09:00:00"],
        "steps": [100, 200],
    })
    right = pd.DataFrame({
        "participant_id": [1, 1, 1, 1],
        "timestamp": [
            "2024-01-01 08:10:00", "2024-01-01 08:50:00",
            "2024-01-01 09:10:00", "2024-01-01 09:50:00",
        ],
        "heart_rate_bpm": [60, 70, 80, 90],
    })

    joined = join(
        tmp_path, left, right,
        "--right-resample", "timestamp:1h", "--right-agg", "heart_rate_bpm:mean",
        "--on", "participant_id,timestamp",
    )

    assert len(joined) == 2
    assert joined.sort_values("timestamp")["heart_rate_bpm"].tolist() == [65.0, 85.0]


def test_reconciles_key_dtypes_across_sides(tmp_path: Path) -> None:
    """Resampling makes one side's key a datetime; the join must still match."""
    left = pd.DataFrame({"participant_id": [1], "timestamp": ["2024-01-01 08:00:00"], "steps": [10]})
    right = pd.DataFrame({
        "participant_id": [1], "timestamp": ["2024-01-01 08:30:00"], "heart_rate_bpm": [60],
    })

    joined = join(
        tmp_path, left, right,
        "--right-resample", "timestamp:1h", "--right-agg", "heart_rate_bpm:mean",
        "--on", "participant_id,timestamp",
    )

    assert len(joined) == 1


def test_reproduces_the_reference_fixture(tmp_path: Path) -> None:
    """The fixture every implementation of this step must reproduce.
    """
    out = tmp_path / "out.csv"
    result = subprocess.run(
        [sys.executable, str(SCRIPT),
         "--left", str(STEP_DIR / "reference" / "left.csv"),
         "--right", str(STEP_DIR / "reference" / "right.csv"),
         "--output", str(out), *FIXTURE_ARGS],
        capture_output=True, text=True, check=False,
    )

    assert result.returncode == 0, result.stderr
    expected = pd.read_csv(STEP_DIR / "reference" / "expected.csv")
    pd.testing.assert_frame_equal(pd.read_csv(out), expected, rtol=1e-6)
