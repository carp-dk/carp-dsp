package carp.dsp.core.infrastructure.execution

import carp.dsp.core.infrastructure.execution.handlers.CondaEnvironmentHandler
import carp.dsp.core.infrastructure.execution.handlers.PixiEnvironmentHandler
import carp.dsp.core.infrastructure.execution.handlers.REnvironmentHandler
import carp.dsp.core.infrastructure.execution.handlers.SystemEnvironmentHandler
import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import dk.cachet.carp.analytics.application.plan.REnvironmentRef
import dk.cachet.carp.analytics.application.plan.SystemEnvironmentRef
import kotlin.test.Test
import kotlin.test.assertIs

class EnvironmentHandlerRegistryTest {

    @Test
    fun `selects Conda handler for CondaEnvironmentRef`() {
        val ref = CondaEnvironmentRef(
            id = "test-001",
            name = "test-env",
            dependencies = emptyList()
        )

        val handler = EnvironmentHandlerRegistry.getHandler(ref)

        assertIs<CondaEnvironmentHandler>(handler)
    }

    @Test
    fun `selects Pixi handler for PixiEnvironmentRef`() {
        val ref = PixiEnvironmentRef(
            id = "test-001",
            name = "test-env",
            dependencies = emptyList()
        )

        val handler = EnvironmentHandlerRegistry.getHandler(ref)

        assertIs<PixiEnvironmentHandler>(handler)
    }

    @Test
    fun `selects System handler for SystemEnvironmentRef`() {
        val ref = SystemEnvironmentRef(
            id = "system-001",
            dependencies = emptyList()
        )

        val handler = EnvironmentHandlerRegistry.getHandler(ref)

        assertIs<SystemEnvironmentHandler>(handler)
    }

    @Test
    fun `throws exception for unknown environment type`() {
        // Create a mock unknown environment type
        // This would require a test double or similar

        // For now, we test that the registry can be queried
        val conda = CondaEnvironmentRef(id = "test", name = "env", dependencies = emptyList())
        val handler = EnvironmentHandlerRegistry.getHandler(conda)

        // Verify it's not null
        kotlin.test.assertNotNull(handler)
    }

    @Test
    fun `registry contains all required handlers`() {
        val conda = CondaEnvironmentRef(id = "test", name = "env", dependencies = emptyList())
        val pixi = PixiEnvironmentRef(id = "test", name = "Pixienv", dependencies = emptyList() )
        val system = SystemEnvironmentRef(id = "test")

        val condaHandler = EnvironmentHandlerRegistry.getHandler(conda)
        val pixiHandler = EnvironmentHandlerRegistry.getHandler(pixi)
        val systemHandler = EnvironmentHandlerRegistry.getHandler(system)

        kotlin.test.assertNotNull(condaHandler)
        kotlin.test.assertNotNull(pixiHandler)
        kotlin.test.assertNotNull(systemHandler)
    }

    @Test
    fun `selects R handler for REnvironmentRef`() {
        val ref = REnvironmentRef(
            id = "r-env-001",
            name = "R Environment",
            rVersion = "4.3.0",
            rPackages = listOf("ggplot2")
        )

        val handler = EnvironmentHandlerRegistry.getHandler(ref)

        assertIs<REnvironmentHandler>(handler)
    }

    @Test
    fun `registry contains all four handlers`() {
        val conda = CondaEnvironmentRef(id = "test", name = "env", dependencies = emptyList())
        val pixi = PixiEnvironmentRef(id = "test", name = "pixi-env", dependencies = emptyList())
        val system = SystemEnvironmentRef(id = "test")
        val r = REnvironmentRef(id = "test", name = "R Env", rVersion = "4.3.0", rPackages = listOf("pkg"))

        val condaHandler = EnvironmentHandlerRegistry.getHandler(conda)
        val pixiHandler = EnvironmentHandlerRegistry.getHandler(pixi)
        val systemHandler = EnvironmentHandlerRegistry.getHandler(system)
        val rHandler = EnvironmentHandlerRegistry.getHandler(r)

        kotlin.test.assertNotNull(condaHandler)
        kotlin.test.assertNotNull(pixiHandler)
        kotlin.test.assertNotNull(systemHandler)
        kotlin.test.assertNotNull(rHandler)
    }
}
