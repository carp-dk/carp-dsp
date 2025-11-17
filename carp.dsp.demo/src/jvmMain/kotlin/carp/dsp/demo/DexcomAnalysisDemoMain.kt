package carp.dsp.demo

import carp.dsp.core.domain.execution.JvmSequentialExecutionStrategy
import dk.cachet.carp.analytics.application.data.DataRegistry

/**
 * JVM entry point for the DEXCOM analysis demo.
 *
 * This demo:
 * 1. Downloads DEXCOM data from PhysioNet
 * 2. Analyzes the data using cgmquantify Python package
 *
 * Prerequisites:
 * 1. Create conda environment:
 *    conda create -n cgm-analysis python=3.11 pandas -c conda-forge
 *    conda activate cgm-analysis
 *    pip install cgmquantify
 *
 * 2. Verify installation:
 *    conda activate cgm-analysis
 *    python -c "import cgmquantify; print('cgmquantify installed successfully')"
 *
 * Run:
 *    ./gradlew :carp.dsp.demo:jvmRun -PmainClass=carp.dsp.demo.DexcomAnalysisDemoMainKt
 *
 * Or from IDE:
 *    Right-click and select "Run"
 */
fun main() {
    println("=" .repeat(80))
    println("DEXCOM Download and Analysis Demo")
    println("=" .repeat(80))
    println()

    // Check if user wants to run single subject or multiple subjects
    val singleSubjectMode = System.getProperty("singleSubject")?.toBoolean() ?: false

    val workflow = if (singleSubjectMode) {
        println("Running in SINGLE SUBJECT mode (subject 001)")
        println("To run multiple subjects, set -DsingleSubject=false")
        println()
        DexcomAnalysisDemo.createSingleSubjectWorkflow(
            subjectId = "001",
            condaEnv = "cgm-analysis"
        )
    } else {
        println("Running in MULTIPLE SUBJECTS mode (001, 002, 005)")
        println()
        DexcomAnalysisDemo.createDexcomAnalysisWorkflow(
            subjectIds = listOf("001", "002", "005"),
            condaEnv = "cgm-analysis"
        )
    }

    println("Workflow: ${workflow.metadata.name}")
    println("Steps: ${workflow.getComponents().size}")
    println()

    // Display setup instructions
    println("Prerequisites:")
    println("1. Conda must be installed")
    println()

    println("Files will be saved to:")
    println("  Downloads: ${System.getProperty("user.home")}/physionet-downloads/big-ideas/")
    println("  Results:   ${System.getProperty("user.home")}/cgm-analysis-results/")
    println()

    print("Press Enter to start workflow execution (or Ctrl+C to cancel)...")
    readLine()
    println()

    // Create execution strategy with JVM-specific capabilities
    val dataRegistry = DataRegistry()
    val strategy = JvmSequentialExecutionStrategy(dataRegistry)
    val executionFactory = carp.dsp.core.domain.execution.ExecutionFactory()

    // Register PythonExecutor for PythonProcess
    executionFactory.register(carp.dsp.core.application.process.PythonProcess::class) {
        carp.dsp.core.infrastructure.execution.PythonExecutor()
    }

    println("Starting workflow execution...")
    println("-".repeat(80))

    try {
        strategy.execute(workflow, executionFactory)

        println("-".repeat(80))
        println("✅ Workflow execution completed successfully!")
        println()

        // Display results location
        val resultsDir = System.getProperty("user.home") + "/cgm-analysis-results"
        println("Analysis results saved to: $resultsDir")
        println()
        println("You can view the results with:")
        println("  cat $resultsDir/001/analysis.json")
        println()

    } catch (e: Exception) {
        println("-".repeat(80))
        println("❌ Workflow execution failed!")
        println("Error: ${e.message}")
        e.printStackTrace()

        println()
        println("Common issues:")
        println("1. Conda environment not found:")
        println("   → Run setup script: .\\setup-cgm-env.ps1")
        println()
        println("2. Script not found:")
        println("   → Make sure analyze_cgm.py is in carp.dsp.demo/scripts/")
        println()
        println("3. Network issues downloading from PhysioNet:")
        println("   → Check internet connection")
        println("   → Try again later")
    }

    println()
    println("=" .repeat(80))
}

