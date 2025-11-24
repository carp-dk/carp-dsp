# CARP DSP - Quick Start Guide

This guide gets you up and running with CARP DSP in minutes.

---

## Installation

```bash
# Clone repository
git clone https://github.com/ngreve/carp-dsp.git
cd carp-dsp

# Build project
./gradlew build
```

✅ **That's it!** The core framework is ready to use.

---

## Run Your First Demo

### Option 1: Data Retrieval (Simplest)

Downloads DEXCOM glucose monitoring data from PhysioNet.

```bash
./gradlew :carp.dsp.demo:jvmRun -PmainClass=carp.dsp.demo.DexcomRetrievalDemoMainKt
```

**What it does**:
- Downloads CSV files from PhysioNet
- Saves to `~/physionet-downloads/`
- No external dependencies needed!

---

### Option 2: Full Analysis Pipeline

Downloads and analyzes DEXCOM data using Python/cgmquantify.

**Prerequisites**:
```bash
# Create conda environment (one-time setup)
conda create -n cgm-analysis python=3.11 pandas -c conda-forge
conda activate cgm-analysis
pip install cgmquantify
```

**Run demo**:
```bash
./gradlew :carp.dsp.demo:jvmRun -PmainClass=carp.dsp.demo.DexcomAnalysisDemoMainKt
```

**What it does**:
1. Downloads DEXCOM CSV files
2. Analyzes using cgmquantify (15+ metrics)
3. Saves JSON results

**Or use auto-creation**: The demo will automatically create the environment if missing!

---

## Create Your Own Workflow

### 1. Define a Process

```kotlin
@Serializable
data class MyProcess(
    override val metadata: ProcessMetadata,
    val inputPath: String,
    val outputPath: String
) : ExternalProcess
```

### 2. Create an Executor

```kotlin
class MyExecutor : ProcessExecutor<MyProcess> {
    override suspend fun setup(process: MyProcess, context: ExecutionContext): Boolean {
        println("Setting up...")
        return true
    }
    
    override suspend fun execute(process: MyProcess, context: ExecutionContext): String {
        println("Processing ${process.inputPath}...")
        // Your logic here
        return "Success!"
    }
    
    override suspend fun cleanup(process: MyProcess, context: ExecutionContext) {
        println("Cleaning up...")
    }
}
```

### 3. Build a Workflow

```kotlin
fun createWorkflow(): Workflow {
    val workflow = Workflow(WorkflowMetadata(name = "My Workflow"))
    
    workflow.addComponent(
        Step(
            metadata = StepMetadata(
                name = "Process Data",
                description = "Processes my data"
            ),
            process = MyProcess(
                metadata = ProcessMetadata(name = "My Process"),
                inputPath = "input.csv",
                outputPath = "output.csv"
            ),
            inputs = emptyList(),
            outputs = emptyList()
        )
    )
    
    return workflow
}
```

### 4. Execute It

```kotlin
fun main() = runBlocking {
    val workflow = createWorkflow()
    
    // Register executor
    val factory = ExecutionFactory()
    factory.register(MyProcess::class) { MyExecutor() }
    
    // Execute
    val strategy = JvmSequentialExecutionStrategy(factory)
    val results = strategy.execute(workflow)
    
    println("Done! Results: $results")
}
```

---

## Common Tasks

### Run Tests

```bash
# All tests
./gradlew test

# Specific module
./gradlew :carp.dsp.core:jvmTest

# With coverage report
./gradlew koverHtmlReport
# Report at: build/reports/kover/html/index.html
```

### Code Quality

```bash
# Run Detekt
./gradlew detektPasses

# Auto-fix some issues
./gradlew detektFormat
```

### Build JAR

```bash
# Core framework
./gradlew :carp.dsp.core:jvmJar
# Output: carp.dsp.core/build/libs/carp.dsp.core-jvm-0.1.0.jar

# Demo
./gradlew :carp.dsp.demo:jvmJar
```

---

## Working with Python

### Execute Python Script

```kotlin
val pythonProcess = PythonProcess(
    metadata = ProcessMetadata(name = "My Script"),
    scriptPath = "/path/to/script.py",
    arguments = listOf("--input", "data.csv", "--output", "results.json"),
    environment = CondaEnvironment(
        name = "my-env",
        dependencies = listOf("pandas", "numpy", "pip:scikit-learn"),
        pythonVersion = "3.11",
        channels = listOf("conda-forge")
    )
)

val executor = PythonExecutor()
executor.setup(pythonProcess, context)
val output = executor.execute(pythonProcess, context)
```

### Conda Environment Management

```kotlin
val envSetup = EnvironmentSetupExecutor()

// Check if exists
val exists = envSetup.condaEnvironmentExists("my-env")

// Create if missing
val created = envSetup.ensureCondaEnvironment(
    envName = "my-env",
    createIfMissing = true,
    dependencies = listOf("pandas", "pip:tensorflow"),
    pythonVersion = "3.11",
    channels = listOf("conda-forge")
)

// List all environments
val envs = envSetup.listCondaEnvironments()
println("Available environments: $envs")
```

---

## Data Retrieval

### HTTP Download

```kotlin
val retrievalProcess = PhysioNetRetrievalProcess(
    metadata = ProcessMetadata(name = "Download Data"),
    files = listOf(
        FileToRetrieve(
            url = "https://example.com/data.csv",
            filename = "data.csv"
        )
    ),
    baseOutputPath = "/path/to/downloads",
    authentication = BasicAuthentication(
        username = "user",
        password = "pass"
    ),
    maxRetries = 3,
    timeoutMs = 30000
)

val executor = PhysioNetRetrievalExecutor(httpClient)
val outputs = executor.execute(retrievalProcess, "/output/path")
```

---

## Troubleshooting

### Conda environment not found

```bash
# Check if it exists
conda env list

# Create manually
conda create -n cgm-analysis python=3.11 pandas -c conda-forge
conda activate cgm-analysis
pip install cgmquantify
```

### Build errors after pulling changes

```bash
# Refresh dependencies
./gradlew --refresh-dependencies
./gradlew clean build
```

### Permission denied installing packages

```bash
# Don't install in base environment
conda create -n myenv python=3.11
conda activate myenv
pip install <package>
```

---

## Next Steps

📚 **[Read Full Documentation](docs/DSP_DOCUMENTATION.md)**

Topics to explore:
- [Architecture](docs/DSP_DOCUMENTATION.md#architecture) - Understand the design
- [Design Decisions](docs/DSP_DOCUMENTATION.md#design-decisions) - Why things work this way
- [Demos](docs/DSP_DOCUMENTATION.md#demos-and-examples) - More examples
- [API Reference](docs/DSP_DOCUMENTATION.md#api-reference) - Detailed API docs

---

## Getting Help

1. Check [Troubleshooting](docs/DSP_DOCUMENTATION.md#troubleshooting)
2. Search [existing issues](https://github.com/ngreve/carp-dsp/issues)
3. Open a new issue with details
4. Contact the team

---

**Happy coding!** 🚀

