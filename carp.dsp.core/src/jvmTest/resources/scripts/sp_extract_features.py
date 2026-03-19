#!/usr/bin/env python
"""
Signal Processing Step 3: Extract Features.
Computes mean, std, min, max per channel column.
Output: one row per channel with columns: channel, mean, std, min, max.
Usage: sp_extract_features.py <input_file> <output_file>
"""

import sys
import csv
import statistics
from pathlib import Path

CHANNEL_COLUMNS = ["channel_1", "channel_2", "channel_3"]


def extract_features(input_file: str, output_file: str) -> None:
    input_path = Path(input_file)
    output_path = Path(output_file)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with open(input_path, 'r', newline='') as infile:
        rows = list(csv.DictReader(infile))

    feature_rows = []
    for col in CHANNEL_COLUMNS:
        values = [float(row[col]) for row in rows]
        feature_rows.append({
            "channel": col,
            "mean": str(statistics.mean(values)),
            "std": str(statistics.stdev(values) if len(values) > 1 else 0.0),
            "min": str(min(values)),
            "max": str(max(values)),
        })

    fieldnames = ["channel", "mean", "std", "min", "max"]
    with open(output_path, 'w', newline='') as outfile:
        writer = csv.DictWriter(outfile, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(feature_rows)

    print(f"Extracted features for {len(CHANNEL_COLUMNS)} channels -> {output_file}")


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: sp_extract_features.py <input_file> <output_file>")
        sys.exit(1)
    extract_features(sys.argv[1], sys.argv[2])
