package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.EnvironmentRef
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.domain.data.FileLocation
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import java.util.UUID as JavaUUID

// A script's own folder is part of what it runs: it may import its neighbours.
private val SCRIPT_EXTENSIONS = setOf("py", "r", "jl", "sh", "js", "ts", "kts")
private const val OUTPUTS = "outputs"
private const val STDOUT = "stdout.log"
private const val ENTRY = "entry.properties"
private const val BYTES_PER_GIGABYTE = 1L shl 30
private const val HEX_RADIX = 16
private const val BYTE_MASK = 0xff
private const val READ_BUFFER_BYTES = 1 shl 16

/**
 * Outputs of earlier steps, kept so a step that would compute the same thing again
 * reuses them instead.
 *
 * A step is the same when its fingerprint is: the command, each argument, the
 * environment it runs in, the contents of every file it reads (its inputs, the
 * files its arguments name, and the folder of a script it runs), and [salt]. The
 * fingerprint never contains a run's own paths, so two workflows that run the same
 * step on the same data share one entry.
 *
 * Outputs are hard links where the file system allows it, so a reused output costs
 * no space. Entries least recently used are removed once the cache holds more than
 * [maxBytes].
 *
 * @param directory Where entries are kept. Created on first use.
 * @param salt Anything outside the step that decides its result and the
 *   fingerprint cannot see, such as the database the run environment points at.
 * @param maxBytes The size the cache is trimmed back to after each store.
 */
