# core.io.fetch-zenodo

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

Download a file from a URL and, if it is a zip archive, extract one named member.

```
(no input)  ->  file (csv)
```

## Overview

This step downloads a file and exposes it as a workflow input. It makes data
retrieval an explicit part of the pipeline, allowing datasets and sources to be
documented, configured, and reused rather than embedded in scripts.

Use it as the first step in workflows that depend on published datasets. The
step is dataset-agnostic: by changing the URL and archive member, it can fetch
any supported file. The default configuration downloads hourly step-count data
from the open Fitbit dataset on Zenodo.

This step requires network access.

## Data it needs

Nothing. The step has no input port; everything it needs is an argument.

- **Granularity**: not applicable.
- **Units**: not applicable.
- **CARP data types**: none. The step moves bytes without interpreting them,
  which is what places it in the `core` tier.
- **Missing data**: not applicable. A failed download or a missing archive member
  raises an error rather than producing an empty file.

## What you get

Whatever was downloaded, unchanged. With `--member` set, the output is that one
member extracted from the zip; without it, the downloaded file copied verbatim.
No parsing, validation or re-encoding happens, so the output's schema is the
source's schema.

With the defaults, that is the hourly step counts from Zenodo record 53894:

```csv
Id,ActivityHour,StepTotal
1,2016-04-12 08:00:00,200
1,2016-04-12 09:00:00,250
1,2016-04-12 10:00:00,300
2,2016-04-12 08:00:00,500
```

Note the column names: `Id`, `ActivityHour`, `StepTotal` - the source's, not the
library's. Nothing downstream in this library consumes that schema directly, so a
rename is normally the next step, for example via `core.reshape.join-tables`'
`--left-rename`.

## How it works

1. Work out this URL's cache entry from `--cache` and the URL, creating the
   directory if needed.
2. If no file exists at that entry, download `--url` to it. If one exists, reuse
   it.
3. With `--member` set, open the cached file as a zip and write that member to
   `--output`. Without it, copy the cached file to `--output`.

The only computation is the cache entry's name, from the `--cache` path and the
URL:

```
dir    = directory of --cache
stem   = filename of --cache without its suffix
suffix = suffix of --cache

entry  = dir / (stem + "-" + sha256(url)[:16] + suffix)
```

So `--cache .cache/download.bin` fetching two different URLs gives
`.cache/download-<digest a>.bin` and `.cache/download-<digest b>.bin`. Otherwise,
the bytes written are the bytes read.

## Choices and limits

**The cache is keyed by URL, so `--cache` sets where downloads are cached, not which one is served.** Two uses of this
step sharing a cache path get separate entries; two uses of the same URL share one download, so fetching two members of
one archive still downloads it once.

**A cache entry is never invalidated.** The same URL always serves the first download, however old. That is what makes a
re-run cheap and repeatable, but it means a source that changes underneath you goes unnoticed. Delete the entry to force
a re-fetch.

**No verification.** No checksum, no size check, no content-type check. A truncated download or an HTML error page
saved as a CSV passes through to the next step, which will fail somewhere less obvious.

**The URL is not pinned to a version.** A URL that serves "latest" will change underneath you. Prefer a versioned record
URL - Zenodo DOIs resolve to specific deposit.

## Options

| Option     | Default                             | Meaning                                                               |
|------------|-------------------------------------|-----------------------------------------------------------------------|
| `--url`    | required (defaulted in `step.yaml`) | Archive or file URL                                                   |
| `--output` | required                            | Output file path                                                      |
| `--member` | none                                | Path of the member to extract, when the URL is a zip                  |
| `--cache`  | `.cache/download.bin`               | Where downloads are cached; the URL's digest is added to the filename |

Override these per use with `args:` on a `uses:` reference - this step is
designed to be configured that way.

## References

No method paper: this is a download and a zip extraction. The dataset the
defaults point at:

- Furberg, Brinton, Keating & Ortiz. *Crowdsourced Fitbit datasets*. Zenodo
  record 53894, CC BY 4.0. <https://zenodo.org/records/53894>

## Implementations

| Language | Path                          |
|----------|-------------------------------|
| Python   | `impl/python/fetch_zenodo.py` |

Every implementation must reproduce `reference/expected.csv` from
`reference/input.zip`. The archive is a small stand-in laid out like the Zenodo
one, holding the member the default arguments name plus one the step must
ignore, and it is served over a `file://` URL - so the fixture verifies the
extraction the defaults describe without asserting what a third party currently
hosts. Tests live beside the implementation and cover the extraction and caching
behaviour without touching the network:

```bash
python -m pytest impl/python -q
```
