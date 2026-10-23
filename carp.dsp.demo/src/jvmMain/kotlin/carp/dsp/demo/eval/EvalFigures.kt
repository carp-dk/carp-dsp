package carp.dsp.demo.eval

import carp.dsp.demo.io.DemoIo
import java.io.File

/**
 * Rebuilds an eval figure from its CSV with a script in `scripts/eval`.
 *
 * Prefers the eval pixi environment (`scripts/eval/pixi.toml`, task [pixiTask]) and
 * falls back to the system Python when pixi is not on PATH. A failed or skipped
 * rebuild is reported but never fails the eval, since the CSV is the result.
 *
 * @return true when the script ran and [figFile] exists.
 */
internal fun rebuildFigure( script: String, pixiTask: String, csvFile: File, figFile: File ): Boolean
{
    val scriptsDir = DemoIo.projectRoot().resolve( "src/jvmMain/resources/scripts/eval" )
    val scriptFile = scriptsDir.resolve( script )
    if ( !scriptFile.exists() )
    {
        System.err.println( "Plot script not found at ${scriptFile.absolutePath} - skipping figure." )
        return false
    }

    val scriptArgs = listOf( "--csv", csvFile.absolutePath, "--out", figFile.absolutePath )
    val manifest = scriptsDir.resolve( "pixi.toml" )
    val command = when
    {
        toolAvailable( "pixi" ) && manifest.exists() ->
            listOf( "pixi", "run", "--manifest-path", manifest.absolutePath, pixiTask ) + scriptArgs
        else -> listOf( "python3", "python" ).firstOrNull { toolAvailable( it ) }
            ?.let { python -> listOf( python, scriptFile.absolutePath ) + scriptArgs }
    }
    if ( command == null )
    {
        System.err.println(
            "Neither pixi nor a Python interpreter found - skipping figure. " +
                "Rebuild manually: python ${scriptFile.absolutePath} ${scriptArgs.joinToString( " " )}"
        )
        return false
    }

    println( "Rebuilding figure: ${command.joinToString( " " )}" )
    val process = ProcessBuilder( command ).directory( scriptsDir ).redirectErrorStream( true ).start()
    process.inputStream.bufferedReader().forEachLine { println( it ) }
    val code = process.waitFor()
    if ( code != 0 ) System.err.println( "Figure rebuild exited with code $code (results CSV is unaffected)." )
    return code == 0 && figFile.exists()
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
