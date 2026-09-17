package carp.dsp.core.infrastructure.execution.handlers

import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.EnvironmentRef
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import dk.cachet.carp.analytics.application.plan.REnvironmentRef
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val DIGEST_CHARS = 16
private const val SLUG_CHARS = 32
private const val MANIFEST = "carp-env.json"

/** Outcome of resolving a requested environment. */
enum class EnvironmentResolution { BUILT, EXACT, SUPERSET }

/** Policy controlling when an existing environment may be reused. */
enum class ReusePolicy { EXACT, ALLOW_SUPERSET }

/**
 * Environment selected to satisfy a workflow's request.
 *
 * For superset reuse, [extras] lists requirements present in the selected
 * environment but not requested by the workflow.
 */
data class ResolvedEnvironment(
    val directory: Path,
    val match: EnvironmentResolution,
    val extras: List<String> = emptyList(),
)

/**
 * Stores and locates provisioned environments.
 *
 * Environments are keyed by a digest derived from their effective definition
 * rather than their name or identifier, allowing environments to be reused
 * when their dependencies are unchanged while ensuring dependency changes
 * produce distinct entries.
 *
 * Each environment directory contains a manifest storing the corresponding
 * [EnvironmentRef], which is used for discovery and reuse decisions.
 */
object EnvironmentStore {

    private val root: Path = Path.of(System.getProperty("user.home"), ".carp-dsp", "envs")

    /**
     * `encodeDefaults` matters here. A manifest is a durable record of what an
     * environment is, and by default kotlinx omits any field equal to its class
     * default - so `pythonVersion = "3.12"` vanished simply because that is the
     * default today. An old manifest would then decode with whatever the default
     * became, quietly changing what a stored environment claims to be.
     */
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** `DSP_ENV_REUSE=superset` opts in. Deployment-level, and switchable at runtime. */
    @Volatile
    var reuse: ReusePolicy =
        if (System.getenv("DSP_ENV_REUSE")?.equals("superset", ignoreCase = true) == true) {
            ReusePolicy.ALLOW_SUPERSET
        } else {
            ReusePolicy.EXACT
        }

    /**
     * Determines how [ref] will be satisfied before provisioning.
     *
     * Returns if an existing environment will be reused, a compatible
     * superset will be used, or a new environment must be created.
     */
    fun resolve(ref: EnvironmentRef): ResolvedEnvironment? {
        val wanted = signatureOf(ref) ?: return null
        val exact = directoryFor(wanted)

        if (manifestOf(exact) != null) {
            return ResolvedEnvironment(exact, EnvironmentResolution.EXACT)
        }

        if (reuse == ReusePolicy.ALLOW_SUPERSET) {
            // The smallest superset, so a run gets as little it did not ask for
            // as possible.
            val best = candidates(wanted.kind)
                .mapNotNull { (dir, candidate) -> signatureOf(candidate)?.let { dir to it } }
                .filter { (_, candidate) -> candidate.satisfies(wanted) }
                .minByOrNull { (_, candidate) -> candidate.extrasOver(wanted).size }

            if (best != null) {
                return ResolvedEnvironment(best.first, EnvironmentResolution.SUPERSET, best.second.extrasOver(wanted))
            }
        }

        return ResolvedEnvironment(exact, EnvironmentResolution.BUILT)
    }

    /** Called once the environment is in place, so it can be found again. */
    fun writeManifest(directory: Path, ref: EnvironmentRef) {
        directory.createDirectories()
        directory.resolve(MANIFEST).writeText(json.encodeToString(EnvironmentRef.serializer(), ref))
    }

    /** Every provisioned environment of a kind. What an environments view lists. */
    fun list(kind: String): List<Pair<Path, EnvironmentRef>> = candidates(kind)

    private fun candidates(kind: String): List<Pair<Path, EnvironmentRef>> {
        val kindRoot = root.resolve(kind)
        if (!kindRoot.isDirectory()) return emptyList()

        return kindRoot.listDirectoryEntries()
            .filter { it.isDirectory() }
            .mapNotNull { dir -> manifestOf(dir)?.let { dir to it } }
    }

    private fun manifestOf(directory: Path): EnvironmentRef? {
        val file = directory.resolve(MANIFEST)
        if (!file.isRegularFile()) return null
        return runCatching { json.decodeFromString(EnvironmentRef.serializer(), file.readText()) }.getOrNull()
    }

    // ── Signature  ───────────────────────────────────────────────────────────

    /**
     * Comparable representation of an environment used for matching,
     * reuse decisions, and directory naming.
     *
     * [pinned] values must match exactly.
     * [required] values may be a subset when superset reuse is enabled.
     */
    private data class EnvironmentSignature(
        val kind: String,
        val name: String,
        val pinned: Map<String, String>,
        val required: Map<String, List<String>>,
    ) {
        fun satisfies(wanted: EnvironmentSignature): Boolean =
            kind == wanted.kind &&
                pinned == wanted.pinned &&
                wanted.required.all { (label, values) -> required[label].orEmpty().containsAll(values) }

        fun extrasOver(wanted: EnvironmentSignature): List<String> =
            required.flatMap { (label, values) -> values - wanted.required[label].orEmpty().toSet() }.sorted()

        /**
         * Everything that changes what gets installed, sorted so declaration
         * order cannot change it. The name is not in it: two environments called
         * the same thing with different dependencies are different environments.
         */
        fun canonical(): String =
            (
                pinned.toSortedMap().map { (label, value) -> "pin:$label=$value" } +
                    required.toSortedMap().flatMap { (label, values) ->
                        values.sorted().map { "req:$label=$it" }
                    }
                ).joinToString("\n")
    }

    /**
     * Function that maps fields of environments to their identity signatures.
     * The kind is the primary identifier, and the rest of the fields are
     * used to create a unique signature for each environment.
     *
     * Null where there is nothing to provision - a system environment is
     * whatever the machine already has, so there is no directory to share.
     */
    private fun signatureOf(ref: EnvironmentRef): EnvironmentSignature? = when (ref) {
        is PixiEnvironmentRef -> EnvironmentSignature(
            kind = "pixi",
            name = ref.name,
            // A different interpreter is a different environment, whatever else matches.
            pinned = mapOf("python" to ref.pythonVersion),
            required = mapOf("channel" to ref.channels, "dep" to ref.dependencies),
        )

        is CondaEnvironmentRef -> EnvironmentSignature(
            kind = "conda",
            name = ref.name,
            pinned = mapOf("python" to ref.pythonVersion),
            required = mapOf("channel" to ref.channels, "dep" to ref.dependencies),
        )

        is REnvironmentRef -> EnvironmentSignature(
            kind = "r",
            name = ref.name,
            // The R version and a pinned lock file are both identity, not preference.
            pinned = buildMap {
                put("r", ref.rVersion)
                ref.renvLockFile?.let { put("lock", it) }
            },
            required = mapOf("pkg" to ref.rPackages, "dep" to ref.dependencies),
        )

        else -> null
    }

    private fun directoryFor(identity: EnvironmentSignature): Path =
        root.resolve(identity.kind).resolve("${slug(identity.name)}-${digest(identity)}")

    private fun digest(identity: EnvironmentSignature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(identity.canonical().encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(DIGEST_CHARS)

    /** Directory-safe, and short enough to read. */
    private fun slug(name: String): String =
        name.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .take(SLUG_CHARS)
            .ifEmpty { "env" }
}
