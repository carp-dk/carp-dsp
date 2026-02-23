package carp.dsp.core.application.execution

import kotlinx.serialization.Serializable

/**
 * Portable, strictly-relative path string for serialization.
 * Never absolute, never drive-letter, never traversal.
 */
@Serializable
@JvmInline
value class RelativePath(val value: String) {
    init {
        require(value.isNotBlank()) { "RelativePath cannot be blank." }
        require(!value.startsWith("/")) { "RelativePath must not be absolute: $value" }
        require(!value.matches(Regex("^[A-Za-z]:.*"))) { "RelativePath must not be a Windows drive path: $value" }
        require(!value.contains("..")) { "RelativePath must not contain '..' segments: $value" }
    }

    override fun toString(): String = value
}
