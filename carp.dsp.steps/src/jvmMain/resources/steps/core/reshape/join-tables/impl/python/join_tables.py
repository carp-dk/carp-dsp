#!/usr/bin/env python3
"""Library step: join-tables.

Join two tabular files on shared key columns. Generic and domain-agnostic: which
columns to rename, which keys to join on, and the join type are all supplied as
options, so the step carries no dataset knowledge.

Optionally, one side can be reduced onto a coarser time grid before the join
(floor a timestamp column to a frequency, then aggregate value columns), which is
what lets a per-second series join a per-hour one.
"""
import argparse
import pandas as pd


def _rename(df, spec):
    if not spec:
        return df
    mapping = dict(p.split(":", 1) for p in spec.split(","))
    return df.rename(columns=mapping)


def _resample(df, keys, timecol, rule, agg):
    """Floor `timecol` to `rule`, then group by `keys` and aggregate value cols."""
    df = df.copy()
    df[timecol] = pd.to_datetime(df[timecol]).dt.floor(rule)
    agg_map = dict(p.split(":", 1) for p in agg.split(",")) if agg else {}
    return df.groupby(keys, as_index=False).agg(agg_map)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--left", required=True)
    ap.add_argument("--right", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--on", required=True, help="comma-separated join keys")
    ap.add_argument("--how", default="inner", choices=["inner", "left", "right", "outer"])
    ap.add_argument("--left-rename", default="")
    ap.add_argument("--right-rename", default="")
    ap.add_argument("--left-resample", default="", help="timecol:rule (optional)")
    ap.add_argument("--right-resample", default="", help="timecol:rule (optional)")
    ap.add_argument("--left-agg", default="", help="col:func,... after resample")
    ap.add_argument("--right-agg", default="", help="col:func,... after resample")
    a = ap.parse_args()

    keys = a.on.split(",")
    left = _rename(pd.read_csv(a.left), a.left_rename)
    right = _rename(pd.read_csv(a.right), a.right_rename)

    if a.left_resample:
        tc, rule = a.left_resample.split(":", 1)
        left = _resample(left, keys, tc, rule, a.left_agg)
    if a.right_resample:
        tc, rule = a.right_resample.split(":", 1)
        right = _resample(right, keys, tc, rule, a.right_agg)

    # Reconcile join-key dtypes across sides (e.g. one side resampled to datetime,
    # the other still a string), so keys of the same name compare equal.
    for k in keys:
        if left[k].dtype != right[k].dtype:
            if pd.api.types.is_datetime64_any_dtype(left[k]) or pd.api.types.is_datetime64_any_dtype(right[k]):
                left[k] = pd.to_datetime(left[k]); right[k] = pd.to_datetime(right[k])
            else:
                left[k] = left[k].astype(str); right[k] = right[k].astype(str)

    out = left.merge(right, on=keys, how=a.how)
    out.to_csv(a.output, index=False)
    print(f"[join-tables] {len(left)} x {len(right)} -> {len(out)} rows ({a.how}) -> {a.output}")


if __name__ == "__main__":
    main()
