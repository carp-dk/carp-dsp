package carp.dsp.demo.io

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Shared filesystem and classpath helpers for the demo module.
 *
 * Every demo and eval needs the same handful of operations: find the module root on
 * disk, read a bundled resource, copy one into a workspace, and resolve the
 * `eval_results` / `demo_results` output directories. Before this object each file
 * carried its own private copy - the project-root walk alone existed a dozen times -
 * which is why several of them also needed per-file name prefixes to stay distinct.
 *
 * All paths are resolved relative to the module root, so results land in
 * `carp.dsp.demo/eval_results` and `carp.dsp.demo/demo_results` regardless of the
 * working directory a demo is launched from.
 */
object DemoIo
{
    /**
     * The `carp.dsp.demo` module root on disk.
     *
     * Derived from the location of the compiled classes
     * (`build/classes/kotlin/jvm/main`), walking up five levels. This works when
     * running from a Gradle build directory, which is how the demos and evals are
     * launched; it is not intended for a packaged jar.
     *
     * @throws IllegalStateException when the root cannot be determined.
     */
    fun projectRoot(): File
    {
        val classPath = DemoIo::class.java.protectionDomain.codeSource.location.toURI().path
        return File( classPath ).parentFile?.parentFile?.parentFile?.parentFile?.parentFile
            ?: throw IllegalStateException( "Cannot determine project root" )
    }

    /**
     * Reads a bundled resource as text, e.g. `"workflows/mobgap-gait-analysis.yaml"`.
     *
     * @throws IllegalStateException when the resource is not on the classpath.
     */
    fun loadResource( path: String ): String =
        DemoIo::class.java.classLoader
            .getResource( path )
            ?.readText()
            ?: throw IllegalStateException( "Resource not found: $path" )

    /**
     * Copies a bundled resource to [target], replacing any existing file. Parent
     * directories are created as needed.
     *
     * @throws IllegalStateException when the resource is not on the classpath.
     */
    fun copyResource( resourcePath: String, target: Path )
    {
        val resource = DemoIo::class.java.classLoader.getResource( resourcePath )
            ?: throw IllegalStateException( "Resource not found: $resourcePath" )
        target.parent?.let { Files.createDirectories( it ) }
        resource.openStream().use { stream ->
            Files.copy( stream, target, StandardCopyOption.REPLACE_EXISTING )
        }
    }

    /** `<module>/eval_results`, created if absent. Evaluation harnesses write here. */
    fun evalResultsDir(): File =
        projectRoot().resolve( "eval_results" ).apply { mkdirs() }

    /**
     * `<module>/demo_results/<name>` for a demo's persistent run artifacts.
     *
     * Unlike [evalResultsDir] the directory is not created here, because demos hand
     * the path to the workspace manager which creates it as part of a run.
     */
    fun demoResultsDir( name: String ): File =
        projectRoot().resolve( "demo_results" ).resolve( name )
}
