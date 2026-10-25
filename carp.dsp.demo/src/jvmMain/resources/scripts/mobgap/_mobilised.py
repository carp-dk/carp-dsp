"""
Shared by the gait step scripts: the Mobilise-D pipeline for a recording, and exact CSV I/O.

Each step runs one block (or one per-gait-sequence loop) of mobgap's official Mobilise-D
pipeline, with the algorithms and settings of the preset mobgap itself would choose for the
participant's cohort (as ``MobilisedPipelineUniversal`` does: impaired walking for MS, PD,
PFF; regular walking for HA, COPD, CHF). Run end to end, the steps give the same outputs as
``MobilisedPipelineUniversal().run(datapoint)``.
"""

import json

import pandas as pd


def read_csv(path, **kwargs) -> pd.DataFrame:
    """Reads a CSV written by another step, with floats read back exactly as written."""
    return pd.read_csv(path, float_precision="round_trip", **kwargs)


def load_config(path) -> dict:
    with open(path) as f:
        return json.load(f)


def action_kwargs(config: dict) -> dict:
    """The metadata the official pipeline passes to every algorithm."""
    return {**config["participant"], **config["recording"], "sampling_rate_hz": config["sampling_rate_hz"]}


def pipeline(config: dict):
    """The official pipeline recommended for the participant's cohort."""
    from mobgap.pipeline import MobilisedPipelineUniversal

    cohort = config["participant"]["cohort"]
    for name, candidate in MobilisedPipelineUniversal().pipelines:
        if cohort in (candidate.get_recommended_cohorts() or ()):
            print(f"[MOBILISED] cohort {cohort}: {name} pipeline ({type(candidate).__name__})")
            return candidate
    raise SystemExit(f"No Mobilise-D pipeline recommends cohort {cohort}")
