#!/usr/bin/env python3
"""{{ID}} - one-line summary.

Longer description: what it computes, and what it assumes about the input.

Inputs:
    --input     describe it
Outputs:
    --output    describe it
"""
from __future__ import annotations

import argparse
import json
from typing import Any

import pandas as pd


def compute(frame: pd.DataFrame) -> dict[str, Any]:
    """The step's logic.

    Kept free of file IO so it can be unit tested directly. Replace this body.
    """
    raise NotImplementedError


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="describe it")
    parser.add_argument("--output", required=True, help="describe it")
    args = parser.parse_args()

    result = compute(pd.read_csv(args.input))

    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(result, handle, indent=2, sort_keys=True)
        handle.write("\n")


if __name__ == "__main__":
    main()
