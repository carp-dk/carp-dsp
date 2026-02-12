package carp.dsp.core.application.plan

import kotlinx.serialization.Serializable

@Serializable
data class PlanIssue(
    val severity: PlanIssueSeverity,
    val code: String,
    val message: String,
    // Optional: which step the issue refers to. Null means plan-level issue.
    val stepId: String? = null,
) {
    init {
        require(code.isNotBlank()) { "code must not be blank." }
        require(message.isNotBlank()) { "message must not be blank." }
        stepId?.let { require(it.isNotBlank()) { "stepId must not be blank when provided." } }
    }
}

@Serializable
enum class PlanIssueSeverity {
    INFO,
    WARNING,
    ERROR,
}
