#!/usr/bin/env python
"""
Signal Processing Step 1: Validate.
Checks required columns exist and all channel values are numeric.
Adds a 'valid' column to the output.
Usage: sp_validate.py <input_file> <output_file>
"""

import sys
import csv
from pathlib import Path

REQUIRED_COLUMNS = {"timestamp", "channel_1", "channel_2", "channel_3"}


def validate(input_file: str, output_file: str) -> None:
    input_path = Path(input_file)
    output_path = Path(output_file)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with open(input_path, 'r', newline='') as infile:
        reader = csv.DictReader(infile)
        rows = list(reader)
        fieldnames = list(reader.fieldnames or [])

    missing = REQUIRED_COLUMNS - set(fieldnames)
    if missing:
        print(f"Error: missing required columns: {missing}", file=sys.stderr)
        sys.exit(1)

    for row in rows:
        for col in REQUIRED_COLUMNS - {"timestamp"}:
            try:
                float(row[col])
            except ValueError:
                print(f"Error: non-numeric value in {col}: {row[col]}", file=sys.stderr)
                sys.exit(1)
        row["valid"] = "true"

    fieldnames = fieldnames + ["valid"]
    with open(output_path, 'w', newline='') as outfile:
        writer = csv.DictWriter(outfile, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"Validated {len(rows)} rows -> {output_file}")


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: sp_validate.py <input_file> <output_file>")
        sys.exit(1)
    validate(sys.argv[1], sys.argv[2])
