"""
Step 1 — Load Data
Generates synthetic wearable data: 7 days of heart rate + steps.
Outputs a single CSV with one row per hour.
"""
import argparse
import csv
import random
from datetime import datetime, timedelta

SEED = 42
random.seed(SEED)

START = datetime(2024, 1, 1, 0, 0)
DAYS = 7


def simulate_hour(day: int, hour: int) -> dict:
    """Simulate HR and steps for one hour based on time of day."""
    # Resting at night, active during day
    if 0 <= hour < 6:
        hr = random.gauss(58, 4)
        steps = 0
    elif 6 <= hour < 9:
        hr = random.gauss(72, 8)
        steps = random.randint(500, 2000)
    elif 9 <= hour < 12:
        hr = random.gauss(78, 10)
        steps = random.randint(800, 2500)
    elif 12 <= hour < 14:
        hr = random.gauss(80, 12)
        steps = random.randint(600, 1500)
    elif 14 <= hour < 18:
        hr = random.gauss(82, 10)
        steps = random.randint(800, 2000)
    elif 18 <= hour < 21:
        # Evening activity spike
        hr = random.gauss(95, 15) if (day % 2 == 0) else random.gauss(75, 8)
        steps = random.randint(1000, 4000) if (day % 2 == 0) else random.randint(300, 800)
    else:
        hr = random.gauss(65, 5)
        steps = random.randint(0, 200)

    return {
        "timestamp": (START + timedelta(days=day, hours=hour)).isoformat(),
        "heart_rate_bpm": max(45, min(180, round(hr, 1))),
        "steps": max(0, steps),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    rows = [simulate_hour(d, h) for d in range(DAYS) for h in range(24)]

    with open(args.output, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["timestamp", "heart_rate_bpm", "steps"])
        writer.writeheader()
        writer.writerows(rows)

    print(f"[load_data] wrote {len(rows)} rows to {args.output}")


if __name__ == "__main__":
    main()
