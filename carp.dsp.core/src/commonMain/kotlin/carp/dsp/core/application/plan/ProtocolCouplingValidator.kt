package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.domain.workflow.Step

/**
 * Supplies the set of data types collected by a study protocol, for plan-time
 * validation of `protocol`-bound inputs.
 *
 * Implementations may read a supplied [dk.cachet.carp.protocols.application.StudyProtocolSnapshot]
 * (see [StudyProtocolSnapshotDataTypeProvider]) or query a protocol service. The
 * planner depends only on this interface, so the protocols subsystem is not a
 * planner dependency.
 */
fun interface ProtocolDataTypeProvider
{
    /**
     * Returns the fully namespaced CARP data types (e.g. `"dk.cachet.carp.heartrate"`)
     * collected by the protocol with [protocolId] at [version], or `null` when no such
     * protocol is known. A `null` [version] means the latest known version.
     */
    fun collectedDataTypes( protocolId: String, version: Int? ): Set<String>?
}

/**
 * Plan-time validation of `protocol`-bound boundary inputs (F5.4).
 *
 * A boundary input bound to a study protocol (source `type: protocol` in the
 * authored document; carried into the domain model as location metadata) declares
 * the CARP data type it expects the protocol to collect. This validator checks
 * that declaration against the protocol's collected data types:
 *
 * - data type not collected by the protocol -> ERROR `PROTOCOL_DATA_NOT_COLLECTED`
 * - referenced protocol unknown to the provider -> ERROR `PROTOCOL_NOT_FOUND`
 * - no [ProtocolDataTypeProvider] supplied at all -> single WARNING
 *   `PROTOCOL_NOT_VALIDATED` (the binding is declared but cannot be checked)
 *
 * Inputs sourced from steps, files, environment variables, or `external` data are
 * never protocol-checked; a workflow with no protocol-bound inputs yields no
 * issues from this validator (F5.5).
 */
class ProtocolCouplingValidator
{
    private data class ProtocolBoundInput(
        val step: Step,
        val inputName: String,
        val protocolId: String?,
        val protocolVersion: Int?,
        val protocolName: String?,
        val dataType: String?
    )
    {
        /** Human-readable protocol identity for messages: the name when given, else the id. */
        val label: String? get() = protocolName ?: protocolId
    }

    /**
     * Validates every protocol-bound input in [steps] against [provider].
     *
     * @return Plan issues; empty when no input is protocol-bound.
     */
    fun validate( steps: List<Step>, provider: ProtocolDataTypeProvider? ): List<PlanIssue>
    {
        val bound = collectProtocolBoundInputs( steps )
        if ( bound.isEmpty() ) return emptyList()
        if ( provider == null ) return listOf( notValidatedWarning( bound ) )

        // Protocols are looked up once per (id, version); a missing protocol is reported once
        // rather than per input bound to it.
        val collectedCache = mutableMapOf<Pair<String, Int?>, Set<String>?>()
        val missingProtocolsReported = mutableSetOf<String>()

        return bound.flatMap { input ->
            validateBoundInput( input, provider, collectedCache, missingProtocolsReported )
        }
    }

    /** Every input whose location metadata marks it as protocol-sourced. */
    private fun collectProtocolBoundInputs( steps: List<Step> ): List<ProtocolBoundInput> =
        steps.flatMap { step ->
            step.inputs.mapNotNull { input ->
                val meta = input.location.metadata
                if ( meta["source"] != "protocol" ) null
                else ProtocolBoundInput(
                    step = step,
                    inputName = input.name,
                    protocolId = meta["protocolId"],
                    protocolVersion = meta["protocolVersion"]?.toIntOrNull(),
                    protocolName = meta["protocolName"],
                    dataType = meta["dataType"]
                )
            }
        }

    private fun validateBoundInput(
        input: ProtocolBoundInput,
        provider: ProtocolDataTypeProvider,
        collectedCache: MutableMap<Pair<String, Int?>, Set<String>?>,
        missingProtocolsReported: MutableSet<String>
    ): List<PlanIssue>
    {
        val protocolId = input.protocolId
        val dataType = input.dataType
        if ( protocolId.isNullOrBlank() || dataType.isNullOrBlank() )
        {
            return listOf( incompleteBindingIssue( input ) )
        }

        val collected = collectedCache.getOrPut( protocolId to input.protocolVersion ) {
            provider.collectedDataTypes( protocolId, input.protocolVersion )
        }

        return when
        {
            collected == null ->
                if ( missingProtocolsReported.add( protocolId ) ) listOf( protocolNotFoundIssue( input ) )
                else emptyList()

            dataType !in collected -> listOf( dataNotCollectedIssue( input, dataType, collected ) )

            else -> emptyList()
        }
    }

    private fun notValidatedWarning( bound: List<ProtocolBoundInput> ) = PlanIssue(
        severity = PlanIssueSeverity.WARNING,
        code = "PROTOCOL_NOT_VALIDATED",
        message = "${bound.size} input(s) are bound to a study protocol, but no protocol " +
            "source was supplied to the planner, so the binding(s) could not be validated. " +
            "Supply a StudyProtocolSnapshot (or protocol service) to validate them.",
        stepId = bound.first().step.metadata.id
    )

    private fun incompleteBindingIssue( input: ProtocolBoundInput ) = PlanIssue(
        severity = PlanIssueSeverity.ERROR,
        code = "PROTOCOL_DATA_NOT_COLLECTED",
        message = "Input '${input.inputName}' of step '${input.step.metadata.name}' is " +
            "bound to a study protocol but its binding is incomplete " +
            "(protocolId and dataType are both required).",
        stepId = input.step.metadata.id
    )

    private fun protocolNotFoundIssue( input: ProtocolBoundInput ) = PlanIssue(
        severity = PlanIssueSeverity.ERROR,
        code = "PROTOCOL_NOT_FOUND",
        message = "Study protocol '${input.label}'" +
            ( input.protocolVersion?.let { " (version $it)" } ?: "" ) +
            " referenced by input '${input.inputName}' of step " +
            "'${input.step.metadata.name}' is not known to the supplied protocol source.",
        stepId = input.step.metadata.id
    )

    private fun dataNotCollectedIssue(
        input: ProtocolBoundInput,
        dataType: String,
        collected: Set<String>
    ) = PlanIssue(
        severity = PlanIssueSeverity.ERROR,
        code = "PROTOCOL_DATA_NOT_COLLECTED",
        message = "Input '${input.inputName}' of step '${input.step.metadata.name}' " +
            "expects data type '$dataType', but study protocol '${input.label}' " +
            "does not collect it. Collected types: " +
            collected.sorted().joinToString( ", " ) + ".",
        stepId = input.step.metadata.id
    )
}
