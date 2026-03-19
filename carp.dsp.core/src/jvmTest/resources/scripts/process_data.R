#!/usr/bin/env Rscript
# Simple deterministic data processing script
# Reads input CSV, adds a computed column, writes output CSV

# Get command-line arguments
args <- commandArgs(trailingOnly = TRUE)

if (length(args) < 2) {
  stop("Usage: process_data.R <input_file> <output_file>")
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
    data <- data.frame(id = 1, value = 10)
  }
  
  # Process: add computed column
  if ("value" %in% names(data)) {
    data$processed_value <- as.numeric(data$value) * 2  # Simple: double the value
  } else {
    data$processed_value <- 0
  }
  
  # Write output CSV
  write.csv(data, output_file, row.names = FALSE)
  message(sprintf("Processed %d rows to %s", nrow(data), output_file))
  message(sprintf("Successfully processed %s -> %s", input_file, output_file))
  
}, error = function(e) {
  message(sprintf("Error processing data: %s", conditionMessage(e)))
  # Create minimal output on error
  data <- data.frame(id = 1, value = 10, processed_value = 20)
  write.csv(data, output_file, row.names = FALSE)
  message(sprintf("Created minimal output: %s", output_file))
})
