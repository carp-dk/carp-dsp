#!/usr/bin/env python
"""
Signal Processing Step 2: Preprocess.
Z-score normalises each channel column (channel_1, channel_2, channel_3).
Usage: sp_preprocess.py <input_file> <output_file>
"""

import sys
import csv
import statistics
from pathlib import Path

CHANNEL_COLUMNS = ["channel_1", "channel_2", "channel_3"]


def preprocess(input_file: str, output_file: str) -> None:
    input_path = Path(input_file)
    output_path = Path(output_file)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with open(input_path, 'r', newline='') as infile:
        reader = csv.DictReader(infile)
        rows = list(reader)
        fieldnames = list(reader.fieldnames or [])

    for col in CHANNEL_COLUMNS:
        values = [float(row[col]) for row in rows]
        mean = statistics.mean(values)
        stdev = statistics.stdev(values) if len(values) > 1 else 1.0
        for row in rows:
            row[col] = str((float(row[col]) - mean) / stdev) if stdev != 0 else "0.0"

    with open(output_path, 'w', newline='') as outfile:
        writer = csv.DictWriter(outfile, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"Preprocessed {len(rows)} rows -> {output_file}")


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: sp_preprocess.py <input_file> <output_file>")
        sys.exit(1)
    preprocess(sys.argv[1], sys.argv[2])
