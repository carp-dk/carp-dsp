# Step cache

A step that has already run on the same inputs is not run again: its outputs are
taken from the earlier run. Its downstream steps then see the same inputs, so they
are reused too.

## When a step is the same

The engine fingerprints a step just before it would run. The fingerprint holds:

- the command and every argument;
- what its environment installs (not the environment's id);
- the contents of every input file, and of every file an argument names;
- for a script (`.py`, `.R`, `.sh`, ...), every file in its folder, since it may import them;
- for a library step on the task runtime, the runtime jar;
- a salt from the host: the portal puts in the `CARP_DSP_` variables, so another
  database never reuses the first one's answer.

Paths of the run never enter it. Two workflows that run the same step on the same
data share one entry.

## `cache:` on a step

```yaml
- id: "query-2025"
  uses: "core.io.query-sql"
  cache: true      # the window is in the past: read once
```

- Unset or `true`: reused when the fingerprint matches.
- `false`: always runs. Library steps that read from outside the run
  (`core.io.query-sql`, `core.io.fetch-study-data`) set this; a reference can
  override it when its source is fixed.

A step that always runs can still stop the work after it: if its output is the
same as last time, everything downstream is reused.

## Where it lives

`StepCache(directory, salt, maxBytes)`, passed in `DefaultPlanExecutor.Options`.
No cache, no reuse. Outputs are hard links where the file system allows, so a
reused output costs no space. Entries least recently used are removed once the
cache passes `maxBytes`.

A reused step is `SUCCEEDED`, with `cache.reused = 1.0` in its detail's metrics
and a log naming the run it came from.

## Limits

- A script that reads files outside its folder, or from the network, is not
  seen changing. Mark such a step `cache: false`.
- A step whose output is not deterministic (a timestamp, a random seed) still
  works, but what follows it is never reused.
