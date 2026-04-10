#!/usr/bin/env python
"""
Step 2: Transform data.
Uppercases all string values (skips row_id).
Usage: step2_transform.py <input_file> <output_file>
"""

import sys
import csv
from pathlib import Path


def transform(input_file: str, output_file: str) -> None:
    input_path = Path(input_file)
    output_path = Path(output_file)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with open(input_path, 'r', newline='') as infile:
        reader = csv.DictReader(infile)
        rows = list(reader)
        fieldnames = list(reader.fieldnames or [])

    for row in rows:
        for key in fieldnames:
            if key != 'row_id':
                row[key] = row[key].upper()

    with open(output_path, 'w', newline='') as outfile:
        writer = csv.DictWriter(outfile, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"Transformed {len(rows)} rows -> {output_file}")


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: step2_transform.py <input_file> <output_file>")
        sys.exit(1)
    transform(sys.argv[1], sys.argv[2])
