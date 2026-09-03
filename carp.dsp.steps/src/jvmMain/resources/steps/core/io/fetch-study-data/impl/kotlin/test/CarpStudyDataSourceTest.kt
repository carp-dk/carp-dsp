package carp.dsp.steps.data

import carp.dsp.core.application.DeploymentTarget
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class CarpStudyDataSourceTest {

    private val d1 = UUID.randomUUID()
    private val d2 = UUID.randomUUID()
    private val d3 = UUID.randomUUID()

    @Test
    fun `two subjects on different devices are never crossed`() {
        val grouped = groupByRole(
            listOf(DeploymentTarget(d1, "phone"), DeploymentTarget(d2, "watch"))
        )

        // Two calls, each naming one role. One call naming both roles would also
        // return d1's watch and d2's phone.
        val expected: Map<String?, Set<UUID>> = mapOf("phone" to setOf(d1), "watch" to setOf(d2))
        assertEquals(expected, grouped)
    }

    @Test
    fun `deployments wanting the same role share one call`() {
        val grouped = groupByRole(
            listOf(DeploymentTarget(d1, "phone"), DeploymentTarget(d2, "phone"))
        )


        val expected: Map<String?, Set<UUID>> = mapOf("phone" to setOf(d1, d2))
        assertEquals(expected, grouped)
    }

    @Test
    fun `targets with no role become one unfiltered call`() {
        val grouped = groupByRole(listOf(DeploymentTarget(d1), DeploymentTarget(d2)))

        val expected: Map<String?, Set<UUID>> = mapOf(null to setOf(d1, d2))
        assertEquals(expected, grouped)
    }

    @Test
    fun `targets with and without roles stay in separate calls`() {
        val grouped = groupByRole(
            listOf(DeploymentTarget(d1, "phone"), DeploymentTarget(d2), DeploymentTarget(d3, "phone"))
        )

        val expected: Map<String?, Set<UUID>> = mapOf("phone" to setOf(d1, d3), null to setOf(d2))
        assertEquals(expected, grouped)
    }

    @Test
    fun `one deployment asked for twice on different devices gets both`() {
        val grouped = groupByRole(
            listOf(DeploymentTarget(d1, "phone"), DeploymentTarget(d1, "watch"))
        )

        val expected: Map<String?, Set<UUID>> = mapOf("phone" to setOf(d1), "watch" to setOf(d1))
        assertEquals(expected, grouped)
    }

    @Test
    fun `no targets means no calls`() {
        val expected: Map<String?, Set<UUID>> = emptyMap()
        assertEquals(expected, groupByRole(emptyList()))
    }
}
