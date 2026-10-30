# Sharing

## Run bundles

A finished run folder is the run's bundle ([RUNNING.md](RUNNING.md#the-run-folder)).
Besides the workflow, its scripts and lock files, and each step's inputs, outputs
and logs, the runtime writes:

- `plan.json`: the execution plan;
- `report.json`: the execution report;
- `environments/`: what each environment contained;
- `ro-crate-metadata.json`: an RO-Crate following the Workflow Run Crate profile.
  It names the workflow, the run with its times and status, its input and output
  files, and the size and SHA-256 of every file.

A bundle can be checked file by file against its RO-Crate and run again from the
folder, given the package index its lock files name.

## Packages

For publication, `PackageBuilder` builds a `WorkflowArtifactPackage` (defined in
`health-workflow-interfaces`) from a workflow:

- an identifier, a version and descriptive metadata;
- the workflow in YAML and its SHA-256 content hash;
- the study protocols it draws on, one reference per protocol with the data types
  it takes ([PROTOCOL_LINEAGE.md](PROTOCOL_LINEAGE.md));
- a CWL translation.

## Exports

- **CWL.** `DspToCwlExporter` writes one CWL v1.2 Workflow with each step as an
  inline `CommandLineTool`, wired by the workflow's data flow. Each tool stages the
  package folder into its working directory, so the CWL file runs from the package.
  Docker environments become a `DockerRequirement`; Conda and Pixi dependencies
  become `SoftwareRequirement` hints. CWL cannot describe a Pixi environment, so a
  CWL engine must run inside it.
- **Snakemake.** `DspToSnakemakeExporter` writes a Snakemake workflow.

## Registries

Packages are published through the `RegistryPort` interface. The
`RegistryClient` posts packages to a `health-workflow-interfaces` server over
HTTP; registering with a public registry such as WorkflowHub is the server's
concern, so the registry backend can change without changing CARP-DSP. Protocol
references travel with the package, so a registry can answer which studies an
analysis draws on.
