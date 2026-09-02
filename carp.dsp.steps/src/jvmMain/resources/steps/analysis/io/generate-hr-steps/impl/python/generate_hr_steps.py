#!/usr/bin/env python3
"""Generate a synthetic hourly heart-rate and step-count dataset.

Creates CSV records with the schema:

    participant_id
    timestamp
    heart_rate_bpm
    steps

The generator is deterministic for a given seed, making it suitable for
repeatable tests, fixtures, demonstrations, and offline pipeline development.

The generated values follow a simple time-of-day activity pattern and are
intended only for developing workflows. They are not physiologically
realistic and must not be used for analysis or research.
"""
from __future__ import annotations

import argparse
import csv
import random
from datetime import datetime, timedelta

START = datetime(2024, 1, 1, 0, 0)


def simulate_hour(rng: random.Random, day: int, hour: int) -> tuple[float, int]:
    """Return synthetic (heart_rate_bpm, steps) values for a given day and hour."""
    if hour < 6:
        return rng.gauss(58, 4), 0
    if hour < 9:
        return rng.gauss(72, 8), rng.randint(500, 2000)
    if hour < 12:
        return rng.gauss(78, 10), rng.randint(800, 2500)
    if hour < 14:
        return rng.gauss(80, 12), rng.randint(600, 1500)
    if hour < 18:
        return rng.gauss(82, 10), rng.randint(800, 2000)
    if hour < 21:
        if day % 2 == 0:
            return rng.gauss(95, 15), rng.randint(1000, 4000)
        return rng.gauss(75, 8), rng.randint(300, 800)
    return rng.gauss(65, 5), rng.randint(0, 200)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--output", required=True, help="hr-steps CSV output path")
    ap.add_argument("--participants", type=int, default=2, help="number of participants")
    ap.add_argument("--days", type=int, default=7, help="days per participant")
    ap.add_argument("--seed", type=int, default=42, help="seed, for reproducibility")
    args = ap.parse_args()

    rows = []
    for p in range(args.participants):
        # Use an independent seed per participant -> data remains stable when the participant count changes
        rng = random.Random(args.seed + p)
        pid = f"p{p + 1:02d}"
        for day in range(args.days):
            for hour in range(24):
                hr, steps = simulate_hour(rng, day, hour)
                rows.append({
                    "participant_id": pid,
                    "timestamp": (START + timedelta(days=day, hours=hour)).isoformat(sep=" "),
                    "heart_rate_bpm": round(hr, 1),
                    "steps": steps,
                })

    with open(args.output, "w", newline="") as handle:
        writer = csv.DictWriter(
            handle, fieldnames=["participant_id", "timestamp", "heart_rate_bpm", "steps"]
        )
        writer.writeheader()
        writer.writerows(rows)
    print(f"[generate-hr-steps] wrote {len(rows)} rows "
          f"({args.participants} participant(s), {args.days} day(s)) -> {args.output}")


if __name__ == "__main__":
    main()
