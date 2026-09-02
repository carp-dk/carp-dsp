# Step templates

Per-language skeletons stamped into a new step directory by the `newStep` task -
not copied by hand:

```bash
./gradlew :carp.dsp.steps:newStep -Pid=sensing.heartrate.hrv-rmssd -Planguage=python
```

Each `<language>/` directory is a copyable step with `{{TOKEN}}` placeholders the
task fills in (id, tier, subject, name). Editing the templates changes what every
future step starts from.

The full contribution walkthrough - checklist, requirements, review, certification
- is [docs/STEP_LIBRARY_CONTRIBUTING.md](../../docs/STEP_LIBRARY_CONTRIBUTING.md).
