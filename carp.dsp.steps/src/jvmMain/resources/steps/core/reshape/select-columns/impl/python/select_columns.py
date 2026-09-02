#!/usr/bin/env python3
"""Library step: select-columns.

Rename, keep and reorder the columns of a table. Generic and domain-agnostic:
every column name is an argument, so the step carries no dataset knowledge.

Deliberately implemented on the standard-library `csv` module rather than pandas.
A projection should move values, not interpret them: pandas would infer a dtype
per column and write back its own rendering, which silently turns `62` into
`62.0` as soon as a column has one missing value, and can reformat timestamps and
long floats. Reading and writing fields as text keeps every value byte-identical
to the input, which is the whole contract of this step.

Inputs:
    --input     CSV table with a header row
Outputs:
    --output    CSV table with the chosen columns, in the chosen order
Optional:
    --rename    old:new,... applied before selection
    --columns   comma-separated columns to keep, in output order (default: all)
"""
from __future__ import annotations

import argparse
import csv
import sys


def parse_pairs(spec: str) -> dict[str, str]:
    """Parse an `old:new,old:new` mapping. Empty spec means no renames."""
    if not spec:
        return {}
    pairs = {}
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        if ":" not in part:
            raise SystemExit(f"[select-columns] bad --rename entry '{part}', expected 'old:new'")
        old, new = part.split(":", 1)
        pairs[old.strip()] = new.strip()
    return pairs


def parse_list(spec: str) -> list[str]:
    """Parse a comma-separated column list. Empty spec means an empty list."""
    return [c.strip() for c in spec.split(",") if c.strip()] if spec else []


def resolve(header: list[str], rename: dict[str, str], columns: list[str]) -> list[int]:
    """Return the input column indexes to emit, in output order.

    Renames are applied first, so `--columns` names the columns as they will
    appear in the output. That ordering matches `join-tables`, where `--on` also
    names keys post-rename, and it means a caller thinks only in target names.

    Every failure is raised and ends the step.
    """
    missing_sources = [old for old in rename if old not in header]
    if missing_sources:
        raise SystemExit(
            f"[select-columns] --rename refers to column(s) not in the input: "
            f"{', '.join(missing_sources)}. Available: {', '.join(header)}"
        )

    renamed = [rename.get(name, name) for name in header]

    duplicates = {name for name in renamed if renamed.count(name) > 1}
    if duplicates:
        raise SystemExit(
            f"[select-columns] renaming produces duplicate column name(s): "
            f"{', '.join(sorted(duplicates))}"
        )

    if not columns:
        return list(range(len(renamed)))

    unknown = [name for name in columns if name not in renamed]
    if unknown:
        raise SystemExit(
            f"[select-columns] --columns refers to column(s) not in the table: "
            f"{', '.join(unknown)}. Available after renaming: {', '.join(renamed)}"
        )
    return [renamed.index(name) for name in columns]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="CSV table with a header row")
    parser.add_argument("--output", required=True, help="output CSV path")
    parser.add_argument("--rename", default="", help="old:new,... applied before selection")
    parser.add_argument("--columns", default="", help="columns to keep, in output order")
    args = parser.parse_args()

    rename = parse_pairs(args.rename)
    columns = parse_list(args.columns)

    with open(args.input, newline="", encoding="utf-8") as handle:
        reader = csv.reader(handle)
        try:
            header = next(reader)
        except StopIteration:
            raise SystemExit(f"[select-columns] {args.input} is empty - no header row")

        keep = resolve(header, rename, columns)
        out_header = [rename.get(header[i], header[i]) for i in keep]

        rows = 0
        with open(args.output, "w", newline="", encoding="utf-8") as out:
            writer = csv.writer(out)
            writer.writerow(out_header)
            for row in reader:
                # A short row keeps its missing trailing fields empty rather than
                # failing: a ragged input is the source's problem, not a reason to
                # lose the rows that are fine.
                writer.writerow([row[i] if i < len(row) else "" for i in keep])
                rows += 1

    print(
        f"[select-columns] {len(header)} -> {len(out_header)} columns, "
        f"{rows} rows -> {args.output}",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
