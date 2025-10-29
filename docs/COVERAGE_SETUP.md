# Code Coverage Setup - Summary

## What Was Added

### 1. Kover (Kotlin Coverage Tool)
- **Version**: 0.8.3
- **Why Kover?**
  - Official JetBrains tool for Kotlin
  - Better Kotlin support than JaCoCo (inline functions, coroutines, etc.)
  - Seamless Kotlin Multiplatform integration
  - Native IntelliJ IDEA integration

### 2. Coverage Configuration

#### Coverage Thresholds
- **Overall project**: 70% minimum
- **Per-class**: 60% minimum

#### Report Formats
1. **HTML** → `build/reports/kover/html/index.html` (human-readable)
2. **XML** → `build/reports/kover/coverage.xml` (CI/CD integration)
3. **Console** → Terminal output for quick checks

### 3. Available Commands

```bash
# Generate HTML coverage report
./gradlew koverHtmlReport

# Generate XML coverage report (for CI/CD)
./gradlew koverXmlReport

# Verify coverage meets thresholds
./gradlew koverVerify

# Run tests with coverage
./gradlew test koverXmlReport

# Full check (tests + coverage + detekt)
./gradlew check
```

### 4. CI/CD Integration

#### GitHub Actions Workflow (`.github/workflows/ci.yml`)
- Runs on PR and push to `develop` and `main`
- Two parallel jobs:
  1. **Build & Test** - Runs tests with coverage
     - Uploads coverage to Codecov
     - Stores coverage reports as artifacts
  2. **Code Quality** - Runs Detekt static analysis

#### Codecov Integration
- Coverage trends over time
- PR comments with coverage diff
- Badge for README
- **Setup Required**: Add `CODECOV_TOKEN` to GitHub secrets

### 5. Best Practices Applied

- ✅ **Excludes test code** from coverage metrics
- ✅ **Multiple report formats** for different use cases
- ✅ **CI/CD integration** with Codecov
- ✅ **Threshold enforcement** (can fail builds)
- ✅ **Artifact preservation** for debugging
- ✅ **Coverage badge** ready for README

### 6. Next Steps

1. **Configure Codecov** (optional `.codecov.yml`):
   ```yaml
   coverage:
     status:
       project:
         default:
           target: 70%
       patch:
         default:
           target: 60%
   ```

2. **Adjust thresholds** as needed in `build.gradle.kts`:
   ```kotlin
   rule {
       minBound(70)  // Adjust this value
   }
   ```

3. **Add coverage to PR requirements**:
   - See detailed guide: [Branch Protection Setup](BRANCH_PROTECTION_SETUP.md)
   - Quick summary: GitHub → Settings → Branches → Protect `develop` → Require status checks
   - Add `build-and-test` and `code-quality` as required checks

## Configuration Details

### Kover Plugin Location
- `gradle/libs.versions.toml` - Version definition
- `build.gradle.kts` - Plugin application and configuration

### Coverage Exclusions
- Test classes: `*Test`, `*Tests`, `*Spec`
- Test packages: `*.test`, `*.test.*`

### Report Triggers
- `onCheck = true` - Reports generated during `./gradlew check`
- Can be run independently with specific tasks

## Troubleshooting

### Coverage too low?
1. Check `build/reports/kover/html/index.html` for details
2. Add more tests for uncovered code
3. Temporarily lower threshold for gradual improvement

### Coverage not generating?
1. Ensure tests actually ran: `./gradlew test`
2. Check Gradle version compatibility (need Gradle 7.5+)
3. Clear cache: `./gradlew clean`

### CI failing on coverage?
1. `continue-on-error: true` is set initially (just warns)
2. Remove this once coverage is stable
3. Check artifacts for detailed reports

## Resources

- [Kover Documentation](https://github.com/Kotlin/kotlinx-kover)
- [Codecov Documentation](https://docs.codecov.com/)
- [CARP Core Coverage](https://github.com/cph-cachet/carp.core-kotlin) - Reference implementation

---

**Last Updated**: October 29, 2025
**Status**: ✅ Configured and ready to use

