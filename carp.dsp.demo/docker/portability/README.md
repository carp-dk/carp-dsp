# Portability eval (cross-OS output determinism)

Runs the HR/step pipeline (`scripts/hr_lib`) in several glibc Linux distros, each under
one **identical, lock-pinned** Pixi env, and compares the SHA-256 of the outputs. Only the
OS varies, so identical hashes = the analysis is portable / OS-independent. This isolates
the numeric layer; the framework's orchestration is pure-JVM and already shown
deterministic on a single machine.

## Run

```
./gradlew :carp.dsp.demo:evalPortability     # from the repo root
```

Prereq: **Docker running** (the eval aborts with a clear message if not). The shared
`pixi.lock` is generated inside a container on first run (no host Pixi needed), and the
open Fitbit dataset (Zenodo 53894, CC BY 4.0) is downloaded once and reused for every image.

Outputs land in `carp.dsp.demo/eval_results/portability.{txt,csv,tex}`. Paste
`portability-table.tex` into `tab:portability` in the paper.

## Files

- `Dockerfile` - parametrized by `--build-arg BASE=<distro>`; installs Pixi, the env, and the scripts.
- `pixi.toml` / `pixi.lock` - the shared pinned env (commit `pixi.lock` after first run for reproducibility).
- `run_pipeline.sh` - in-container: run the pipeline, print the output hash fingerprint.
- `run-portability.sh` - driver: Docker check, build+run each distro, collect hashes.
- `compare_hashes.py` - diff hashes across distros -> `eval_results/portability.{txt,csv,tex}`.

Edit the `IMAGES=(...)` list in `run-portability.sh` to change the distro set.
Scope: Docker varies Linux distros (glibc). macOS/Windows would need a CI matrix (future work).
