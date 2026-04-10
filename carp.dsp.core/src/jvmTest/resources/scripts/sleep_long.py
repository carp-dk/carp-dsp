#!/usr/bin/env python
"""Script that sleeps for a long time — used to trigger a timeout."""
import time
import sys

seconds = int(sys.argv[1]) if len(sys.argv) > 1 else 30
time.sleep(seconds)

# This line should never be reached during a timeout test.
print("done")
