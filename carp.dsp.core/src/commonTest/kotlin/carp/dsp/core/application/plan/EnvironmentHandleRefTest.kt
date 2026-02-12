package carp.dsp.core.application.plan

import kotlin.test.Test
import kotlin.test.assertFailsWith

class EnvironmentHandleRefTest {

    @Test
    fun `validate accepts non-blank handleId`() {
        EnvironmentHandleRef("h1").validate()
    }

    @Test
    fun `validate rejects blank handleId`() {
        assertFailsWith<IllegalArgumentException> {
            EnvironmentHandleRef(" ").validate()
        }
    }
}
