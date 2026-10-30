# Troubleshooting

## Building

**`carp.core-kotlin at … has no settings.gradle.kts`.** The CARP Core checkout
beside this repository is incomplete or on the wrong branch. Check it out at the
commit in the [README](../README.md#building-v100). Set `coreSetupVerbose=true` in
`gradle.properties` to see where the build looks for CARP Core.

**Build errors after pulling changes.** Refresh and rebuild:

```bash
./gradlew --refresh-dependencies
./gradlew clean build -PskipIntegration
```

## Environments

**`pixi` not found.** Install [Pixi](https://pixi.sh) and make sure it is on your
`PATH` in the terminal that runs Gradle.

**The first run is slow.** The first run of a workflow installs its environments
and may download data. Later runs reuse them.

**A Docker environment does not run.** Docker environments are declared and
exported to CWL and Snakemake; the first major release has no handler that runs
them.

**`InconsistentVersionWarning` from `scikit-learn` in the gait workflow.** The
locked environment loads a model that an older `scikit-learn` serialised. The
warning is expected and does not affect the outputs.

## Workflows

**The plan fails.** Each issue names its code and the path to the step, for
example `steps[gsd].dependsOn: references non-existent step '…'`. Fix the named
step; all issues are reported at once.

**`UNEXPECTED_OUTPUT`.** A step wrote a file outside its own folders. Write
outputs to the paths bound to `output.N`.

**A Kotlin library step cannot run.** Kotlin steps run on the shared task runtime
and must be vendored in this repository ([STEP_LIBRARY.md](STEP_LIBRARY.md#steps-written-in-kotlin)).

## Evaluation

**`evalAll` fails at the Linux runs.** Docker must be running.

**`-PpaperDir=… looks like a Windows path whose backslashes were removed`.** Write
the path with forward slashes, or quote it.

See [REPRODUCING.md](../REPRODUCING.md) for the evaluation itself.
