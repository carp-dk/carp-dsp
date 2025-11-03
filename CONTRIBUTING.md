# Contributing to CARP-DSP

Thank you for considering contributing to CARP-DSP! We welcome contributions from the community.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Workflow](#development-workflow)
- [Coding Standards](#coding-standards)
- [Testing](#testing)
- [Documentation](#documentation)
- [Submitting Changes](#submitting-changes)

## Code of Conduct

This project adheres to the Contributor Covenant [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## Getting Started

### Prerequisites

- Java 17 or higher
- Git

### Clone and Build

```bash
git clone https://github.com/carp-dk/carp-dsp.git
cd carp-dsp
./gradlew build
```

### Run Tests

```bash
./gradlew test
```

### Check Code Quality

```bash
./gradlew detektAll
```

## Development Workflow

We follow a GitFlow-inspired workflow:

1. **Fork** the repository (for external contributors)
2. **Create a feature branch** from `develop`:
   ```bash
   git checkout develop
   git pull
   git checkout -b feature/your-feature-name
   ```
3. **Make your changes** following our coding standards
4. **Test your changes**:
   ```bash
   ./gradlew test koverHtmlReport
   ./gradlew detektAll
   ```
5. **Commit your changes** with clear messages:
   ```bash
   git commit -m "Add feature: description of your changes"
   ```
6. **Push to your fork**:
   ```bash
   git push origin feature/your-feature-name
   ```
7. **Create a Pull Request** to `develop`

## Coding Standards

This project follows the [standard Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html), with CARP-specific modifications:

### Spacing in Parentheses

Spaces are required in all parentheses, **except** for higher-order functions:

```kotlin
// ✅ Correct
if ( true )
{
    val answer = 42
}

fun test( a: Int, b: Int ): Int
{
    return a + b
}

// ✅ Correct (higher-order functions - no spaces)
val higherOrder: (Int, Int) -> Int = { a, b -> a + b }

// ❌ Incorrect
if (true) {
    val answer = 42
}
```

### Curly Braces on Separate Lines

Curly braces of **multi-line** blocks must be placed on separate lines, aligned with the start of the definition:

```kotlin
// ✅ Correct
class Example
{
    fun test(): Int
    {
        return 42
    }
}

// ✅ Correct (single-line blocks are allowed)
class OneLine { val x = 42 }

// ❌ Incorrect
class Example {
    fun test(): Int {
        return 42
    }
}
```

**Exception:** Trailing lambda arguments:

```kotlin
// ✅ Correct (lambda on multiple lines)
list.forEach {
    println( it )
}
```

### Running Detekt

Our custom Detekt rules enforce these standards:

```bash
./gradlew detektAll
```

Fix any reported issues before submitting a PR.

## Testing

### Writing Tests

- Use `kotlin.test` for common tests
- Place tests in appropriate source sets:
  - `commonTest` for cross-platform code
  - `jvmTest` for JVM-specific code
  - `jsTest` for JS-specific code

### Test Structure

Test namespaces and classes should mirror the main source code:

```
src/
  commonMain/kotlin/carp/dsp/MyClass.kt
  commonTest/kotlin/carp/dsp/MyClassTest.kt
```

### Coverage Requirements

- Minimum overall coverage: **70%**
- Minimum per-class coverage: **60%**

Check coverage:

```bash
./gradlew test koverHtmlReport
# Open: build/reports/kover/html/index.html
```

## Documentation

### Code Documentation

- Add KDoc comments for public APIs
- Include examples for complex functions
- Document parameters and return values

```kotlin
/**
 * Calculates the sum of two integers.
 *
 * @param a The first integer
 * @param b The second integer
 * @return The sum of [a] and [b]
 */
fun sum( a: Int, b: Int ): Int = a + b
```

### Updating Documentation

When adding features:
- Update README.md if user-facing
- Add/update docs/ files for detailed guides
- Update CHANGELOG.md (see below)

## Submitting Changes

### Pull Request Process

1. **Ensure all checks pass**:
   - ✅ Build succeeds
   - ✅ Tests pass
   - ✅ Coverage meets thresholds
   - ✅ Detekt passes

2. **Update CHANGELOG.md**:
   ```markdown
   ## [Unreleased]
   ### Added
   - Description of your feature
   ```

3. **Create a clear PR description**:
   - What does this PR do?
   - Why is it needed?
   - How was it tested?

4. **Link related issues**: Use `Closes #123` in the PR description

5. **Request review** from maintainers

### PR Review Criteria

Your PR will be reviewed for:
- ✅ Code quality and style
- ✅ Test coverage
- ✅ Documentation
- ✅ No breaking changes (without discussion)
- ✅ Follows contribution guidelines

### After Approval

Once approved, a maintainer will merge your PR to `develop`. Your contribution will be included in the next release!

## Release Workflow

**For Maintainers:**

- **develop**: Integration branch, receives all PRs
- **main**: Stable releases only

To create a release:
1. Create release branch from develop: `release/x.y.z`
2. Update version in `gradle.properties`
3. Update CHANGELOG.md
4. Merge to main via PR
5. Tag the release: `git tag -a vx.y.z -m "Release x.y.z"`
6. Merge back to develop

## Getting Help

- 💬 **Discussions**: Use GitHub Discussions for questions
- 🐛 **Bugs**: Open an issue with the bug template
- 💡 **Features**: Open an issue with the feature request template

## Recognition

Contributors are recognized in:
- The project README (via All Contributors bot)
- Release notes
- CHANGELOG.md

Thank you for contributing to CARP-DSP! 🎉

