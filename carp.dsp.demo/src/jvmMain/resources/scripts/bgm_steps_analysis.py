#!/usr/bin/env python3
"""
Blood Glucose Monitoring and Steps Analysis Script

Analyzes blood glucose measurements and daily steps data to compute:
- Blood glucose metrics (in range, below, above thresholds)
- Daily step statistics
- Step trend analysis
"""

import argparse
import json
from datetime import datetime, timedelta
import pandas as pd
import numpy as np


def parse_arguments():
    """Parse command line arguments."""
    parser = argparse.ArgumentParser(
        description="Analyze blood glucose monitoring and steps data"
    )
    parser.add_argument(
        "--bgm",
        type=str,
        required=True,
        help="Path to BGM JSON file with blood glucose measurements"
    )
    parser.add_argument(
        "--steps",
        type=str,
        required=True,
        help="Path to steps JSON file with daily step counts"
    )
    parser.add_argument(
        "--output",
        type=str,
        required=True,
        help="Path to output JSON file for results"
    )
    return parser.parse_args()


def load_json(filepath):
    """Load JSON file."""
    try:
        with open(filepath, 'r') as f:
            return json.load(f)
    except FileNotFoundError:
        print(f"Error: File not found: {filepath}")
        exit(1)
    except json.JSONDecodeError:
        print(f"Error: Invalid JSON in file: {filepath}")
        exit(1)


def analyze_bgm_data(bgm_data):
    """
    Analyze blood glucose measurements.

    Args:
        bgm_data: List of dicts with 'date' and 'measured_value' keys

    Returns:
        Dict with BGM metrics
    """
    if not bgm_data:
        return {
            "pct_in_range": 0.0,
            "pct_below": 0.0,
            "pct_above": 0.0,
            "total_readings": 0
        }

    values = [reading["measured_value"] for reading in bgm_data]
    total = len(values)

    # Thresholds: 3.9-10 mmol/L is in range
    in_range = sum(1 for v in values if 3.9 <= v <= 10)
    below = sum(1 for v in values if v < 3.9)
    above = sum(1 for v in values if v > 10)

    return {
        "pct_in_range": round((in_range / total) * 100, 2),
        "pct_below": round((below / total) * 100, 2),
        "pct_above": round((above / total) * 100, 2),
        "total_readings": total,
        "mean_bgm": round(np.mean(values), 2),
        "std_bgm": round(np.std(values), 2)
    }


def aggregate_daily_steps(steps_data):
    """
    Aggregate steps data to daily totals.

    Args:
        steps_data: List of dicts with 'date' and 'measured_value' keys

    Returns:
        DataFrame with date and daily steps
    """
    if not steps_data:
        return pd.DataFrame(columns=['date', 'steps'])

    df = pd.DataFrame(steps_data)
    df['date'] = pd.to_datetime(df['date']).dt.date
    df['steps'] = df['measured_value']

    # Group by date and sum steps
    daily_steps = df.groupby('date')['steps'].sum().reset_index()
    daily_steps = daily_steps.sort_values('date')

    return daily_steps


def analyze_steps_data(daily_steps_df):
    """
    Analyze daily steps data.

    Args:
        daily_steps_df: DataFrame with 'date' and 'steps' columns

    Returns:
        Dict with steps metrics
    """
    if daily_steps_df.empty:
        return {
            "mean_daily_steps": 0.0,
            "median_daily_steps": 0.0,
            "step_trend": "insufficient_data"
        }

    steps = daily_steps_df['steps'].values
    mean_steps = np.mean(steps)
    median_steps = np.median(steps)

    return {
        "mean_daily_steps": round(mean_steps, 2),
        "median_daily_steps": round(median_steps, 2),
        "min_daily_steps": int(np.min(steps)),
        "max_daily_steps": int(np.max(steps))
    }


def compute_step_trend(daily_steps_df):
    """
    Compute step trend comparing last 3 days to preceding 3 days.

    Args:
        daily_steps_df: DataFrame with 'date' and 'steps' columns

    Returns:
        String: "improving", "declining", "stable", or "insufficient_data"
    """
    if len(daily_steps_df) < 6:
        return "insufficient_data"

    # Last 3 days
    last_3_mean = daily_steps_df.iloc[-3:]['steps'].mean()

    # Preceding 3 days (before the last 3)
    preceding_3_mean = daily_steps_df.iloc[-6:-3]['steps'].mean()

    # Calculate percentage change
    pct_change = ((last_3_mean - preceding_3_mean) / preceding_3_mean) * 100

    # Determine trend with 10% threshold
    if pct_change > 10:
        return "improving"
    elif pct_change < -10:
        return "declining"
    else:
        return "stable"


def main():
    """Main execution."""
    args = parse_arguments()

    # Load data
    bgm_data = load_json(args.bgm)
    steps_data = load_json(args.steps)

    # Ensure data is in expected format
    if isinstance(bgm_data, dict) and "blood_glucose" in bgm_data:
        bgm_data = bgm_data["blood_glucose"]

    if isinstance(steps_data, dict) and "daily_steps" in steps_data:
        steps_data = steps_data["daily_steps"]

    # Analyze BGM data
    bgm_metrics = analyze_bgm_data(bgm_data)

    # Aggregate and analyze steps data
    daily_steps_df = aggregate_daily_steps(steps_data)
    steps_metrics = analyze_steps_data(daily_steps_df)

    # Compute step trend
    step_trend = compute_step_trend(daily_steps_df)

    # Combine results
    results = {
        "analysis_timestamp": datetime.now().isoformat(),
        "blood_glucose_metrics": bgm_metrics,
        "steps_metrics": steps_metrics,
        "step_trend": step_trend
    }

    # Write results
    try:
        with open(args.output, 'w') as f:
            json.dump(results, f, indent=2)
        print(f"Analysis complete. Results written to {args.output}")
    except IOError as e:
        print(f"Error writing output file: {e}")
        exit(1)


if __name__ == "__main__":
    main()

