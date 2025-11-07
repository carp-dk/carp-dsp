# Contributing to CARP DSP

Thank you for your interest in contributing to CARP DSP! This document provides guidelines and information for contributors.

## Getting Started

### Prerequisites
- JDK 17 or higher
- Git with submodules support
- Kotlin/Gradle knowledge

### Development Setup
1. Fork the repository
2. Clone with submodules:
   ```bash
   git clone --recurse-submodules https://github.com/yourusername/carp-dsp.git
   cd carp-dsp
   ```
3. Build and test:
   ```bash
   ./gradlew build
   ./gradlew test
   ```

## Development Workflow

### Branch Strategy
- `main` - Production-ready code
- `develop` - Integration branch for features
- `feature/*` - Feature development branches
- `hotfix/*` - Emergency fixes

### Making Changes
1. Create a feature branch:
   ```bash
   git checkout -b feature/your-feature-name
   ```
2. Make your changes
3. Run quality checks:
   ```bash
   ./gradlew detektPasses
   ./gradlew test
   ./gradlew koverHtmlReport
   ```
4. Commit with conventional commits:
   ```bash
   git commit -m "feat: add new tabular data operation"
   ```
5. Push and create a Pull Request

## Quality Standards

### Code Quality
- **Detekt**: All code must pass Detekt analysis
- **Testing**: Maintain 95%+ test coverage
- **Documentation**: Document public APIs with KDoc
- **Type Safety**: Prefer type-safe solutions

### Commit Messages
Follow [Conventional Commits](https://www.conventionalcommits.org/):
- `feat:` - New features
- `fix:` - Bug fixes
- `docs:` - Documentation changes
- `test:` - Test additions/changes
- `refactor:` - Code refactoring
- `chore:` - Maintenance tasks

### Pull Request Requirements
- ✅ All CI checks pass
- ✅ Code review approval
- ✅ Up-to-date with target branch
- ✅ Clear description of changes

## 🧪 Testing Guidelines

### Test Structure
```
src/
├── commonMain/kotlin/          # Implementation
├── commonTest/kotlin/          # Common tests
├── jvmMain/kotlin/            # JVM-specific code
└── jvmTest/kotlin/            # JVM-specific tests
```

### Test Categories
- **Unit Tests**: Test individual components
- **Integration Tests**: Test component interactions
- **End-to-End Tests**: Test complete workflows

### Coverage Requirements
- Minimum 95% line coverage
- All public APIs must be tested
- Critical paths require comprehensive testing

## 📁 Project Structure

### Module Organization
```
carp.dsp.core/
├── domain/
│   ├── data/              # Data models and structures
│   └── execution/         # Workflow execution logic
└── application/           # Application services

carp.dsp.demo/
├── commonMain/            # Cross-platform demo code
└── jvmMain/              # JVM-specific utilities
```

### Package Conventions
- `domain` - Core business logic and models
- `application` - Application services and use cases
- `infrastructure` - External concerns (future)

## 🔧 Development Tools

### Recommended IDE Settings
- **IntelliJ IDEA**: Latest version with Kotlin plugin
- **Code Style**: Use project's `.editorconfig`
- **Inspections**: Enable Kotlin inspections

### Useful Gradle Tasks
```bash
# Development
./gradlew build                    # Full build
./gradlew :carp.dsp.core:jvmTest  # Core tests
./gradlew :carp.dsp.demo:jvmTest  # Demo tests

# Quality
./gradlew detekt                   # Code analysis
./gradlew koverHtmlReport         # Coverage report
./gradlew check                   # All checks

# Artifacts
./gradlew :carp.dsp.core:jvmJar   # Build core JAR
./gradlew publishToMavenLocal     # Local publishing
```

## Reporting Issues

### Bug Reports
Include:
- Clear description of the issue
- Steps to reproduce
- Expected vs actual behavior
- Environment details (OS, JDK version)
- Relevant code snippets or logs

### Feature Requests
Include:
- Clear description of the feature
- Use case and motivation
- Proposed API or implementation approach
- Impact on existing functionality


## 📚 Resources

- [CARP Documentation](https://carp.cachet.dk/)
- [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html)
- [Detekt Rules](https://detekt.github.io/detekt/)
- [Kover Coverage](https://github.com/Kotlin/kotlinx-kover)

## 💬 Community

- **Issues**: GitHub Issues for bugs and features
- **Discussions**: GitHub Discussions for questions
- **Code Review**: All changes go through pull request review

Thank you for contributing to CARP DSP! 🎉
