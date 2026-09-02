"""Unit tests for sensing.steps.clean.

Run from the step directory:
    python -m pytest impl/python -q
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
from clean_steps import clean  # noqa: E402

SCRIPT = Path(__file__).parent / "clean_steps.py"
STEP_DIR = Path(__file__).resolve().parents[2]

def hourly(steps: list, participant: str = "p01", start: int = 8) -> pd.DataFrame:
    return pd.DataFrame({
        "participant_id": [participant] * len(steps),
        "timestamp": [f"2024-01-01 {start + i:02d}:00:00" for i in range(len(steps))],
        "steps": steps,
    })


def test_masks_negative_counts_rather_than_clipping_them() -> None:
    """A negative count means the reading is wrong, not that no steps were taken."""
    result = clean(hourly([100, -50, 200]))

    assert pd.isna(result.iloc[1]["steps"])
    assert result.iloc[0]["steps"] == 100


def test_leaves_a_gap_empty_rather_than_filling_it_with_zero() -> None:
    """No record is not no steps; zero-filling would destroy the non-wear signal."""
    frame = hourly([100, 300])
    frame.loc[1, "timestamp"] = "2024-01-01 10:00:00"

    result = clean(frame)

    assert len(result) == 3
    assert pd.isna(result.iloc[1]["steps"])


def test_applies_no_ceiling_unless_one_is_given() -> None:
    result = clean(hourly([100, 50000]))

    assert result.iloc[1]["steps"] == 50000


def test_masks_counts_above_a_given_ceiling() -> None:
    result = clean(hourly([100, 50000]), max_steps=9000)

    assert pd.isna(result.iloc[1]["steps"])


def test_removes_duplicate_timestamps_keeping_the_first() -> None:
    frame = pd.concat([hourly([100, 200]), hourly([999])])

    result = clean(frame)

    assert result["timestamp"].is_unique
    assert result.iloc[0]["steps"] == 100


def test_keeps_participants_on_their_own_grids() -> None:
    frame = pd.concat([hourly([100, 200]), hourly([300, 400], participant="p02")])

    result = clean(frame)

    assert set(result["participant_id"]) == {"p01", "p02"}
    assert result.query("participant_id == 'p02'")["steps"].sum() == 700


def test_steps_stay_integral_despite_missing_values() -> None:
    """A nullable integer keeps counts as counts; NaN would force them to float."""
    result = clean(hourly([100, -1, 200]))

    assert str(result["steps"].dtype) == "Int64"


def test_preserves_the_schema() -> None:
    result = clean(hourly([100, 200]))

    assert list(result.columns) == ["participant_id", "timestamp", "steps"]


def test_readings_within_an_interval_are_summed() -> None:
    """Sub-interval readings must be aggregated, not sampled.

    This step used to place readings on the grid by exact-timestamp lookup, so
    anything not landing precisely on an interval boundary was silently dropped:
    three minute-level readings became one value, and the rest disappeared.
    """
    frame = pd.DataFrame({
        "participant_id": ["p01"] * 3,
        "timestamp": [
            "2024-01-01 08:10:00", "2024-01-01 08:40:00", "2024-01-01 09:20:00",
        ],
        "steps": [100, 150, 200],
    })

    result = clean(frame)

    assert result["steps"].tolist() == [250, 200]


def test_the_grid_is_aligned_to_interval_boundaries() -> None:
    """Timestamps land on the interval, not on the first reading's offset.

    An unaligned grid would not join with another signal cleaned to the same
    interval, which is how these steps are composed.
    """
    frame = pd.DataFrame({
        "participant_id": ["p01", "p01"],
        "timestamp": ["2024-01-01 08:10:00", "2024-01-01 09:20:00"],
        "steps": [100, 200],
    })

    result = clean(frame)

    assert [t.strftime("%H:%M") for t in result["timestamp"]] == ["08:00", "09:00"]


def test_an_interval_with_no_reading_is_empty_not_zero() -> None:
    """Summing an all-missing interval to 0 would assert absence of movement."""
    frame = pd.DataFrame({
        "participant_id": ["p01", "p01"],
        "timestamp": ["2024-01-01 08:00:00", "2024-01-01 10:00:00"],
        "steps": [100, 200],
    })

    result = clean(frame)

    assert len(result) == 3
    assert pd.isna(result.iloc[1]["steps"])


def test_drop_gaps_removes_the_empty_intervals() -> None:
    frame = pd.DataFrame({
        "participant_id": ["p01", "p01"],
        "timestamp": ["2024-01-01 08:00:00", "2024-01-01 10:00:00"],
        "steps": [100, 200],
    })

    result = clean(frame, drop_gaps=True)

    assert result["steps"].tolist() == [100, 200]


def test_a_masked_duplicate_does_not_hide_a_good_reading() -> None:
    frame = pd.DataFrame({
        "participant_id": ["p01", "p01"],
        "timestamp": ["2024-01-01 08:00:00", "2024-01-01 08:00:00"],
        "steps": [-50, 100],
    })

    result = clean(frame)

    assert result.iloc[0]["steps"] == 100


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
