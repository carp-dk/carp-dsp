# core.io.query-sql

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

Run one read statement against a database and write the result as a table.

```
query (txt)  ->  rows (csv)
```

## Overview

This step is how a workflow gets at data that lives in a database rather than a
file. The statement is an input, so a workflow supplies its own; the database is
named by the environment or a connection file, so the same step runs against
whichever one the environment points at.

It knows nothing about any schema, which is what places it in the `core` tier.
Reading CARP's own database is one use of it, and the query for that is in
[docs/CARP_DATABASE_COUPLING.md](../../../../../../../../docs/CARP_DATABASE_COUPLING.md);
the step itself is no more about CARP than `core.io.fetch-zenodo` is about
Fitbit.

This step requires network access to a database, and a JDBC driver for it on the
task runtime. Postgres ships; another database needs its driver added there.

## Data it needs

One input: the statement to run.

- **Granularity**: whatever the statement selects.
- **Units**: none. Every value is written as the database rendered it.
- **CARP data types**: none. The step moves rows without interpreting them.
- **Missing data**: a SQL NULL is written as an empty cell.

The statement must be a single `SELECT` or `WITH`. Anything else is refused
before a connection is opened - comments and quoted text are ignored when
deciding, so a `;` or a keyword inside a literal does not count. That rejects
mistakes, not attackers: a read-only database role is what actually prevents a
write.

## What you get

The result set as CSV, header first. Column names are the statement's labels, so
an `AS` alias is what comes out:

```csv
id,label,bpm
1,morning,60
2,midday,72
3,,
```

Values are whatever the driver renders as text, which for a JSON column is the
JSON itself. Nothing is parsed, re-typed or reformatted.

The file appears only once the whole result has been read. A read that fails
part way leaves nothing behind, rather than a partial table the next step would
consume as if it were whole.

## How it works

1. Read the statement from the input and replace each `:name` with `?`, keeping
   the names in the order their placeholders appear.
2. Open a connection from the connection file, with any environment variable
   overriding it, and set it read-only.
3. Bind each `--param` by name and stream the result, a fetch-size batch at a
   time.
4. Write the rows beside the output and move the file into place.

## Choices and limits

**A row cap fails, it does not truncate.** `--max-rows 1000` asks the database
for 1001 rows and fails on reading the last, so an over-large result is an error
rather than a table that silently depends on a limit. It is a guard, not a page
size - `--fetch-size` is the page size.

**An empty result fails**, unless `--allow-empty` is given. A query matching
nothing is usually a mis-scoped query, and a header-only table hides that until
much further downstream.

**No connection setting is an argument.** The url, user and password come from a
connection file or from `CARP_DSP_SQL_URL`, `CARP_DSP_SQL_USER` and
`CARP_DSP_SQL_PASSWORD`. A process listing shows arguments to every user on the
host; it does not show a file or an environment.

**Parameters are bound, never interpolated.** A `--param` reaches the database as
a value to compare against, so it cannot change what the statement does. A
parameter naming no placeholder is an error, so a typo is not silently ignored.

**A parameter is sent as text, so compare it to a number with a cast.** A value
on a command line carries no type, so every `--param` is bound as a string.
Postgres has no `integer = varchar` and refuses `WHERE id = :id` outright; write
`WHERE id = CAST(:id AS integer)`. Databases that coerce will take either.

**Everything is text.** Dates, numbers and JSON all arrive as the driver's string
form, which differs between databases. A workflow that needs a type converts
downstream.

**One statement, one connection, no transaction of your own.** There is no way
to run a setup statement first, and no way to hold a cursor across steps.

## Options

| Option              | Default            | Meaning                                       |
|---------------------|--------------------|-----------------------------------------------|
| `--query-file`      | required (`input.0`) | The statement to run                        |
| `--output`          | required           | CSV file to write                             |
| `--param`           | none               | Repeatable `name=value`, bound to a `:name`   |
| `--connection-file` | none               | Properties file: `url`, `user`, `password`    |
| `--max-rows`        | no cap             | Fail rather than return more than this many   |
| `--fetch-size`      | 1000               | Rows the driver reads at a time               |
| `--allow-empty`     | off                | Treat a result with no rows as a result       |

Override these per use with `args:` on a `uses:` reference - the defaults here
run the reference fixture, so a real use always supplies at least its own query.

## References

No method paper: this is a query and a CSV write. The formats it produces:

- RFC 4180, *Common Format and MIME Type for Comma-Separated Values (CSV)
  Files*. <https://www.rfc-editor.org/rfc/rfc4180>

## Implementations

| Language | Path                           |
|----------|--------------------------------|
| Kotlin   | `impl/kotlin/main/QuerySql.kt` |

Kotlin steps are laid out differently from Python ones: `impl/kotlin/main` is a
source root of `carp.dsp.steps` and `impl/kotlin/test` a test source root, so the
classes in the shared task runtime are compiled from exactly the files this step
publishes. Tests run with the module:

```bash
./gradlew :carp.dsp.steps:jvmTest
```

The fixture is three files. `reference/query.sql` is the declared input and
`reference/expected.csv` the expected output; `reference/seed.sql` builds the
table they describe, because a step that reads a database needs the database
fixed as well as the input. `QuerySqlFixtureTest` applies the seed to an
in-memory database, runs the step with the arguments `step.yaml` declares, and
asserts the published bytes.
