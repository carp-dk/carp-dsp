package carp.dsp.demo.eval

import carp.dsp.demo.io.DemoIo
import java.io.File

/**
 * Rebuilds an eval figure from its CSV with a script in `scripts/eval`.
 *
 * Prefers the eval pixi environment (`scripts/eval/pixi.toml`, task [pixiTask]) and
 * falls back to the system Python when pixi is not on PATH. An existing [figFile] is
 * deleted first, so a figure on disk always comes from [csvFile]. Call it after the
 * eval has written its results and numbers: a failure then costs only the figure.
 *
 * @throws IllegalStateException when the script is missing, no interpreter is found,
 *   or the script fails to write [figFile].
 */
internal fun rebuildFigure( script: String, pixiTask: String, csvFile: File, figFile: File )
{
    val scriptsDir = DemoIo.projectRoot().resolve( "src/jvmMain/resources/scripts/eval" )
    val scriptFile = scriptsDir.resolve( script )
    // A stale figure must not survive a failed rebuild and be published as current.
    figFile.delete()
    check( scriptFile.exists() ) { "Plot script not found at ${scriptFile.absolutePath}." }

    val scriptArgs = listOf( "--csv", csvFile.absolutePath, "--out", figFile.absolutePath )
    val manifest = scriptsDir.resolve( "pixi.toml" )
    val command = when
    {
        toolAvailable( "pixi" ) && manifest.exists() ->
            listOf( "pixi", "run", "--manifest-path", manifest.absolutePath, pixiTask ) + scriptArgs
        else -> listOf( "python3", "python" ).firstOrNull { toolAvailable( it ) }
            ?.let { python -> listOf( python, scriptFile.absolutePath ) + scriptArgs }
    }
    checkNotNull( command ) {
        "Neither pixi nor a Python interpreter found, so ${figFile.name} was not rebuilt. " +
            "Rebuild manually: python ${scriptFile.absolutePath} ${scriptArgs.joinToString( " " )}"
    }

    println( "Rebuilding figure: ${command.joinToString( " " )}" )
    val process = ProcessBuilder( command ).directory( scriptsDir ).redirectErrorStream( true ).start()
    process.inputStream.bufferedReader().forEachLine { println( it ) }
    val code = process.waitFor()
    check( code == 0 && figFile.exists() ) {
        "Figure rebuild failed (exit code $code), so ${figFile.name} was not written. " +
            "The results and numbers are written; fix the plot environment and rerun the eval."
    }
}

/** True if [exe] responds to --version on this machine. */
@Suppress( "TooGenericExceptionCaught" )
private fun toolAvailable( exe: String ): Boolean =
    try
    {
        ProcessBuilder( exe, "--version" ).redirectErrorStream( true ).start().waitFor() == 0
    }
    catch ( _: Exception )
    {
        // Not installed or not on PATH; the caller falls back to the next option.
        false
    }
