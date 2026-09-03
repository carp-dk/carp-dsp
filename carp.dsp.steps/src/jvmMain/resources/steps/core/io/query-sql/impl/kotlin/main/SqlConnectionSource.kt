@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.sql

import java.io.File
import java.util.Properties

/** Keys a connection file may set, and the only ones it may set. */
object SqlConnectionKeys
{
    const val URL: String = "url"
    const val USER: String = "user"
    const val PASSWORD: String = "password"

    val ALL: Set<String> = setOf(URL, USER, PASSWORD)
}

/** Environment variables a connection is read from. */
object SqlConnectionEnvironment
{
    const val URL: String = "CARP_DSP_SQL_URL"
    const val USER: String = "CARP_DSP_SQL_USER"
    const val PASSWORD: String = "CARP_DSP_SQL_PASSWORD"
}

/**
 * Returns the connection described by [file] and [environment].
 *
 * The file supplies defaults and the environment overrides them field by field,
 * so a url and user can be committed beside a workflow while the password
 * arrives only at run time. Neither source is a command-line argument, which a
 * process listing would show to everyone on the host.
 *
 * @param file A properties file setting `url`, `user` and `password`. Omitted
 *   means the environment is the only source.
 * @throws IllegalArgumentException when [file] does not exist, sets a key that
 *   is not one of [SqlConnectionKeys], or when no url is given at all.
 */
fun sqlConnectionFrom(
    file: File? = null,
    environment: Map<String, String> = System.getenv(),
): SqlConnection
{
    val fromFile = file?.let(::readConnectionFile).orEmpty()

    val url = environment[SqlConnectionEnvironment.URL] ?: fromFile[SqlConnectionKeys.URL]
    require(!url.isNullOrBlank())
    {
        "No database url. Set ${SqlConnectionEnvironment.URL}, or '${SqlConnectionKeys.URL}' " +
            "in a connection file."
    }

    return SqlConnection(
        url = url,
        user = environment[SqlConnectionEnvironment.USER] ?: fromFile[SqlConnectionKeys.USER],
        password = environment[SqlConnectionEnvironment.PASSWORD] ?: fromFile[SqlConnectionKeys.PASSWORD],
    )
}

private fun readConnectionFile(file: File): Map<String, String>
{
    require(file.isFile) { "No connection file at ${file.path}." }

    val properties = Properties()
    file.inputStream().use { properties.load(it) }

    val keys = properties.stringPropertyNames()

    // Unknown keys are taken as config error.
    val unknown = keys - SqlConnectionKeys.ALL
    require(unknown.isEmpty())
    {
        "${unknown.sorted()} in ${file.name} are not connection settings. " +
            "Expected ${SqlConnectionKeys.ALL.sorted()}."
    }

    return keys.associateWith { properties.getProperty(it) }
}
