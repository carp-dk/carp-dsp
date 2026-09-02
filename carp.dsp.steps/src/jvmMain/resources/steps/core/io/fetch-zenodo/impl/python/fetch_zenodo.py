#!/usr/bin/env python3
"""Library step: fetch-zenodo.

Download a file from a URL and, if it is a zip archive, extract one named member.
Generic and domain-agnostic: the archive URL and the member path are options, so
the step carries no dataset knowledge. Used to pull open datasets (e.g. from
Zenodo) into a workflow.

Downloads are cached under a filename derived from the URL, so two uses of this
step in one workflow cannot serve each other's data. See `cache_path_for`.
"""
from __future__ import annotations

import argparse
import hashlib
import pathlib
import shutil
import urllib.request
import zipfile

# Enough digest to make a collision between two URLs implausible while keeping
# the filename readable.
_DIGEST_CHARS = 16


def cache_path_for(cache_option: str, url: str) -> pathlib.Path:
    """Return the cache path for a URL.

    The cache filename is derived from `cache_option` and includes a digest of
    `url`, ensuring different URLs use different cache entries. For example,
    `.cache/download.bin` becomes `.cache/download-<digest>.bin`.

    Args:
        cache_option: The `--cache` path template.
        url: The URL being fetched.

    Returns:
        Path to the URL-specific cache entry.
    """
    base = pathlib.Path(cache_option)
    digest = hashlib.sha256(url.encode("utf-8")).hexdigest()[:_DIGEST_CHARS]
    return base.with_name(f"{base.stem}-{digest}{base.suffix}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True, help="archive or file URL")
    ap.add_argument("--member", default="", help="path of the member to extract, if the URL is a zip")
    ap.add_argument("--output", required=True, help="output file path")
    ap.add_argument(
        "--cache",
        default=".cache/download.bin",
        help="download cache path; the URL's digest is added to the filename",
    )
    a = ap.parse_args()

    cache = cache_path_for(a.cache, a.url)
    cache.parent.mkdir(parents=True, exist_ok=True)
    if not cache.exists():
        print(f"[fetch-zenodo] downloading {a.url}")
        urllib.request.urlretrieve(a.url, cache)
    else:
        print(f"[fetch-zenodo] using cached {cache}")

    if a.member:
        with zipfile.ZipFile(cache) as zf, open(a.output, "wb") as out:
            out.write(zf.read(a.member))
        print(f"[fetch-zenodo] extracted '{a.member}' -> {a.output}")
    else:
        shutil.copyfile(cache, a.output)
        print(f"[fetch-zenodo] wrote {a.output}")


if __name__ == "__main__":
    main()
