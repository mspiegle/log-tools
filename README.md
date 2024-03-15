# Log Tools
Tools to help analyze log files from various engine control units

## Supported ECUs
All tools should support the following ECUs:
* Haltech NSP (Experimental)

## Requirements
All tools require Java.  At least Java8 is required which means any
modern version is supported.  These are command-line tools that require
`PowerShell` or `cmd.exe` to run.

## Tools

### summarize-logs
Summarize your log files with statistical functions

#### Usage
The workhorse of this tool is the `--op` argument.  You can summarize
the channels of your log file with a statistical function and optionally
filter the results so you can find the log files you're looking for.
Please run the tool with `--help` to see all options available:
```
$ java -jar /path/to/summarize-logs.jar --help

 summarize-logs: Summarize log files with statistics

 USAGE:
   summarize-logs OPTIONS FILES


 EXAMPLES:
   summarize-logs -o "max(RPM)" /directory/of/logs
   summarize-logs -o "min(RPM) = 0" log1.csv log2.csv
   summarize-logs -PU -o "max(Manifold Pressure)" log1.csv


 OPTIONS are:
   -o, --op OPSPEC:   Perform operation and include result in output,
                      at least 1 required, more details below

   -a, --all:         Only show file in output if all filters pass

   -U, --long-units:  When parsing files, convert values into the
                      units the ECU's software would typically show

   -r, --recursive:   Recursively process all nested directories

   -P, --no-path:     By default, the output table shows the full
                      path.  This option shortens the output by
                      omitting the leading directories.

   -T, --no-threads:  Disable automatic multi-threading

   -v, --verbose:     Verbose output

   -h, --help:        Show help


 OPSPEC:
   The format of OPSPEC is: STATISTIC(CHANNEL) FILTER
   Valid STATISTICs are listed below, CHANNEL must be present in
   the log file.  FILTER is optional and explained below.


 STATISTIC may be one of:
      min: Minimum value of channel
      max: Maximum value of channel
     mean: Statistical mean of channel
      avg: Same as mean
   stddev: Standard deviation of channel
   median: Median of channel
    first: The first value of a channel
     last: The last value of a channel


 FILTER:
   The FILTER is a combination of COMPARISON and THRESHOLD.  The
   COMPARISONs are listed below.  THRESHOLD is just a number to
   compare against.  Some examples of FILTERs are:
     > 100
     < 50
     = 0

   When a filter is specified, the row will only show up in the
   output if the value in the log satisfies the filter.  Using
   the first example, the value needs to be greater than 100 to
   pass the filter.


 COMPARISON may be one of:
   <: Less than
   >: Greater than
   =: Equal to
```

#### Example 1: Summarize logs by highest knock count seen in each log
```
$ java -jar summarize-logs.jar `
    --op "max(Knock Sensor 1 Knock Count)" `
    LogFiles
+----------+----------------------------+-----------+-------+
| Filename | Channel                    | Statistic | Value |
+----------+----------------------------+-----------+-------+
| Log1.csv | Knock Sensor 1 Knock Count | max       | 0.0   |
| Log2.csv | Knock Sensor 1 Knock Count | max       | 0.0   |
| Log3.csv | Knock Sensor 1 Knock Count | max       | 39.0  |
| Log4.csv | Knock Sensor 1 Knock Count | max       | 0.0   |
+----------+----------------------------+-----------+-------+
```

#### Example 2: Summarize by knock count, but only show files with knocks
```
$ java -jar summarize-logs.jar `
    --op "max(Knock Sensor 1 Knock Count) > 0" `
    LogFiles
+----------+----------------------------+-----------+-------+
| Filename | Channel                    | Statistic | Value |
+----------+----------------------------+-----------+-------+
| Log3.csv | Knock Sensor 1 Knock Count | max       | 39.0  |
+----------+----------------------------+-----------+-------+
```

#### Example 3: Find logs that start with the engine off
```
$ java -jar summarize-logs.jar `
    --op "first(Engine Running Time) = 0" `
    LogFiles
+----------+---------------------+-----------+--------+
| Filename | Channel             | Statistic | Value  |
+----------+---------------------+-----------+--------+
| Log1.csv | Engine Running Time | first     | 0      |
| Log2.csv | Engine Running Time | first     | 0      |
| Log3.csv | Engine Running Time | first     | 0      |
+----------+---------------------+-----------+--------+
```

