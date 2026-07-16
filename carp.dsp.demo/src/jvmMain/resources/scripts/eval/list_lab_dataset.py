#!/usr/bin/env python3
"""List the recordings in mobgap's LabExampleDataset.

Use this to pick a valid HA (healthy) recording for the reuse variant
(mobgap-gait-analysis-ha.yaml) - set import-data's --cohort/--participant-id/--test/--trial
to a row shown here. Run inside the mobgap env, e.g.:

    pixi run --manifest-path <env>/pixi.toml python list_lab_dataset.py
"""
from mobgap.data import LabExampleDataset

ds = LabExampleDataset(reference_system="INDIP")
idx = ds.index  # columns: cohort, participant_id, test, trial
print(idx.to_string(index=False))
print()
for cohort in sorted(idx["cohort"].unique()):
    print(f"{cohort}: {len(idx[idx['cohort'] == cohort])} recording(s)")
