package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.PlanHasher
import dk.cachet.carp.analytics.infrastructure.serialization.CoreAnalyticsSerializer
import kotlinx.serialization.encodeToString
import java.security.MessageDigest

/**
 * SHA-256 based plan hasher.
 *
 * Lives in DSP (not core) because it uses Java's MessageDigest.
 * Keeps core/analytics completely MPP-compatible.
 */
class SHA256PlanHasher : PlanHasher {

    override fun hash(plan: ExecutionPlan): String {
        // 1. Canonicalize the plan (deterministic JSON)
        val canonical = canonicalizePlan(plan)

        // 2. Hash the canonical JSON
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(canonical.toByteArray(Charsets.UTF_8))

        // 3. Convert to hex string
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun canonicalizePlan(plan: ExecutionPlan): String {
        // Serialize plan to JSON using CoreAnalyticsSerializer
        return CoreAnalyticsSerializer.json.encodeToString(plan)
    }
}
