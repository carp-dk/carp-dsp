package carp.dsp.steps

import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.RTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.TaskDescriptor
import carp.dsp.core.application.authoring.resolve.LibraryStep
import carp.dsp.core.application.authoring.resolve.StepCertificationFile
import carp.dsp.core.application.authoring.resolve.StepContentHash
import carp.dsp.core.application.authoring.resolve.StepLibrary
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import java.security.MessageDigest

/**
 * The step library, read from this module's `steps/` resources on the
 * classpath.
 *
 * A step id maps to a resource path (`sensing.heartrate.clean` ->
 * `steps/sensing/heartrate/clean/step.yaml`), read through the classloader as a
 * stream. Using streams rather than `File` means it works whether the resources
 * are an exploded directory in development or packed in a jar on a consumer's
 * classpath.
 *
 * The content hash is over the `step.yaml` contract. Versions live in the step's
 * metadata: a `null` requested version returns the bundled step; an explicit
 * version returns it only when it matches.
 */
class ClasspathStepLibrary(
    private val codec: WorkflowYamlCodec = WorkflowYamlCodec(),
) : StepLibrary
{
    override fun lookup( id: String, version: String? ): LibraryStep?
    {
        val path = "steps/" + id.replace( '.', '/' ) + "/step.yaml"
        val yaml = javaClass.classLoader.getResourceAsStream( path )
            ?.bufferedReader()?.use { it.readText() }
            ?: return null

        val descriptor = codec.decodeOrThrow( yaml )
        val step = descriptor.steps.singleOrNull() as? DefinedStepDescriptor ?: return null

        val resolvedVersion = descriptor.metadata.version
        if ( version != null && version != resolvedVersion ) return null

        return LibraryStep(
            version = resolvedVersion,
            step = step,
            environments = descriptor.environments,
            contentHash = certifiedHash( id ) ?: sha256( yaml ),
            implFiles = implFilesOf( id, step.task ),
        )
    }

    /** The content hash published in the step's certification record, if any. */
    private fun certifiedHash( id: String ): String?
    {
        val path = "steps/" + id.replace( '.', '/' ) + "/" + StepContentHash.CERTIFICATION_FILE
        val text = javaClass.classLoader.getResourceAsStream( path )
            ?.bufferedReader()?.use { it.readText() }
            ?: return null
        return StepCertificationFile.parse( text )?.contentHash
    }

    /**
     * The step's implementation entry script, read from the classpath.
     *
     * A jar can't be listed like a directory, so only the declared script is
     * read (single-file impls). Multi-file impls are read in full only by the
     * directory-backed library; that is a known limitation here.
     */
    private fun implFilesOf( id: String, task: TaskDescriptor ): Map<String, String>
    {
        val scriptPath = when ( task )
        {
            is PythonTaskDescriptor -> ( task.entryPoint as? ScriptEntryPointDescriptor )?.scriptPath
            is RTaskDescriptor -> task.entryPoint.scriptPath
            else -> null
        } ?: return emptyMap()

        val resource = "steps/" + id.replace( '.', '/' ) + "/" + scriptPath
        val content = javaClass.classLoader.getResourceAsStream( resource )
            ?.bufferedReader()?.use { it.readText() }
            ?: return emptyMap()
        return mapOf( scriptPath to content )
    }

    private fun sha256( text: String ): String =
        MessageDigest.getInstance( "SHA-256" ).digest( text.toByteArray() )
            .joinToString( "" ) { "%02x".format( it ) }
}
