#!/usr/bin/env python3
"""
Load Blood Glucose Data Script

Reads diafocus_mock.json and extracts the blood_glucose array,
writing it to a JSON file for downstream processing.
"""

import json
import sys


def main():
    """Main execution."""
    if len(sys.argv) != 3:
        print("Usage: load_bgm.py <input_json> <output_json>")
        sys.exit(1)

    input_file = sys.argv[1]
    output_file = sys.argv[2]

    try:
        # Load input JSON
        with open(input_file, 'r') as f:
            data = json.load(f)

        # Extract blood_glucose array
        if isinstance(data, dict) and "blood_glucose" in data:
            bgm_data = data["blood_glucose"]
        elif isinstance(data, list):
            # Assume it's already a BGM array
            bgm_data = data
        else:
            print("Error: Expected 'blood_glucose' key or array in JSON")
            sys.exit(1)

        # Write output
        with open(output_file, 'w') as f:
            json.dump(bgm_data, f, indent=2)

        print(f"Extracted {len(bgm_data)} blood glucose readings to {output_file}")

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

