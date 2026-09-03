-- The four columns-and-tables `docs/CARP_DATABASE_COUPLING.md` reads, cut down
-- to what the documented query touches. Deliberately not the web service's
-- migration set: the schema this agrees with is checked against the dev server
-- by hand, not here. What this database is for is the parts of the step only a
-- real driver can exercise - jsonb, bound parameter types, SQLState classes.

CREATE TABLE recruitment_participant_groups (
    study_id    VARCHAR(255) NOT NULL,
    group_id    VARCHAR(255) NOT NULL, -- == study_deployment_id once deployed
    is_deployed BOOLEAN      NOT NULL
);

CREATE TABLE data_stream_ids (
    id                 INTEGER PRIMARY KEY,
    study_deployment_id VARCHAR(255) NOT NULL,
    device_role_name   VARCHAR(255),
    name               VARCHAR(255),
    name_space         VARCHAR(255)
);

CREATE TABLE data_stream_sequence (
    id                INTEGER PRIMARY KEY,
    data_stream_id    INTEGER,
    first_sequence_id INTEGER,
    snapshot          JSONB
);

-- One study, one deployed group and one still staged; two sequences on the
-- deployed group's stream, so the ORDER BY has something to order.
INSERT INTO recruitment_participant_groups (study_id, group_id, is_deployed)
VALUES ('11111111-0000-4000-8000-000000000001', '4f2b8a10-0000-4000-8000-000000000001', TRUE),
       ('11111111-0000-4000-8000-000000000001', '4f2b8a10-0000-4000-8000-0000000000ff', FALSE);

INSERT INTO data_stream_ids (id, study_deployment_id, device_role_name, name_space, name)
VALUES (1, '4f2b8a10-0000-4000-8000-000000000001', 'phone', 'dk.cachet.carp', 'stepcount'),
       (2, '4f2b8a10-0000-4000-8000-0000000000ff', 'phone', 'dk.cachet.carp', 'stepcount');

INSERT INTO data_stream_sequence (id, data_stream_id, first_sequence_id, snapshot)
VALUES (1, 1, 0, '{"measurements":[{"sensorStartTime":1000000,"data":{"__type":"dk.cachet.carp.stepcount","steps":1000}},{"sensorStartTime":2000000,"data":{"__type":"dk.cachet.carp.stepcount","steps":2000}}],"triggerIds":[1],"syncPoint":{"synchronizedOn":"1970-01-01T00:00:00Z","sensorTimestampAtSyncPoint":0,"relativeClockSpeed":1.0}}'),
       (2, 1, 2, '{"measurements":[{"sensorStartTime":3000000,"data":{"__type":"dk.cachet.carp.stepcount","steps":3000}}],"triggerIds":[1],"syncPoint":{"synchronizedOn":"1970-01-01T00:00:00Z","sensorTimestampAtSyncPoint":0,"relativeClockSpeed":1.0}}'),
       (3, 2, 0, '{"measurements":[{"sensorStartTime":9000000,"data":{"__type":"dk.cachet.carp.stepcount","steps":9}}],"triggerIds":[1],"syncPoint":{"synchronizedOn":"1970-01-01T00:00:00Z","sensorTimestampAtSyncPoint":0,"relativeClockSpeed":1.0}}');
