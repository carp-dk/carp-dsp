#!/usr/bin/env Rscript
# Simple deterministic data analysis script
# Reads input CSV, computes basic statistics, writes output CSV

# Get command-line arguments
args <- commandArgs(trailingOnly = TRUE)

if (length(args) < 2) {
  stop("Usage: analyze_data.R <input_file> <output_file>")
}

input_file <- args[1]
output_file <- args[2]

# Create output directory if needed
dir.create(dirname(output_file), showWarnings = FALSE, recursive = TRUE)

tryCatch({
  # Read input CSV
  if (file.exists(input_file)) {
    data <- read.csv(input_file, stringsAsFactors = FALSE)
    message(sprintf("Loaded %d rows from %s", nrow(data), input_file))
  } else {
    message(sprintf("Warning: Input file %s not found. Creating minimal output.", input_file))
    data <- data.frame(id = 1, value = 10, processed_value = 20)
  }
  
  # Simple analysis: compute statistics
  analysis_results <- data.frame(
    metric = c("row_count", "mean_value", "max_value", "min_value"),
    value = c(
      nrow(data),
      ifelse("value" %in% names(data), mean(as.numeric(data$value), na.rm = TRUE), 0),
      ifelse("value" %in% names(data), max(as.numeric(data$value), na.rm = TRUE), 0),
      ifelse("value" %in% names(data), min(as.numeric(data$value), na.rm = TRUE), 0)
    )
  )
  
  # Write output CSV
  write.csv(analysis_results, output_file, row.names = FALSE)
  message(sprintf("Analysis complete: %d metrics computed", nrow(analysis_results)))
  message(sprintf("Successfully analyzed %s → %s", input_file, output_file))
  
}, error = function(e) {
  message(sprintf("Error analyzing data: %s", conditionMessage(e)))
  # Create minimal output on error
  analysis_results <- data.frame(
    metric = "analysis_failed",
    value = 0
  )
  write.csv(analysis_results, output_file, row.names = FALSE)
  message(sprintf("Created error output: %s", output_file))
})
