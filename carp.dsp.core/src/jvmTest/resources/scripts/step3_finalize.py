#!/usr/bin/env python
"""
Step 3: Finalize data.
Appends a 'status' column set to 'done'.
Usage: step3_finalize.py <input_file> <output_file>
"""

import sys
import csv
from pathlib import Path


def finalize(input_file: str, output_file: str) -> None:
    input_path = Path(input_file)
    output_path = Path(output_file)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with open(input_path, 'r', newline='') as infile:
        reader = csv.DictReader(infile)
        rows = list(reader)
        fieldnames = list(reader.fieldnames or [])

    fieldnames = fieldnames + ['status']
    for row in rows:
        row['status'] = 'done'

    with open(output_path, 'w', newline='') as outfile:
        writer = csv.DictWriter(outfile, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"Finalized {len(rows)} rows -> {output_file}")


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: step3_finalize.py <input_file> <output_file>")
        sys.exit(1)
    finalize(sys.argv[1], sys.argv[2])
