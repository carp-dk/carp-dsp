"""Unit tests for sensing.heartrate.clean.

Run from the step directory:
    python -m pytest impl/python -q
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).parent))
from clean_heart_rate import clean  # noqa: E402

STEP_DIR = Path(__file__).resolve().parents[2]


def frame(rows: list[tuple[str, str, float]]) -> pd.DataFrame:
    return pd.DataFrame(rows, columns=["participant_id", "timestamp", "heart_rate_bpm"])


def test_masks_values_above_the_plausible_range() -> None:
    # The interval still exists; only the reading is unusable.
    result = clean(frame([("p1", "2026-01-01T00:00:00", 300.0)]))
    assert len(result) == 1
    assert pd.isna(result["heart_rate_bpm"].iloc[0])


def test_masks_values_below_the_plausible_range() -> None:
    result = clean(frame([("p1", "2026-01-01T00:00:00", 12.0)]))
    assert len(result) == 1
    assert pd.isna(result["heart_rate_bpm"].iloc[0])


def test_keeps_values_on_the_range_boundary() -> None:
    rows = [("p1", "2026-01-01T00:00:00", 30.0), ("p1", "2026-01-01T01:00:00", 220.0)]
    assert clean(frame(rows))["heart_rate_bpm"].notna().all()


def test_range_is_configurable() -> None:
    rows = [("p1", "2026-01-01T00:00:00", 45.0)]
    assert pd.isna(clean(frame(rows), min_bpm=50.0)["heart_rate_bpm"].iloc[0])
    assert clean(frame(rows), min_bpm=40.0)["heart_rate_bpm"].iloc[0] == 45.0


def test_duplicate_timestamps_keep_the_first_value() -> None:
    rows = [
        ("p1", "2026-01-01T00:00:00", 62.0),
        ("p1", "2026-01-01T00:00:00", 64.0),
    ]
    result = clean(frame(rows))
    assert len(result) == 1
    assert result["heart_rate_bpm"].iloc[0] == 62.0


def test_a_masked_duplicate_does_not_hide_a_good_reading() -> None:
    # Two readings at the same instant, the first unusable. Keeping the first
    # outright would discard the only real measurement for that interval.
    rows = [
        ("p1", "2026-01-01T00:00:00", 300.0),
        ("p1", "2026-01-01T00:00:00", 62.0),
    ]
    result = clean(frame(rows))
    assert result["heart_rate_bpm"].iloc[0] == 62.0


def test_resamples_onto_a_regular_grid() -> None:
    # Two samples an hour apart with nothing between them produce two buckets.
    rows = [
        ("p1", "2026-01-01T00:00:00", 60.0),
        ("p1", "2026-01-01T01:00:00", 62.0),
    ]
    result = clean(frame(rows))
    assert list(result["timestamp"]) == [
        pd.Timestamp("2026-01-01 00:00:00"),
        pd.Timestamp("2026-01-01 01:00:00"),
    ]


def test_averages_multiple_samples_in_one_bucket() -> None:
    rows = [
        ("p1", "2026-01-01T00:00:00", 60.0),
        ("p1", "2026-01-01T00:30:00", 70.0),
    ]
    result = clean(frame(rows))
    assert len(result) == 1
    assert result["heart_rate_bpm"].iloc[0] == 65.0


def test_interpolates_short_gaps() -> None:
    rows = [
        ("p1", "2026-01-01T00:00:00", 60.0),
        ("p1", "2026-01-01T02:00:00", 62.0),
    ]
    result = clean(frame(rows))
    assert result["heart_rate_bpm"].tolist() == [60.0, 61.0, 62.0]


def test_leaves_a_hole_for_gaps_longer_than_the_limit() -> None:
    # A gap of five empty buckets with limit 3: the first three are filled, the
    # rest stay empty. The intervals are still emitted, so a long outage is
    # visible in the output rather than absent from it.
    rows = [
        ("p1", "2026-01-01T00:00:00", 60.0),
        ("p1", "2026-01-01T06:00:00", 66.0),
    ]
    result = clean(frame(rows), gap_limit=3)
    assert [t.hour for t in result["timestamp"]] == [0, 1, 2, 3, 4, 5, 6]
    assert result["heart_rate_bpm"].isna().tolist() == [
        False, False, False, False, True, True, False
    ]


def test_drop_gaps_removes_the_still_empty_intervals() -> None:
    rows = [
        ("p1", "2026-01-01T00:00:00", 60.0),
        ("p1", "2026-01-01T06:00:00", 66.0),
    ]
    result = clean(frame(rows), gap_limit=3, drop_gaps=True)
    assert [t.hour for t in result["timestamp"]] == [0, 1, 2, 3, 6]
    assert result["heart_rate_bpm"].notna().all()


def test_gap_limit_is_configurable() -> None:
    rows = [
        ("p1", "2026-01-01T00:00:00", 60.0),
        ("p1", "2026-01-01T04:00:00", 64.0),
    ]
    # The grid is the same either way; the limit decides how much of it is filled.
    assert clean(frame(rows), gap_limit=0)["heart_rate_bpm"].notna().sum() == 2
    assert clean(frame(rows), gap_limit=5)["heart_rate_bpm"].notna().sum() == 5


def test_participants_are_cleaned_independently() -> None:
    # p2's gap must not be filled from p1's values.
    rows = [
        ("p1", "2026-01-01T00:00:00", 60.0),
        ("p1", "2026-01-01T01:00:00", 62.0),
        ("p2", "2026-01-01T00:00:00", 80.0),
    ]
    result = clean(frame(rows))
    assert result[result["participant_id"] == "p2"]["heart_rate_bpm"].tolist() == [80.0]


def test_output_is_sorted_by_participant_then_time() -> None:
    rows = [
        ("p2", "2026-01-01T01:00:00", 80.0),
        ("p1", "2026-01-01T00:00:00", 60.0),
    ]
    result = clean(frame(rows))
    assert result["participant_id"].tolist() == ["p1", "p2"]


def test_a_participant_with_no_usable_reading_still_appears() -> None:
    # Previously this participant vanished from the output entirely, which reads
    # as "not in the study" rather than "sensor produced nothing usable".
    result = clean(frame([("p1", "2026-01-01T00:00:00", 300.0)]))
    assert result["participant_id"].tolist() == ["p1"]
    assert result["heart_rate_bpm"].isna().all()
    assert list(result.columns) == ["participant_id", "timestamp", "heart_rate_bpm"]


def test_interval_is_configurable() -> None:
    rows = [
        ("p1", "2026-01-01T00:00:00", 60.0),
        ("p1", "2026-01-01T00:30:00", 70.0),
    ]
    # At 30-minute resolution the two samples stay separate.
    assert len(clean(frame(rows), interval="30min")) == 2


def test_reproduces_the_reference_fixture(tmp_path: Path) -> None:
    """The fixture every implementation of this step must reproduce."""
    output = tmp_path / "out.csv"
    subprocess.run(
        [
            sys.executable,
            str(STEP_DIR / "impl" / "python" / "clean_heart_rate.py"),
            "--input", str(STEP_DIR / "reference" / "input.csv"),
            "--output", str(output),
        ],
        check=True,
    )
    expected = pd.read_csv(STEP_DIR / "reference" / "expected.csv")
    actual = pd.read_csv(output)
    pd.testing.assert_frame_equal(actual, expected, rtol=1e-6)
