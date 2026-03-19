#!/usr/bin/env Rscript
# Step 2: Transform data.
# Uppercases all character columns (skips row_id).
# Usage: step2_transform.R <input_file> <output_file>

args <- commandArgs(trailingOnly = TRUE)

if (length(args) < 2) {
  stop("Usage: step2_transform.R <input_file> <output_file>")
}

input_file  <- args[1]
output_file <- args[2]

dir.create(dirname(output_file), showWarnings = FALSE, recursive = TRUE)

data <- read.csv(input_file, stringsAsFactors = FALSE)

char_cols <- names(data)[sapply(data, is.character) & names(data) != "row_id"]
data[char_cols] <- lapply(data[char_cols], toupper)

write.csv(data, output_file, row.names = FALSE)
message(sprintf("Transformed %d rows -> %s", nrow(data), output_file))
