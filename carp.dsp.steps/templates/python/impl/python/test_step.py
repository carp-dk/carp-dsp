"""Unit tests for {{ID}}.

Run from the step directory:
    python -m pytest impl/python -q
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
from {{STEP_MODULE}} import compute  # noqa: E402

STEP_DIR = Path(__file__).resolve().parents[2]


def test_computes_expected_output() -> None:
    # Replace with real assertions on `compute`.
    raise NotImplementedError


def test_reproduces_the_reference_fixture(tmp_path: Path) -> None:
    """The fixture every implementation of this step must reproduce."""
    output = tmp_path / "out.json"
    subprocess.run(
        [
            sys.executable,
            str(STEP_DIR / "impl" / "python" / "{{STEP}}.py"),
            "--input", str(STEP_DIR / "reference" / "input.csv"),
            "--output", str(output),
        ],
        check=True,
    )
    expected = json.loads((STEP_DIR / "reference" / "expected.json").read_text())
    assert json.loads(output.read_text()) == expected
