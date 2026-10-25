#!/usr/bin/env python3
"""PL1 baseline - do Snakemake and CWL catch the six injected faults before a run?

Reads the wiring JSON that StaticBaselineEval.kt writes (two clean workflows and six
fault-injected ones). For each, writes the workflow the way a Snakemake or CWL author
would, then runs the tool's own pre-run check:

    snakemake -n --sdm conda      (dry run: builds the DAG, runs nothing)
    cwltool --validate            (schema, wiring and type check, runs nothing)

The generators only translate the wiring; no fault is special-cased. A fault the tool's
language has no place for (an environment reference in CWL) is reported as "n/a".
The clean workflows are the control: both must pass, or the script exits 1.

Writes a CSV (one row per fault and tool) and LaTeX macros.
"""
from __future__ import annotations

import argparse
import csv
import json
import pathlib
import re
import shutil
import subprocess
import sys

EXT = {"csv": "csv", "json": "json", "png": "png", "tsv": "tsv"}
EDAM = {"csv": "edam:format_3752", "json": "edam:format_3464", "png": "edam:format_3603", "tsv": "edam:format_3475"}
CONDA_KINDS = {"conda", "pixi"}


def cid(s: str) -> str:
    return s.replace("-", "_")


def out_path(step: str, port: str, fmt: str) -> str:
    return f"{step}/{port}.{EXT.get(fmt, 'dat')}"


def producer_ports(wf: dict) -> dict[tuple[str, str], dict]:
    return {(s["id"], o["id"]): o for s in wf["steps"] for o in s["outputs"]}


# -- Snakemake --------------------------------------------------------------------

def snakefile(wf: dict, work: pathlib.Path) -> None:
    """One rule per step. A step-output input is the path the producer writes; the
    consumer's declared format is not part of a Snakefile, so it cannot conflict."""
    produced = producer_ports(wf)
    rules, targets = [], []
    for step in wf["steps"]:
        ins = []
        for p in step["inputs"]:
            src = p["source"]
            if src["kind"] == "step":
                prod = produced.get((src["stepId"], src["outputId"]))
                ins.append(out_path(src["stepId"], src["outputId"], (prod or p)["format"]))
            elif src["kind"] == "file":
                ins.append(src["path"])
                f = work / src["path"]  # raw input data exists before a run
                f.parent.mkdir(parents=True, exist_ok=True)
                f.touch()
        outs = [out_path(step["id"], o["id"], o["format"]) for o in step["outputs"]]
        targets += outs
        lines = [f"rule {cid(step['id'])}:"]
        if ins:
            lines += ["    input:"] + [f'        "{i}",' for i in ins]
        if outs:
            lines += ["    output:"] + [f'        "{o}",' for o in outs]
        if wf["environments"].get(step["environmentId"], "conda") in CONDA_KINDS:
            lines.append(f'    conda: "envs/{step["environmentId"]}.yaml"')
        lines += ["    shell:", f'        "run-step {step["id"]}"', ""]
        rules.append("\n".join(lines))
    env_dir = work / "envs"
    env_dir.mkdir(parents=True, exist_ok=True)
    for env_id, kind in wf["environments"].items():
        if kind in CONDA_KINDS:
            (env_dir / f"{env_id}.yaml").write_text(f"name: {env_id}\nchannels: [conda-forge]\ndependencies: [python]\n")
    head = "rule all:\n    input:\n" + "".join(f'        "{t}",\n' for t in targets) + "\n"
    (work / "Snakefile").write_text(head + "\n".join(rules))


def check_snakemake(work: pathlib.Path) -> tuple[bool, str]:
    r = subprocess.run(["snakemake", "-n", "--sdm", "conda", "--cores", "1", "--quiet"],
                       cwd=work, capture_output=True, text=True)
    return r.returncode == 0, first_error(r.stdout + r.stderr).replace(f"{work}/", "")[:160]


# -- CWL --------------------------------------------------------------------------

