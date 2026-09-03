# Reading CARP's database directly

`core.io.query-sql` can point at any database; this document is about the one
query that matters for the demo, and what it costs to reach past a service into
someone else's schema.

## Why at all

**A database connector is a capability the framework was missing.** A step that
takes a connection and a statement and makes the result available in the workflow.

**The service route is not ready.** Reading a study through
`DataStreamService.getBatchForStudyDeployments` is the temp path used to demo DSP. 

There is also a cost argument: a real dataset is  fine-grained points for many participants, 
and the service path serialises every measurement into a `DataStreamBatch` and back out. 
Reading rows skips two passes.

## What is read

Three tables, and only to read.

### `data_stream_ids`

The index of data streams. One row per `(study deployment, device role, data
type)`.

| Column                | Used for                                               |
|-----------------------|--------------------------------------------------------|
| `id`                  | joined to `data_stream_sequence.data_stream_id`        |
| `study_deployment_id` | which deployment a row belongs to                      |
| `device_role_name`    | which device on that deployment                        |
| `name_space`, `name`  | the CARP data type, as `name_space \|\| '.' \|\| name` |

The data type is split across two columns here and is one string everywhere
else: `dk.cachet.carp` and `stepcount` become `dk.cachet.carp.stepcount`.

### `data_stream_sequence`

Where the measurements are.

| Column              | Used for                            |
|---------------------|-------------------------------------|
| `data_stream_id`    | the stream this sequence belongs to |
| `first_sequence_id` | the sequence's starting point       |
| `snapshot`          | the measurements, as JSON           |

`snapshot` holds the web service's `DataStreamSnapshot`: `measurements`,
`triggerIds`, `syncPoint`. It is **not** carp-core's
`DataStreamSequenceSnapshot`, which also carries `dataStream` and
`firstSequenceId` - here those are the joined row and the column beside it. A
decoder has to put the three back together.

### `recruitment_participant_groups`

How a study id becomes a set of deployments. Not `deployments` - that table has
no study column, so it cannot say which deployments belong to a study.

| Column        | Used for                                      |
|---------------|-----------------------------------------------|
| `study_id`    | the study being read                          |
| `group_id`    | **is** the study deployment id, once deployed |
| `is_deployed` | a staged group has no deployment              |


`is_deployed` is the same filter the service path applies as
`ParticipantGroupStatus.InDeployment`.

### The query

```sql
-- noinspection SqlResolve
SELECT i.study_deployment_id,
       i.device_role_name,
       i.name_space || '.' || i.name AS data_type,
       s.first_sequence_id,
       s.snapshot
FROM data_stream_sequence s
         JOIN data_stream_ids i
              ON i.id = s.data_stream_id
         JOIN recruitment_participant_groups g
              ON g.group_id = i.study_deployment_id
                  AND g.is_deployed
WHERE g.study_id = :studyId
ORDER BY i.id, s.first_sequence_id;
```

The time window is not here, deliberately: `sensorStartTime` is the *sensor's*
clock and only means UTC once the sequence's `syncPoint` is applied, so a `WHERE`
on it filters the wrong axis for any device whose clock drifted. The decoder
synchronises first, then filters.

## The snapshot is kotlinx JSON

The web service's serialization notes say database JSONB is Jackson's, which is
true of the column and misleading about its contents. The Jackson serializer for
a measurement is a delegate:

```kotlin
serialized = WS_JSON.encodeToString(dk.cachet.carp.data.application.MeasurementSerializer, value)
gen.writeRawValue(serialized)
```

`WS_JSON` is `createDefaultJSON(WS_MODULE)` - carp-core's own JSON. So a
measurement is written by carp-core's `MeasurementSerializer`, which omits the
data type and puts it inside `data` as `__type`:

```json
{ "sensorStartTime": 1000000,
  "data": { "__type": "dk.cachet.carp.geolocation", "latitude": 55.78, "longitude": 12.52 } }
```

A measurement with the type *beside* `data` rather than inside it does not
decode. If one ever appears, the column was written by something other than this
path, and that is worth finding out rather than working around.

**The web service registers `Data` subclasses carp-dsp does not have** - consent,
diagnosis, phone number, date of birth and others. carp-core wraps an
unresolvable `__type` as `CustomData` instead of failing and recovers the type
from `data.className`, so those rows survive and land in the generic column.
They are also mostly participant details: the statement is the only thing
scoping what comes out.
