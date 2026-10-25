package carp.dsp.core.application.plan

import dk.cachet.carp.common.application.tasks.Measure
import dk.cachet.carp.common.application.triggers.TaskControl
import dk.cachet.carp.protocols.application.StudyProtocolSnapshot

/**
 * [ProtocolDataTypeProvider] backed by supplied [StudyProtocolSnapshot]s.
 *
 * A protocol snapshot is an authored, serializable artefact independent of any
 * deployment, so this provider makes protocol coupling checkable without a live
 * study or a protocol service: load the snapshot(s), hand them to the planner.
 *
 * A data type counts as collected when a task that some trigger starts measures it
 * as a [Measure.DataStream], on any device. A task the protocol defines but never
 * starts collects nothing. [Measure.TriggerData] measures carry no data type.
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

        // Only a started task runs on a device; CARP never collects for one that is only defined.
        val startedTasks = snapshot.taskControls
            .filter { it.control == TaskControl.Control.Start }
            .map { it.taskName }
            .toSet()

        return snapshot.tasks
            .filter { it.name in startedTasks }
            .flatMap { it.measures }
            .filterIsInstance<Measure.DataStream>()
            .map { it.type.toString() }
            .toSet()
    }
}
