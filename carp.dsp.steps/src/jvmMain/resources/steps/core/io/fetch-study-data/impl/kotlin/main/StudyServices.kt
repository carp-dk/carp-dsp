package carp.dsp.steps.data

import dk.cachet.carp.common.application.services.createApplicationServiceAdapter
import dk.cachet.carp.common.infrastructure.services.SingleThreadedEventBus
import dk.cachet.carp.data.application.DataStreamService
import dk.cachet.carp.data.infrastructure.InMemoryDataStreamService
import dk.cachet.carp.deployments.application.DeploymentService
import dk.cachet.carp.deployments.application.DeploymentServiceHost
import dk.cachet.carp.deployments.infrastructure.InMemoryDeploymentRepository
import dk.cachet.carp.studies.application.RecruitmentService
import dk.cachet.carp.studies.application.RecruitmentServiceHost
import dk.cachet.carp.studies.application.StudyService
import dk.cachet.carp.studies.application.StudyServiceHost
import dk.cachet.carp.studies.infrastructure.InMemoryParticipantRepository
import dk.cachet.carp.studies.infrastructure.InMemoryStudyRepository

/** The CARP services a study-data fetch reads through. */
interface StudyServices
{
    val dataStreams: DataStreamService
    val recruitment: RecruitmentService
}

/**
 * Creates [StudyServices] by name.
 *
 * Names are the step's `--services` values. Unknown names are refused.
 */
object StudyServicesFactory
{
    const val IN_MEMORY = "in-memory"

    // Needs a base URL and credentials, so
    // its entry will read configuration rather than take no arguments.
    private val factories: Map<String, () -> StudyServices> = mapOf(
        IN_MEMORY to { InMemoryStudyServices() },
    )

    /** Names [create] accepts. */
    val names: Set<String> get() = factories.keys

    /**
     * Returns a fresh [StudyServices] for [name].
     *
     * @throws IllegalArgumentException when [name] is not one of [names].
     */
    fun create(name: String): StudyServices =
        factories[name]?.invoke()
            ?: throw IllegalArgumentException(
                "'$name' is not a services configuration this step knows. Known: ${names.joinToString(", ")}."
            )
}

/**
 * A complete CARP studies stack held in memory, with nothing in it.
 *
 * Deployments created through [recruitment] open their data streams in
 * [dataStreams], so data appended after deployment is what a fetch reads.
 * [studies] is exposed so a caller can create and take a study live.
 */
class InMemoryStudyServices : StudyServices
{
    // The three hosts must share one bus: recruitment only learns of a study
    // through StudyService events, and deployments only exist once a group is
    // invited. Same wiring as carp-core's RecruitmentServiceHostTest.
    private val bus = SingleThreadedEventBus()

    val studies: StudyService = StudyServiceHost(
        InMemoryStudyRepository(),
        bus.createApplicationServiceAdapter(StudyService::class),
    )

    override val dataStreams: DataStreamService = InMemoryDataStreamService()

    private val deployments: DeploymentService = DeploymentServiceHost(
        InMemoryDeploymentRepository(),
        dataStreams,
        bus.createApplicationServiceAdapter(DeploymentService::class),
    )

    override val recruitment: RecruitmentService = RecruitmentServiceHost(
        InMemoryParticipantRepository(),
        deployments,
        bus.createApplicationServiceAdapter(RecruitmentService::class),
    )
}
