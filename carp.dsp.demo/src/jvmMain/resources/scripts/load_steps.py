#!/usr/bin/env python3
"""
Load Daily Steps Data Script

Reads diafocus_mock.json and extracts the daily_steps array,
writing it to a JSON file for downstream processing.
"""

import json
import sys


def main():
    """Main execution."""
    if len(sys.argv) != 3:
        print("Usage: load_steps.py <input_json> <output_json>")
        sys.exit(1)

    input_file = sys.argv[1]
    output_file = sys.argv[2]

    try:
        # Load input JSON
        with open(input_file, 'r') as f:
            data = json.load(f)

        # Extract daily_steps array
        if isinstance(data, dict) and "daily_steps" in data:
            steps_data = data["daily_steps"]
        elif isinstance(data, list):
            # Assume it's already a steps array
            steps_data = data
        else:
            print("Error: Expected 'daily_steps' key or array in JSON")
            sys.exit(1)

        # Write output
        with open(output_file, 'w') as f:
            json.dump(steps_data, f, indent=2)

        print(f"Extracted {len(steps_data)} daily step readings to {output_file}")

    except FileNotFoundError:
        print(f"Error: File not found: {input_file}")
        sys.exit(1)
    except json.JSONDecodeError:
        print(f"Error: Invalid JSON in file: {input_file}")
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()

