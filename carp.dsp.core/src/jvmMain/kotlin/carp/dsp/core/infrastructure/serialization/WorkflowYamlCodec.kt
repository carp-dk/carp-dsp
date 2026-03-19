package carp.dsp.core.infrastructure.serialization

import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.serialization.descriptorYaml
import com.charleskorn.kaml.MalformedYamlException
import com.charleskorn.kaml.YamlException
import dk.cachet.carp.analytics.application.exceptions.YamlCodecException
import kotlinx.serialization.SerializationException

// ── Codec result type ─────────────────────────────────────────────────────────

/**
 * Sealed result of a [WorkflowYamlCodec.decode] call.
 *
 * Callers can exhaustively `when`-match to distinguish success from each failure kind
 * without catching exceptions.
 *
 * ```kotlin
 * when (val result = codec.decode(yaml)) {
 *     is DecodeResult.Success        -> use(result.descriptor)
 *     is DecodeResult.MalformedYaml  -> log(result.message)
 *     is DecodeResult.SchemaError    -> report(result.message)
 *     is DecodeResult.PolicyViolation -> handle(result.message)
 * }
 * ```
 */
sealed class DecodeResult
{
    /** Parse succeeded. [descriptor] is ready to use. */
    data class Success( val descriptor: WorkflowDescriptor ) : DecodeResult()

    /** The input is not valid YAML (syntax error, premature EOF, etc.). */
    data class MalformedYaml( val message: String, val cause: Throwable ) : DecodeResult()

    /**
     * The YAML is well-formed but the document doesn't match the [WorkflowDescriptor] schema.
     *
     * Typical causes:
     * - Unknown `type` discriminator on a sealed subtype.
     * - Missing required field.
     * - Type mismatch (e.g. number where string expected).
     */
    data class SchemaError( val message: String, val cause: Throwable ) : DecodeResult()

    /**
     * The document is structurally valid but violates a codec policy.
     *
     * Reserved for future policy enforcement — not currently raised by [WorkflowYamlCodec].
     */
    @Suppress("unused")
    data class PolicyViolation( val message: String ) : DecodeResult()
}

// ── Codec ─────────────────────────────────────────────────────────────────────

/**
 * Infrastructure codec for converting between YAML text and [WorkflowDescriptor].
 *
 * ### Decisions
 *
 * | Concern | Decision |
 * |---|---|
 * | Unknown fields | **Ignored** — `strictMode = false` in [descriptorYaml]. Allows forward-compatible authored documents. |
 * | Missing `schemaVersion` | **Defaulted to `"1.0"`**. Lenient read: authored documents need not declare the version. |
 * | Null IDs on decode | **Allowed**. The importer layer (not this codec) is responsible for generating missing UUIDs. |
 * | Unknown `type` discriminator | **Fails with [DecodeResult.SchemaError]**. Depending on which sealed hierarchy is involved, kaml/kotlinx.serialization throws [SerializationException] (top-level task type) or [IllegalArgumentException] (`"Can't get known types for descriptor of kind CLASS"`, nested types such as entry points); both are caught and mapped to [DecodeResult.SchemaError]. |
 * | Malformed YAML | **Fails with [DecodeResult.MalformedYaml]**. |
 * | Encoding defaults | **Suppressed** — `encodeDefaults = false`. Keeps emitted YAML minimal. |
 *
 * ### Architectural note
 *
 * This class deliberately lives in `infrastructure.serialization`, not in `application`.
 * Core domain and descriptor DTOs have zero knowledge of YAML or kaml — this is the only
 * layer that touches the serialization library.
 */
class WorkflowYamlCodec
{
    // ── Decode ────────────────────────────────────────────────────────────────

    /**
     * Parses [yaml] text into a [DecodeResult].
     *
     * Never throws. Callers exhaustively `when`-match on the returned [DecodeResult].
     *
     * ### `schemaVersion` defaulting
     *
     * `WorkflowDescriptor.schemaVersion` is declared as `String = ""` so that kaml treats
     * the field as optional — an absent key decodes to `""` instead of throwing.
     * After parsing, [applyDecodeDefaults] replaces any blank value (absent → `""`,
     * or an explicit empty string `schemaVersion: ""`) with `"1.0"`.
     * Callers therefore always receive a non-blank version in [DecodeResult.Success].
     */
    fun decode( yaml: String ): DecodeResult =
        try
        {
            val descriptor = descriptorYaml.decodeFromString( WorkflowDescriptor.serializer(), yaml )
            DecodeResult.Success( applyDecodeDefaults( descriptor ) )
        }
        catch ( cause: MalformedYamlException )
        {
            DecodeResult.MalformedYaml( "Malformed YAML: ${cause.message}", cause )
        }
        catch ( cause: YamlException )
        {
            DecodeResult.SchemaError( "YAML schema error: ${cause.message}", cause )
        }
        catch ( cause: SerializationException )
        {
            // kotlinx.serialization throws this directly (not wrapped in YamlException) when
            // a polymorphic discriminator value has no registered serializer — e.g. an unknown
            // task type such as `type: "alien-task-type"`.
            DecodeResult.SchemaError( "Schema error: ${cause.message}", cause )
        }
        catch ( cause: IllegalArgumentException )
        {
            // kaml throws IllegalArgumentException with "Can't get known types for descriptor of
            // kind CLASS" when an unknown discriminator value is encountered for certain sealed
            // hierarchies (e.g. an unknown PythonEntryPointDescriptor subtype).
            DecodeResult.SchemaError( "Schema error (unknown type): ${cause.message}", cause )
        }

    /**
     * Parses [yaml] text into a [WorkflowDescriptor], throwing on any error.
     *
     * Prefer [decode] for production call-sites where exhaustive error handling is required.
     * This overload is provided for test convenience and scripting contexts.
     *
     * @throws YamlCodecException wrapping the underlying parse failure.
     */
    fun decodeOrThrow( yaml: String ): WorkflowDescriptor =
        when ( val result = decode( yaml ) )
        {
            is DecodeResult.Success -> result.descriptor
            is DecodeResult.MalformedYaml -> throw YamlCodecException(result.message, result.cause)
            is DecodeResult.SchemaError -> throw YamlCodecException( result.message, result.cause )
            is DecodeResult.PolicyViolation -> throw YamlCodecException( result.message )
        }

    // ── Encode ────────────────────────────────────────────────────────────────

    /**
     * Encodes [descriptor] to a canonical YAML string.
     *
     * Ordering guarantees:
     * - Steps are emitted in list order (insertion order from the exporter).
     * - Environments are emitted in key-sorted order (guaranteed by the exporter).
     * - Fields within each object follow the `data class` declaration order.
     *
     * Optional fields at their default values are suppressed (`encodeDefaults = false`),
     * keeping the emitted YAML minimal and author-readable.
     *
     * @throws YamlCodecException if serialization fails for any reason.
     */
    fun encode( descriptor: WorkflowDescriptor ): String =
        try
        {
            descriptorYaml.encodeToString( WorkflowDescriptor.serializer(), descriptor )
        }
        catch ( cause: YamlException )
        {
            throw YamlCodecException( "Failed to encode WorkflowDescriptor to YAML: ${cause.message}", cause )
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun applyDecodeDefaults( descriptor: WorkflowDescriptor ): WorkflowDescriptor =
        if ( descriptor.schemaVersion.isBlank() )
            descriptor.copy( schemaVersion = DEFAULT_SCHEMA_VERSION )
        else
            descriptor

    companion object
    {
        /** Default schema version applied when `schemaVersion` is absent or blank. */
        const val DEFAULT_SCHEMA_VERSION: String = "1.0"
    }
}
