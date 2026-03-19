#!/usr/bin/env Rscript
# Step 3: Finalize data.
# Appends a 'status' column set to 'done'.
# Usage: step3_finalize.R <input_file> <output_file>

args <- commandArgs(trailingOnly = TRUE)

if (length(args) < 2) {
  stop("Usage: step3_finalize.R <input_file> <output_file>")
}

input_file  <- args[1]
output_file <- args[2]

dir.create(dirname(output_file), showWarnings = FALSE, recursive = TRUE)

data <- read.csv(input_file, stringsAsFactors = FALSE)
data$status <- "done"

write.csv(data, output_file, row.names = FALSE)
message(sprintf("Finalized %d rows -> %s", nrow(data), output_file))
