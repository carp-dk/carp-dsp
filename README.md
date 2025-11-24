# CARP DSP - Data Science Platform

[![CI](https://github.com/ngreve/carp-dsp/actions/workflows/ci.yml/badge.svg)](https://github.com/ngreve/carp-dsp/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/ngreve/carp-dsp/branch/main/graph/badge.svg)](https://codecov.io/gh/ngreve/carp-dsp)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-blue.svg?logo=kotlin)](https://kotlinlang.org)

A Kotlin Multiplatform framework for CARP (Copenhagen Research Platform) data science and analytics processing.

## 🚀 Features

- **Type-Safe Tabular Data**: Modern tabular data structures with full CARP semantics preservation
- **Multiplatform Support**: Kotlin Multiplatform targeting JVM (with future JS/Native support)
- **CARP Integration**: Seamless integration with existing CARP data structures and workflows
- **Execution Framework**: Pluggable execution strategies for data processing workflows
- **Python Integration**: First-class support for Python scripts and conda environments
- **Data Retrieval**: Built-in HTTP-based data retrieval with retry logic
- **Auto Environment Setup**: Automatic conda environment creation and management

## 📚 Documentation

**[📖 Quick Start Guide](QUICKSTART.md)** - Get started in minutes!

**[📘 Complete Documentation](docs/DSP_DOCUMENTATION.md)** - Comprehensive guide covering:
- Architecture and design decisions
- Module structure and components  
- Demos and examples
- API reference
- Troubleshooting

**Quick Links**:
- [Getting Started](docs/DSP_DOCUMENTATION.md#getting-started)
- [Demos and Examples](docs/DSP_DOCUMENTATION.md#demos-and-examples)
- [Architecture Overview](docs/DSP_DOCUMENTATION.md#architecture)
- [Documentation Index](docs/README.md)

## 🔧 Development

### Prerequisites
- JDK 17 or higher
- Kotlin 2.1.20+
- (Optional) Conda - for running Python-based demos

### Quick Start

```bash
# Clone and build
git clone https://github.com/ngreve/carp-dsp.git
cd carp-dsp
./gradlew build

# Run a demo
./gradlew :carp.dsp.demo:jvmRun -PmainClass=carp.dsp.demo.DexcomRetrievalDemoMainKt
```

See [Demos and Examples](docs/DSP_DOCUMENTATION.md#demos-and-examples) for more examples.

### Building
```bash
./gradlew build
```

### Running Tests
```bash
# All tests
./gradlew test

# Module-specific tests
./gradlew :carp.dsp.core:jvmTest
./gradlew :carp.dsp.demo:jvmTest
```

### Code Quality
```bash
# Run Detekt analysis
./gradlew detektPasses

# Generate coverage reports
./gradlew koverHtmlReport
./gradlew koverXmlReport
```

### Building Artifacts
```bash
# Build JAR files
./gradlew :carp.dsp.core:jvmJar
./gradlew :carp.dsp.demo:jvmJar
```

## 🎯 CI/CD Workflows

The project includes comprehensive GitHub Actions workflows:

### Main CI Pipeline (`.github/workflows/ci.yml`)
-  Multiplatform builds and testing
-  Detekt code quality analysis  
-  Kover coverage reporting
-  Codecov integration
-  Artifact generation

### Documentation & Artifacts (`.github/workflows/docs-and-artifacts.yml`)
-  JAR artifact building
-  Quality gates and verification
-  Coverage report uploads

## 📊 Coverage Reports

Coverage reports are automatically generated and uploaded:
- **HTML Report**: Available as CI artifacts
- **XML Report**: Integrated with Codecov
- **Badge**: Shows current coverage status

## 🏗️ Architecture

```
carp-dsp/
├── carp.dsp.core/          # Core framework
│   ├── domain/             # Domain models and logic
│   │   ├── data/           # Tabular data structures
│   │   └── execution/      # Workflow execution
│   └── application/        # Data Converters
├── carp.dsp.demo/          # Demo and examples
└── .github/workflows/      # CI/CD automation
```

## 📈 Project Status

- ✅ **Core Framework**: Complete with comprehensive testing
- ✅ **CI/CD Pipeline**: Fully automated with quality gates
- ✅ **Demo Implementation**: Working CLI examples with DEXCOM CGM analysis
- ✅ **Python Integration**: Conda environment management and script execution
- ✅ **Documentation**: Comprehensive documentation available
- 🚧 **Dokka Integration**: API docs generation in progress
- 🚧 **Additional Platforms**: JS/Native support planned

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'feat: add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

All PRs are automatically tested with the CI pipeline and require:
- ✅ All tests passing
- ✅ Detekt quality checks
- ✅ Coverage requirements met

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
