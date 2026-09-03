package carp.dsp.steps.data

import carp.dsp.core.application.StudyDataReader
import carp.dsp.core.domain.data.CarpTabularCsv
import java.io.File


/**
 * Read a study's data, write it as a table.
 *
 * @param reader Read and convert study data.
 * @param readFile Read inputFile.
 * @param writeFile Writes output file.
 */
class StudyDataStep(
    private val reader: StudyDataReader,
    private val readFile: (String) -> List<String> = { File(it).readLines() },
    private val writeFile: (String, String) -> Unit = { path, text ->
        File(path).apply { parentFile?.mkdirs() }.writeText(text)
    },
)
{
    /**
     * Runs the step, writing the measurement table and, when configured, the
     * provenance table beside it.
     *
     * @return The paths to data written.
     * @throws IllegalStateException when the study returned nothing.
     */
    suspend fun run(config: StudyDataStepConfig): List<String>
    {
        val fromFile = config.targetsFile?.let { parseTargets(readFile(it)) }.orEmpty()
        val request = config.request.copy(targets = config.request.targets + fromFile)

        val data = reader.read(request)

        check(!data.isEmpty)
        {
            "Study '${request.studyId}' returned no measurements for this request. " +
                "Check the deployments, data types and time window: " +
                "${request.targets.size} target(s), " +
                "types ${request.dataTypes.ifEmpty { setOf("(all)") }}, " +
                "window ${request.fromMs ?: "(open)"}..${request.toMs ?: "(open)"}."
        }

        val written = mutableListOf<String>()
        when (config.format)
        {
            StudyDataFormat.CSV ->
            {
                writeFile(config.outputPath, CarpTabularCsv.measurements(data, config.columns))
                written += config.outputPath

                config.provenancePath?.let {
                    writeFile(it, CarpTabularCsv.provenance(data))
                    written += it
                }
            }
        }
        return written
    }

    companion object
    {
        /** Returns a step that fetches through [services]. */
        fun over(
            services: StudyServices,
            readFile: (String) -> List<String> = { File(it).readLines() },
            writeFile: (String, String) -> Unit = { path, text ->
                File(path).apply { parentFile?.mkdirs() }.writeText(text)
            },
        ): StudyDataStep = StudyDataStep(
            reader = StudyDataReader(
                source = CarpStudyDataSource(services.dataStreams),
                deployments = CarpStudyDeploymentSource(services.recruitment),
            ),
            readFile = readFile,
            writeFile = writeFile,
        )
    }
}
