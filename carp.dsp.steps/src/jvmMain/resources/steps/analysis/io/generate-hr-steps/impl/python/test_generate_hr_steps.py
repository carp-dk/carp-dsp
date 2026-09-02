"""Unit tests for analysis.io.generate-hr-steps.

Run from the step directory:
    python -m pytest impl/python -q
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pandas as pd

SCRIPT = Path(__file__).parent / "generate_hr_steps.py"
STEP_DIR = Path(__file__).resolve().parents[2]

# Note this is NOT step.yaml's default `--days 7`: the fixture is one day.
FIXTURE_ARGS = ["--participants", "2", "--days", "1", "--seed", "42"]
COLUMNS = ["participant_id", "timestamp", "heart_rate_bpm", "steps"]


def generate(out: Path, *args: str) -> None:
    result = subprocess.run(
        [sys.executable, str(SCRIPT), "--output", str(out), *args],
        capture_output=True, text=True, check=False,
    )
    assert result.returncode == 0, result.stderr


def test_emits_the_library_schema(tmp_path: Path) -> None:
    out = tmp_path / "hr.csv"
    generate(out, "--participants", "2", "--days", "1")

    frame = pd.read_csv(out)
    assert list(frame.columns) == COLUMNS
    # One row per participant per hour.
    assert len(frame) == 2 * 24
    assert frame["participant_id"].nunique() == 2


def test_is_deterministic_for_a_given_seed(tmp_path: Path) -> None:
    first, second = tmp_path / "a.csv", tmp_path / "b.csv"
    generate(first, "--participants", "2", "--days", "1", "--seed", "7")
    generate(second, "--participants", "2", "--days", "1", "--seed", "7")

    assert first.read_text() == second.read_text()


def test_a_different_seed_gives_different_data(tmp_path: Path) -> None:
    first, second = tmp_path / "a.csv", tmp_path / "b.csv"
    generate(first, "--participants", "1", "--days", "1", "--seed", "1")
    generate(second, "--participants", "1", "--days", "1", "--seed", "2")

    assert first.read_text() != second.read_text()


def test_a_participants_series_does_not_depend_on_the_others(tmp_path: Path) -> None:
    """Each participant is seeded independently, so adding one must not shift the rest."""
    one, three = tmp_path / "one.csv", tmp_path / "three.csv"
    generate(one, "--participants", "1", "--days", "1")
    generate(three, "--participants", "3", "--days", "1")

    first_of_one = pd.read_csv(one)
    first_of_three = pd.read_csv(three).query("participant_id == 'p01'").reset_index(drop=True)
    pd.testing.assert_frame_equal(first_of_one, first_of_three)


def test_heart_rate_stays_physiologically_plausible(tmp_path: Path) -> None:
    out = tmp_path / "hr.csv"
    generate(out, "--participants", "4", "--days", "3")

    frame = pd.read_csv(out)
    assert frame["heart_rate_bpm"].between(30, 220).all()
    assert (frame["steps"] >= 0).all()


def test_reproduces_the_reference_fixture(tmp_path: Path) -> None:
    """The fixture every implementation of this step must reproduce.
    """
    out = tmp_path / "out.csv"
    generate(out, *FIXTURE_ARGS)

    expected = pd.read_csv(STEP_DIR / "reference" / "expected.csv")
    pd.testing.assert_frame_equal(pd.read_csv(out), expected, rtol=1e-6)
