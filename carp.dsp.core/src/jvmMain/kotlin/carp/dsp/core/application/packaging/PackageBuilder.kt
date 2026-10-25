package carp.dsp.core.application.packaging

import carp.dsp.core.application.authoring.descriptor.ProtocolInputSource
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.translation.cwl.DspToCwlExporter
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import health.workflows.interfaces.model.CwlTranslationAsset
import health.workflows.interfaces.model.DataSensitivity
import health.workflows.interfaces.model.NativeWorkflowAsset
import health.workflows.interfaces.model.PackageMetadata
import health.workflows.interfaces.model.ProtocolReference
import health.workflows.interfaces.model.ValidationAssets
import health.workflows.interfaces.model.WorkflowArtifactPackage
import health.workflows.interfaces.model.WorkflowFormat
import health.workflows.interfaces.model.WorkflowGranularity
import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest

/**
 * Assembles a [WorkflowArtifactPackage] from a [WorkflowDescriptor].
 *
 * The package id is derived from [WorkflowDescriptor.metadata]:
 * - Uses [carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor.id] if present and non-blank.
 * - Otherwise slugifies [carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor.name] (lowercase, non-alphanumeric → hyphens).
 *
 * [WorkflowArtifactPackage.contentHash] is SHA-256 over [NativeWorkflowAsset.content] (UTF-8) —
 * this matches what the registry server validates on publish.
 */
object PackageBuilder {

    private val codec = WorkflowYamlCodec()

    /**
     * Builds a [WorkflowArtifactPackage] from [descriptor].
     *
     * The CWL asset is one CWL `Workflow` document from [DspToCwlExporter.exportWorkflow];
     * it is null when no step can be translated.
     *
     * @param descriptor The workflow to package.
     * @param execution  Optional execution snapshot as a [JsonElement].
     *                   TODO use `Json.encodeToJsonElement(snapshot)` once RunSnapshot is available (S0).
     * @param validation Optional validation assets (schemas, test inputs).
     */
    fun build(
        descriptor: WorkflowDescriptor,
        execution: JsonElement? = null,
        validation: ValidationAssets? = null,
    ): WorkflowArtifactPackage {
        val content = codec.encode(descriptor)
        val cwl = DspToCwlExporter.exportWorkflow(descriptor)
            ?.let { CwlTranslationAsset(content = it.content, toolVersion = it.toolVersion) }
        return WorkflowArtifactPackage(
            id = descriptor.toPackageId(),
            version = descriptor.metadata.version,
            contentHash = sha256Hex(content),
            metadata = PackageMetadata(
                name = descriptor.metadata.name,
                granularity = WorkflowGranularity.WORKFLOW,
                description = descriptor.metadata.description,
                tags = descriptor.metadata.tags.ifEmpty { null },
                sensitivityClass = DataSensitivity.PUBLIC,
                protocols = descriptor.protocolReferences(),
            ),
            native = NativeWorkflowAsset(
                format = WorkflowFormat.CARP_DSP,
                content = content,
            ),
            cwl = cwl,
            execution = execution,
            validation = validation,
        )
    }
}

/**
 * Returns a list of [ProtocolReference] this workflow draws data from, derived from its protocol-bound inputs.
 */
internal fun WorkflowDescriptor.protocolReferences(): List<ProtocolReference> =
    steps
        .asSequence()
        .flatMap { it.inputs }
        .mapNotNull { it.source as? ProtocolInputSource }
        .groupBy { it.protocol.id to it.protocol.version }
        .map { (key, sources) ->
            val (protocolId, version) = key
            ProtocolReference(
                id = protocolId,
                version = version,
                name = sources.firstNotNullOfOrNull { it.protocol.name },
                dataTypes = sources.map { it.dataType }.distinct().sorted(),
            )
        }
        .sortedWith(compareBy({ it.id }, { it.version }))
        .toList()

/**
 * Derives a stable package id from this descriptor.
 *
 * Prefers [carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor.id] when non-blank; falls back to
 * a slug of [carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor.name].
 */
internal fun WorkflowDescriptor.toPackageId(): String =
    metadata.id?.takeIf { it.isNotBlank() }
        ?: metadata.name
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

private fun sha256Hex(content: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
