#!/usr/bin/env python
"""
Simple CSV processing script for testing.

Usage:
    python simple_process.py <input_file> <output_file>

Reads a CSV, adds a timestamp column, writes output.
"""

import sys
import csv
from datetime import datetime

def process_csv(input_file, output_file):
    """Read input CSV and write processed output."""
    try:
        # Read input
        with open(input_file, 'r') as f:
            reader = csv.DictReader(f)
            if reader.fieldnames is None:
                print(f"Warning: Input file {input_file} is empty or has no headers")
                fieldnames = ["col1", "col2", "col3"]
                rows = []
            else:
                fieldnames = list(reader.fieldnames)
                rows = list(reader)

        # Add timestamp column
        fieldnames.append("processed_at")
        timestamp = datetime.now().isoformat()

        for row in rows:
            row["processed_at"] = timestamp

        # Write output
        with open(output_file, 'w', newline='') as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)

        print(f"Successfully processed {input_file} to {output_file}")
        print(f"Rows processed: {len(rows)}")
        return 0

    except FileNotFoundError as e:
        print(f"Error: File not found - {e}", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    print("Python start up")
    if len(sys.argv) != 3:
        print("Usage: simple_process.py <input_file> <output_file>")
        sys.exit(1)

    input_file = sys.argv[1]
    output_file = sys.argv[2]

    exit_code = process_csv(input_file, output_file)
    sys.exit(exit_code)