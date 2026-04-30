package carp.dsp.core.application.cwl

import carp.dsp.core.application.translation.cwl.DspToCwlExporter
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CWLTOOL_IMAGE = "quay.io/commonwl/cwltool"

/**
 * Integration tests that translate DSP fixture workflows to CWL and validate the output
 * using `cwltool --validate` inside a Docker container.
 *
 * The Docker socket is mounted so cwltool can start up, then --no-container prevents
 * it from actually launching any containers during validation.
 *
 * Requires Docker to be running. Skip with SKIP_INTEGRATION=true.
 * CI: image is pre-pulled in the workflow before tests run.
 */
class CwlValidationIntegrationTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun pullImage() {
            if (System.getenv("SKIP_INTEGRATION") == "true") return
            val exit = try {
                ProcessBuilder("docker", "pull", CWLTOOL_IMAGE).inheritIO().start().waitFor()
            } catch (e: Exception) {
                error("Docker not available — make sure Docker is running. (${e.message})")
            }
            check(exit == 0) { "Failed to pull $CWLTOOL_IMAGE (exit $exit)" }
        }
    }

    private val codec = WorkflowYamlCodec()

    @Test
    fun `single-step Python conda workflow produces valid CWL`() {
        validateFixture("single-step-python-conda.yaml")
    }

    @Test
    fun `single-step R workflow produces valid CWL`() {
        validateFixture("single-step-r.yaml")
    }

    @Test
    fun `single-step Docker workflow produces valid CWL`() {
        validateFixture("single-step-docker.yaml")
    }

    // -- helpers --------------------------------------------------------------

    private fun validateFixture(fixtureName: String) {
        assumeTrue(
            "Skipping CWL validation — SKIP_INTEGRATION=true",
            System.getenv("SKIP_INTEGRATION") != "true"
        )

        val yaml = loadFixtureYaml(fixtureName)
        val descriptor = codec.decodeOrThrow(yaml)
        val assets = DspToCwlExporter.export(descriptor)
        assertTrue(assets.isNotEmpty(), "No CWL assets generated from $fixtureName")

        assets.forEach { asset ->
            val (exit, output) = runCwltoolValidation(asset.content)
            assertEquals(
                0, exit,
                "cwltool --validate failed for step '${asset.stepId}' in $fixtureName:\n$output"
            )
        }
    }

    private fun runCwltoolValidation(cwlContent: String): Pair<Int, String> {
        val pwd = System.getProperty("user.dir").replace("\\", "/")
        val tmpFile = File(System.getProperty("user.dir"), "cwl-validate-tmp-${System.nanoTime()}.cwl")
        return try {
            tmpFile.writeText(cwlContent)
            val process = ProcessBuilder(
                "docker", "run", "--rm",
                "-v", "/var/run/docker.sock:/var/run/docker.sock",
                "-v", "$pwd:/workspace",
                "-w", "/workspace",
                CWLTOOL_IMAGE, "--validate", "--no-container",
                "/workspace/${tmpFile.name}"
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            process.exitValue() to output
        } finally {
            tmpFile.delete()
        }
    }

    private fun loadFixtureYaml(name: String): String =
        javaClass.classLoader
            .getResource("integration-fixtures/$name")
            ?.readText()
            ?: error("Fixture not found: $name")
}
