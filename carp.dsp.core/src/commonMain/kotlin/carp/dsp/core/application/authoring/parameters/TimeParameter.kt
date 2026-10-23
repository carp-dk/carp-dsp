package carp.dsp.core.application.authoring.parameters

import kotlinx.serialization.Serializable

/**
 * Where a value sits in one step's arguments.
 *
 * @property stepKey The step's `id`, or `#<index>` for a step without one.
 * @property flag The flag the value follows, e.g. `--from`.
 * @property name For a `--flag name=value` argument, the name; `null` when the
 *   value follows [flag] directly.
 */
@Serializable
data class ArgumentRef(
    val stepKey: String,
    val flag: String,
    val name: String? = null,
)
{
    /** The key as written: `--from`, or `--param from`. */
    val label: String get() = if (name == null) flag else "$flag $name"
}

/** A time value found in a step's arguments, and the format it is written in. */
@Serializable
data class TimeParameter(val ref: ArgumentRef, val value: String, val format: TimeFormat)
