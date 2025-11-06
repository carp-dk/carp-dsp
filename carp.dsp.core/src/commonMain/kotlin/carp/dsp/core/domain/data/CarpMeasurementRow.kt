package carp.dsp.core.domain.data

import dk.cachet.carp.common.application.data.*
import kotlin.math.sqrt

/**
 * Sealed class hierarchy for type-safe CARP measurement rows.
 * Each row represents a single measurement with its timing, metadata, and typed data.
 */
sealed class CarpMeasurementRow {
    /**
     * The sensor start time from the original CARP measurement.
     */
    abstract val sensorStartTime: Long

    /**
     * The sensor end time from the original CARP measurement (nullable for instantaneous measurements).
     */
    abstract val sensorEndTime: Long?

    /**
     * The data type from the original CARP measurement.
     */
    abstract val dataType: DataType

    /**
     * All metadata associated with this measurement including provenance information.
     */
    abstract val metadata: CarpMeasurementMetadata

    /**
     * The original CARP data object, preserved exactly as received.
     */
    abstract val originalData: Data

    /**
     * Convenience property for backward compatibility and readability.
     * Points to sensorStartTime.
     */
    val sensorTimestamp: Long get() = sensorStartTime

    /**
     * Duration of the measurement in milliseconds, if available.
     */
    val duration: Long? get() = sensorEndTime?.let { it - sensorStartTime }
}

/**
 * Measurement row for StepCount data.
 */
data class StepCountMeasurementRow(
    override val sensorStartTime: Long,
    override val sensorEndTime: Long?,
    override val dataType: DataType,
    override val metadata: CarpMeasurementMetadata,
    val stepCount: StepCount
) : CarpMeasurementRow() {
    override val originalData: Data get() = stepCount

    /**
     * Convenience property to get the step count value.
     */
    val steps: Int get() = stepCount.steps
}

/**
 * Measurement row for Acceleration data.
 */
data class AccelerationMeasurementRow(
    override val sensorStartTime: Long,
    override val sensorEndTime: Long?,
    override val dataType: DataType,
    override val metadata: CarpMeasurementMetadata,
    val acceleration: Acceleration
) : CarpMeasurementRow() {
    override val originalData: Data get() = acceleration

    /**
     * Convenience properties for acceleration components.
     */
    val x: Double get() = acceleration.x
    val y: Double get() = acceleration.y
    val z: Double get() = acceleration.z

    /**
     * Calculated magnitude of the acceleration vector.
     */
    val magnitude: Double get() = sqrt(x * x + y * y + z * z)
}


/**
 * Measurement row for Geolocation data.
 */
data class GeolocationMeasurementRow(
    override val sensorStartTime: Long,
    override val sensorEndTime: Long?,
    override val dataType: DataType,
    override val metadata: CarpMeasurementMetadata,
    val geolocation: Geolocation
) : CarpMeasurementRow() {
    override val originalData: Data get() = geolocation

    /**
     * Convenience properties for location components.
     */
    val latitude: Double get() = geolocation.latitude
    val longitude: Double get() = geolocation.longitude
}

/**
 * Measurement row for HeartRate data.
 */
data class HeartRateMeasurementRow(
    override val sensorStartTime: Long,
    override val sensorEndTime: Long?,
    override val dataType: DataType,
    override val metadata: CarpMeasurementMetadata,
    val heartRate: HeartRate
) : CarpMeasurementRow() {
    override val originalData: Data get() = heartRate

    /**
     * Convenience property to get the heart rate value.
     */
    val bpm: Int get() = heartRate.bpm
}

/**
 * Generic measurement row for any CARP data type that doesn't have a specific implementation.
 * This ensures we can handle all CARP data types, even ones not explicitly supported.
 */
data class GenericMeasurementRow(
    override val sensorStartTime: Long,
    override val sensorEndTime: Long?,
    override val dataType: DataType,
    override val metadata: CarpMeasurementMetadata,
    override val originalData: Data
) : CarpMeasurementRow()