Just because the first entry in the log indicates that the engine is
off, does not mean that the engine was ever started while you were
logging.  You can use the `last` statistic to see this.  Note how
`Log2.csv` has `0` runtime at the start and end of the log:
```
$ java -jar summarize-logs.jar `
    --op "first(Engine Running Time) = 0" `
    LogFiles
+----------+---------------------+-----------+--------+
| Filename | Channel             | Statistic | Value  |
+----------+---------------------+-----------+--------+
| Log1.csv | Engine Running Time | first     | 0      |
| Log1.csv | Engine Running Time | last      | 1234   |
| Log2.csv | Engine Running Time | first     | 0      |
| Log2.csv | Engine Running Time | last      | 0      |
| Log3.csv | Engine Running Time | first     | 0      |
| Log3.csv | Engine Running Time | last      | 98765  |
+----------+---------------------+-----------+--------+
```

What if you only wanted to see files where `Engine Running Time`
increased between the first and last log entry?  You can use the `-a`
option which adds an implicit `AND` condition between all filters:
```
$ java -jar summarize-logs.jar `
    --op "first(Engine Running Time) = 0" `
    --op "last(Engine Running Time) > 0" `
    -a LogFiles
+----------+---------------------+-----------+--------+
| Filename | Channel             | Statistic | Value  |
+----------+---------------------+-----------+--------+
| Log1.csv | Engine Running Time | first     | 0      |
| Log1.csv | Engine Running Time | last      | 1234   |
| Log3.csv | Engine Running Time | first     | 0      |
| Log3.csv | Engine Running Time | last      | 98765  |
+----------+---------------------+-----------+--------+
```

#### Example 4: Interpreting the data with standard units
By default, this tool will show you the raw values in the log files.
Many ECUs will only log integers even if the value typically is a
decimal.  You can see that below (NOTE: due to a limitation, many
integers are interpreted as decimals with `.0` at the end):
```
$ java -jar summarize-logs.jar --op "min(Target Lambda)" LogFiles
+----------+---------------+-----------+--------+
| Filename | Channel       | Statistic | Value  |
+----------+---------------+-----------+--------+
| Log1.csv | Target Lambda | min       | 716.0  |
| Log2.csv | Target Lambda | min       | 0.0    |
| Log3.csv | Target Lambda | min       | 671.0  |
| Log4.csv | Target Lambda | min       | 871.0  |
+----------+---------------+-----------+--------+
```

There is an experimental `-U` feature for some supported ECUs that will
attempt to convert the raw value into a human readable format:
```
$ java -jar summarize-logs.jar --op "min(Target Lambda)" -U LogFiles
+----------+---------------+-----------+--------+--------+
| Filename | Channel       | Statistic | Value  | Units  |
+----------+---------------+-----------+--------+--------+
| Log1.csv | Target Lambda | min       | 0.716  | Lambda |
| Log2.csv | Target Lambda | min       | 0.0    | Lambda |
| Log3.csv | Target Lambda | min       | 0.671  | Lambda |
| Log4.csv | Target Lambda | min       | 0.871  | Lambda |
+----------+---------------+-----------+--------+--------+
```

Here's an example for Manifold Pressure.  NOTE that it is converting the
raw value, and interpreting it as "gauge" pressure because that is what
the tuning software would show by default.  First, here's the raw
version:
```
$ java -jar summarize-logs.jar --op "max(Manifold Pressure)" LogFiles
+----------+-------------------+-----------+--------+
| Filename | Channel           | Statistic | Value  |
+----------+-------------------+-----------+--------+
| Log1.csv | Manifold Pressure | max       | 1825.0 |
| Log2.csv | Manifold Pressure | max       | 910.0  |
| Log3.csv | Manifold Pressure | max       | 1676.0 |
| Log4.csv | Manifold Pressure | max       | 1515.0 |
+----------+-------------------+-----------+--------+
```

Here's the converted version as kPa gauge pressure:
```
$ java -jar summarize-logs.jar --op "max(Manifold Pressure)" -U LogFiles
+----------+-------------------+-----------+-------+------------+
| Filename | Channel           | Statistic | Value | Units      |
+----------+-------------------+-----------+-------+------------+
| Log1.csv | Manifold Pressure | max       | 81.5  | Kilopascal |
| Log2.csv | Manifold Pressure | max       | -10.0 | Kilopascal |
| Log3.csv | Manifold Pressure | max       | 66.6  | Kilopascal |
| Log4.csv | Manifold Pressure | max       | 50.5  | Kilopascal |
+----------+-------------------+-----------+-------+------------+
```
