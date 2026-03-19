#!/usr/bin/env Rscript
# Step 1: Load data.
# Reads input CSV and adds a row_id column.
# Usage: step1_load.R <input_file> <output_file>

args <- commandArgs(trailingOnly = TRUE)

if (length(args) < 2) {
  stop("Usage: step1_load.R <input_file> <output_file>")
}

input_file  <- args[1]
output_file <- args[2]

dir.create(dirname(output_file), showWarnings = FALSE, recursive = TRUE)

data <- read.csv(input_file, stringsAsFactors = FALSE)
data <- cbind(row_id = seq_len(nrow(data)), data)

write.csv(data, output_file, row.names = FALSE)
message(sprintf("Loaded %d rows -> %s", nrow(data), output_file))
