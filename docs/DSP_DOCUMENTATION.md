# CARP DSP - Data Science Platform Documentation

**Version**: 0.1.0  
**Last Updated**: November 24, 2025  
**Status**: Production Ready

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Modules](#modules)
4. [Design Decisions](#design-decisions)
5. [Demos and Examples](#demos-and-examples)
6. [Getting Started](#getting-started)
7. [Troubleshooting](#troubleshooting)
8. [Development History](#development-history)

---

## Overview

CARP DSP is a **Kotlin Multiplatform framework** for data science and analytics processing within the Copenhagen Research Platform (CARP) ecosystem. It provides type-safe data structures, execution strategies, and seamless integration with external data processing tools (Python, R, etc.).

### Key Features

- 🎯 **Type-Safe Tabular Data**: Modern tabular data structures with full CARP semantics preservation
- 🔄 **Multiplatform Support**: Kotlin Multiplatform targeting JVM (with future JS/Native support)
- 🔌 **Pluggable Execution**: Flexible execution strategies for data processing workflows
- 🐍 **Python Integration**: First-class support for Python scripts and conda environments
- 📊 **Data Retrieval**: Built-in HTTP-based data retrieval with retry logic
- 🧪 **Analytics Ready**: Integration with cgmquantify and other analytics libraries

---

## Architecture

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        CARP DSP                              │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Domain     │  │ Application  │  │Infrastructure│      │
│  │              │  │              │  │              │      │
│  │ • Execution  │  │ • Converters │  │ • Executors  │      │
│  │ • Data       │  │ • Processes  │  │ • Strategies │      │
│  │ • Process    │  │ • Environment│  │              │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                               │
└─────────────────────────────────────────────────────────────┘
         ▲                    ▲                    ▲
         │                    │                    │
         └────────────────────┴────────────────────┘
                     CARP Analytics Core
           (Environment, Workflow, ExecutionContext)
```

### Layer Responsibilities

#### Domain Layer (`domain/`)
Contains core business logic and models:
- **Execution**: Execution strategies and context
- **Data**: Tabular data structures and schemas
- **Process**: Process definitions and specifications

#### Application Layer (`application/`)
Contains application services and use cases:
- **Converters**: Data stream to tabular data conversion
- **Processes**: Concrete process implementations (PythonProcess, etc.)
- **Environment**: Environment specifications (CondaEnvironment)

#### Infrastructure Layer (`infrastructure/`)
Contains concrete implementations:
- **Executors**: Process executors (PythonExecutor, PhysioNetRetrievalExecutor)
- **Strategies**: Execution strategy implementations (JvmSequentialExecutionStrategy)

---

## Modules

### 1. carp.dsp.core

**Purpose**: Core framework for data science processing

**Location**: `carp.dsp.core/`

#### Structure

```
carp.dsp.core/
├── src/
│   ├── commonMain/kotlin/carp/dsp/core/
│   │   ├── application/
│   │   │   ├── DataStreamBatchConverter.kt
│   │   │   ├── environment/
│   │   │   │   └── CondaEnvironment.kt
│   │   │   └── process/
│   │   │       └── PythonProcess.kt
│   │   ├── domain/
│   │   │   ├── data/
│   │   │   │   └── TabularData.kt
│   │   │   ├── execution/
│   │   │   │   └── SequentialExecutionStrategy.kt
│   │   │   └── process/
│   │   │       └── PhysioNetRetrievalProcess.kt
│   │   └── infrastructure/
│   │       └── process/
│   │           └── (empty - JVM implementations)
│   └── jvmMain/kotlin/carp/dsp/core/
│       ├── application/
│       │   └── process/
│       │       └── PythonProcessUtils.kt
│       ├── domain/
│       │   └── execution/
│       │       └── JvmSequentialExecutionStrategy.kt
│       └── infrastructure/
│           └── execution/
│               ├── ProcessExecutor.kt
│               ├── PythonExecutor.kt
│               ├── EnvironmentSetupExecutor.kt
│               ├── PhysioNetRetrievalExecutor.kt
│               └── DataRetrievalExecutorFactory.kt
└── build.gradle.kts
```

#### Key Components

##### DataStreamBatchConverter
**Location**: `application/DataStreamBatchConverter.kt`  
**Purpose**: Converts CARP DataStreamBatch to TabularData

**Features**:
- Type-safe conversion
- Metadata preservation
- Schema extraction

##### TabularData
**Location**: `domain/data/TabularData.kt`  
**Purpose**: Core data structure for tabular data

**Features**:
- Row-based access
- Column schema
- Type information
- Metadata support

##### SequentialExecutionStrategy
**Location**: `domain/execution/SequentialExecutionStrategy.kt`  
**Purpose**: Base execution strategy for workflows

**Features**:
- Sequential step execution
- Data registry management
- Input/output resolution
- Process dispatching

##### JvmSequentialExecutionStrategy
**Location**: `domain/execution/JvmSequentialExecutionStrategy.kt`  
**Purpose**: JVM-specific implementation with actual I/O

**Features**:
- HTTP data retrieval
- External process execution
- File system operations
- Coroutine-based async operations

##### PythonProcess
**Location**: `application/process/PythonProcess.kt`  
**Purpose**: Represents a Python script execution

**Properties**:
- `scriptPath`: Path to Python script
- `arguments`: Command-line arguments
- `environment`: Conda/venv environment specification

##### PythonExecutor
**Location**: `infrastructure/execution/PythonExecutor.kt`  
**Purpose**: Executes Python processes

**Features**:
- Conda environment validation
- Auto-environment creation
- Script execution
- Output capture
- Error handling

##### EnvironmentSetupExecutor
**Location**: `infrastructure/execution/EnvironmentSetupExecutor.kt`  
**Purpose**: Manages conda/venv environments

**Features**:
- Environment existence checking
- Auto-creation with dependencies
- Conda + pip package support
- Channel configuration
- Python version specification

##### PhysioNetRetrievalExecutor
**Location**: `infrastructure/execution/PhysioNetRetrievalExecutor.kt`  
**Purpose**: Downloads data from PhysioNet and other sources

**Features**:
- HTTP/HTTPS downloads via Ktor
- Retry logic with exponential backoff
- Authentication support (Basic, Bearer, ApiKey)
- Progress reporting
- Timeout configuration

#### Dependencies

```kotlin
commonMain {
    implementation("dk.cachet.carp:carp-core-common")
    implementation("dk.cachet.carp:carp-core-data")
    implementation("dk.cachet.carp:carp-core-analytics")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
}

jvmMain {
    implementation("io.ktor:ktor-client-cio:2.3.7")
}
```

---

### 2. carp.dsp.demo

**Purpose**: Demonstration applications and examples

**Location**: `carp.dsp.demo/`

#### Structure

```
carp.dsp.demo/
├── src/
│   ├── commonMain/kotlin/carp/dsp/demo/
│   │   ├── cgm/
│   │   │   └── (future CGM-specific demos)
│   │   ├── DexcomAnalysisDemo.kt
│   │   ├── DexcomRetrievalDemo.kt
│   │   └── DummyDemoProcess.kt
│   └── jvmMain/kotlin/carp/dsp/demo/
│       ├── DexcomAnalysisDemoMain.kt
│       └── DexcomRetrievalDemoMain.kt
├── scripts/
│   ├── analyze_cgm.py
│   └── setup-cgm-env.bat
└── build.gradle.kts
```

#### Demo Applications

See [Demos and Examples](#demos-and-examples) section for detailed information.

---

## Design Decisions

### 1. Multiplatform Architecture

**Decision**: Use Kotlin Multiplatform with common and JVM-specific source sets

**Rationale**:
- Domain logic is platform-agnostic
- Infrastructure (file I/O, process execution) is platform-specific
- Enables future JS/Native support
- Type safety across platforms

**Implementation**:
- `commonMain`: Domain models, interfaces, base strategies
- `jvmMain`: Concrete executors, I/O operations

---

### 2. Execution Strategy Pattern

**Decision**: Use pluggable execution strategies instead of hard-coded execution

**Rationale**:
- Flexibility to swap execution strategies
- Different platforms can have different implementations
- Easier testing (mock strategies)
- Clear separation of concerns

**Implementation**:
```kotlin
interface ExecutionStrategy {
    suspend fun execute(workflow: Workflow): Map<String, Any>
}

// Base implementation
class SequentialExecutionStrategy : ExecutionStrategy

// JVM-specific with I/O
class JvmSequentialExecutionStrategy : SequentialExecutionStrategy()
```

---

### 3. Executor Factory Pattern

**Decision**: Use factory pattern for process executors

**Rationale**:
- Decouples process types from executor implementations
- Allows runtime registration of executors
- Easier to extend with new process types
- Clear registration point in main()

**Implementation**:
```kotlin
class ExecutionFactory {
    fun <T : Process> register(
        processType: KClass<T>,
        executorProvider: () -> ProcessExecutor<T>
    )
    
    fun getExecutor(process: Process): ProcessExecutor<*>
}
```

---

### 4. Environment Auto-Creation

**Decision**: Automatically create missing conda environments

**Rationale**:
- Reduces manual setup steps
- Improves developer experience
- Ensures consistent environments
- Dependencies are version-controlled in code

**Implementation**:
```kotlin
fun ensureCondaEnvironment(
    envName: String,
    createIfMissing: Boolean = true,
    dependencies: List<String> = emptyList(),
    pythonVersion: String? = null,
    channels: List<String> = emptyList()
): Boolean
```

**Features**:
- Checks if environment exists
- Creates if missing (when enabled)
- Separates conda vs pip packages
- Configurable Python version and channels

---

### 5. Pip Package Support

**Decision**: Support both conda and pip packages with `pip:` prefix

**Rationale**:
- Some packages only available via pip (e.g., cgmquantify)
- Need to install conda packages first (including pip itself)
- Then install pip packages in the environment

**Implementation**:
```kotlin
dependencies = listOf(
    "pandas",           // conda package
    "pip:cgmquantify"   // pip package
)

// Execution:
// 1. conda create -n env python pandas pip --yes
// 2. conda run -n env pip install cgmquantify
```

---

### 6. Data Registry Pattern

**Decision**: Use in-memory data registry for passing data between steps

**Rationale**:
- Avoids unnecessary serialization
- Efficient for in-memory workflows
- Clear data flow
- Can be extended with persistent storage

**Implementation**:
```kotlin
class DataRegistry {
    private val data = mutableMapOf<String, Any>()
    
    fun register(key: String, value: Any)
    fun resolve(key: String): Any?
}
```

---

### 7. Serialization Support

**Decision**: Remove all non-serializable types (Any, Throwable, functions)

**Rationale**:
- Enable workflow serialization/deserialization
- Support workflow persistence
- Enable network transfer
- Kotlinx.serialization compatibility

**Changes**:
- `Map<String, Any>` → `Map<String, String>`
- `Throwable?` → `String?` (error message)
- Removed function fields
- Added `@Serializable` annotations

---

### 8. Retry Logic with Exponential Backoff

**Decision**: Implement retry logic for HTTP downloads

**Rationale**:
- Network calls can fail transiently
- Improves reliability
- Standard pattern for HTTP clients

**Implementation**:
```kotlin
// Retry delays: 1s, 2s, 4s, 8s, 10s (max)
private suspend fun downloadFile(
    url: String,
    maxRetries: Int = 3
): ExecutionOutput
```

---

### 9. Process Type Hierarchy

**Decision**: Define process types in CARP Analytics Core, implementations in DSP

**Rationale**:
- Core defines contracts
- DSP provides implementations
- Clear separation of concerns
- Reusable across projects

**Types**:
- `DataRetrievalProcess` → `PhysioNetRetrievalProcess`
- `AnalysisProcess` → (base type)
- `ExternalProcess` → `PythonProcess`

---

## Demos and Examples

### Demo 1: DEXCOM Data Retrieval

**Purpose**: Download DEXCOM continuous glucose monitoring (CGM) data from PhysioNet

**Location**: 
- Workflow: `carp.dsp.demo/src/commonMain/kotlin/carp/dsp/demo/DexcomRetrievalDemo.kt`
- Main: `carp.dsp.demo/src/jvmMain/kotlin/carp/dsp/demo/DexcomRetrievalDemoMain.kt`

#### What It Does

1. Downloads DEXCOM CSV files from PhysioNet Big Ideas Lab dataset
2. Saves files to `~/physionet-downloads/big-ideas/NNN/`
3. Supports multiple subjects (001, 002, 005, etc.)

#### How to Run

```bash
# From project root
./gradlew :carp.dsp.demo:jvmRun -PmainClass=carp.dsp.demo.DexcomRetrievalDemoMainKt
```

**Or from IntelliJ IDEA**:
1. Open `DexcomRetrievalDemoMain.kt`
2. Right-click on `fun main()`
3. Select "Run 'DexcomRetrievalDemoMainKt'"

#### Expected Output

```
=== DEXCOM Data Retrieval Demo ===

This demo will download DEXCOM CGM data from PhysioNet.

Subjects to download: [001]
Output directory: C:\Users\<username>\physionet-downloads\big-ideas\

Workflow: DEXCOM Data Retrieval
  Steps: 1
  Step 1: Download DEXCOM Files

Starting workflow execution...

Running step 1/1: Download DEXCOM Files
Handling data retrieval process: PhysioNetRetrievalProcess(...)
[PhysioNetRetrievalExecutor] Executing retrieval process...
[PhysioNetRetrievalExecutor] Files to download: 1

Downloading: Dexcom_001.csv
  URL: https://physionet.org/.../Dexcom_001.csv
  Attempt 1/3...
  ✓ Downloaded successfully (45.2 KB)

=== Download Summary ===
Total files: 1
Successful: 1
Failed: 0

✓ Files saved to: C:\Users\<username>\physionet-downloads\big-ideas\001\
```

#### Dataset Information

- **Source**: PhysioNet Big Ideas Lab
- **Type**: DEXCOM continuous glucose monitoring data
- **Format**: CSV files
- **Subjects**: Multiple subjects available (001, 002, 005, etc.)

---

### Demo 2: DEXCOM CGM Analysis

**Purpose**: Complete pipeline - download + analyze DEXCOM data using cgmquantify

**Location**:
- Workflow: `carp.dsp.demo/src/commonMain/kotlin/carp/dsp/demo/DexcomAnalysisDemo.kt`
- Main: `carp.dsp.demo/src/jvmMain/kotlin/carp/dsp/demo/DexcomAnalysisDemoMain.kt`
- Script: `carp.dsp.demo/scripts/analyze_cgm.py`

#### What It Does

1. **Step 1**: Downloads DEXCOM CSV files from PhysioNet
2. **Step 2**: Analyzes CGM data using Python/cgmquantify
   - Calculates 15+ glycemic metrics
   - Outputs JSON results

#### Prerequisites

**Option A: Auto-Creation (Recommended)**
- Just run the demo - environment will be created automatically!

**Option B: Manual Setup**
```bash
# Create conda environment
conda create -n cgm-analysis python=3.11 pandas -c conda-forge

# Activate and install cgmquantify
conda activate cgm-analysis
pip install cgmquantify

# Verify installation
python -c "import cgmquantify; print('Ready!')"
```

#### How to Run

```bash
# Single subject (001) - recommended for first run
./gradlew :carp.dsp.demo:jvmRun -PmainClass=carp.dsp.demo.DexcomAnalysisDemoMainKt

# Multiple subjects (001, 002, 005)
./gradlew :carp.dsp.demo:jvmRun -PmainClass=carp.dsp.demo.DexcomAnalysisDemoMainKt -DsingleSubject=false
```

**Or from IntelliJ IDEA**:
1. Open `DexcomAnalysisDemoMain.kt`
2. Right-click on `fun main()`
3. Select "Run 'DexcomAnalysisDemoMainKt'"

#### Expected Output

```
=== DEXCOM CGM Analysis Demo ===

This demo will:
1. Download DEXCOM data from PhysioNet
2. Analyze CGM data using cgmquantify

Prerequisites:
  ✓ Python 3.11+
  ✓ Conda environment 'cgm-analysis'
  ✓ Package: cgmquantify

Note: Missing environment will be created automatically!

Subjects to analyze: [001]

Workflow: DEXCOM CGM Analysis Pipeline
  Steps: 2
  Step 1: Download DEXCOM Files
  Step 2: Analyze CGM Data

Press ENTER to start...

Running step 1/2: Download DEXCOM Files
  ✓ Downloaded: Dexcom_001.csv (45.2 KB)

Running step 2/2: Analyze CGM Data
[PythonExecutor] Setting up execution environment...
[PythonExecutor] Checking conda environment 'cgm-analysis'...
✓ Conda environment 'cgm-analysis' exists.

[PythonExecutor] Executing Python script...
Executing: conda run -n cgm-analysis python C:\...\analyze_cgm.py --input C:\...\Dexcom_001.csv --output C:\...\analysis.json

=== CGM Analysis ===
Input: C:\...\Dexcom_001.csv
Output: C:\...\analysis.json

Loading data from Dexcom_001.csv...
✓ Loaded 2880 records

Calculating metrics...
✓ Mean glucose: 154.3 mg/dL
✓ Time in range (70-180): 68.5%
✓ GMI: 7.1%

Results saved to analysis.json

[PythonExecutor] ✓ Execution completed successfully
[PythonExecutor] Output: ...

✓ Analysis complete!

Results saved to:
  C:\Users\<username>\cgm-analysis-results\001\analysis.json
```

#### Analysis Metrics

The Python script calculates the following CGM metrics using cgmquantify:

**Basic Statistics**:
- Mean glucose
- Standard deviation
- Coefficient of variation (CV)

**Time in Range**:
- Time in range: 70-180 mg/dL
- Time below range: < 70 mg/dL
- Time above range: > 180 mg/dL

**Hypoglycemia**:
- Time < 70 mg/dL
- Time < 54 mg/dL
- Low Blood Glucose Index (LBGI)

**Hyperglycemia**:
- Time > 180 mg/dL
- Time > 250 mg/dL
- High Blood Glucose Index (HBGI)

**Advanced Metrics**:
- J-Index
- eA1c (estimated A1c)
- GMI (Glucose Management Indicator)

#### Output Format

Results are saved as JSON:
```json
{
  "subject_id": "001",
  "total_records": 2880,
  "duration_days": 2.0,
  "metrics": {
    "mean_glucose": 154.3,
    "std_glucose": 45.2,
    "cv": 29.3,
    "time_in_range_70_180": 68.5,
    "time_below_70": 8.2,
    "time_above_180": 23.3,
    "gmi": 7.1,
    ...
  },
  "analysis_timestamp": "2025-11-24T10:30:45Z"
}
```

---

### Demo 3: Dummy Process

**Purpose**: Simple example for testing execution framework

**Location**: `carp.dsp.demo/src/commonMain/kotlin/carp/dsp/demo/DummyDemoProcess.kt`

**Use Case**: Testing, learning the framework, template for new processes

---

## Getting Started

### Prerequisites

- **JDK 17+**: Required for Kotlin compilation
- **Conda**: Required for Python environment management (for demos)
- **Git**: For version control

### Installation

```bash
# Clone repository
git clone https://github.com/ngreve/carp-dsp.git
cd carp-dsp

# Build project
./gradlew build

# Run tests
./gradlew test

# Run code quality checks
./gradlew detektPasses
```

### Running Demos

See [Demos and Examples](#demos-and-examples) section for specific commands.

### Creating Your Own Workflow

#### 1. Define Your Process

```kotlin
// In carp.dsp.core or your application
@Serializable
data class MyCustomProcess(
    override val metadata: ProcessMetadata,
    val customParam: String
) : ExternalProcess
```

#### 2. Create an Executor (if needed)

```kotlin
// In infrastructure layer
class MyCustomExecutor : ProcessExecutor<MyCustomProcess> {
    override suspend fun setup(process: MyCustomProcess, context: ExecutionContext): Boolean {
        // Setup logic
        return true
    }
    
    override suspend fun execute(process: MyCustomProcess, context: ExecutionContext): String {
        // Execution logic
        return "result"
    }
    
    override suspend fun cleanup(process: MyCustomProcess, context: ExecutionContext) {
        // Cleanup logic
    }
}
```

#### 3. Define Your Workflow

```kotlin
fun createMyWorkflow(): Workflow {
    val workflow = Workflow(WorkflowMetadata(name = "My Workflow"))
    
    workflow.addComponent(
        Step(
            metadata = StepMetadata(
                name = "My Step",
                description = "Does something useful"
            ),
            process = MyCustomProcess(
                metadata = ProcessMetadata(name = "My Process"),
                customParam = "value"
            ),
            inputs = emptyList(),
            outputs = emptyList()
        )
    )
    
    return workflow
}
```

#### 4. Execute Your Workflow

```kotlin
fun main() = runBlocking {
    val workflow = createMyWorkflow()
    
    val factory = ExecutionFactory()
    factory.register(MyCustomProcess::class) { MyCustomExecutor() }
    
    val strategy = JvmSequentialExecutionStrategy(factory)
    
    val results = strategy.execute(workflow)
    println("Results: $results")
}
```

---

## Troubleshooting

### Issue 1: Conda Environment Not Found

**Error**:
```
EnvironmentNameNotFound: Could not find conda environment: cgm-analysis
```

**Solution**:
```bash
# Create environment manually
conda create -n cgm-analysis python=3.11 pandas -c conda-forge
conda activate cgm-analysis
pip install cgmquantify
```

**Or**: Enable auto-creation in code:
```kotlin
ensureCondaEnvironment(
    envName = "cgm-analysis",
    createIfMissing = true,  // ← Set to true
    dependencies = listOf("pandas", "pip:cgmquantify"),
    pythonVersion = "3.11",
    channels = listOf("conda-forge")
)
```

---

### Issue 2: Permission Denied Installing Packages

**Error**:
```
Access is denied
```

**Cause**: Trying to install in base environment or without proper permissions

**Solution**:
```bash
# Always use a named environment, not base
conda create -n myenv python=3.11
conda activate myenv
pip install <package>
```

---

### Issue 3: Script Not Found

**Error**:
```
FileNotFoundException: analyze_cgm.py
```

**Solution**: Verify script location:
```bash
# Should exist at:
carp.dsp.demo/scripts/analyze_cgm.py

# In code, use:
val scriptPath = Paths.get("carp.dsp.demo", "scripts", "analyze_cgm.py")
    .toAbsolutePath()
    .toString()
```

---

### Issue 4: Download Fails

**Error**:
```
HTTP 403 Forbidden
HTTP 404 Not Found
```

**Solutions**:

**403 Forbidden**: Check authentication
```kotlin
PhysioNetRetrievalProcess(
    // ...
    authentication = BasicAuthentication(
        username = "your-username",
        password = "your-password"
    )
)
```

**404 Not Found**: Verify URL
- Check PhysioNet dataset URL
- Ensure subject ID is valid
- Verify file name format

---

### Issue 5: Gradle Build Fails

**Error**:
```
Unresolved reference 'io'
```

**Solution**: Refresh dependencies
```bash
./gradlew --refresh-dependencies
./gradlew clean build
```

---

### Issue 6: Tests Fail

**Common causes**:
1. Environment not set up
2. Network issues (for retrieval tests)
3. Missing dependencies

**Solution**: Run specific test suites
```bash
# Unit tests only (no external dependencies)
./gradlew :carp.dsp.core:jvmTest --tests "*Unit*"

# Integration tests
./gradlew :carp.dsp.core:jvmTest --tests "*Integration*"
```

---

## Development History

### Key Milestones

#### Phase 1: Foundation (Early November 2025)
- ✅ Initial project structure
- ✅ Gradle multiplatform setup
- ✅ CI/CD pipeline (GitHub Actions)
- ✅ Code quality (Detekt, Kover)

#### Phase 2: Core Framework (Mid November 2025)
- ✅ TabularData implementation
- ✅ SequentialExecutionStrategy
- ✅ DataRegistry pattern
- ✅ Process type hierarchy

#### Phase 3: Integration (Mid-Late November 2025)
- ✅ CARP Analytics Core integration
- ✅ Breaking changes from new data model
- ✅ Serialization support
- ✅ Updated to new Step/Workflow API

#### Phase 4: Executors (Late November 2025)
- ✅ PythonExecutor implementation
- ✅ PhysioNetRetrievalExecutor
- ✅ EnvironmentSetupExecutor
- ✅ Retry logic and error handling

#### Phase 5: Environment Management (Late November 2025)
- ✅ Conda environment detection
- ✅ Auto-environment creation
- ✅ Pip package support
- ✅ Channel and Python version configuration

#### Phase 6: Demos (Late November 2025)
- ✅ DEXCOM data retrieval demo
- ✅ CGM analysis demo
- ✅ Python script integration
- ✅ End-to-end pipeline

### Breaking Changes Fixed

#### November 13, 2025

**Issue**: New data model in carp.analytics.core broke existing code

**Changes**:
- `Step.inputData` → `Step.inputs` (List<InputDataSpec>)
- `Step.outputData` → `Step.outputs` (List<OutputDataSpec>)
- `StepMetadata(id, name)` → `StepMetadata(name, description)`
- `Workflow(metadata, steps)` → `Workflow(metadata)` + `addComponent()`

**Files Updated**:
- ✅ SequentialExecutionStrategy.kt
- ✅ PhysioNetRetrievalExample.kt
- ✅ DummyDemoProcess.kt
- ✅ All demo files

**Status**: All errors fixed, 0 compilation errors

### Serialization Fixes

#### November 13, 2025

**Issue**: Non-serializable types prevented workflow serialization

**Changes**:
- Removed all `Map<String, Any>` → `Map<String, String>`
- Removed all `Throwable?` → `String?` (error messages)
- Removed function fields
- Added `@Serializable` to all classes

**Files Updated**:
- ✅ DataSourceType.kt, FileFormat.kt
- ✅ DataSource.kt, DataDestination.kt
- ✅ DataSchema.kt, DataConstraints.kt
- ✅ DataReference.kt
- ✅ ExecutionOutput.kt
- ✅ Step.kt

**Status**: All types now serializable

### CI/CD Improvements

#### November 2025

**Added**:
- ✅ Codecov integration
- ✅ Coverage badges
- ✅ Artifact uploads
- ✅ Multi-job workflows
- ✅ Branch protection

**Status**: Fully automated CI/CD

---

## API Reference

### Core Classes

#### TabularData
```kotlin
data class TabularData(
    val schema: DataSchema,
    val rows: List<Map<String, Any?>>,
    val metadata: Map<String, String> = emptyMap()
)
```

#### SequentialExecutionStrategy
```kotlin
open class SequentialExecutionStrategy(
    protected val executionFactory: ExecutionFactory? = null,
    protected val dataRegistry: DataRegistry = DataRegistry()
) : ExecutionStrategy {
    suspend fun execute(workflow: Workflow): Map<String, Any>
}
```

#### PythonProcess
```kotlin
@Serializable
data class PythonProcess(
    override val metadata: ProcessMetadata,
    val scriptPath: String,
    val arguments: List<String> = emptyList(),
    override val environment: Environment? = null
) : ExternalProcess
```

#### CondaEnvironment
```kotlin
@Serializable
data class CondaEnvironment(
    override val name: String,
    override val dependencies: List<String> = emptyList(),
    val pythonVersion: String? = null,
    val channels: List<String> = emptyList()
) : Environment
```

---

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for contribution guidelines.

### Development Workflow

1. Create feature branch
2. Make changes
3. Run tests: `./gradlew test`
4. Run quality checks: `./gradlew detektPasses`
5. Submit pull request

### Code Quality Standards

- ✅ All code passes Detekt checks
- ✅ Test coverage > 80%
- ✅ All tests pass
- ✅ Documentation updated
- ✅ No compiler warnings

---

## Future Roadmap

### Short Term
- 🚧 Additional demos (R integration, data visualization)
- 🚧 Performance benchmarks

### Medium Term
- 📋 JavaScript/TypeScript support
- 📋 Advanced caching strategies
- 📋 Distributed execution
- 📋 Real-time data processing
- 📋 ML/AI integration

---

## License

MIT License - See [LICENSE](../LICENSE) for details

---

## Contact

For questions or issues, please:
- Open an issue on GitHub
- Contact the development team
- Check existing documentation

---

**Last Updated**: November 24, 2025  
**Version**: 0.1.0  
**Status**: ✅ Production Ready

