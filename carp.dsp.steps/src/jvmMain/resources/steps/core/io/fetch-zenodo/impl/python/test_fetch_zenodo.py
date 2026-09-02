"""Unit tests for core.io.fetch-zenodo.

Run from the step directory:
    python -m pytest impl/python -q

The download is exercised against a `file://` URL rather than a mock, so the real
urlretrieve and zip-extraction paths run without touching the network.
"""
from __future__ import annotations

import subprocess
import sys
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).parent / "fetch_zenodo.py"
STEP_DIR = Path(__file__).resolve().parents[2]
# The member the shipped default arguments name inside the Zenodo archive.
FIXTURE_MEMBER = "Fitabase Data 4.12.16-5.12.16/hourlySteps_merged.csv"


def run(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(SCRIPT), *args], capture_output=True, text=True, check=False
    )


def test_extracts_the_named_member_from_a_zip(tmp_path: Path) -> None:
    archive = tmp_path / "dataset.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("data/wanted.csv", "a,b\n1,2\n")
        zf.writestr("data/ignored.csv", "should,not,appear\n")
    out = tmp_path / "out.csv"

    result = run(
        "--url", archive.as_uri(), "--member", "data/wanted.csv",
        "--output", str(out), "--cache", str(tmp_path / "cache.zip"),
    )

    assert result.returncode == 0, result.stderr
    assert out.read_text() == "a,b\n1,2\n"


def test_copies_the_download_when_no_member_is_named(tmp_path: Path) -> None:
    source = tmp_path / "plain.csv"
    source.write_text("x\n1\n")
    out = tmp_path / "out.csv"

    result = run(
        "--url", source.as_uri(), "--output", str(out), "--cache", str(tmp_path / "cache.bin")
    )

    assert result.returncode == 0, result.stderr
    assert out.read_text() == "x\n1\n"


def test_reuses_the_cache_rather_than_downloading_twice(tmp_path: Path) -> None:
    source = tmp_path / "plain.csv"
    source.write_text("first\n")
    cache = tmp_path / "cache.bin"
    out = tmp_path / "out.csv"

    run("--url", source.as_uri(), "--output", str(out), "--cache", str(cache))
    # Change the source: a second run at the same URL must still serve the cached copy.
    source.write_text("second\n")
    run("--url", source.as_uri(), "--output", str(out), "--cache", str(cache))

    assert out.read_text() == "first\n"


def test_two_urls_sharing_a_cache_path_do_not_collide(tmp_path: Path) -> None:
    """Regression: the cache is keyed by URL, not by the `--cache` path alone.

    Two references sharing a cache path must not serve each other's downloads.
    The library's own workflows did not expose this - they fetch two members of
    the same archive, so one download is correct there - which is why it survived
    review. It fires the first time two references name different URLs.
    """
    first = tmp_path / "first.csv"
    first.write_text("first\n")
    second = tmp_path / "second.csv"
    second.write_text("second\n")
    cache = tmp_path / "cache.bin"

    out_first = tmp_path / "out-first.csv"
    out_second = tmp_path / "out-second.csv"
    run("--url", first.as_uri(), "--output", str(out_first), "--cache", str(cache))
    run("--url", second.as_uri(), "--output", str(out_second), "--cache", str(cache))

    assert out_first.read_text() == "first\n"
    assert out_second.read_text() == "second\n"


def test_the_cache_entry_keeps_the_requested_directory_and_suffix(tmp_path: Path) -> None:
    """`--cache` still decides where and under what name; the URL picks the entry."""
    source = tmp_path / "plain.csv"
    source.write_text("x\n")
    cache_dir = tmp_path / "nested" / "cache"

    run(
        "--url", source.as_uri(),
        "--output", str(tmp_path / "out.csv"),
        "--cache", str(cache_dir / "download.bin"),
    )

    entries = list(cache_dir.iterdir())
    assert len(entries) == 1
    assert entries[0].name.startswith("download-")
    assert entries[0].suffix == ".bin"


def test_fails_when_the_member_is_absent(tmp_path: Path) -> None:
    archive = tmp_path / "dataset.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("data/present.csv", "a\n1\n")

    result = run(
        "--url", archive.as_uri(), "--member", "data/missing.csv",
        "--output", str(tmp_path / "out.csv"), "--cache", str(tmp_path / "c.zip"),
    )

    assert result.returncode != 0


def test_reproduces_the_reference_fixture(tmp_path: Path) -> None:
    """The fixture every implementation of this step must reproduce.

    The archive is served from `reference/input.zip` over a `file://` URL rather
    than fetched from Zenodo: the step's behaviour is download-then-extract, and
    pinning the remote half would make the test assert what a third party
    currently hosts. The member path is the one the shipped default arguments
    name, so what is verified is the extraction the defaults describe.
    """
    out = tmp_path / "out.csv"
    result = run(
        "--url", (STEP_DIR / "reference" / "input.zip").as_uri(),
        "--member", FIXTURE_MEMBER,
        "--output", str(out), "--cache", str(tmp_path / "cache.zip"),
    )

    assert result.returncode == 0, result.stderr
    assert out.read_text(encoding="utf-8") == (
        STEP_DIR / "reference" / "expected.csv"
    ).read_text(encoding="utf-8")
