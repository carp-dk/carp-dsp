package carp.dsp.demo

import carp.dsp.core.domain.execution.JvmSequentialExecutionStrategy
import dk.cachet.carp.analytics.application.data.DataRegistry

private const val LINE_WIDTH = 80

/**
 * JVM entry point for the DEXCOM analysis demo.
 */
fun main() {
    printDemoHeader()

    val workflow = selectWorkflow()
    printWorkflowInfo(workflow)
    printPrerequisites()

    waitForUserConfirmation()

    val strategy = setupExecutionStrategy()
    executeWorkflow(workflow, strategy)

    println()
    println("=".repeat(LINE_WIDTH))
}

/**
 * Prints the demo header.
 */
private fun printDemoHeader() {
    println("=".repeat(LINE_WIDTH))
    println("DEXCOM Download and Analysis Demo")
    println("=".repeat(LINE_WIDTH))
    println()
}

/**
 * Selects the workflow based on user configuration.
 */
private fun selectWorkflow(): dk.cachet.carp.analytics.domain.workflow.Workflow {
    val singleSubjectMode = System.getProperty("singleSubject")?.toBoolean() ?: false

    return if (singleSubjectMode) {
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
}

/**
 * Prints workflow information.
 */
private fun printWorkflowInfo(workflow: dk.cachet.carp.analytics.domain.workflow.Workflow) {
    println("Workflow: ${workflow.metadata.name}")
    println("Steps: ${workflow.getComponents().size}")
    println()
}

/**
 * Prints prerequisites and file locations.
 */
private fun printPrerequisites() {
    println("Prerequisites:")
    println("1. Conda must be installed")
    println()

    println("Files will be saved to:")
    println("  Downloads: ${System.getProperty("user.home")}/physionet-downloads/big-ideas/")
    println("  Results:   ${System.getProperty("user.home")}/cgm-analysis-results/")
    println()
}

/**
 * Waits for user confirmation to proceed.
 */
private fun waitForUserConfirmation() {
    print("Press Enter to start workflow execution (or Ctrl+C to cancel)...")
    readlnOrNull()
    println()
}

/**
 * Sets up the execution strategy with required executors.
 */
private fun setupExecutionStrategy(): JvmSequentialExecutionStrategy {
    val dataRegistry = DataRegistry()
    val strategy = JvmSequentialExecutionStrategy(dataRegistry)
    val executionFactory = carp.dsp.core.domain.execution.ExecutionFactory()

    // Register PythonExecutor for PythonProcess
    executionFactory.register(carp.dsp.core.application.process.PythonProcess::class) {
        carp.dsp.core.infrastructure.execution.PythonStepExecutor()
    }

    return strategy
}

/**
 * Executes the workflow and handles results or errors.
 */
private fun executeWorkflow(
    workflow: dk.cachet.carp.analytics.domain.workflow.Workflow,
    strategy: JvmSequentialExecutionStrategy
) {
    println("Starting workflow execution...")
    println("-".repeat(LINE_WIDTH))

    try {
        strategy.execute(workflow, carp.dsp.core.domain.execution.ExecutionFactory())
        printSuccess()
    } catch (e: IllegalStateException) {
        printError(e)
    }
}

/**
 * Prints success message with results location.
 */
private fun printSuccess() {
    println("-".repeat(LINE_WIDTH))
    println("✅ Workflow execution completed successfully!")
    println()

    val resultsDir = System.getProperty("user.home") + "/cgm-analysis-results"
    println("Analysis results saved to: $resultsDir")
    println()
    println("You can view the results with:")
    println("  cat $resultsDir/001/analysis.json")
    println()
}

/**
 * Prints error message with troubleshooting tips.
 */
private fun printError(e: IllegalStateException) {
    println("-".repeat(LINE_WIDTH))
    println("❌ Workflow execution failed!")
    println("Error: ${e.message}")
    println("Error details: ${e.javaClass.simpleName}")

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