class StepCache(
    val directory: Path,
    private val salt: String = "",
    private val maxBytes: Long = DEFAULT_MAX_GIGABYTES * BYTES_PER_GIGABYTE,
)
{
    private val logger = KotlinLogging.logger {}

    // Inputs are hashed once per file version, however many steps read them.
    private val fileHashes = HashMap<Triple<Path, Long, Long>, String>()

    /** An entry found for a step, and where it was first computed. */
    data class Hit(val key: String, val firstRun: String?, val createdAt: String?, val stdout: String?)

    /**
     * Returns the fingerprint of [step] as it would run in [workspace], or null when
     * the step cannot be fingerprinted (it is not a command, or reads a file that is
     * missing).
     */
    fun fingerprint(
        step: PlannedStep,
        workspace: ExecutionWorkspace,
        environment: EnvironmentRef?,
        taskRuntimeJar: Path = TaskRuntime.jar,
    ): String?
    {
        val spec = step.process as? CommandSpec ?: return null
        val root = Path.of(workspace.executionRoot)
        val parts = mutableListOf("v1", "salt:$salt", "exe:${spec.executable}", "env:${signatureOf(environment)}")

        for (arg in spec.args)
        {
            parts += when (arg)
            {
                is ExpandedArg.Literal -> literal(arg.value, root, taskRuntimeJar) ?: return null
                is ExpandedArg.DataReference -> reference(step, arg.id, root) ?: return null
                is ExpandedArg.PathSubstitution ->
                    "sub:${arg.template}:" + (reference(step, arg.id, root) ?: return null)
                is ExpandedArg.EnvironmentVariable -> "envvar:${arg.name}:${arg.template}"
            }
        }

        // An input the command does not name is still read: it is staged where the step expects it.
        // Port ids are minted per import, so the inputs are taken by content, in content order.
        parts += step.bindings.inputs.keys
            .map { id -> "input:" + (reference(step, id, root) ?: return null) }
            .sorted()

        return sha256(parts.joinToString("\n"))
    }

    /** Returns the entry stored under [key], or null when there is none or it is incomplete. */
    fun find(key: String): Hit?
    {
        val entry = directory.resolve(key)
        val properties = entry.resolve(ENTRY)
        if (!properties.isRegularFile()) return null

        val values = properties.readText().lines()
            .mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
        val stdout = entry.resolve(STDOUT).takeIf { it.isRegularFile() }?.readText()
        return Hit(key, values["run"], values["created"], stdout)
    }

    /**
     * Puts the outputs stored under [hit] where [step] would have written them.
     *
     * @return false, leaving nothing half-done that matters, when an output is
     *   missing from the entry; the step then has to run.
     */
    fun restore(hit: Hit, step: PlannedStep, workspace: ExecutionWorkspace): Boolean
    {
        val root = Path.of(workspace.executionRoot)
        val stored = directory.resolve(hit.key).resolve(OUTPUTS)
        val targets = outputFiles(step, root) ?: return false
        if (targets.any { !stored.resolve(it.name).isRegularFile() }) return false

        targets.forEach { target ->
            Files.createDirectories(target.parent)
            Files.deleteIfExists(target)
            link(stored.resolve(target.name), target)
        }
        touch(directory.resolve(hit.key))
        return true
    }

    /**
     * Keeps [step]'s outputs under [key], once its run has succeeded, then trims the
     * cache to its size.
     *
     * Never throws: a cache that cannot be written only means the next run computes again.
     */
    @Suppress("TooGenericExceptionCaught")
    fun store(key: String, step: PlannedStep, workspace: ExecutionWorkspace, runId: String, stdout: String?)
    {
        val entry = directory.resolve(key)
        if (entry.resolve(ENTRY).isRegularFile()) return

        val staging = directory.resolve(".tmp-${JavaUUID.randomUUID()}")
        try
        {
            val root = Path.of(workspace.executionRoot)
            val outputs = outputFiles(step, root) ?: return
            if (outputs.any { !it.isRegularFile() }) return

            Files.createDirectories(staging.resolve(OUTPUTS))
            outputs.forEach { link(it, staging.resolve(OUTPUTS).resolve(it.name)) }
            stdout?.takeIf { it.isNotBlank() }?.let { staging.resolve(STDOUT).writeText(it) }
            staging.resolve(ENTRY).writeText(
                "step=${step.metadata.name}\nrun=$runId\ncreated=${Instant.now()}\n"
            )
            try
            {
                Files.move(staging, entry, StandardCopyOption.ATOMIC_MOVE)
            }
            catch (_: FileAlreadyExistsException)
            {
                // Another run stored the same step first; its entry is as good as ours.
            }
            trim()
        }
        catch (e: Exception)
        {
            // The cache is an optimisation: any failure to store must leave the run unaffected.
            logger.warn(e) { "Could not cache the outputs of '${step.metadata.name}'" }
        }
        finally
        {
            if (staging.exists()) staging.toFile().deleteRecursively()
        }
    }

    /** Removes the least recently used entries until the cache holds at most [maxBytes]. */
    fun trim()
    {
        if (!directory.isDirectory()) return
        val entries = directory.listDirectoryEntries()
            .filter { it.isDirectory() && !it.name.startsWith(".") }
            .map { it to sizeOf(it) }
            .sortedBy { (dir, _) -> Files.getLastModifiedTime(dir).toMillis() }

        var total = entries.sumOf { it.second }
        for ((dir, size) in entries)
        {
            if (total <= maxBytes) break
            dir.toFile().deleteRecursively()
            total -= size
        }
    }

    // What the environment installs, not its id: ids are minted per import.
    private fun signatureOf(environment: EnvironmentRef?): String =
        environment?.let { "${it::class.simpleName}|${it.dependencies.sorted()}|${it.generateSetupCommand()}" } ?: "-"

    private fun literal(value: String, root: Path, taskRuntimeJar: Path): String?
    {
        if (TaskRuntime.TOKEN in value)
        {
            return "runtime:" + (hashOf(taskRuntimeJar) ?: return null)
        }
        val file = fileAt(value, root) ?: return "lit:$value"

        val folder = file.parent.takeIf { file.extension() in SCRIPT_EXTENSIONS }
        val neighbours = folder?.let { dir ->
            Files.walk(dir).use { paths ->
                paths.filter { it.isRegularFile() && "__pycache__" !in it.toString() }
                    .sorted()
                    .map { "${dir.relativize(it)}=${hashOf(it)}" }
                    .toList()
            }.joinToString(",")
        }
        return "file:${file.name}:${hashOf(file)}" + (neighbours?.let { ":dir[$it]" } ?: "")
    }

    private fun reference(step: PlannedStep, id: dk.cachet.carp.common.application.UUID, root: Path): String?
    {
        step.bindings.inputs[id]?.let { input ->
            val location = input.location
            if (location !is FileLocation) return "in:$location"
            val file = root.resolve(location.path).normalize()
            return "in:" + (hashOf(file) ?: return null)
        }
        step.bindings.outputs[id]?.let { output ->
            val location = output.location
            return if (location is FileLocation) "out:${Path.of(location.path).name}" else "out:$location"
        }
        return "ref:$id"
    }

    private fun outputFiles(step: PlannedStep, root: Path): List<Path>? =
        step.bindings.outputs.values.map { output ->
            val location = output.location as? FileLocation ?: return null
            root.resolve(location.path).normalize()
        }

    /** A literal names a file when one exists at that path, absolute or under the run. */
    private fun fileAt(value: String, root: Path): Path? =
        runCatching {
            val path = Path.of(value)
            val file = if (path.isAbsolute) path else root.resolve(path).normalize()
            file.takeIf { value.isNotBlank() && it.isRegularFile() }
        }.getOrNull()

    private fun hashOf(file: Path): String?
    {
        if (!file.isRegularFile()) return null
        val key = Triple(file.toAbsolutePath(), Files.size(file), Files.getLastModifiedTime(file).toMillis())
        return fileHashes.getOrPut(key) {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { stream ->
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (true)
                {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().toHex()
        }
    }

    private fun link(source: Path, target: Path)
    {
        try
        {
            Files.createLink(target, source)
        }
        catch (_: Exception)
        {
            // Another file system, or one without hard links: a copy does the same job.
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun touch(dir: Path) =
        runCatching { Files.setLastModifiedTime(dir, FileTime.from(Instant.now())) }

    private fun sizeOf(dir: Path): Long =
        Files.walk(dir).use { paths -> paths.filter { it.isRegularFile() }.mapToLong { Files.size(it) }.sum() }

    private fun Path.extension(): String = name.substringAfterLast('.', "").lowercase()

    companion object
    {
        /** The size a cache is trimmed to when none is given. */
        const val DEFAULT_MAX_GIGABYTES: Long = 10

        /** Marks a step result whose outputs were reused, in its detail's metrics. */
        const val REUSED_METRIC: String = "cache.reused"

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).toHex()

        private fun ByteArray.toHex(): String =
            joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0') }
    }
}
