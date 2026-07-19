#!/usr/bin/env python3
"""Locate cross-OS numeric divergence at the column level.

Given per-OS copies of a pipeline output (downloaded to <base>/outputs-<os>/<file>),
compares each column against the reference OS and reports, per column, whether it is
identical and the largest absolute difference. This shows that divergence is confined to
a few floating-point columns and bounded in magnitude (not a logic difference).

  python diff_outputs.py <base> daily-features.csv ubuntu-latest macos-latest windows-latest [--out DIR]
"""
import argparse
import pathlib

import numpy as np
import pandas as pd


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("base", help="dir containing outputs-<os>/ subdirs")
    ap.add_argument("file", help="output filename to diff, e.g. daily-features.csv")
    ap.add_argument("oses", nargs="+")
    ap.add_argument("--out", default=".")
    args = ap.parse_args()

    base = pathlib.Path(args.base)
    frames = {o: pd.read_csv(base / f"outputs-{o}" / args.file) for o in args.oses}
    ref = args.oses[0]
    rdf = frames[ref]

    lines = [f"Column-level divergence in {args.file} (reference OS: {ref})", ""]
    lines.append(f"{'column':18}{'identical':11}{'max_abs_diff':16}worst_os")
    for c in rdf.columns:
        if pd.api.types.is_numeric_dtype(rdf[c]):
            worst, worst_os = 0.0, "-"
            for o in args.oses[1:]:
                diff = frames[o][c].to_numpy(dtype=float) - rdf[c].to_numpy(dtype=float)
                m = float(np.nanmax(np.abs(diff))) if diff.size else 0.0
                if m > worst:
                    worst, worst_os = m, o
            ident = worst == 0.0
            lines.append(f"{c:18}{str(ident):11}{worst:<16.3e}{worst_os if worst > 0 else '-'}")
        else:
            ident = all(frames[o][c].equals(rdf[c]) for o in args.oses)
            lines.append(f"{c:18}{str(ident):11}{'-':16}{'(non-numeric)'}")

    txt = "\n".join(lines) + "\n"
    print(txt)
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "divergence-detail.txt").write_text(txt)


if __name__ == "__main__":
    main()
