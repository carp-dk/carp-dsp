#!/usr/bin/env python3
"""
Load HR and Steps Data Script

Reads dbdp_covid_sample.csv, validates required columns, and writes to output.
"""

import csv
import sys


def main():
    """Main execution."""
    if len(sys.argv) != 3:
        print("Usage: load_hr_steps.py <input_csv> <output_csv>")
        sys.exit(1)

    input_file = sys.argv[1]
    output_file = sys.argv[2]

    try:
        # Required columns
        required_columns = {"date", "resting_hr", "steps"}

        # Read input CSV
        with open(input_file, 'r') as infile:
            reader = csv.DictReader(infile)

            # Validate columns
            if not reader.fieldnames:
                print("Error: CSV file is empty")
                sys.exit(1)

            missing = required_columns - set(reader.fieldnames)
            if missing:
                print(f"Error: Missing required columns: {missing}")
                sys.exit(1)

            rows = list(reader)

        # Write output CSV
        with open(output_file, 'w', newline='') as outfile:
            writer = csv.DictWriter(outfile, fieldnames=reader.fieldnames)
            writer.writeheader()
            writer.writerows(rows)

        print(f"Loaded {len(rows)} rows from {input_file} to {output_file}")

    except FileNotFoundError:
        print(f"Error: File not found: {input_file}")
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()

