package carp.dsp.steps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClasspathStepLibraryTest
{
    private val library = ClasspathStepLibrary()

    @Test
    fun `resolves a vendored step against the real library`()
    {
        val result = library.lookup( "sensing.heartrate.clean", null )
        assertNotNull( result, "the bundled sensing.heartrate.clean step should resolve" )
        assertEquals( "clean", result.step.id )
        assertEquals( "env-python-data", result.step.environmentId )
        assertEquals( "1.0", result.version )
        assertEquals( setOf( "env-python-data" ), result.environments.keys )
        assertEquals(
            true,
            result.implFiles["impl/python/clean_heart_rate.py"]?.isNotBlank(),
            "the step's impl script is read from the classpath"
        )
    }

    @Test
    fun `returns null for a step that is not in the library`()
    {
        assertNull( library.lookup( "sensing.heartrate.nonexistent", null ) )
    }
}
