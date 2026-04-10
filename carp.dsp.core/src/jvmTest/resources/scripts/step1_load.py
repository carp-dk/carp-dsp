#!/usr/bin/env python
"""
Step 1: Load data.
Reads input CSV and adds a row_id column.
Usage: step1_load.py <input_file> <output_file>
"""

import sys
import csv
from pathlib import Path


def load(input_file: str, output_file: str) -> None:
    input_path = Path(input_file)
    output_path = Path(output_file)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with open(input_path, 'r', newline='') as infile:
        reader = csv.DictReader(infile)
        rows = list(reader)
        fieldnames = list(reader.fieldnames or [])

    fieldnames = ['row_id'] + fieldnames
    for i, row in enumerate(rows):
        row['row_id'] = str(i + 1)

    with open(output_path, 'w', newline='') as outfile:
        writer = csv.DictWriter(outfile, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"Loaded {len(rows)} rows -> {output_file}")


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: step1_load.py <input_file> <output_file>")
        sys.exit(1)
    load(sys.argv[1], sys.argv[2])
