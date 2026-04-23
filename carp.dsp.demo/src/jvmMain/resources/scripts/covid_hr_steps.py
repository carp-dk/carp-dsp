#!/usr/bin/env python3
"""
COVID-19 / Health Monitoring: Heart Rate and Steps Analysis Script

Analyzes resting heart rate and steps data to detect anomalies that may
indicate illness or health deviation events.

Compares baseline metrics (initial period) with recent metrics to flag alerts.
"""

import argparse
import json
from datetime import datetime
import pandas as pd
import numpy as np


def parse_arguments():
    """Parse command line arguments."""
    parser = argparse.ArgumentParser(
        description="Analyze heart rate and steps data for health anomalies"
    )
    parser.add_argument(
        "--input",
        type=str,
        required=True,
        help="Path to input CSV file with columns: date, resting_hr, steps"
    )
    parser.add_argument(
        "--output",
        type=str,
        required=True,
        help="Path to output JSON file for results"
    )
    return parser.parse_args()


def load_csv(filepath):
    """Load CSV file into DataFrame."""
    try:
        df = pd.read_csv(filepath)
        df['date'] = pd.to_datetime(df['date'])
        return df
    except FileNotFoundError:
        print(f"Error: File not found: {filepath}")
        exit(1)
    except pd.errors.ParserError:
        print(f"Error: Invalid CSV format in file: {filepath}")
        exit(1)


def compute_baseline_metrics(df, baseline_days=7):
    """
    Compute baseline metrics from the first N days.

    Args:
        df: DataFrame with 'date', 'resting_hr', 'steps' columns
        baseline_days: Number of days to consider as baseline (default: 7)

    Returns:
        Dict with baseline HR and steps
    """
    if len(df) < baseline_days:
        baseline_df = df
    else:
        baseline_df = df.head(baseline_days)

    baseline_hr = baseline_df['resting_hr'].mean()
    baseline_steps = baseline_df['steps'].mean()
    baseline_hr_std = baseline_df['resting_hr'].std()
    baseline_steps_std = baseline_df['steps'].std()

    return {
        "baseline_hr_mean": float(round(baseline_hr, 2)),
        "baseline_hr_std": float(round(baseline_hr_std, 2)),
        "baseline_steps_mean": float(round(baseline_steps, 2)),
        "baseline_steps_std": float(round(baseline_steps_std, 2)),
        "baseline_days": int(min(baseline_days, len(df)))
    }


def compute_recent_metrics(df, recent_days=3):
    """
    Compute recent metrics from the last N days.

    Args:
        df: DataFrame with 'date', 'resting_hr', 'steps' columns
        recent_days: Number of days to consider as recent (default: 3)

    Returns:
        Dict with recent HR and steps
    """
    if len(df) < recent_days:
        recent_df = df
    else:
        recent_df = df.tail(recent_days)

    recent_hr = recent_df['resting_hr'].mean()
    recent_steps = recent_df['steps'].mean()

    return {
        "recent_hr_mean": float(round(recent_hr, 2)),
        "recent_steps_mean": float(round(recent_steps, 2)),
        "recent_days": int(min(recent_days, len(df)))
    }


def detect_hr_elevation(baseline_metrics, recent_metrics, threshold_std=1.5):
    """
    Detect if recent HR is elevated above baseline.

    Args:
        baseline_metrics: Dict with baseline HR statistics
        recent_metrics: Dict with recent HR mean
        threshold_std: Number of standard deviations above baseline to flag (default: 1.5)

    Returns:
        Bool indicating if HR is elevated
    """
    baseline_hr = baseline_metrics["baseline_hr_mean"]
    baseline_std = baseline_metrics["baseline_hr_std"]
    recent_hr = recent_metrics["recent_hr_mean"]

    # HR is elevated if it exceeds baseline + threshold_std * standard_deviations
    threshold = baseline_hr + (threshold_std * baseline_std)

    return bool(recent_hr > threshold)


def detect_steps_reduction(baseline_metrics, recent_metrics, threshold_pct=30):
    """
    Detect if recent steps are reduced below baseline.

    Args:
        baseline_metrics: Dict with baseline steps statistics
        recent_metrics: Dict with recent steps mean
        threshold_pct: Percentage reduction to flag (default: 30%)

    Returns:
        Bool indicating if steps are reduced
    """
    baseline_steps = baseline_metrics["baseline_steps_mean"]
    recent_steps = recent_metrics["recent_steps_mean"]

    # Calculate percentage reduction
    pct_reduction = ((baseline_steps - recent_steps) / baseline_steps) * 100

    return bool(pct_reduction > threshold_pct)


def compute_deviations(baseline_metrics, recent_metrics):
    """
    Compute deviation metrics (changes from baseline).

    Args:
        baseline_metrics: Dict with baseline statistics
        recent_metrics: Dict with recent statistics

    Returns:
        Dict with deviation metrics
    """
    hr_diff = recent_metrics["recent_hr_mean"] - baseline_metrics["baseline_hr_mean"]
    hr_pct_change = (hr_diff / baseline_metrics["baseline_hr_mean"]) * 100

    steps_diff = recent_metrics["recent_steps_mean"] - baseline_metrics["baseline_steps_mean"]
    steps_pct_change = (steps_diff / baseline_metrics["baseline_steps_mean"]) * 100

    return {
        "hr_absolute_change": float(round(hr_diff, 2)),
        "hr_pct_change": float(round(hr_pct_change, 2)),
        "steps_absolute_change": float(round(steps_diff, 2)),
        "steps_pct_change": float(round(steps_pct_change, 2))
    }


def main():
    """Main execution."""
    args = parse_arguments()

    # Load data
    df = load_csv(args.input)

    if df.empty:
        print("Error: CSV file is empty")
        exit(1)

    # Compute baseline metrics (first 7 days or less)
    baseline_metrics = compute_baseline_metrics(df, baseline_days=7)

    # Compute recent metrics (last 3 days)
    recent_metrics = compute_recent_metrics(df, recent_days=3)

    # Compute deviations
    deviations = compute_deviations(baseline_metrics, recent_metrics)

    # Detect anomalies
    hr_elevated = detect_hr_elevation(baseline_metrics, recent_metrics)
    steps_reduced = detect_steps_reduction(baseline_metrics, recent_metrics)

    # Determine alert status
    alert_flag = "ALERT" if (hr_elevated and steps_reduced) else "NORMAL"

    # Compile results
    results = {
        "analysis_timestamp": datetime.now().isoformat(),
        "data_span": {
            "start_date": df['date'].min().isoformat(),
            "end_date": df['date'].max().isoformat(),
            "total_days": len(df)
        },
        "baseline_metrics": baseline_metrics,
        "recent_metrics": recent_metrics,
        "deviations": deviations,
        "anomaly_detection": {
            "hr_elevated": hr_elevated,
            "steps_reduced": steps_reduced,
            "flag": alert_flag
        }
    }

    # Write results
    try:
        print(results)
        with open(args.output, 'w') as f:
            json.dump(results, f, indent=2)
        print(f"Analysis complete. Results written to {args.output}")
        print(f"Alert Status: {alert_flag}")
    except IOError as e:
        print(f"Error writing output file: {e}")
        exit(1)


if __name__ == "__main__":
    main()

