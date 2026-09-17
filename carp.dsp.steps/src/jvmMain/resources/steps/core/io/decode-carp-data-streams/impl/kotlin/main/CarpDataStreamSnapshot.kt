@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.datastream

import dk.cachet.carp.common.application.data.Data
import dk.cachet.carp.common.infrastructure.serialization.createDefaultJSON
import dk.cachet.carp.data.application.Measurement
import dk.cachet.carp.data.application.SyncPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val CARP_JSON: Json = createDefaultJSON()

/**
 * The web service's `DataStreamSnapshot`, as held in
 * `data_stream_sequence.snapshot`.
 */
@Serializable
data class CarpDataStreamSnapshot(
    val measurements: List<Measurement<Data>>,
    val triggerIds: List<Int>,
    val syncPoint: SyncPoint,
)
