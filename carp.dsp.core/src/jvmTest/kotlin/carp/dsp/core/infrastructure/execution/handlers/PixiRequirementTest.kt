package carp.dsp.core.infrastructure.execution.handlers

import kotlin.test.Test
import kotlin.test.assertEquals

class PixiRequirementTest {

    @Test
    fun `a bare name is unconstrained`() {
        assertEquals("""pandas = "*"""", pixiRequirement("pandas"))
    }

    @Test
    fun `a single equals pins an exact version`() {
        // Pixi reads the '=' as part of the constraint, so it is dropped.
        assertEquals("""openjdk = "17"""", pixiRequirement("openjdk=17"))
    }

    @Test
    fun `range operators are kept as written`() {
        assertEquals("""pandas = ">=2.0"""", pixiRequirement("pandas>=2.0"))
        assertEquals("""numpy = "<=1.26"""", pixiRequirement("numpy<=1.26"))
        assertEquals("""scipy = "==1.11.4"""", pixiRequirement("scipy==1.11.4"))
    }

    @Test
    fun `a name may carry punctuation`() {
        assertEquals("""scikit-learn = "*"""", pixiRequirement("scikit-learn"))
        assertEquals("""ruamel.yaml = "0.18"""", pixiRequirement("ruamel.yaml=0.18"))
        assertEquals("""my_package = "*"""", pixiRequirement("my_package"))
    }

    @Test
    fun `surrounding whitespace does not become part of the constraint`() {
        assertEquals("""openjdk = "17"""", pixiRequirement("openjdk = 17"))
    }

    @Test
    fun `a name with a dangling operator is unconstrained`() {
        // "openjdk =" says nothing about a version, so it says nothing.
        assertEquals("""openjdk = "*"""", pixiRequirement("openjdk ="))
    }

    @Test
    fun `a wildcard version is kept`() {
        assertEquals("""openjdk = "17.*"""", pixiRequirement("openjdk=17.*"))
    }
}
