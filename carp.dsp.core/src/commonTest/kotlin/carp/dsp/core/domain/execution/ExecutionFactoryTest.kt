package carp.dsp.core.domain.execution

import dk.cachet.carp.analytics.domain.execution.Executor
import dk.cachet.carp.analytics.domain.process.ExternalProcess
import dk.cachet.carp.analytics.domain.workflow.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExecutionFactoryTest {
    private class DummyProcess : ExternalProcess {
        override val name: String = "Dummy"
        override val description: String = "desc"
        override fun getArguments(): Any = Unit
    }
    private class DummyExecutor : Executor {
        override fun execute(step: Step) = Unit
    }
    private class AnotherProcess : ExternalProcess {
        override val name: String = "Another"
        override val description: String? = null
        override fun getArguments(): Any = Unit
    }
    private class AnotherExecutor : Executor {
        override fun execute(step: Step) = Unit
    }

    @Test
    fun `register and getExecutor returns correct executor`() {
        val factory = ExecutionFactory()
        factory.register(DummyProcess::class) { DummyExecutor() }
        val executor = factory.getExecutor(DummyProcess())
        assertTrue(executor is DummyExecutor)
    }

    @Test
    fun `register multiple executors and get correct one`() {
        val factory = ExecutionFactory()
        factory.register(DummyProcess::class) { DummyExecutor() }
        factory.register(AnotherProcess::class) { AnotherExecutor() }
        val executor1 = factory.getExecutor(DummyProcess())
        val executor2 = factory.getExecutor(AnotherProcess())
        assertTrue(executor1 is DummyExecutor)
        assertTrue(executor2 is AnotherExecutor)
        assertEquals(DummyExecutor::class, executor1::class)
        assertEquals(AnotherExecutor::class, executor2::class)
    }

    @Test
    fun `getExecutor throws for unregistered process type`() {
        val factory = ExecutionFactory()
        factory.register(DummyProcess::class) { DummyExecutor() }
        assertFailsWith<IllegalArgumentException> {
            factory.getExecutor(AnotherProcess())
        }
    }

    @Test
    fun `register overwrites previous executor for same process type`() {
        val factory = ExecutionFactory()
        factory.register(DummyProcess::class) { DummyExecutor() }
        factory.register(DummyProcess::class) { AnotherExecutor() }
        val executor = factory.getExecutor(DummyProcess())
        assertTrue(executor is AnotherExecutor)
    }
}
