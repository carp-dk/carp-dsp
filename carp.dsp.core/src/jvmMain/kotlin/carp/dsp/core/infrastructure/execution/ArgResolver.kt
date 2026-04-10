package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.domain.data.*
import dk.cachet.carp.common.application.UUID

/**
 * Resolves ExpandedArg tokens to concrete literal strings using bindings.
 *
 * Adding new argument types no longer requires modifying this class:
 * - Literal → handled by LiteralArgStrategy
 * - DataReference → handled by DataReferenceArgStrategy
 * - PathSubstitution → handled by PathSubstitutionArgStrategy
 * - EnvironmentVariable → handled by EnvironmentVariableArgStrategy
 * - New types → create new strategy, register in map
 *
 * Separates argument resolution from execution orchestration (SRP).
 * Can be reused by other components that need to resolve arguments.
 */
class ArgResolver( private val bindings: ResolvedBindings )
{
    /**
     * Resolve a single argument using the appropriate strategy.
     *
     * @param arg The argument token to resolve
     * @return Resolved string value
     */
    fun resolve( arg: ExpandedArg ): String =
        resolverStrategies[arg::class]?.resolve( arg, bindings )
            ?: throw IllegalArgumentException( "Unknown argument type: ${arg::class.simpleName}" )

    companion object
    {
        /**
         * Strategy registry: maps argument types to resolvers.
         *
         * Can be extended with new strategies without modifying ArgResolver.
         * Each strategy is responsible for a single argument type.
         */
        private val resolverStrategies: Map<kotlin.reflect.KClass<*>, ArgResolverStrategy> = mapOf(
            ExpandedArg.Literal::class to LiteralArgStrategy,
            ExpandedArg.DataReference::class to DataReferenceArgStrategy,
            ExpandedArg.PathSubstitution::class to PathSubstitutionArgStrategy,
            ExpandedArg.EnvironmentVariable::class to EnvironmentVariableArgStrategy
        )
    }
}

/**
 * **SOLID Improvement (OCP):** Strategy interface for argument resolution.
 *
 * New argument types can be added by:
 * 1. Creating a class implementing ArgResolverStrategy
 * 2. Registering in ArgResolver.resolverStrategies
 * 3. No modification to ArgResolver class needed
 */
interface ArgResolverStrategy
{
    fun resolve( arg: ExpandedArg, bindings: ResolvedBindings ): String
}

/**
 * Strategy for literal arguments (pass through as-is).
 */
object LiteralArgStrategy : ArgResolverStrategy
{
    override fun resolve( arg: ExpandedArg, bindings: ResolvedBindings ): String
    {
        arg as ExpandedArg.Literal
        return arg.value
    }
}

/**
 * Strategy for data reference arguments (resolve from bindings).
 *
 * Works with all 6 DataLocation types:
 * - FileLocation → extract path
 * - InMemoryLocation → use registryKey
 * - UrlLocation → use URL
 * - DatabaseLocation → use connection details
 * - ApiLocation → use endpoint
 * - StreamLocation → use stream address
 */
object DataReferenceArgStrategy : ArgResolverStrategy
{
    override fun resolve( arg: ExpandedArg, bindings: ResolvedBindings ): String
    {
        arg as ExpandedArg.DataReference
        return resolveDataReferencePath( arg.id, bindings )
    }

    private fun resolveDataReferencePath( id: UUID, bindings: ResolvedBindings ): String
    {
        // Try to resolve from inputs
        bindings.inputs[id]?.let { input ->
            return resolveLocationToString( input.location )
        }

        // Try to resolve from outputs
        bindings.outputs[id]?.let { output ->
            return resolveLocationToString( output.location )
        }

        // Fallback to UUID string
        return id.toString()
    }

    /**
     * Convert DataLocation to a string reference suitable for passing as argument.
     *
     * For file-based locations: returns the path
     * For other locations: returns a reference suitable for the executor
     */
    private fun resolveLocationToString( location: DataLocation ): String
    {
        return when ( location )
        {
            is FileLocation -> location.path
            is InMemoryLocation -> location.registryKey
            is UrlLocation -> location.url
            is DatabaseLocation -> "${location.connectionString}:${location.table}"
            is ApiLocation -> location.endpoint
            is StreamLocation -> "${location.streamAddress}/${location.topicOrStream}"
        }
    }
}

/**
 * Strategy for path substitution arguments (template replacement).
 *
 * Replaces template placeholders with resolved data reference paths.
 * Example: "--input=$()" → "--input=/path/to/data.csv"
 */
object PathSubstitutionArgStrategy : ArgResolverStrategy
{
    override fun resolve( arg: ExpandedArg, bindings: ResolvedBindings ): String
    {
        arg as ExpandedArg.PathSubstitution
        val resolvedPath = DataReferenceArgStrategy.resolve(
            ExpandedArg.DataReference( arg.id ),
            bindings
        )
        return arg.template.replace( "()", resolvedPath )
    }
}

/**
 * Strategy for environment variable arguments (system property lookup).
 *
 * Replaces template placeholders with environment variable values.
 * Example: "--model=$(env.MODEL_PATH)" → "--model=/models/v2.pkl"
 */
object EnvironmentVariableArgStrategy : ArgResolverStrategy
{
    override fun resolve( arg: ExpandedArg, bindings: ResolvedBindings ): String
    {
        arg as ExpandedArg.EnvironmentVariable
        val envValue = System.getenv( arg.name ) ?: ""
        return arg.template.replace( "()", envValue )
    }
}
