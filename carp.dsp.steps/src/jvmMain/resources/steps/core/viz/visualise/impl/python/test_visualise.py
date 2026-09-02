"""Unit tests for core.viz.visualise.

Run from the step directory:
    python -m pytest impl/python -q

Rendered pixels are not stable across matplotlib and font versions, so nothing
here compares images. What this step *decides* is which numbers go on which
panel, and that is what is asserted - against `reference/expected.json`, the
step's declared fixture. `reference/expected.png` is kept as an illustration of
the chart, not as something verified.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd
import pytest

SCRIPT = Path(__file__).parent / "visualise.py"
STEP_DIR = SCRIPT.parents[2]
FIXTURE = json.loads((STEP_DIR / "reference" / "expected.json").read_text(encoding="utf-8"))
INVOCATION = FIXTURE["invocation"]


def run(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(SCRIPT), *args], capture_output=True, text=True, check=False
    )


@pytest.fixture
def figure(monkeypatch, tmp_path):
    """Render the reference input and hand back the figure that was drawn."""
    captured = {}
    original = plt.subplots

    def spy(*args, **kwargs):
        fig, axes = original(*args, **kwargs)
        captured["fig"], captured["axes"] = fig, axes
        return fig, axes

    monkeypatch.setattr(plt, "subplots", spy)
    sys.path.insert(0, str(SCRIPT.parent))
    monkeypatch.setattr(
        sys, "argv",
        ["visualise.py", "--input", str(STEP_DIR / "reference" / "input.csv"),
         "--output", str(tmp_path / "out.png"), *INVOCATION],
    )
    import visualise

    visualise.main()
    yield captured["axes"]
    plt.close(captured["fig"])


def test_lays_out_the_declared_panel_grid(figure) -> None:
    assert figure.shape == (FIXTURE["figure"]["rows"], FIXTURE["figure"]["cols"])


@pytest.mark.parametrize(
    "panel,row,col",
    [("top_left", 0, 0), ("top_right", 0, 1), ("bottom_left", 1, 0), ("bottom_right", 1, 1)],
)
def test_each_panel_carries_its_declared_title(figure, panel, row, col) -> None:
    assert figure[row, col].get_title() == FIXTURE["panels"][panel]["title"]


@pytest.mark.parametrize("panel,row,col", [("top_left", 0, 0), ("top_right", 0, 1)])
def test_the_time_series_plot_the_declared_daily_means(figure, panel, row, col) -> None:
    """Catches the realistic failure: plotting the right shape from the wrong column."""
    line = figure[row, col].get_lines()[0]
    assert line.get_ydata().tolist() == pytest.approx(FIXTURE["panels"][panel]["y"])


def test_the_scatter_plots_steps_against_mean_hr(figure) -> None:
    points = figure[1, 1].collections[0].get_offsets()
    expected = FIXTURE["panels"]["bottom_right"]
    assert points[:, 0].tolist() == pytest.approx(expected["x"])
    assert points[:, 1].tolist() == pytest.approx(expected["y"])


def test_the_histogram_covers_the_declared_values(figure) -> None:
    expected = FIXTURE["panels"]["bottom_left"]
    patches = figure[1, 0].patches
    assert len(patches) == expected["bins"]
    lo = min(p.get_x() for p in patches)
    hi = max(p.get_x() + p.get_width() for p in patches)
    assert lo == pytest.approx(min(expected["values"]))
    assert hi == pytest.approx(max(expected["values"]))


def test_writes_a_png(tmp_path: Path) -> None:
    out = tmp_path / "out.png"
    result = run("--input", str(STEP_DIR / "reference" / "input.csv"),
                 "--output", str(out), *INVOCATION)
    assert result.returncode == 0, result.stderr
    assert out.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n"


def test_handles_a_single_day(tmp_path: Path) -> None:
    src = tmp_path / "one.csv"
    pd.DataFrame({
        "participant_id": ["p01"], "date": ["2016-04-12"], "mean_hr": [70.0],
        "resting_hr": [63.0], "peak_hr": [76.0], "total_steps": [2650], "active_intervals": [7],
    }).to_csv(src, index=False)

    result = run("--input", str(src), "--output", str(tmp_path / "out.png"), *INVOCATION)

    assert result.returncode == 0, result.stderr


def test_fails_when_a_required_column_is_missing(tmp_path: Path) -> None:
    src = tmp_path / "bad.csv"
    pd.DataFrame({"participant_id": ["p01"], "date": ["2016-04-12"]}).to_csv(src, index=False)

    result = run("--input", str(src), "--output", str(tmp_path / "out.png"), *INVOCATION)

    assert result.returncode != 0
    assert "not in the table" in result.stderr


def test_refuses_to_draw_an_empty_figure(tmp_path: Path) -> None:
    """No panels named is a mistake, not a request for a blank image."""
    result = run("--input", str(STEP_DIR / "reference" / "input.csv"),
                 "--output", str(tmp_path / "out.png"))

    assert result.returncode != 0
    assert "nothing to plot" in result.stderr


def test_the_grid_sizes_itself_to_the_panel_count(tmp_path: Path) -> None:
    """Three panels fit a 2x2 grid with the spare cell hidden, not left blank."""
    result = run("--input", str(STEP_DIR / "reference" / "input.csv"),
                 "--output", str(tmp_path / "out.png"),
                 "--line", "total_steps", "--histogram", "total_steps,mean_hr")

    assert result.returncode == 0, result.stderr
    assert "3 panel(s)" in result.stdout
