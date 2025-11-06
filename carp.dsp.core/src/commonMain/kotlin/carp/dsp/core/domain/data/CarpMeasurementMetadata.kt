package carp.dsp.core.domain.data

import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.DataType
import dk.cachet.carp.data.application.DataStreamId
import dk.cachet.carp.data.application.SyncPoint

/**
 * Encapsulates all metadata associated with a CARP measurement.
 * This preserves provenance and stream-level information from the original DataStreamBatch.
 */
data class CarpMeasurementMetadata(
    /**
     * Index of the sequence in the original DataStreamBatch.sequences list.
     * Used for provenance tracking back to the original data structure.
     */
    val sequenceIndex: Int,

    /**
     * Index of the measurement within the MutableDataStream.measurements list.
     * Used for provenance tracking back to the original measurement.
     */
    val measurementIndex: Int,

    /**
     * The data stream identifier from the original MutableDataStream.
     * Contains device ID and data type information.
     */
    val dataStreamId: DataStreamId,

    /**
     * The first sequence ID from the original MutableDataStream.
     */
    val firstSequenceId: Long,

    /**
     * The trigger IDs from the original MutableDataStream.
     */
    val triggerIds: List<Int>,

    /**
     * The sync point from the original MutableDataStream.
     * Contains sensor clock synchronization information.
     */
    val syncPoint: SyncPoint
) {
    /**
     * Convenience property to get the study deployment ID from the data stream.
     */
    val studyDeploymentId: UUID get() = dataStreamId.studyDeploymentId

    /**
     * Convenience property to get the device role name from the data stream.
     */
    val deviceRoleName: String get() = dataStreamId.deviceRoleName

    /**
     * Convenience property to get the data type from the data stream.
     */
    val dataType: DataType get() = dataStreamId.dataType

    /**
     * Convenience property to get the data type as a string representation.
     */
    val dataTypeString: String get() = dataStreamId.dataType.toString()
}
