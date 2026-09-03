package carp.dsp.steps.data

import dk.cachet.carp.common.application.EmailAddress
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.devices.Smartphone
import dk.cachet.carp.common.application.users.AssignedTo
import dk.cachet.carp.protocols.domain.StudyProtocol
import dk.cachet.carp.studies.application.users.AssignedParticipantRoles
import dk.cachet.carp.studies.application.users.ParticipantGroupStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [CarpStudyDeploymentSource] against carp-core's own studies stack, in memory.
 */
class CarpStudyDeploymentSourceTest {

    private class Stack {
        private val services = InMemoryStudyServices()
        private val studies get() = services.studies
        private val recruitment get() = services.recruitment

        val source = CarpStudyDeploymentSource(recruitment)

        /** A study that is live, so participant groups can be deployed into it. */
        suspend fun liveStudy(): UUID {
            // One primary device is the least a protocol needs to be deployable.
            val protocol = StudyProtocol(UUID.randomUUID(), "Test protocol")
            protocol.addPrimaryDevice(Smartphone("phone"))

            val studyId = studies.createStudy(UUID.randomUUID(), "Test").studyId
            studies.setProtocol(studyId, protocol.getSnapshot())
            studies.goLive(studyId)
            return studyId
        }

        private suspend fun roles(studyId: UUID): Set<AssignedParticipantRoles> {
            val participant = recruitment.addParticipant(studyId, EmailAddress("p${UUID.randomUUID()}@test.com"))
            return setOf(AssignedParticipantRoles(participant.id, AssignedTo.All))
        }

        /** Creates a group and invites it, which deploys it. */
        suspend fun deployedGroup(studyId: UUID): ParticipantGroupStatus {
            val group = recruitment.createParticipantGroup(UUID.randomUUID(), roles(studyId), studyId)
            return recruitment.inviteParticipantGroup(group.id)
        }

        /** Creates a group but never invites it, so it has no deployment. */
        suspend fun stagedGroup(studyId: UUID): ParticipantGroupStatus =
            recruitment.createParticipantGroup(UUID.randomUUID(), roles(studyId), studyId)
    }

    @Test
    fun `every deployed group's deployment is returned`() = runTest {
        val stack = Stack()
        val studyId = stack.liveStudy()
        val first = stack.deployedGroup(studyId)
        val second = stack.deployedGroup(studyId)

        val found = stack.source.deploymentsOf(studyId)

        val expected = listOf(first, second)
            .map { (it as ParticipantGroupStatus.InDeployment).studyDeploymentStatus.studyDeploymentId }
            .toSet()
        assertEquals(expected, found)
    }

    @Test
    fun `a staged group has no deployment and is left out`() = runTest {
        val stack = Stack()
        val studyId = stack.liveStudy()
        val deployed = stack.deployedGroup(studyId) as ParticipantGroupStatus.InDeployment
        val staged = stack.stagedGroup(studyId)

        val found = stack.source.deploymentsOf(studyId)

        // "All deployments" means all deployed groups;
        assertTrue(staged is ParticipantGroupStatus.Staged, "fixture did not stage the group")
        assertEquals(setOf(deployed.studyDeploymentStatus.studyDeploymentId), found)
    }

    @Test
    fun `a study with no groups resolves to nothing`() = runTest {
        val stack = Stack()
        val studyId = stack.liveStudy()

        assertTrue(stack.source.deploymentsOf(studyId).isEmpty())
    }

    @Test
    fun `an unknown study is refused by the service, not hidden as empty`() = runTest {
        val stack = Stack()

        // Empty would read as "nothing deployed yet"; the study not existing is a
        // different problem and the caller should see it.
        assertFailsWith<IllegalArgumentException> {
            stack.source.deploymentsOf(UUID.randomUUID())
        }
    }
}
