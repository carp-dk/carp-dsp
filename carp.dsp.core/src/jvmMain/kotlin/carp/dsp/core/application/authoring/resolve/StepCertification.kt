package carp.dsp.core.application.authoring.resolve

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/**
 * The certification record published alongside a library step.
 *
 * Records the outcome of the contribution review: what was reviewed, at which
 * version, to what level, and the hash of the content that was reviewed. The
 * hash is what a consumer's `steps.lock` pins against, so it is published with
 * the step rather than recomputed differently by each consumer.
 *
 * @property id Step id, matching the step's directory and `metadata.id`.
 * @property version Step version the record applies to.
 * @property level Review level reached, e.g. `reviewed`.
 * @property contentHash Canonical hash of the step's published content; see
 *   [StepContentHash]. `null` on a step whose record has not been generated yet.
 * @property reviewedOn ISO date the review completed, when a human reviewed it.
 * @property reviewer Who performed the review, as a GitHub handle.
 * @property reviewedPr URL of the pull request whose approval constituted the
 *   review, so the claim is traceable to a record outside this file.
 * @property reviewedHash The content hash **at the time of review**. Kept apart
 *   from [contentHash] on purpose: a step edited after review would otherwise
 *   have its hash refreshed by `certifySteps` while keeping the reviewer's name,
 *   silently extending a human's approval to bytes they never read. When the two
 *   differ, the step has changed since it was reviewed and the gate says so.
 */
@Serializable
data class CertificationRecord(
    val id: String,
    val version: String,
    val level: String = CertificationLevel.GATED,
    val contentHash: String? = null,
    val reviewedOn: String? = null,
    val reviewer: String? = null,
    val reviewedPr: String? = null,
    val reviewedHash: String? = null,
)
{
    /** True when a human approved exactly the content that ships today. */
    val isReviewCurrent: Boolean
        get() = level == CertificationLevel.REVIEWED &&
            reviewedHash != null && reviewedHash == contentHash
}

/**
 * What a step's certification asserts.
 *
 * Two levels rather than one, because the automated gate is a real and
 * defensible claim on its own. With a single value, a step that passes every
 * machine check but has not been read by anyone has no honest label.
 */
object CertificationLevel
{
    /** Passes the automated conformance gate. True of every published step. */
    const val GATED = "gated"

    /** Gated, and a named person approved the pull request that last changed it. */
    const val REVIEWED = "reviewed"

    val ALL = setOf( GATED, REVIEWED )
}

/**
 * The canonical content hash of a library step.
 *
 * SHA-256 over every file the step publishes - contract, implementations,
 * fixtures and documentation - so any change to what the step ships changes the
 * hash. Files contribute their path then their bytes, ordered by path, so the
 * digest is stable across platforms.
 *
 * `certification.yaml` is deliberately **excluded**: it is where the resulting
 * hash is recorded, so including it would make the hash depend on itself.
 *
 * A step directory can only be walked when the library is on disk. A step read
 * from a jar cannot be listed, which is why the hash is published in the
 * certification record rather than recomputed by every consumer - both library
 * implementations then agree on one value.
 */
object StepContentHash
{
    /** Name of the file that records the hash, and is therefore not part of it. */
    const val CERTIFICATION_FILE = "certification.yaml"

    /**
     * Artefacts left behind by running a step's tests or implementations.
     *
     * Excluded deliberately: they are produced by working with the step rather
     * than published as part of it, they differ by interpreter version, and
     * counting them would mean running the tests invalidated the certification
     * they were run to support.
     */
    private val TRANSIENT = setOf( "__pycache__", ".pytest_cache", ".ruff_cache", ".ipynb_checkpoints" )

    private fun File.isPublished( root: File ): Boolean
    {
        if ( !isFile || name == CERTIFICATION_FILE ) return false
        if ( extension in setOf( "pyc", "pyo" ) ) return false
        return relativeTo( root ).invariantSeparatorsPath.split( '/' ).none { it in TRANSIENT }
    }

    /** Computes the canonical hash over the published content of the step at [dir]. */
    fun of( dir: File ): String
    {
        val digest = MessageDigest.getInstance( "SHA-256" )
        dir.walkTopDown()
            .filter { it.isPublished( dir ) }
            .sortedBy { it.relativeTo( dir ).invariantSeparatorsPath }
            .forEach { file ->
                digest.update( file.relativeTo( dir ).invariantSeparatorsPath.toByteArray() )
                digest.update( file.readBytes() )
            }
        return digest.digest().joinToString( "" ) { "%02x".format( it ) }
    }
}

/** Reads and writes [CertificationRecord]s. */
object StepCertificationFile
{
    private val yaml = Yaml(
        configuration = YamlConfiguration( strictMode = false, encodeDefaults = true ),
    )

    /** Parses [text] as a certification record, or `null` when it does not parse. */
    fun parse( text: String ): CertificationRecord? =
        runCatching { yaml.decodeFromString( CertificationRecord.serializer(), text ) }.getOrNull()

    /** Reads the certification record from a step directory, if present. */
    fun read( dir: File ): CertificationRecord?
    {
        val file = dir.resolve( StepContentHash.CERTIFICATION_FILE )
        return if ( file.isFile ) parse( file.readText() ) else null
    }

    /** Writes [record] into the step directory at [dir]. */
    fun write( dir: File, record: CertificationRecord )
    {
        val file = dir.resolve( StepContentHash.CERTIFICATION_FILE )
        file.writeText( yaml.encodeToString( CertificationRecord.serializer(), record ) + "\n" )
    }
}
