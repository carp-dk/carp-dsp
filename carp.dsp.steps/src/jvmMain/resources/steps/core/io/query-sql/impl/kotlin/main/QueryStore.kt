@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.sql

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Where a store is kept when the environment names none. */
const val QUERY_STORE_VARIABLE: String = "CARP_DSP_QUERY_STORE"

private const val HEX_RADIX = 16
private const val BYTE_MASK = 0xff

/**
 * What earlier runs of one windowed query fetched, so the next run asks the
 * database only for rows that arrived since.
 *
 * A query is the same when its statement, parameters, window start and database
 * are. Each run fetches `[previous end, end)`, appends the rows, and writes the
 * whole store as the result, so the result is what one read of `[start, end)`
 * would return, row order aside: rows come in the order they were fetched.
 *
 * A window end earlier than the stored one, or a result whose columns changed,
 * starts the store again with one full read.
 *
 * Runs of the same query take turns, in one process or several, so two
 * workflows can share a store.
 *
 * @param directory Where stores are kept, one CSV and one properties file per query.
 */
class QueryStore(private val directory: File)
{
    /** The rows written, and how many of them this run fetched. */
    data class Result(val rows: Long, val fetched: Long)

    /**
     * Writes everything fetched for [config]'s query up to its window end to its output.
     *
     * @param query Builds the statement for a window that starts at the given instant.
     * @throws IllegalArgumentException when [config] has no window end.
     * @throws IllegalStateException when the store holds no rows and the query requires rows.
     */
    fun run(step: QuerySqlStep, target: SqlConnection, config: QuerySqlConfig, query: (Instant?) -> SqlQuery): Result
    {
        val end = requireNotNull(config.to)
        {
            "--incremental needs --to: a run fetches up to it, and the next run starts from it."
        }
        directory.mkdirs()
        val key = keyOf(target, config)
        // Two runs at once - two schedules, or a schedule and a run by hand - would
        // both read the same state and append overlapping rows. The monitor covers
        // threads of this process; the file lock, other processes.
        return synchronized(monitors.computeIfAbsent(key) { Any() })
        {
            FileChannel.open(File(directory, "$key.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                .use { channel -> channel.lock().use { locked(step, target, config, query, key, end) } }
        }
    }

    private fun locked(
        step: QuerySqlStep,
        target: SqlConnection,
        config: QuerySqlConfig,
        query: (Instant?) -> SqlQuery,
        key: String,
        end: Instant,
    ): Result
    {
        val rows = File(directory, "$key.csv")
        val state = File(directory, "$key.properties")
        val previous = readState(state)?.takeIf { rows.isFile }

        val fetched: Long
        val total: Long
        when
        {
            previous != null && previous.end == end ->
            {
                fetched = 0
                total = previous.rows
            }
            previous != null && previous.end < end ->
            {
                val delta = temporary(rows)
                try
                {
                    fetched = step.run(target, query(previous.end).copy(requireRows = false), delta)
                    if (fetched > 0 && !sameHeader(rows, delta)) return fullRead(step, target, config, query, rows, state, end)
                    if (fetched > 0) appendBody(rows, delta)
                    total = previous.rows + fetched
                }
                finally
                {
                    delta.delete()
                }
            }
            else -> return fullRead(step, target, config, query, rows, state, end)
        }

        writeState(state, end, total)
        return finish(config, rows, Result(total, fetched))
    }

    private fun fullRead(
        step: QuerySqlStep,
        target: SqlConnection,
        config: QuerySqlConfig,
        query: (Instant?) -> SqlQuery,
        rows: File,
        state: File,
        end: Instant,
    ): Result
    {
        val total = step.run(target, query(config.from).copy(requireRows = false), rows)
        writeState(state, end, total)
        return finish(config, rows, Result(total, total))
    }

    private fun finish(config: QuerySqlConfig, rows: File, result: Result): Result
    {
        check(result.rows > 0 || !config.requireRows)
        {
            "The query returned no rows. Check its filters and parameters: an empty table " +
                "downstream is harder to notice than a failure here."
        }
        val directory = config.output.absoluteFile.parentFile.apply { mkdirs() }
        val partial = File(directory, "${config.output.name}.${System.nanoTime()}.partial")
        try
        {
            // The store file is never changed in place - an append writes a new one -
            // so the result can be a hard link to it and cost no space.
            runCatching { Files.createLink(partial.toPath(), rows.toPath()) }
                .getOrElse { Files.copy(rows.toPath(), partial.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            Files.move(partial.toPath(), config.output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        finally
        {
            partial.delete()
        }
        return result
    }

    /** Appends [delta]'s rows, without its header, through a copy moved into place. */
    private fun appendBody(rows: File, delta: File)
    {
        val merged = temporary(rows)
        try
        {
            Files.copy(rows.toPath(), merged.toPath(), StandardCopyOption.REPLACE_EXISTING)
            FileOutputStream(merged, true).buffered().use { out ->
                delta.inputStream().buffered().use { input ->
                    // The header is one line: column names hold no line breaks.
                    while (true)
                    {
                        val byte = input.read()
                        if (byte < 0 || byte == '\n'.code) break
                    }
                    input.copyTo(out)
                }
            }
            Files.move(merged.toPath(), rows.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        finally
        {
            merged.delete()
        }
    }

    private fun sameHeader(rows: File, delta: File): Boolean =
        rows.bufferedReader().use { it.readLine() } == delta.bufferedReader().use { it.readLine() }

    private fun temporary(beside: File): File = File.createTempFile(beside.name, ".tmp", beside.absoluteFile.parentFile)

    private data class State(val end: Instant, val rows: Long)

    private fun readState(file: File): State? = runCatching {
        val values = file.readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        State(Instant.parse(values.getValue("end")), values.getValue("rows").toLong())
    }.getOrNull()

    private fun writeState(file: File, end: Instant, rows: Long)
    {
        val partial = temporary(file)
        partial.writeText("end=$end\nrows=$rows\n")
        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun keyOf(target: SqlConnection, config: QuerySqlConfig): String
    {
        val text = listOf(
            target.url,
            target.user.orEmpty(),
            config.queryFile.readText(),
            config.params.toSortedMap().toString(),
            config.from?.toString().orEmpty(),
        ).joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0') }
    }

    companion object
    {
        private val monitors = ConcurrentHashMap<String, Any>()

        /** The store named by [QUERY_STORE_VARIABLE], or `.carp-dsp/query-store` in `$HOME`. */
        fun from(environment: Map<String, String>): QueryStore = QueryStore(
            environment[QUERY_STORE_VARIABLE]?.takeIf { it.isNotBlank() }?.let(::File)
                // $HOME, not user.home: a container may set the one and not the other.
                ?: File(environment["HOME"] ?: System.getProperty("user.home"), ".carp-dsp/query-store")
        )
    }
}
