package carp.dsp.core.domain.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataRetrievalProcessTest {

    @Test
    fun testRetrievalConfig_DefaultValues() {
        val config = RetrievalConfig()

        assertEquals(3, config.maxRetries)
        assertEquals(30_000L, config.timeoutMs)
        assertTrue(config.useCache)
        assertEquals(null, config.cacheDir)
        assertTrue(config.customParams.isEmpty())
    }

    @Test
    fun testRetrievalConfig_CustomValues() {
        val customParams = mapOf("key1" to "value1", "key2" to "value2")
        val config = RetrievalConfig(
            maxRetries = 5,
            timeoutMs = 60_000L,
            useCache = false,
            cacheDir = "/custom/cache/dir",
            customParams = customParams
        )

        assertEquals(5, config.maxRetries)
        assertEquals(60_000L, config.timeoutMs)
        assertFalse(config.useCache)
        assertEquals("/custom/cache/dir", config.cacheDir)
        assertEquals(2, config.customParams.size)
        assertEquals("value1", config.customParams["key1"])
        assertEquals("value2", config.customParams["key2"])
    }

    @Test
    fun testRetrievalConfig_MaxRetries() {
        val config1 = RetrievalConfig(maxRetries = 0)
        assertEquals(0, config1.maxRetries)

        val config2 = RetrievalConfig(maxRetries = 10)
        assertEquals(10, config2.maxRetries)
    }

    @Test
    fun testRetrievalConfig_Timeout() {
        val config1 = RetrievalConfig(timeoutMs = 1_000L)
        assertEquals(1_000L, config1.timeoutMs)

        val config2 = RetrievalConfig(timeoutMs = 120_000L)
        assertEquals(120_000L, config2.timeoutMs)
    }

    @Test
    fun testRetrievalConfig_CacheDir() {
        val config1 = RetrievalConfig(cacheDir = null)
        assertEquals(null, config1.cacheDir)

        val config2 = RetrievalConfig(cacheDir = "/tmp/cache")
        assertEquals("/tmp/cache", config2.cacheDir)

        val config3 = RetrievalConfig(cacheDir = "C:\\Users\\cache")
        assertEquals("C:\\Users\\cache", config3.cacheDir)
    }

    @Test
    fun testRetrievalConfig_CustomParams() {
        val params = mapOf(
            "apiKey" to "secret123",
            "region" to "us-east-1",
            "version" to "v2"
        )
        val config = RetrievalConfig(customParams = params)

        assertEquals(3, config.customParams.size)
        assertEquals("secret123", config.customParams["apiKey"])
        assertEquals("us-east-1", config.customParams["region"])
        assertEquals("v2", config.customParams["version"])
    }

    @Test
    fun testRetrievalConfig_EmptyCustomParams() {
        val config1 = RetrievalConfig()
        assertTrue(config1.customParams.isEmpty())

        val config2 = RetrievalConfig(customParams = emptyMap())
        assertTrue(config2.customParams.isEmpty())
    }

    @Test
    fun testRetrievalConfig_Copy() {
        val original = RetrievalConfig(
            maxRetries = 5,
            timeoutMs = 60_000L,
            useCache = false
        )

        val modified = original.copy(maxRetries = 7)
        assertEquals(7, modified.maxRetries)
        assertEquals(60_000L, modified.timeoutMs)
        assertFalse(modified.useCache)
    }

    @Test
    fun testRetrievalConfig_Equality() {
        val config1 = RetrievalConfig(maxRetries = 5, timeoutMs = 60_000L)
        val config2 = RetrievalConfig(maxRetries = 5, timeoutMs = 60_000L)
        val config3 = RetrievalConfig(maxRetries = 3, timeoutMs = 60_000L)

        assertEquals(config1, config2)
        assertTrue(config1 != config3)
    }

    @Test
    fun testDataRetrievalProcess_DefaultProperties() {
        // Create a process that uses the interface's default implementations
        val process = object : DataRetrievalProcess {
            override val name: String = "TestProcess"
            override val description: String = "Test"
            override val retrievalConfig: RetrievalConfig = RetrievalConfig()
            // Do NOT override supportsCaching or requiresAuthentication
            // to test the default get() implementations
        }

        // Verify the default get() implementations are used
        assertTrue(process.supportsCaching, "Default supportsCaching should be true")
        assertFalse(process.requiresAuthentication, "Default requiresAuthentication should be false")
    }

    @Test
    fun testDataRetrievalProcess_DefaultSupportsCachingGetter() {
        // Test that the default getter for supportsCaching returns true
        val process = object : DataRetrievalProcess {
            override val name: String = "CachingTest"
            override val description: String = "Tests default caching"
            override val retrievalConfig: RetrievalConfig = RetrievalConfig()
        }

        // This specifically tests the default get() = true implementation
        assertTrue(process.supportsCaching)
    }

    @Test
    fun testDataRetrievalProcess_DefaultRequiresAuthenticationGetter() {
        // Test that the default getter for requiresAuthentication returns false
        val process = object : DataRetrievalProcess {
            override val name: String = "AuthTest"
            override val description: String = "Tests default auth requirement"
            override val retrievalConfig: RetrievalConfig = RetrievalConfig()
        }

        // This specifically tests the default get() = false implementation
        assertFalse(process.requiresAuthentication)
    }

    @Test
    fun testDataRetrievalProcess_CustomSupportsCaching() {
        val process = object : DataRetrievalProcess {
            override val name: String = "TestProcess"
            override val description: String = "Test"
            override val retrievalConfig: RetrievalConfig = RetrievalConfig()
            override val supportsCaching: Boolean = false
        }

        assertFalse(process.supportsCaching)
    }

    @Test
    fun testDataRetrievalProcess_CustomRequiresAuthentication() {
        val process = object : DataRetrievalProcess {
            override val name: String = "TestProcess"
            override val description: String = "Test"
            override val retrievalConfig: RetrievalConfig = RetrievalConfig()
            override val requiresAuthentication: Boolean = true
        }

        assertTrue(process.requiresAuthentication)
    }

    @Test
    fun testDataRetrievalProcess_WithDefaultConfig() {
        val process = object : DataRetrievalProcess {
            override val name: String = "TestProcess"
            override val description: String = "Test description"
            override val retrievalConfig: RetrievalConfig = RetrievalConfig()
        }

        assertEquals("TestProcess", process.name)
        assertEquals("Test description", process.description)
        assertEquals(3, process.retrievalConfig.maxRetries)
        assertEquals(30_000L, process.retrievalConfig.timeoutMs)
        assertTrue(process.retrievalConfig.useCache)
    }

    @Test
    fun testDataRetrievalProcess_WithCustomConfig() {
        val customConfig = RetrievalConfig(
            maxRetries = 10,
            timeoutMs = 120_000L,
            useCache = false,
            cacheDir = "/custom/path"
        )

        val process = object : DataRetrievalProcess {
            override val name: String = "CustomProcess"
            override val description: String = "Custom description"
            override val retrievalConfig: RetrievalConfig = customConfig
        }

        assertEquals("CustomProcess", process.name)
        assertEquals("Custom description", process.description)
        assertEquals(10, process.retrievalConfig.maxRetries)
        assertEquals(120_000L, process.retrievalConfig.timeoutMs)
        assertFalse(process.retrievalConfig.useCache)
        assertEquals("/custom/path", process.retrievalConfig.cacheDir)
    }

    @Test
    fun testDataRetrievalProcess_CachingEnabledByDefault() {
        val process = createMockDataRetrievalProcess(useCache = true)

        assertTrue(process.supportsCaching)
        assertTrue(process.retrievalConfig.useCache)
    }

    @Test
    fun testDataRetrievalProcess_CachingDisabled() {
        val process = createMockDataRetrievalProcess(
            useCache = false,
            supportsCaching = false
        )

        assertFalse(process.supportsCaching)
        assertFalse(process.retrievalConfig.useCache)
    }

    @Test
    fun testDataRetrievalProcess_AuthenticationNotRequiredByDefault() {
        // Create process without specifying auth requirement
        val process = object : DataRetrievalProcess {
            override val name: String = "NoAuthProcess"
            override val description: String = "Process without auth"
            override val retrievalConfig: RetrievalConfig = RetrievalConfig()
        }

        // Verify default get() implementation is used
        assertFalse(process.requiresAuthentication)
    }

    @Test
    fun testDataRetrievalProcess_CachingSupportedByDefault() {
        // Create process without specifying caching support
        val process = object : DataRetrievalProcess {
            override val name: String = "CacheProcess"
            override val description: String = "Process with caching"
            override val retrievalConfig: RetrievalConfig = RetrievalConfig()
        }

        // Verify default get() implementation is used
        assertTrue(process.supportsCaching)
    }

    @Test
    fun testDataRetrievalProcess_AuthenticationRequired() {
        val process = createMockDataRetrievalProcess(requiresAuth = true)

        assertTrue(process.requiresAuthentication)
    }

    @Test
    fun testDataRetrievalProcess_VariousTimeouts() {
        val process1 = createMockDataRetrievalProcess(timeoutMs = 10_000L)
        assertEquals(10_000L, process1.retrievalConfig.timeoutMs)

        val process2 = createMockDataRetrievalProcess(timeoutMs = 60_000L)
        assertEquals(60_000L, process2.retrievalConfig.timeoutMs)

        val process3 = createMockDataRetrievalProcess(timeoutMs = 300_000L)
        assertEquals(300_000L, process3.retrievalConfig.timeoutMs)
    }

    @Test
    fun testDataRetrievalProcess_VariousRetryAttempts() {
        val process1 = createMockDataRetrievalProcess(maxRetries = 0)
        assertEquals(0, process1.retrievalConfig.maxRetries)

        val process2 = createMockDataRetrievalProcess(maxRetries = 5)
        assertEquals(5, process2.retrievalConfig.maxRetries)

        val process3 = createMockDataRetrievalProcess(maxRetries = 20)
        assertEquals(20, process3.retrievalConfig.maxRetries)
    }

    @Test
    fun testDataRetrievalProcess_WithCustomParams() {
        val customParams = mapOf(
            "endpoint" to "https://api.example.com",
            "version" to "v1",
            "format" to "json"
        )

        val process = object : DataRetrievalProcess {
            override val name: String = "APIProcess"
            override val description: String = "API retrieval"
            override val retrievalConfig: RetrievalConfig = RetrievalConfig(customParams = customParams)
        }

        assertEquals(3, process.retrievalConfig.customParams.size)
        assertEquals("https://api.example.com", process.retrievalConfig.customParams["endpoint"])
        assertEquals("v1", process.retrievalConfig.customParams["version"])
        assertEquals("json", process.retrievalConfig.customParams["format"])
    }

    @Test
    fun testDataRetrievalProcess_MultipleInstances() {
        val process1 = createMockDataRetrievalProcess(name = "Process1")
        val process2 = createMockDataRetrievalProcess(name = "Process2")
        val process3 = createMockDataRetrievalProcess(name = "Process3")

        assertEquals("Process1", process1.name)
        assertEquals("Process2", process2.name)
        assertEquals("Process3", process3.name)
    }

    @Test
    fun testRetrievalConfig_ImmutableCustomParams() {
        val mutableParams = mutableMapOf("key1" to "value1")

        // Best practice: pass an immutable copy to the config
        val config = RetrievalConfig(customParams = mutableParams.toMap())

        // Modify original map
        mutableParams["key2"] = "value2"

        // Config should not be affected since we passed an immutable copy
        assertEquals(1, config.customParams.size)
        assertEquals("value1", config.customParams["key1"])
        assertEquals(null, config.customParams["key2"])
    }

    @Test
    fun testDataRetrievalProcess_CombinedSettings() {
        val config = RetrievalConfig(
            maxRetries = 7,
            timeoutMs = 90_000L,
            useCache = true,
            cacheDir = "/data/cache",
            customParams = mapOf("region" to "eu-west-1")
        )

        val process = object : DataRetrievalProcess {
            override val name: String = "CombinedProcess"
            override val description: String = "Process with all settings"
            override val retrievalConfig: RetrievalConfig = config
            override val supportsCaching: Boolean = true
            override val requiresAuthentication: Boolean = true
        }

        assertEquals("CombinedProcess", process.name)
        assertEquals("Process with all settings", process.description)
        assertEquals(7, process.retrievalConfig.maxRetries)
        assertEquals(90_000L, process.retrievalConfig.timeoutMs)
        assertTrue(process.retrievalConfig.useCache)
        assertEquals("/data/cache", process.retrievalConfig.cacheDir)
        assertEquals("eu-west-1", process.retrievalConfig.customParams["region"])
        assertTrue(process.supportsCaching)
        assertTrue(process.requiresAuthentication)
    }

    // Helper function to create mock DataRetrievalProcess instances
    private fun createMockDataRetrievalProcess(
        name: String = "MockRetrievalProcess",
        description: String = "Mock retrieval process for testing",
        maxRetries: Int = 3,
        timeoutMs: Long = 30_000L,
        useCache: Boolean = true,
        cacheDir: String? = null,
        customParams: Map<String, String> = emptyMap(),
        supportsCaching: Boolean = true,
        requiresAuth: Boolean = false
    ): DataRetrievalProcess {
        return object : DataRetrievalProcess {
            override val name: String = name
            override val description: String = description
            override val retrievalConfig: RetrievalConfig = RetrievalConfig(
                maxRetries = maxRetries,
                timeoutMs = timeoutMs,
                useCache = useCache,
                cacheDir = cacheDir,
                customParams = customParams
            )
            override val supportsCaching: Boolean = supportsCaching
            override val requiresAuthentication: Boolean = requiresAuth
        }
    }
}

