#!/usr/bin/env python
"""
Signal Processing Step 4: Generate Report.
Summarises the feature matrix into a pipeline report CSV.
Adds a 'summary' column describing each channel's stats.
Usage: sp_generate_report.py <input_file> <output_file>
"""

import sys
import csv
from pathlib import Path


def generate_report(input_file: str, output_file: str) -> None:
    input_path = Path(input_file)
    output_path = Path(output_file)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with open(input_path, 'r', newline='') as infile:
        rows = list(csv.DictReader(infile))

    report_rows = []
    for row in rows:
        report_rows.append({
            "channel": row["channel"],
            "mean": row["mean"],
            "std": row["std"],
            "min": row["min"],
            "max": row["max"],
            "summary": f"mean={float(row['mean']):.4f} std={float(row['std']):.4f}",
        })

    fieldnames = ["channel", "mean", "std", "min", "max", "summary"]
    with open(output_path, 'w', newline='') as outfile:
        writer = csv.DictWriter(outfile, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(report_rows)

    print(f"Report generated with {len(report_rows)} channel(s) -> {output_file}")


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: sp_generate_report.py <input_file> <output_file>")
        sys.exit(1)
    generate_report(sys.argv[1], sys.argv[2])
