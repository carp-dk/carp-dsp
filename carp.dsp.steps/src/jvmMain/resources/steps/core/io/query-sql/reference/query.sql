-- Every column is aliased with a quoted name, so the header is the statement's
-- and not the database's: an unquoted identifier comes back uppercased on some.
SELECT id    AS "id",
       label AS "label",
       bpm   AS "bpm"
FROM reading
WHERE id >= :minId
ORDER BY id
