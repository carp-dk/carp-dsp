#!/usr/bin/env python3
"""Cross-OS portability runner (GitHub Actions matrix).

Runs the HR/step pipeline (load -> daily-features -> summarise) using the Python of the
current pixi env, then hashes the outputs with hashlib (no shell tools, so it behaves the
same on Linux, macOS, and Windows). Writes a fingerprint file: one "sha256  basename" line
per output, sorted by name.

Run under the env, e.g.:
  pixi run --manifest-path <ci/pixi.toml> python run_and_hash.py \
    --hr-lib <.../scripts/hr_lib> --workdir <tmp> --out fingerprint-<os>.txt
"""
import argparse
import hashlib
import pathlib
import subprocess
import sys


def sha256(p: pathlib.Path) -> str:
    # Normalise line endings before hashing: Windows writes CRLF in text mode, so raw
    # bytes would differ from Linux/macOS on newline style alone. The portability claim is
    # about the data being identical, not the newline encoding, so compare content.
    data = p.read_bytes().replace(b"\r\n", b"\n")
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--hr-lib", required=True, help="path to scripts/hr_lib")
    ap.add_argument("--workdir", required=True, help="scratch dir for outputs")
    ap.add_argument("--out", required=True, help="fingerprint output file")
    args = ap.parse_args()

    lib = pathlib.Path(args.hr_lib)
    wd = pathlib.Path(args.workdir)
    wd.mkdir(parents=True, exist_ok=True)
    hr, daily, summ = wd / "hr-steps.csv", wd / "daily-features.csv", wd / "summary.json"

    # Use the same interpreter (this pixi env) for every step -> deterministic stack.
    def run(script: str, *a):
        subprocess.run([sys.executable, str(lib / script), *map(str, a)], check=True)

    run("load_hr_steps.py", "--output", hr, "--cache", wd / "fitbit.zip", "--participants", "8")
    run("daily_features.py", "--input", hr, "--output", daily)
    run("summarise.py", "--input", daily, "--output", summ)

    lines = sorted((f"{sha256(f)}  {f.name}" for f in (hr, daily, summ)),
                   key=lambda s: s.split("  ", 1)[1])
    pathlib.Path(args.out).write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
