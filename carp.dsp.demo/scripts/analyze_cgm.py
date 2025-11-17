"""
CGM Analysis Script using cgmquantify

This script analyzes continuous glucose monitoring (CGM) data using the cgmquantify package.
It reads a DEXCOM CSV file and computes various CGM metrics.

Usage:
    python analyze_cgm.py --input <input_csv> --output <output_json> [options]

Arguments:
    --input         Path to input DEXCOM CSV file
    --output        Path to output JSON file for results
    --subject-id    Subject ID (optional, extracted from filename if not provided)
    --verbose       Enable verbose output
"""

import argparse
import json
import sys
from pathlib import Path

try:
    import pandas as pd
    import numpy as np
    # Import cgmquantify summary function
    from cgmquantify import summary
    CGMQUANTIFY_AVAILABLE = True
except ImportError as e:
    print(f"Error: Required package not installed: {e}")
    if 'cgmquantify' in str(e):
        print("Please install cgmquantify:")
        print("  pip install git+https://github.com/brinnaebent/cgmquantify.git")
    else:
        print("Please install required packages:")
        print("  pip install pandas numpy")
    sys.exit(1)


def parse_dexcom_csv(filepath):
    """
    Parse a DEXCOM CSV file and create DataFrame in cgmquantify format.

    Following cgmquantify documentation format exactly.
    """
    try:
        print(f"Loading DEXCOM data from: {filepath}")

        # Read file
        df = pd.read_csv(filepath)

        # Create data frame
        data = pd.DataFrame()

        # Add Time to data
        data['Time'] = df['Timestamp (YYYY-MM-DDThh:mm:ss)']

        # Add glucose column to data
        data['Glucose'] = pd.to_numeric(df['Glucose Value (mg/dL)'], errors='coerce')

        # Index: drop first 12 rows (DEXCOM header rows)
        data.drop(data.index[:12], inplace=True)

        # Format time to datetime - use mixed format to handle variations
        data['Time'] = pd.to_datetime(data['Time'], format='mixed')

        # Add column for Day
        data['Day'] = data['Time'].dt.date

        # Reset index
        data = data.reset_index(drop=True)

        # Remove rows with missing glucose values
        data = data.dropna(subset=['Glucose'])

        print(f"Loaded {len(data)} glucose readings")
        print(f"Date range: {data['Day'].min()} to {data['Day'].max()}")

        return data

    except Exception as e:
        print(f"Error parsing DEXCOM CSV: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


def calculate_cgm_metrics(df):
    """
    Calculate CGM metrics using cgmquantify.

    Uses cgmquantify's summary() function for basic statistics
    and adds additional CGM-specific metrics.

    Returns a dictionary of calculated metrics.
    """
    metrics_dict = {}

    try:
        # Use cgmquantify's summary function for basic statistics
        print("Calculating summary statistics...")
        stats = summary(df)

        # Unpack tuple: summary() returns (mean, median, min, max, Q1, Q3)
        mean_val, median_val, min_val, max_val, q1_val, q3_val = stats

        # Extract statistics from summary output
        metrics_dict['mean_glucose'] = float(mean_val)
        metrics_dict['median_glucose'] = float(median_val)
        metrics_dict['min_glucose'] = float(min_val)
        metrics_dict['max_glucose'] = float(max_val)
        metrics_dict['q1_glucose'] = float(q1_val)
        metrics_dict['q3_glucose'] = float(q3_val)

        # Calculate additional metrics manually
        # Note: cgmquantify uses 'Glucose' column (capital G)
        glucose = df['Glucose'].values

        # Standard deviation and CV
        metrics_dict['std_glucose'] = float(np.std(glucose))
        metrics_dict['cv'] = float((np.std(glucose) / np.mean(glucose)) * 100)

        # Time in range metrics (70-180 mg/dL is standard range)
        in_range = ((glucose >= 70) & (glucose <= 180)).sum() / len(glucose) * 100
        below_range = (glucose < 70).sum() / len(glucose) * 100
        above_range = (glucose > 180).sum() / len(glucose) * 100

        metrics_dict['time_in_range'] = float(in_range)
        metrics_dict['time_below_range'] = float(below_range)
        metrics_dict['time_above_range'] = float(above_range)

        # Hypoglycemia metrics
        metrics_dict['time_below_70'] = float((glucose < 70).sum() / len(glucose) * 100)
        metrics_dict['time_below_54'] = float((glucose < 54).sum() / len(glucose) * 100)

        # Hyperglycemia metrics
        metrics_dict['time_above_180'] = float((glucose > 180).sum() / len(glucose) * 100)
        metrics_dict['time_above_250'] = float((glucose > 250).sum() / len(glucose) * 100)

        # Estimated HbA1c (eA1c) - formula: (mean_glucose + 46.7) / 28.7
        mean_glucose = metrics_dict['mean_glucose']
        metrics_dict['eA1c'] = float((mean_glucose + 46.7) / 28.7)

        # GMI (Glucose Management Indicator) - formula: 3.31 + (0.02392 * mean_glucose)
        metrics_dict['gmi'] = float(3.31 + (0.02392 * mean_glucose))

        print(f"Calculated {len(metrics_dict)} CGM metrics")

    except Exception as e:
        print(f"Warning: Error calculating metrics: {e}")
        import traceback
        traceback.print_exc()

    return metrics_dict


def main():
    parser = argparse.ArgumentParser(
        description='Analyze CGM data using cgmquantify'
    )
    parser.add_argument('--input', required=True, help='Input DEXCOM CSV file')
    parser.add_argument('--output', required=True, help='Output JSON file for results')
    parser.add_argument('--subject-id', help='Subject ID (optional)')
    parser.add_argument('--verbose', action='store_true', help='Verbose output')

    args = parser.parse_args()

    # Check input file exists
    input_path = Path(args.input)
    if not input_path.exists():
        print(f"Error: Input file not found: {args.input}")
        sys.exit(1)

    # Extract subject ID from filename if not provided
    subject_id = args.subject_id
    if subject_id is None:
        # Try to extract from filename (e.g., "Dexcom_001.csv" -> "001")
        filename = input_path.stem
        if '_' in filename:
            subject_id = filename.split('_')[-1]
        else:
            subject_id = filename

    if args.verbose:
        print(f"Processing subject: {subject_id}")
        print(f"Input file: {args.input}")
        print(f"Output file: {args.output}")

    # Parse DEXCOM data
    df = parse_dexcom_csv(args.input)

    # Calculate metrics
    metrics = calculate_cgm_metrics(df)

    # Add metadata
    # Extract date range from Day column
    if 'Day' in df.columns:
        start_date = df['Day'].min()
        end_date = df['Day'].max()
    else:
        # Fallback if Day column doesn't exist
        start_date = "Unknown"
        end_date = "Unknown"

    results = {
        'subject_id': subject_id,
        'input_file': str(input_path),
        'num_readings': len(df),
        'date_range': {
            'start': str(start_date),
            'end': str(end_date)
        },
        'metrics': metrics
    }

    # Save results
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with open(output_path, 'w') as f:
        json.dump(results, f, indent=2)

    print(f"\n✓ Analysis complete. Results saved to: {args.output}")

    # Print summary
    if args.verbose:
        print("\nSummary of key metrics:")

        # Helper function to format metric values
        def format_metric(value, default='N/A', decimal_places=1):
            if value is None:
                return default
            try:
                return f"{float(value):.{decimal_places}f}"
            except (ValueError, TypeError):
                return default

        mean_glucose = metrics.get('mean_glucose')
        cv = metrics.get('cv')
        time_in_range = metrics.get('time_in_range')
        gmi = metrics.get('gmi')
        eA1c = metrics.get('eA1c')

        print(f"  Mean glucose: {format_metric(mean_glucose)} mg/dL")
        print(f"  CV: {format_metric(cv)}%")
        print(f"  Time in range (70-180): {format_metric(time_in_range)}%")
        print(f"  GMI: {format_metric(gmi)}%")
        print(f"  eA1c: {format_metric(eA1c)}%")


if __name__ == '__main__':
    main()

