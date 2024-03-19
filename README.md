# Log Tools
Tools to help analyze log files from various engine control units


## Supported ECUs
All tools support the following ECUs:
* Haltech NSP (Experimental)

To request support for a new ECU, please [open a new
issue](https://github.com/mspiegle/log-tools/issues/new) with a sample
log file.  The log file should contain as many channels as possible.


## Requirements
* Windows 8.1 or later (Older versions may work, but untested)
* Familiarity with command-line shell (like `PowerShell` or `cmd.exe`)
* MSVC Runtime: This is already installed on many computers.  If the
  program doesn't run, or you get missing DLL errors, install the
  latest:
  * [64-bit Windows](https://aka.ms/vs/17/release/vc_redist.x64.exe)
  * [32-bit Windows](https://aka.ms/vs/17/release/vc_redist.x86.exe)


## Downloading
[Download the latest version from
GitHub](https://github.com/mspiegle/log-tools/releases/latest).  Most
people will want the Windows 64 native release.  If you're using Mac,
Linux, or 32-bit Windows, then use the uberjar.


## Installing & Running
If you want the best user experience:
* Download a native release
* Unzip the file
* Put the binaries (.exe files) in a directory
* Make sure the directory is part of your `Path` (I use `C:\Users\<your
  username>\bin`)
* Open a shell (like `PowerShell` or `cmd.exe`)
* Navigate to your log directory: `cd /path/to/log/files`
* Run the tool: `summarize-logs.exe --help`
* If you get a missing DLL error, install MSVC as described above


## Problems
If something isn't working, please [open a new
issue](https://github.com/mspiegle/log-tools/issues/new).  Please
describe the problem in detail, the version of windows you're using, and
screenshots of any errors you receive.


## Tools

### summarize-logs
Summarize your log files with statistical functions

#### Usage
The workhorse of this tool is the `--op` argument.  You can summarize
the channels of your log file with a statistical function and optionally
filter the results so you can find the log files you're looking for.
Please run the tool with `--help` to see all options available:
```
> summarize-logs.exe --help

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

   -a, --all:         Only show file in output if all filters are
                      matched

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
> summarize-logs.exe -o "max(Knock Sensor 1 Knock Count)" LogDir
+----------+----------------------------+-----------+-------+
| Filename | Channel                    | Statistic | Value |
+----------+----------------------------+-----------+-------+
| Log1.csv | Knock Sensor 1 Knock Count | max       | 0.0   |
| Log2.csv | Knock Sensor 1 Knock Count | max       | 0.0   |
| Log3.csv | Knock Sensor 1 Knock Count | max       | 39.0  |
| Log4.csv | Knock Sensor 1 Knock Count | max       | 0.0   |
+----------+----------------------------+-----------+-------+
```

#### Example 2: Summarize by knock count, but only show logs with knocks
```
> summarize-logs.exe -o "max(Knock Sensor 1 Knock Count) > 0" LogDir
+----------+----------------------------+-----------+-------+
| Filename | Channel                    | Statistic | Value |
+----------+----------------------------+-----------+-------+
| Log3.csv | Knock Sensor 1 Knock Count | max       | 39.0  |
+----------+----------------------------+-----------+-------+
```

#### Example 3: Find logs that start with the engine off
```
> summarize-logs.exe -o "first(Engine Running Time) = 0" LogDir
+----------+---------------------+-----------+-------+
| Filename | Channel             | Statistic | Value |
+----------+---------------------+-----------+-------+
| Log1.csv | Engine Running Time | first     | 0     |
| Log2.csv | Engine Running Time | first     | 0     |
| Log3.csv | Engine Running Time | first     | 0     |
+----------+---------------------+-----------+-------+
```

The above example shows logs where the engine was off at the start.  We
can also see if the engine was running at the end using the `last`
statistic.  Note how `Log2.csv` has `0` runtime at the start and end of
the log:
```
> summarize-logs.exe -o "first(Engine Running Time) = 0" `
                     -o "last(Engine Running Time)" `
                     LogDir
+----------+---------------------+-----------+-------+
| Filename | Channel             | Statistic | Value |
+----------+---------------------+-----------+-------+
| Log1.csv | Engine Running Time | first     | 0     |
| Log1.csv | Engine Running Time | last      | 1234  |
| Log2.csv | Engine Running Time | first     | 0     |
| Log2.csv | Engine Running Time | last      | 0     |
| Log3.csv | Engine Running Time | first     | 0     |
| Log3.csv | Engine Running Time | last      | 98765 |
+----------+---------------------+-----------+-------+
```

What if you only wanted to see files where `Engine Running Time`
increased between the first and last log entry?  You can use the `-a`
option which adds an implicit `AND` condition between all filters.  This
means that all filters must match for a file to show up in the output:
```
> summarize-logs.exe -o "first(Engine Running Time) = 0" `
                     -o "last(Engine Running Time) > 0" `
                     -a LogDir
+----------+---------------------+-----------+-------+
| Filename | Channel             | Statistic | Value |
+----------+---------------------+-----------+-------+
| Log1.csv | Engine Running Time | first     | 0     |
| Log1.csv | Engine Running Time | last      | 1234  |
| Log3.csv | Engine Running Time | first     | 0     |
| Log3.csv | Engine Running Time | last      | 98765 |
+----------+---------------------+-----------+-------+
```

#### Example 4: Interpreting the data with standard units
By default, this tool will show you the raw values in the log files.
Sometimes the raw values won't make sense because the ECU logs them in
such a way to maximize performance.  In the following example, most
would expect `Target Lambda` to typically be between 0.5 and 1.5, but
the ECU logs it as a large integer:

<sub>NOTE: Due to a limitation, the raw values are often interpreted as
decimals with `.0` at the end.  I hope to fix this in the future.</sub>
```
> summarize-logs.exe -o "min(Target Lambda)" LogDir
+----------+---------------+-----------+--------+
| Filename | Channel       | Statistic | Value  |
+----------+---------------+-----------+--------+
| Log1.csv | Target Lambda | min       | 716.0  |
| Log2.csv | Target Lambda | min       | 0.0    |
| Log3.csv | Target Lambda | min       | 671.0  |
| Log4.csv | Target Lambda | min       | 871.0  |
+----------+---------------+-----------+--------+
```

There is an experimental `-U` feature for some ECUs that will attempt to
convert the raw value into a human-friendly format:
```
> summarize-logs.exe -o "min(Target Lambda)" -U LogDir
+----------+---------------+-----------+--------+--------+
| Filename | Channel       | Statistic | Value  | Units  |
+----------+---------------+-----------+--------+--------+
| Log1.csv | Target Lambda | min       | 0.716  | Lambda |
| Log2.csv | Target Lambda | min       | 0.0    | Lambda |
| Log3.csv | Target Lambda | min       | 0.671  | Lambda |
| Log4.csv | Target Lambda | min       | 0.871  | Lambda |
+----------+---------------+-----------+--------+--------+
```

Here's an example for Manifold Pressure.  Notice the large numbers in
the raw version:
```
> summarize-logs.exe -o "max(Manifold Pressure)" LogDir
+----------+-------------------+-----------+--------+
| Filename | Channel           | Statistic | Value  |
+----------+-------------------+-----------+--------+
| Log1.csv | Manifold Pressure | max       | 1825.0 |
| Log2.csv | Manifold Pressure | max       | 910.0  |
| Log3.csv | Manifold Pressure | max       | 1676.0 |
| Log4.csv | Manifold Pressure | max       | 1515.0 |
+----------+-------------------+-----------+--------+
```

Add `-U` to see the converted version.  It's showing kPa gauge pressure
since that's the default for that ECU's tuning software:
```
> summarize-logs.exe -o "max(Manifold Pressure)" -U LogDir
+----------+-------------------+-----------+-------+--------------------+
| Filename | Channel           | Statistic | Value | Units              |
+----------+-------------------+-----------+-------+--------------------+
| Log1.csv | Manifold Pressure | max       | 81.5  | Kilopascal (Gauge) |
| Log2.csv | Manifold Pressure | max       | -10.0 | Kilopascal (Gauge) |
| Log3.csv | Manifold Pressure | max       | 66.6  | Kilopascal (Gauge) |
| Log4.csv | Manifold Pressure | max       | 50.5  | Kilopascal (Gauge) |
+----------+-------------------+-----------+-------+--------------------+
```


## Developers
This section is for people who want to know more about the tools or how
to contribute.


### Software Stack
* Clojure programming language
* tech.ml.dataset (like Python Pandas on the JVM)
* GraalVM for building native binaries


### Why Clojure?
Clojure has fantastic developer ergonomics especially for data
processing applications.  GraalVM compiles the app as a native binary
which minimizes startup time and provides a better user-facing
experience.


### Contributing
[Fork the repo](https://github.com/mspiegle/log-tools/fork) and submit a
pull request


### Development Environment
Local development should be as easy as [installing
leiningen](https://leiningen.org/), then cloning the repo and getting to
work.  I use NeoVim + Conjure for my development environment, but any
Clojure-compatible IDE should work.
