# carp-dsp

[![CI](https://github.com/carp-dk/carp-dsp/actions/workflows/ci.yml/badge.svg)](https://github.com/carp-dk/carp-dsp/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/carp-dk/carp-dsp/branch/develop/graph/badge.svg)](https://codecov.io/gh/carp-dk/carp-dsp)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-blue.svg?logo=kotlin)](https://kotlinlang.org)

CARP-DSP (Digital Science Pipelines) extends the CARP ecosystem with a framework for defining, executing, and sharing FAIR, reproducible data-science workflows for digital phenotyping and mobile sensing research.



## Development

### Building
```bash
./gradlew build
```

### Running Tests
```bash
./gradlew test
```

### Code Coverage
This project uses [Kover](https://github.com/Kotlin/kotlinx-kover) for code coverage reporting.

**Generate coverage reports:**
```bash
./gradlew test koverHtmlReport
```

**View reports:**
- Root project: `build/reports/kover/html/index.html` (aggregated coverage)
- Per-module: `<module>/build/reports/kover/html/index.html` (e.g., `detekt/build/reports/kover/html/index.html`)

**Generate XML report (for CI/CD):**
```bash
./gradlew koverXmlReport
```
Output: `build/reports/kover/coverage.xml` (root) or `<module>/build/reports/kover/coverage.xml` (per-module)

### Code Quality
**Run Detekt (static analysis):**
```bash
./gradlew detektAll
```

**Run all checks (tests + coverage + detekt):**
```bash
./gradlew check
```

