"""Unit tests for core.stats.summarise.

Run from the step directory:
    python -m pytest impl/python -q
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).parent))
from summarise import summarise  # noqa: E402

STEP_DIR = Path(__file__).resolve().parents[2]


@pytest.fixture
def frame() -> pd.DataFrame:
    return pd.DataFrame(
        {
            "participant_id": ["p01", "p01", "p02"],
            "steps": [100, 200, 300],
            "label": ["a", "b", "c"],
        }
    )


def test_reports_row_count(frame: pd.DataFrame) -> None:
    assert summarise(frame)["n_rows"] == 3


def test_ignores_non_numeric_columns(frame: pd.DataFrame) -> None:
    assert set(summarise(frame)["columns"]) == {"steps"}


def test_computes_descriptive_statistics(frame: pd.DataFrame) -> None:
    stats = summarise(frame)["columns"]["steps"]
    assert stats["n"] == 3
    assert stats["mean"] == 200.0
    assert stats["median"] == 200.0
    assert stats["min"] == 100.0
    assert stats["max"] == 300.0
    assert stats["sd"] == pytest.approx(100.0)


def test_missing_values_excluded_from_column_count_but_not_row_count() -> None:
    result = summarise(pd.DataFrame({"x": [1.0, None, 3.0]}))
    assert result["n_rows"] == 3
    assert result["columns"]["x"]["n"] == 2
    assert result["columns"]["x"]["mean"] == 2.0


def test_standard_deviation_undefined_for_single_value() -> None:
    # ddof=1 is undefined for n=1; report None rather than NaN, which has no
    # JSON representation.
    assert summarise(pd.DataFrame({"x": [42]}))["columns"]["x"]["sd"] is None


def test_column_subset_selects_named_numeric_columns() -> None:
    frame = pd.DataFrame({"a": [1, 2], "b": [3, 4]})
    assert set(summarise(frame, columns=["a"])["columns"]) == {"a"}


def test_column_subset_ignores_unknown_and_non_numeric_names(frame: pd.DataFrame) -> None:
    # Asking for a column that is absent or non-numeric yields no statistics for
    # it rather than an error, so a caller cannot request something undefined.
    assert summarise(frame, columns=["label", "nope"])["columns"] == {}


def test_columns_are_emitted_in_sorted_order() -> None:
    frame = pd.DataFrame({"z": [1], "a": [2], "m": [3]})
    assert list(summarise(frame)["columns"]) == ["a", "m", "z"]


def test_empty_table_yields_no_columns_and_zero_rows() -> None:
    result = summarise(pd.DataFrame({"x": pd.Series(dtype="float64")}))
    assert result["n_rows"] == 0
    assert result["columns"]["x"]["n"] == 0


def test_reproduces_the_reference_fixture(tmp_path: Path) -> None:
    """The fixture every implementation of this step must reproduce."""
    output = tmp_path / "out.json"
    subprocess.run(
        [
            sys.executable,
            str(STEP_DIR / "impl" / "python" / "summarise.py"),
            "--input", str(STEP_DIR / "reference" / "input.csv"),
            "--output", str(output),
        ],
        check=True,
    )
    expected = json.loads((STEP_DIR / "reference" / "expected.json").read_text())
    assert json.loads(output.read_text()) == expected