def cwl_workflow(wf: dict, work: pathlib.Path) -> bool:
    """A CWL Workflow with inline tools; File ports carry EDAM formats. Returns False when
    the wiring holds something CWL has no place for (a reference to an environment)."""
    expressible = all(s["environmentId"] in wf["environments"] for s in wf["steps"])
    wf_inputs, steps = {}, {}
    for step in wf["steps"]:
        tool_in, step_in = {}, {}
        for i, p in enumerate(step["inputs"]):
            src, pid = p["source"], cid(p["id"])
            tool_in[pid] = {"type": "File", "inputBinding": {"position": i + 1}}
            if p["format"] in EDAM:
                tool_in[pid]["format"] = EDAM[p["format"]]
            if src["kind"] == "step":
                step_in[pid] = f"{cid(src['stepId'])}/{cid(src['outputId'])}"
            else:
                name = f"{cid(step['id'])}__{pid}"
                wf_inputs[name] = {"type": "File"} | ({"format": EDAM[p["format"]]} if p["format"] in EDAM else {})
                step_in[pid] = name
        tool_out = {}
        for o in step["outputs"]:
            tool_out[cid(o["id"])] = {"type": "File", "outputBinding": {"glob": out_path(step["id"], o["id"], o["format"])}}
            if o["format"] in EDAM:
                tool_out[cid(o["id"])]["format"] = EDAM[o["format"]]
        steps[cid(step["id"])] = {
            "run": {"class": "CommandLineTool", "baseCommand": ["run-step", step["id"]],
                    "inputs": tool_in, "outputs": tool_out},
            "in": step_in,
            "out": list(tool_out),
        }
    doc = {"cwlVersion": "v1.2", "class": "Workflow", "$namespaces": {"edam": "http://edamontology.org/"},
           "inputs": wf_inputs, "outputs": {}, "steps": steps}
    (work / "workflow.cwl").write_text(json.dumps(doc, indent=2))  # JSON is valid CWL YAML
    return expressible


def check_cwl(work: pathlib.Path) -> tuple[bool, str]:
    r = subprocess.run(["cwltool", "--validate", "workflow.cwl"], cwd=work, capture_output=True, text=True)
    return r.returncode == 0, first_error(r.stdout + r.stderr).replace(f"{work}/", "")[:160]


# The line that names the fault, per tool; a Python traceback falls back to its last line.
KEY_LINES = ("circular dependency", "references unknown identifier", "Exception in rule", "Error:")


def first_error(log: str) -> str:
    lines = [re.sub(r"\x1b\[[0-9;]*m", "", ln).strip() for ln in log.splitlines()]
    for key in KEY_LINES:
        for ln in lines:
            if key in ln:
                return ln
    return ""


def version(cmd: str) -> str:
    r = subprocess.run([cmd, "--version"], capture_output=True, text=True)
    return (r.stdout or r.stderr).strip().split()[-1]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--wiring", required=True, type=pathlib.Path)
    ap.add_argument("--work", required=True, type=pathlib.Path)
    ap.add_argument("--csv", required=True, type=pathlib.Path)
    ap.add_argument("--tex", required=True, type=pathlib.Path)
    args = ap.parse_args()

    for tool in ("snakemake", "cwltool"):
        if shutil.which(tool) is None:
            print(f"{tool} not found - run through pixi (scripts/eval/baselines/pixi.toml)", file=sys.stderr)
            return 1

    rows, controls_ok = [], True
    for f in sorted(args.wiring.glob("*.json")):
        wf = json.loads(f.read_text())
        case = wf["case"]
        for tool in ("snakemake", "cwl"):
            work = args.work / case / tool
            shutil.rmtree(work, ignore_errors=True)
            work.mkdir(parents=True)
            if tool == "snakemake":
                snakefile(wf, work)
                expressible = True
                passed, msg = check_snakemake(work)
            else:
                expressible = cwl_workflow(wf, work)
                passed, msg = check_cwl(work)
            if case.startswith("clean-"):
                print(f"control {case:34s} {tool:9s} {'pass' if passed else 'FAIL: ' + msg}")
                controls_ok &= passed
                continue
            caught = expressible and not passed
            rows.append({"fault": case, "tool": tool, "expressible": expressible,
                         "caught_before_run": caught, "message": msg if caught else ""})
            print(f"fault   {case:34s} {tool:9s} {'n/a' if not expressible else 'caught' if caught else 'not caught'}"
                  + (f"  ({msg})" if caught else ""))

    if not controls_ok:
        print("A clean workflow failed its check - the generator is wrong, not the tool.", file=sys.stderr)
        return 1

    args.csv.parent.mkdir(parents=True, exist_ok=True)
    with open(args.csv, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)

    def count(tool: str, key: str) -> int:
        return sum(1 for r in rows if r["tool"] == tool and r[key])

    faults = len({r["fault"] for r in rows})
    macros = {
        "StaticFaults": faults,
        "SnakemakeCaught": count("snakemake", "caught_before_run"),
        "CwlCaught": count("cwl", "caught_before_run"),
        "CwlExpressible": count("cwl", "expressible"),
        "SnakemakeVersion": version("snakemake"),
        "CwltoolVersion": version("cwltool"),
    }
    args.tex.write_text(
        "% GENERATED by static_baselines.py - do not edit; run :carp.dsp.demo:evalStaticBaselines\n"
        + "".join(f"\\newcommand{{\\{k}}}{{{v}}}\n" for k, v in macros.items())
    )
    print(f"Wrote {args.csv} and {args.tex}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
