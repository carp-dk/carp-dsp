package carp.dsp.core.application.authoring.resolve

import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import java.io.File

/**
 * A [StepLibrary] backed by a directory of published steps on disk.
 *
 * A step id maps to a directory path under [root] (`sensing.heartrate.clean` ->
 * `sensing/heartrate/clean/`), holding a `step.yaml` that is a single-step
 * workflow. This is the vendored-library case: point [root] at the resolved
 * `steps/` resource directory.
 *
 * Versions live in the step's metadata, not the path, so one `step.yaml` is the
 * latest (and only) version on disk. A `null` requested version returns it; an
 * explicit version returns it only when it matches.
 */
class DirectoryStepLibrary(
    private val root: File,
    private val codec: WorkflowYamlCodec = WorkflowYamlCodec(),
) : StepLibrary
{
    override fun lookup( id: String, version: String? ): LibraryStep?
    {
        val dir = id.split( '.' ).fold( root ) { dir, part -> dir.resolve( part ) }
        val file = dir.resolve( "step.yaml" )
        if ( !file.isFile ) return null

        val descriptor = codec.decodeOrThrow( file.readText() )
        val step = descriptor.steps.singleOrNull() as? DefinedStepDescriptor ?: return null

        val resolvedVersion = descriptor.metadata.version
        if ( version != null && version != resolvedVersion ) return null

        return LibraryStep(
            version = resolvedVersion,
            step = step,
            environments = descriptor.environments,
            contentHash = StepCertificationFile.read( dir )?.contentHash ?: StepContentHash.of( dir ),
            implFiles = implFilesOf( dir ),
        )
    }

    /** Every file under the step's `impl/` tree, keyed by its path relative to the step dir. */
    private fun implFilesOf( dir: File ): Map<String, String>
    {
        val implDir = dir.resolve( "impl" )
        if ( !implDir.isDirectory ) return emptyMap()
        return implDir.walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo( dir ).invariantSeparatorsPath to it.readText() }
    }
}
