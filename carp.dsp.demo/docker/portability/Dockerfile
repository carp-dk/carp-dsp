# Portability eval image (Option B): a glibc Linux distro + Pixi + the HR/step pipeline.
# One image per base distro; the pinned env (pixi.lock) is identical across all of them,
# so only the OS differs. Build context is assembled by run-portability.sh.
ARG BASE=ubuntu:22.04
FROM ${BASE}

SHELL ["/bin/bash", "-lc"]

# curl + ca-certificates for the Pixi installer (distro-agnostic, best effort)
RUN (command -v curl >/dev/null 2>&1) || \
    (command -v apt-get  >/dev/null && apt-get update && apt-get install -y --no-install-recommends curl ca-certificates) || \
    (command -v dnf      >/dev/null && dnf install -y curl ca-certificates) || \
    (command -v microdnf >/dev/null && microdnf install -y curl ca-certificates)

RUN curl -fsSL https://pixi.sh/install.sh | bash
ENV PATH="/root/.pixi/bin:${PATH}"

WORKDIR /work
COPY pixi.toml pixi.lock run_pipeline.sh /work/
COPY hr_lib/ /work/hr_lib/
COPY data/  /work/data/

# Solve/install the pinned env at build time (fails loudly if the lock is missing/stale)
RUN pixi install --manifest-path /work/pixi.toml --locked
RUN chmod +x /work/run_pipeline.sh

CMD ["/work/run_pipeline.sh"]
