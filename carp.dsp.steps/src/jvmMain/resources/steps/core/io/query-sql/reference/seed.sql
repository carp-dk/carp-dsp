-- The table the reference query reads. Applied by QuerySqlFixtureTest, not by
-- the step: several statements, and the step runs exactly one.
CREATE TABLE reading (id INT, label VARCHAR(32), bpm INT);
INSERT INTO reading VALUES (1, 'morning', 60);
INSERT INTO reading VALUES (2, 'midday', 72);
INSERT INTO reading VALUES (3, NULL, NULL)
