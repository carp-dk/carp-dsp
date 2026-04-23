#!/usr/bin/env python3
"""
Generate Biomarker Report Script

Reads biomarker.json analysis results and formats as a human-readable report.
Also writes a copy to report.json for artifact storage.
"""

import json
import sys


def main():
    """Main execution."""
    if len(sys.argv) != 3:
        print("Usage: report_biomarker.py <input_json> <output_json>")
        sys.exit(1)

    input_file = sys.argv[1]
    output_file = sys.argv[2]

    try:
        # Load biomarker results
        with open(input_file, 'r') as f:
            results = json.load(f)

        # Print human-readable summary
        print("\n" + "=" * 70)
        print("BIOMARKER ANALYSIS REPORT")
        print("=" * 70)

        if "anomaly_detection" in results:
            anomaly = results["anomaly_detection"]
            flag = anomaly.get("flag", "UNKNOWN")

            print(f"\nAlert Status:                       {flag}")
            print(f"HR Elevated:                        {anomaly.get('hr_elevated', False)}")
            print(f"Steps Reduced:                      {anomaly.get('steps_reduced', False)}")

        if "baseline_metrics" in results:
            baseline = results["baseline_metrics"]
            print(f"\nBaseline Metrics (first 7 days):")
            print(f"  Baseline HR Mean:                 {baseline.get('baseline_hr_mean', 'N/A')} bpm")
            print(f"  Baseline Steps Mean:              {baseline.get('baseline_steps_mean', 'N/A')}")

        if "recent_metrics" in results:
            recent = results["recent_metrics"]
            print(f"\nRecent Metrics (last 3 days):")
            print(f"  Recent HR Mean:                   {recent.get('recent_hr_mean', 'N/A')} bpm")
            print(f"  Recent Steps Mean:                {recent.get('recent_steps_mean', 'N/A')}")

        if "deviations" in results:
            dev = results["deviations"]
            print(f"\nDeviation from Baseline:")
            print(f"  HR Change (bpm):                  {dev.get('hr_absolute_change', 'N/A')}")
            print(f"  HR Change (%):                    {dev.get('hr_pct_change', 'N/A')}%")
            print(f"  Steps Change:                     {dev.get('steps_absolute_change', 'N/A')}")
            print(f"  Steps Change (%):                 {dev.get('steps_pct_change', 'N/A')}%")

        print("\n" + "=" * 70 + "\n")

        # Write report to output JSON file
        with open(output_file, 'w') as f:
            json.dump(results, f, indent=2)

        print(f"Report written to {output_file}")

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

