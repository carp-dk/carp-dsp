"""Unit tests for core.reshape.select-columns.

Run from the step directory:
    python -m pytest impl/python -q
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).parent / "select_columns.py"
STEP_DIR = SCRIPT.parents[2]

# The invocation the README documents and the reference fixture is built from.
FIXTURE_ARGS = [
    "--rename", "Id:participant_id,ActivityHour:timestamp,StepTotal:steps",
    "--columns", "participant_id,timestamp,steps",
]


def run(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(SCRIPT), *args], capture_output=True, text=True, check=False
    )


def write(path: Path, text: str) -> Path:
    path.write_text(text, encoding="utf-8")
    return path


def test_reproduces_the_reference_fixture(tmp_path: Path) -> None:
    """The documented arguments must turn the fixture input into the fixture output.

    The conformance gate only checks that a fixture exists, so without this the
    published `expected.csv` is an assertion nothing tests. Asserting it here
    keeps the fixture honest as the step changes.
    """
    out = tmp_path / "out.csv"
    result = run(
        "--input", str(STEP_DIR / "reference" / "input.csv"),
        "--output", str(out), *FIXTURE_ARGS,
    )

    assert result.returncode == 0, result.stderr
    assert out.read_text(encoding="utf-8") == (
        STEP_DIR / "reference" / "expected.csv"
    ).read_text(encoding="utf-8")


def test_renames_keeps_and_reorders(tmp_path: Path) -> None:
    src = write(tmp_path / "in.csv", "a,b,c\n1,2,3\n")
    out = tmp_path / "out.csv"

    result = run(
        "--input", str(src), "--output", str(out),
        "--rename", "a:alpha", "--columns", "c,alpha",
    )

    assert result.returncode == 0, result.stderr
    assert out.read_text() == "c,alpha\n3,1\n"


def test_keeps_every_column_when_none_are_named(tmp_path: Path) -> None:
    src = write(tmp_path / "in.csv", "a,b\n1,2\n")
    out = tmp_path / "out.csv"

    result = run("--input", str(src), "--output", str(out), "--rename", "b:beta")

    assert result.returncode == 0, result.stderr
    assert out.read_text() == "a,beta\n1,2\n"


def test_values_are_copied_verbatim(tmp_path: Path) -> None:
    """A projection must not reinterpret values.

    A dtype-inferring implementation turns `62` into `62.0` once a column has a
    missing value, and can reformat long floats and timestamps. Empty stays
    empty, and every other field is byte-identical.
    """
    src = write(
        tmp_path / "in.csv",
        "n,when,precise\n62,2016-04-12 08:00:00,60.666666666666664\n,2016-04-12 09:00:00,0\n",
    )
    out = tmp_path / "out.csv"

    result = run("--input", str(src), "--output", str(out), "--columns", "n,when,precise")

    assert result.returncode == 0, result.stderr
    assert out.read_text() == (
        "n,when,precise\n62,2016-04-12 08:00:00,60.666666666666664\n,2016-04-12 09:00:00,0\n"
    )


def test_fails_on_an_unknown_column(tmp_path: Path) -> None:
    src = write(tmp_path / "in.csv", "a,b\n1,2\n")

    result = run(
        "--input", str(src), "--output", str(tmp_path / "out.csv"), "--columns", "a,typo",
    )

    assert result.returncode != 0
    assert "typo" in result.stderr


def test_fails_on_an_unknown_rename_source(tmp_path: Path) -> None:
    src = write(tmp_path / "in.csv", "a,b\n1,2\n")

    result = run(
        "--input", str(src), "--output", str(tmp_path / "out.csv"), "--rename", "nope:x",
    )

    assert result.returncode != 0
    assert "nope" in result.stderr


def test_fails_when_renaming_collides(tmp_path: Path) -> None:
    src = write(tmp_path / "in.csv", "a,b\n1,2\n")

    result = run(
        "--input", str(src), "--output", str(tmp_path / "out.csv"), "--rename", "a:b",
    )

    assert result.returncode != 0
    assert "duplicate" in result.stderr


def test_a_short_row_keeps_its_missing_fields_empty(tmp_path: Path) -> None:
    src = write(tmp_path / "in.csv", "a,b,c\n1,2,3\n4,5\n")
    out = tmp_path / "out.csv"

    result = run("--input", str(src), "--output", str(out), "--columns", "a,c")

    assert result.returncode == 0, result.stderr
    assert out.read_text() == "a,c\n1,3\n4,\n"
