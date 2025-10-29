# carp-dsp
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
./gradlew koverHtmlReport
```
View the report: `build/reports/kover/html/index.html`

**Check coverage thresholds:**
```bash
./gradlew koverVerify
```
- Minimum overall coverage: 70%
- Minimum per-class coverage: 60%

**Generate XML report (for CI/CD):**
```bash
./gradlew koverXmlReport
```
Output: `build/reports/kover/coverage.xml`

### Code Quality
**Run Detekt (static analysis):**
```bash
./gradlew detektAll
```

**Run all checks (tests + coverage + detekt):**
```bash
./gradlew check
```

