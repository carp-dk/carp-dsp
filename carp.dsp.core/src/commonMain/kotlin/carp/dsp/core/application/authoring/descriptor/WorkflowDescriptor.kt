package carp.dsp.core.application.authoring.descriptor

import kotlinx.serialization.Serializable

// ── WorkflowMetadataDescriptor ────────────────────────────────────────────────

/**
 * Descriptor for workflow-level metadata.
 *
 * Maps to [dk.cachet.carp.analytics.domain.workflow.WorkflowMetadata] at import time.
 *
 * @property id Optional stable UUID that uniquely identifies this workflow across versions.
 *   **Lenient read:** `null` is accepted; the importer generates a UUID.
 *   **Strict write:** exporters always emit a concrete, non-null value.
 * @property name Human-readable workflow name. Required.
 * @property description Optional longer-form description.
 * @property version Semantic version string (e.g. `"1.0"`, `"2.3"`).
 * @property tags Free-form labels for search / filtering.
 */
@Serializable
data class WorkflowMetadataDescriptor(
    val id: String? = null,
    val name: String,
    val description: String? = null,
    val version: String = "1.0",
    val tags: List<String> = emptyList(),
)

// ── EnvironmentDescriptor ─────────────────────────────────────────────────────

/**
 * Descriptor for an execution environment referenced by one or more steps.
 *
 * Mirrors [dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition] but stays
 * YAML-friendly: the [spec] map carries kind-specific fields without requiring a separate
 * sealed hierarchy at the descriptor layer.
 *
 * The environment's identity is the **map key** in [WorkflowDescriptor.environments] — there
 * is no separate `id` field to avoid duplication.
 *
 * **Known [kind] values** (checked by the importer, not enforced here):
 * - `"conda"` — Conda-managed environment.
 * - `"pixi"` — Pixi-managed environment.
 * - `"python"` — Plain Python venv/system.
 * - `"system"` — No managed environment; commands run in the host process environment.
 *
 * @property name Human-readable environment name.
 * @property kind Environment manager / type identifier (case-insensitive).
 * @property spec Key-value pairs forwarded verbatim to the environment factory.
 *   List values (e.g. channels, dependencies) are serialized as comma-separated strings.
 *   Using plain `String` keeps the descriptor format-agnostic (YAML and JSON compatible).
 */
@Serializable
data class EnvironmentDescriptor(
    val name: String,
    val kind: String,
    val spec: Map<String, String> = emptyMap(),
)

// ── WorkflowDescriptor ────────────────────────────────────────────────────────

/**
 * Root DTO for a workflow definition expressed in a YAML/JSON document.
 *
 * This is the versioned, serializable contract between authoring tools and the
 * CARP-DSP runtime. It is intentionally flat and YAML-friendly — no domain types
 * leak through this boundary.
 *
 * Lifecycle: authored document → [WorkflowDescriptor] → domain WorkflowDefinition.
 *
 * @property schemaVersion Version of the descriptor schema itself (e.g. `"1.0"`),
 *   distinct from the workflow's own [WorkflowMetadataDescriptor.version].
 *   Defaults to `""` so that kaml treats the field as **optional** during deserialization —
 *   an absent `schemaVersion` key in YAML decodes to `""` rather than throwing.
 *   `WorkflowYamlCodec` replaces any blank value with `"1.0"` after parsing, so callers
 *   always receive a non-blank version string.
 * @property metadata Workflow-level identity and documentation fields.
 * @property steps Ordered list of step descriptors.
 * @property environments Environments referenced by steps, keyed by their string id.
 */
@Serializable
data class WorkflowDescriptor(
    val schemaVersion: String = "",
    val metadata: WorkflowMetadataDescriptor,
    val steps: List<StepDescriptor> = emptyList(),
    val environments: Map<String, EnvironmentDescriptor> = emptyMap(),
)
