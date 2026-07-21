package carp.dsp.core.application.plan

import dk.cachet.carp.common.application.tasks.Measure
import dk.cachet.carp.protocols.application.StudyProtocolSnapshot

/**
 * [ProtocolDataTypeProvider] backed by supplied [StudyProtocolSnapshot]s.
 *
 * A protocol snapshot is an authored, serializable artefact independent of any
 * deployment, so this provider makes protocol coupling checkable without a live
 * study or a protocol service: load the snapshot(s), hand them to the planner.
 *
 * The collected data types of a protocol are derived from its tasks' measures:
 * every [Measure.DataStream] contributes its [dk.cachet.carp.common.application.data.DataType]
 * (device-agnostic - the type counts as collected if any task on any device
 * measures it). [Measure.TriggerData] measures carry no data type and are ignored.
 */
class StudyProtocolSnapshotDataTypeProvider(
    snapshots: Collection<StudyProtocolSnapshot>
) : ProtocolDataTypeProvider
{
    constructor( vararg snapshots: StudyProtocolSnapshot ) : this( snapshots.toList() )

    private val snapshotsById: Map<String, List<StudyProtocolSnapshot>> =
        snapshots.groupBy { it.id.toString() }

    override fun collectedDataTypes( protocolId: String, version: Int? ): Set<String>?
    {
        val candidates = snapshotsById[ protocolId ] ?: return null
        val snapshot =
            if ( version == null ) candidates.maxByOrNull { it.version }
            else candidates.firstOrNull { it.version == version }
        snapshot ?: return null

        return snapshot.tasks
            .flatMap { it.measures }
            .filterIsInstance<Measure.DataStream>()
            .map { it.type.toString() }
            .toSet()
    }
}
