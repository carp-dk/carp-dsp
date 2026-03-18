package carp.dsp.core.infrastructure.execution


import dk.cachet.carp.analytics.application.plan.EnvironmentRef
import dk.cachet.carp.analytics.infrastructure.execution.CleanupPolicy
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentConfig
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentExecutionLogs
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentHandler
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentLog
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentMetadata
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentOrchestrator
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentPhase
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentRegistry
import dk.cachet.carp.analytics.infrastructure.execution.ErrorHandling
import dk.cachet.carp.analytics.infrastructure.execution.LogLevel
import kotlinx.datetime.Clock
import java.io.IOException

/**
 * Default implementation of EnvironmentOrchestrator.
 *
 * Coordinates environment provisioning, execution, and clean-up.
 */
class DefaultEnvironmentOrchestrator(
    private val registry: EnvironmentRegistry,
    private val config: EnvironmentConfig = EnvironmentConfig()
) : EnvironmentOrchestrator {

    private companion object {
        private const val MIN_ENV_ID_PARTS_WITH_STEP = 4
        private const val RUN_ID_PARTS = 3
        private const val STEP_ID_INDEX = 3
    }

    private val logs = mutableListOf<EnvironmentLog>()

    override fun setup(environmentRef: EnvironmentRef): Boolean {
        if (config.reuseExisting && reuseExisting(environmentRef)) return true

        return performSetup(environmentRef)
    }

    override fun generateExecutionCommand(
        environmentRef: EnvironmentRef,
        command: String
    ): String {
        val handler = EnvironmentHandlerRegistry.getHandler(environmentRef)
        return handler.generateExecutionCommand(environmentRef, command)
    }

    override fun teardown(environmentRef: EnvironmentRef): Boolean {
        return try {
            when (config.cleanupPolicy) {
                CleanupPolicy.REUSE -> {
                    log(
                        EnvironmentPhase.TEARDOWN,
                        environmentRef.id,
                        "Keeping environment (REUSE policy)",
                        LogLevel.INFO
                    )
                    true
                }
                CleanupPolicy.CLEAN -> {
                    val handler = EnvironmentHandlerRegistry.getHandler(environmentRef)
                    val success = handler.teardown(environmentRef)

                    if (success) {
                        log(
                            EnvironmentPhase.TEARDOWN,
                            environmentRef.id,
                            "Cleaned (CLEAN policy)",
                            LogLevel.INFO
                        )
                    }
                    success
                }
                CleanupPolicy.PURGE -> {
                    val handler = EnvironmentHandlerRegistry.getHandler(environmentRef)
                    val success = handler.teardown(environmentRef)

                    if (success) {
                        registry.remove(environmentRef.id)
                        log(
                            EnvironmentPhase.TEARDOWN,
                            environmentRef.id,
                            "Purged (PURGE policy)",
                            LogLevel.INFO
                        )
                    }
                    success
                }
            }
        } catch (e: IllegalArgumentException) {
            log(EnvironmentPhase.TEARDOWN, environmentRef.id, "Teardown error: ${e.message}", LogLevel.WARN)
            false
        } catch (e: IllegalStateException) {
            log(EnvironmentPhase.TEARDOWN, environmentRef.id, "Teardown error: ${e.message}", LogLevel.WARN)
            false
        } catch (e: IOException) {
            log(EnvironmentPhase.TEARDOWN, environmentRef.id, "Teardown I/O error: ${e.message}", LogLevel.WARN)
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log(EnvironmentPhase.TEARDOWN, environmentRef.id, "Teardown interrupted: ${e.message}", LogLevel.WARN)
            false
        }
    }

    fun getEnvironmentLogs(): EnvironmentExecutionLogs {
        return EnvironmentExecutionLogs(
            setupLogs = logs.filter { it.phase == EnvironmentPhase.SETUP },
            executionLogs = logs.filter { it.phase == EnvironmentPhase.EXECUTION },
            teardownLogs = logs.filter { it.phase == EnvironmentPhase.TEARDOWN }
        )
    }

    // Private Helpers

    private fun setupWithErrorHandling(
        handler: EnvironmentHandler,
        environmentRef: EnvironmentRef
    ): Boolean = runCatching { handler.setup(environmentRef) }
        .getOrElse { handleSetupFailure(environmentRef, it) }

    private fun setupWithRetry(
        handler: EnvironmentHandler,
        environmentRef: EnvironmentRef
    ): Boolean {
        var lastException: Throwable? = null

        for (attempt in 1..config.retryAttempts) {
            val result = runCatching { handler.setup(environmentRef) }
            if (result.isSuccess) return result.getOrThrow()

            val failure = result.exceptionOrNull()
            if (failure is InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            }

            lastException = failure
            if (attempt < config.retryAttempts) {
                val backoff = config.retryBackoffMs * attempt
                log(
                    EnvironmentPhase.SETUP,
                    environmentRef.id,
                    "Attempt $attempt failed, retrying in ${backoff}ms",
                    LogLevel.WARN
                )
                Thread.sleep(backoff)
            }
        }

        throw lastException ?: Exception("Setup failed after ${config.retryAttempts} attempts")
    }

    private fun reuseExisting(environmentRef: EnvironmentRef): Boolean {
        val exists = registry.exists(environmentRef.id)
        if (exists) {
            log(
                EnvironmentPhase.SETUP,
                environmentRef.id,
                "Reusing existing environment",
                LogLevel.INFO
            )
        }
        return exists
    }

    private fun performSetup(environmentRef: EnvironmentRef): Boolean {
        val handler = EnvironmentHandlerRegistry.getHandler(environmentRef)
        val success = if (config.errorHandling == ErrorHandling.RETRY) {
            setupWithRetry(handler, environmentRef)
        } else {
            setupWithErrorHandling(handler, environmentRef)
        }

        if (success) registerEnvironment(environmentRef)
        return success
    }

    private fun registerEnvironment(environmentRef: EnvironmentRef) {
        val metadata = EnvironmentMetadata(
            id = environmentRef.id,
            name = environmentRef::class.simpleName ?: "unknown",
            kind = determinateName(environmentRef),
            runId = extractRunId(environmentRef.id),
            stepId = extractStepId(environmentRef.id),
            createdAt = Clock.System.now(),
            lastUsedAt = Clock.System.now(),
            sizeBytes = 0L,
            status = "active"
        )
        registry.register(environmentRef, metadata)
        log(EnvironmentPhase.SETUP, environmentRef.id, "Setup complete", LogLevel.INFO)
    }

    private fun handleSetupFailure(environmentRef: EnvironmentRef, throwable: Throwable): Boolean {
        return when (throwable) {
            is IllegalArgumentException,
            is IllegalStateException -> {
                log(
                    EnvironmentPhase.SETUP,
                    environmentRef.id,
                    "Setup failed: ${throwable.message}",
                    LogLevel.ERROR
                )
                when (config.errorHandling) {
                    ErrorHandling.FAIL_FAST -> throw throwable
                    ErrorHandling.CONTINUE -> false
                    ErrorHandling.RETRY -> false
                }
            }
            is IOException -> {
                log(
                    EnvironmentPhase.SETUP,
                    environmentRef.id,
                    "Setup I/O failed: ${throwable.message}",
                    LogLevel.ERROR
                )
                when (config.errorHandling) {
                    ErrorHandling.FAIL_FAST -> throw throwable
                    ErrorHandling.CONTINUE -> false
                    ErrorHandling.RETRY -> false
                }
            }
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                log(
                    EnvironmentPhase.SETUP,
                    environmentRef.id,
                    "Setup interrupted: ${throwable.message}",
                    LogLevel.WARN
                )
                false
            }
            else -> throw throwable
        }
    }

    private fun log(phase: EnvironmentPhase, envId: String, message: String, level: LogLevel) {
        logs.add(
            EnvironmentLog(
                timestamp = Clock.System.now(),
                environmentId = envId,
                phase = phase,
                message = message,
                level = level
            )
        )
    }

    private fun determinateName(environmentRef: EnvironmentRef): String {
        return environmentRef::class.simpleName?.replace("EnvironmentRef", "")?.lowercase()
            ?: "unknown"
    }

    private fun extractRunId(envId: String): String {
        // Format: run-id-step-id-env-name or run-id-env-name
        val parts = envId.split("-")
        return if (parts.size >= MIN_ENV_ID_PARTS_WITH_STEP) {
            parts.take(RUN_ID_PARTS).joinToString("-")
        } else {
            parts.firstOrNull() ?: envId
        }
    }

    private fun extractStepId(envId: String): String? {
        // If format is run-id-step-id-env-name, extract step-id
        val parts = envId.split("-")
        return if (parts.size >= MIN_ENV_ID_PARTS_WITH_STEP) {
            parts[STEP_ID_INDEX]
        } else {
            null
        }
    }
}
