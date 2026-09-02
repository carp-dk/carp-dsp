# core.viz.visualise

![certification](https://img.shields.io/badge/CARP--DSP-gated-blue)

Plot a table as a small multi-panel figure, with every panel named by an argument.

```
table (csv)  ->  plot (png)
```

## Overview

This step draws three kinds of panel - a line of a column's mean over time, a histogram of a column, and a scatter of
one column against another - over whichever columns you name.

Use it as a step to display a step's output. Very basic plot for quick inspection of data, and the panels aggregate
across participants in a way that hides individual differences.

## Data it needs

Any CSV with a header row. The panel options name the columns to plot, so the step imposes no schema of its own.

| Column                   | Type    | Meaning                            |
|--------------------------|---------|------------------------------------|
| named in `--time-column` | date    | The x axis of every `--line` panel |
| named in `--line`        | numeric | Averaged per `--time-column` value |
| named in `--histogram`   | numeric | Binned over its own range          |
| named in `--scatter`     | numeric | Plotted as an x:y pair             |

A column named by an option and absent from the table stops the step. Columns no option names are ignored.

- **Granularity**: any. Rows sharing a `--time-column` value are averaged together in the line panels, whatever
  participant they belong to.
- **Units**: taken from the input and not labelled on the chart.
- **CARP data types**: none. It plots named columns without interpreting what they measure, which is what places it in
  the `core` tier.
- **Missing data**: matplotlib skips missing points, leaving a break in a line or a smaller histogram. Nothing marks
  that this happened.

## What you get

One PNG at 120 dpi. Panels are drawn in a fixed order - lines, then histograms, then scatters - into a grid sized to
their number, each cell 4.5 x 3 inches. Any cell the count does not fill is hidden rather than left as an empty frame.

The published fixture is produced with:

```bash
--line total_steps,resting_hr --histogram total_steps --scatter total_steps:mean_hr
```

which gives the four panels this step used to hardcode: mean steps per day, mean resting heart rate per day, the spread
of daily step counts, and steps against mean heart rate. The invocation is recorded in `reference/expected.json` rather
than in the implementation, because the step names no column of its own.

The two panels to read together are the top row: they share an x-axis, so a day where steps drop and resting heart rate
rises is visible as the two lines moving apart. The bottom-right scatter is the one that most often reveals a problem -
a vertical stripe of points means many days share a step count, which usually means zero-filling upstream rather than
anything about the participants.

## How it works

1. Parse the panel options and stop on any column that is not in the table.
2. Parse `--time-column` as a date and group by it, if any line panel was asked for.
3. Size the grid to the panel count: `columns = ceil(sqrt(panels))`,
   `rows = ceil(panels / columns)`.
4. Draw lines, then histograms, then scatters; hide unused cells; apply a tight layout and write the PNG at 120 dpi
   through the non-interactive `Agg` backend.

The only computation is in the line panels, over the rows `i` sharing each
`--time-column` value `d` - **across all participants**:

```
line(c, d) = mean(c_i | time_i = d)      for each column c named in --line
```

The other panels plot rows directly: a histogram bins its column into 20 equal-width bins over its own range, and a
scatter draws one point per row.

## Choices and limits

**The time-series panels average across participants.** A cohort of ten becomes one line. If per-participant lines
matter, split the input and run this step per participant, or write a study-specific plotting step.

**Axes are unlabelled and unitless.** Only the bottom-right panel has axis labels, and none of the four states units.
The titles are the only description.

**Which panels are drawn is configurable; how they look is not.** Bin count, figure size, dpi, colours and the panel
ordering are fixed in the implementation.

**A missing column stops the step rather than dropping a panel.** A figure short one panel still looks finished, so a
typo would otherwise be reported by nothing at all. Naming no panels at all is likewise an error, not a request for a
blank image.

**The output is a raster image.** At 120 dpi, and it cannot be re-styled after the fact.

**Validate upstream.** Given columns that exist and hold implausible values, it renders a chart of implausible values.
It also has no idea whether a column is worth plotting the way you asked: a histogram of a participant identifier is
drawn without complaint.

## Options

| Option          | Default  | Meaning                                    |
|-----------------|----------|--------------------------------------------|
| `--input`       | required | CSV table with a header row                |
| `--output`      | required | Output PNG path                            |
| `--time-column` | `date`   | Column the line panels are grouped by      |
| `--line`        | none     | `col,...` - mean per `--time-column` value |
| `--histogram`   | none     | `col,...` - distribution of the column     |
| `--scatter`     | none     | `x:y,...` - one column against another     |

At least one panel option is required. This step ships no default panels, so a
`uses:` reference must supply them with `args:` - naming columns in the step itself would make it a step about heart
rate and step counts, which is what it used to be.

## References

No method paper: these are standard descriptive plots - two time series, a histogram and a scatter - with no analysis
behind them.

## Implementations

| Language | Path                       |
|----------|----------------------------|
| Python   | `impl/python/visualise.py` |

Every implementation must reproduce `reference/expected.json` - the panel titles and the data plotted on each - from
`reference/input.csv`. The tests assert against that fixture directly, so plotting the right shape from the wrong column
fails. `reference/expected.png` shows what the chart looks like. Tests live beside the implementation:

```bash
python -m pytest impl/python -q
```
