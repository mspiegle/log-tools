(ns summarize-logs.main
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.cli :as cli]
            [log-tools.core :as core]
            [log-tools.ecu.haltech :as haltech]
            [tech.v3.datatype.statistics :as dts]
            [table.core :as table]
            [com.climate.claypoole :as cp])
  (:import [java.io File])
  (:gen-class))


(defn invalid-source?
  [filename]
  (not
    (when-let [f (io/file filename)]
      (and
        (or
          (.isFile f)
          (.isDirectory f))
        (.canRead f)))))


(def comparison-info
  [["<"
    (fn [v t] (< v (Double/parseDouble t)))
    "Less than"]
   [">"
    (fn [v t] (> v (Double/parseDouble t)))
    "Greater than"]
   ["="
    (fn [v t] (== v (Double/parseDouble t)))
    "Equal to"]])


(def comparison-map
  ; Transform comparison-info into map of:
  ; {comparison-name1: comparison-fn1
  ;  comparison-name2: comparison-fn2}
  (->> comparison-info
       (mapcat (fn [[name f]] [name f]))
       (apply hash-map)))


; Supported statistics
(def statistic-info
  ; Each tuple is [name, function, help]
  [["min"
    dts/min
    "Minimum value of channel"]
   ["max"
    dts/max
    "Maximum value of channel"]
   ["mean"
    dts/mean
    "Statistical mean of channel"]
   ["avg"
    dts/mean
    "Same as mean"]
   ["stddev"
    dts/standard-deviation
    "Standard deviation of channel"]
   ;["var"
   ; dts/variance
   ; "Variance of channel"]
   ["median"
    dts/median
    "Median of channel"]
   ;["mode"
   ; dts/mode
   ; "Statistical mode of channel"]
   ["first"
    first
    "The first value of a channel"]
   ["last"
    last
    "The last value of a channel"]])


(def statistic-map
  ; Transform statistics-info into map of:
  ; {stat-name1: stat-fn1
  ;  stat-name2: stat-fn2}
  (->> statistic-info
       (mapcat (fn [[name f]] [name f]))
       (apply hash-map)))


(defn help-text
  [statistic-info comparison-info]
  (->>
    [""
     " summarize-logs: Summarize log files with statistics"
     ""
     " USAGE:"
     "   summarize-logs OPTIONS FILES"
     ""
     ""
     " EXAMPLES:"
     "   summarize-logs -o \"max(RPM)\" /directory/of/logs"
     "   summarize-logs -o \"min(RPM) = 0\" log1.csv log2.csv"
     "   summarize-logs -PU -o \"max(Manifold Pressure)\" log1.csv"
     ""
     ""
     " OPTIONS are:"
     "   -o, --op OPSPEC:   Perform operation and include result in output,"
     "                      at least 1 required, more details below"
     ""
     "   -a, --all:         Only show file in output if all filters pass"
     ""
     "   -U, --long-units:  When parsing files, convert values into the"
     "                      units the ECU's software would typically show"
     ""
     ;"   -u, --short-units: Same as --long-units, but abbreviated"
     ;""
     "   -r, --recursive:   Recursively process all nested directories"
     ""
     "   -P, --no-path:     By default, the output table shows the full"
     "                      path.  This option shortens the output by"
     "                      omitting the leading directories."
     ""
     "   -T, --no-threads:  Disable automatic multi-threading"
     ""
     "   -v, --verbose:     Verbose output"
     ""
     "   -h, --help:        Show help"
     ""
     ""
     " OPSPEC:"
     "   The format of OPSPEC is: STATISTIC(CHANNEL) FILTER"
     "   Valid STATISTICs are listed below, CHANNEL must be present in"
     "   the log file.  FILTER is optional and explained below."
     ""
     ""
     " STATISTIC may be one of:"
     (map
       (fn [[name _ text]]
         (format "%9s: %s" name text))
       statistic-info)
     ""
     ""
     " FILTER:"
     "   The FILTER is a combination of COMPARISON and THRESHOLD.  The"
     "   COMPARISONs are listed below.  THRESHOLD is just a number to"
     "   compare against.  Some examples of FILTERs are:"
     "     > 100"
     "     < 50"
     "     = 0"
     ""
     "   When a filter is specified, the row will only show up in the"
     "   output if the value in the log satisfies the filter.  Using"
     "   the first example, the value needs to be greater than 100 to"
     "   pass the filter."
     ""
     ""
     " COMPARISON may be one of:"
     (map
       (fn [[name _ text]]
         (format "%4s: %s" name text))
       comparison-info)
     ""]
    (flatten)
    (string/join "\n")))


(defn parse-opspec
  "Parse opspec into a map"
  [op-arg]
  (let [regex  #"^([a-z]{1,10})\(([A-z0-9_ -]+)\)(?:\s*([<>=])\s*([0-9]+))?$"
        result (re-matches regex op-arg)]
    (when-let [[_ statistic channel comparison threshold] result]
      (merge
        {:statistic statistic
         :channel   channel}
        (when (and comparison threshold)
          {:comparison (get comparison-map comparison)
           :threshold  threshold})))))


(def cli-opts
  [["-o" "--op OPSPEC" "STATISTIC(CHANNEL) FILTER"
    :id        :operations
    :multi     true
    :default   []
    :parse-fn  parse-opspec
    :update-fn conj]
   ["-a" "--all"]
   ["-u" "--short-units"]
   ["-U" "--long-units"]
   ;["-s" "--sort"]
   ; How would sort work?  It'd have to be a stat(chan), right?
   ; and what if the value got filtered?  Does it still count
   ; for sorting?  Probably not.  If the stat(chan) doesn't already
   ; exist, does it show up in the output?
   ["-r" "--recursive"]
   ["-P" "--no-path"]
   ["-T" "--no-threads"]
   ["-v" "--verbose"]
   ["-d" "--debug"]
   ["-h" "--help"]])


(defn valid-filter?
  "Validate filter"
  [{:keys [comparison threshold] :as o}]
  (if (or (contains? o :comparison)
          (contains? o :threshold))
    (and (some? comparison)
         (some? threshold))
    true))


(defn validate-cli-args
  "Validate CLI arguments"
  [{:keys [options arguments errors]}]
  (cond
    (true? (:help options))
    {:error? true}

    ; errors detected by clojure.tools.cli
    (some? errors)
    {:error? true :msg (string/join "\n" errors)}

    ; at least 1 file required
    (empty? arguments)
    {:error? true :msg "No files to parse"}

    ; arguments must be readable files or directories
    (some invalid-source? arguments)
    {:error? true :msg (->> arguments
                            (filter invalid-source?)
                            (map #(str "[" % "] is not readable"))
                            (string/join "\n"))}

    ; all operations must be valid
    (some nil? (:operations options))
    {:error? true :msg "Malformed operation"}

    ; filter of operation must be valid
    (not-every? valid-filter? (:operations options))
    {:error? true :msg "Invalid filter"}

    ; must define at least 1 operation
    (< (count (remove nil? (:operations options))) 1)
    {:error? true :msg "At least 1 operation required"}

    ; if we got here, all is well
    :else
    {:files       arguments
     :operations  (:operations options)
     :all         (true? (:all options))
     :show-units  (cond
                    (true? (:long-units options))  :long
                    (true? (:short-units options)) :short
                    :else false)
     :recursive?  (true? (:recursive options))
     :no-path?    (true? (:no-path options))
     :no-threads? (true? (:no-threads options))
     :verbose?    (true? (:verbose options))
     :debug?      (true? (:debug options))}))


(defn exit
  "Shows error message with help and exits"
  [{:keys [msg]}]
  ; Show error messages
  (when msg
    (println "Error:")
    (println msg)
    (println))

  ; Show help
  (println (help-text statistic-info comparison-info))

  ; Ensure all output is flushed before exiting
  (flush)
  (shutdown-agents)
  (System/exit 1))


(defn process-file
  "file is java.io.File or string filename.
  parser is a function that returns a dataset.
  operations is seq of map with keys:
  [:channel,
  :statistic,
  :comparison,
  :threshold]
  opts is map of {:show-units ?}"
  [file parser operations {:keys [show-units]}]
  (let [channels (map :channel operations)
        opts     (merge
                   {}
                   (when (seq channels)
                     {:parse-channels channels})
                   (when (keyword? show-units)
                     {:native-units true}))
        dataset  (parser file opts)]
    (merge
      ; If the dataset returned units, include them
      (select-keys dataset [:units])
      ; Parsed file result
      {:file
       file

       :stats
       (for [{:keys [channel statistic comparison threshold]} operations]
         (let [stat-fn (get statistic-map statistic)
               column  (get-in dataset [:dataset channel])]
           (merge
             {:channel   channel
              :statistic statistic
              ; If this file doesn't have the requested column, we
              ; return a nil because we still need to support downstream
              ; filtering
              :value     (when (and stat-fn column)
                           (stat-fn column))}
             (when (and comparison threshold)
               {:filter [comparison threshold]}))))})))


(defn process-files
  "Read files and return results based on operations.
  file-parsers is a tuple of [java.io.File parser-fn]"
  [{:keys [operations threadpool] :as ctx} file-parsers]
  (cp/pfor
    (or threadpool :serial)
    ; Process each file in a separate thread
    [[^File file parser] file-parsers]
    (let [opts (select-keys ctx [:show-units])]
      (try
       ; Only process files with valid parsers
       (when (every? some? [file parser])
         (process-file file parser operations opts))
       (catch RuntimeException e
         (throw (ex-info
                  "Error processing file"
                  {:file (.getPath file)}
                  e)))))))


(defn filter-stat
  "Input stat:
  {:statistic \"max\"
  :channel   \"RPM\"
  :value     123
  :filter    [f 999]}

  Output is input plus :filter? key:
  {:statistic \"max\"
  :channel   \"RPM\"
  :value     123
  :filter    [> 999]
  :filter?   true}"
  [{:keys [value] [comparison threshold] :filter :as stat}]
  (assoc
    stat
    :filter?
    (if (some? value)
      (if (and comparison threshold)
        ; If there's a filter, allow row if it passes filter
        (comparison value threshold)
        ; If there's no filter, always allow row
        true)
      ; If there is no value, reject row
      false)))


(defn filter-result
  "Input result:
  {:file  \"somefile\"
   :stats [stat, stat, stat]}

  Output is input plus modified stats:
  {:file  \"somefile\"
   :stats [stat2, stat2, stat2]}"
  [{:keys [stats] :as result}]
  (assoc result :stats (map filter-stat stats)))


(defn result->rows
  "Convert a result to rows subject to ctx.  Input looks like:
  {:file \"somefile\"
   :stats [stat1, stat2, stat3]}

  Output looks like:
  [[filename channel statistic value]
   [filename channel statistic value]
   [filename channel statistic value]]

  Output only includes rows where stat had (true? :filter?):"
  [{:keys [no-path? show-units]} {:keys [^File file stats units]}]
  (let [fname     (if no-path?
                    (.getName file)
                    (.getPath file))
        stat->row (fn [{:keys [channel statistic value]}]
                    (condp = show-units
                      :short
                      (let [[unit _ _] (get units channel)]
                        (cons fname [channel statistic value unit]))

                      :long
                      (let [[_ unit _] (get units channel)]
                        (cons fname [channel statistic value unit]))

                      ; default
                      (cons fname [channel statistic value])))]
    (->> stats
         (filter :filter?)
         (map stat->row))))


(defn print-table
  "Show results based on ctx"
  [{:keys [show-units] :as ctx} results]
  (let [heading (into
                  ["Filename" "Channel" "Statistic" "Value"]
                  (when (and
                          (keyword? show-units)
                          (contains? (first results) :units))
                    ["Units"]))
        rows    (mapcat #(result->rows ctx %) results)]
    (if (seq rows)
      (table/table (cons heading rows))
      (println "No results found!"))))


(defn paths-to-files
  "Convert seq of paths into seq of java.io.File.  If a path is a directory,
  then files in that directory will be returned.  If recurse? is true, return
  all files by recursing into directories.  Only returns files."
  ([paths]
   (paths-to-files paths false))
  ([paths recurse?]
   (filter
     ; Only include files in result
     #(.isFile ^File %)
     (reduce
       (fn [result path]
         (let [f (io/file path)]
           (if (.isDirectory f)
             ; path is a directory
             (if recurse?
               ; recurse into directory
               (concat result (file-seq f))
               ; don't recurse into directory
               (concat result (.listFiles f)))
             ; path is an individual file
             (conj result f))))
       []
       paths))))


(defn parse-cli-args
  "Parse raw CLI args and return map of instructions"
  [args]
  (let [parsed-args (-> (cli/parse-opts args cli-opts)
                        (validate-cli-args))]
    (merge
      parsed-args
      ; Resolve directories into filenames
      {:files (paths-to-files
                (:files parsed-args)
                (:recursive? parsed-args))}
      ; Only create threadpool if threading is allowed
      {:threadpool (if (:no-threads? parsed-args)
                     :serial
                     (cp/threadpool
                       (cp/ncpus)
                       :name "summarize-logs"))})))


(defn get-parsers-for-files
  "Takes a seq of java.io.File and returns:
  [java.io.File parser-function]"
  [files]
  (let [priority [[haltech/detect-nsp haltech/parse]]
        detect-f (fn detect-f
                   [file]
                   (let [f (fn [[detect-f parse-f]]
                             (if (detect-f file)
                               [file parse-f]
                               [file nil]))]
                     (some f priority)))]
    (map detect-f files)))


(comment
  ; Example args with multiple stats
  (def args
    ["-o" "min(RPM)"
     "-o" "max(RPM)"
     "-o" "max(Manifold Pressure)"
     "-vs"
     (str "C:/Users/mspiegle/Documents"
          "/Haltech/Nexus Maps and Data Logs"
          "/Rossion Q1/Archived Logs/csv"
          "/PCLog_2024-01-12_1201pm.csv")])

  ; Example args with filter
  (def args
    ["-o" "max(RPM) > 5000"
     "-vs"
     (str "C:/Users/mspiegle/Documents"
          "/Haltech/Nexus Maps and Data Logs"
          "/Rossion Q1/Archived Logs/csv"
          "/PCLog_2024-01-12_1201pm.csv")])

  ; Example w/ directory of logs
  (def args
    ["-o" "max(RPM)"
     "-o" "max(Manifold Pressure)"
     "-vrs"
     (str "C:/Users/mspiegle/Documents"
          "/Haltech/Nexus Maps and Data Logs"
          "/Rossion Q1/Logs")])

  (def args
    ["-o" "max(RPM)"
     "-su"
     (str "C:/Users/mspiegle/Documents"
          "/Haltech/Nexus Maps and Data Logs"
          "/Rossion Q1/Logs")]))


(defn filter-file
  "Input is:
  {:file java.io.File
   :stats []}"
  [{:keys [stats]}]
  (every? #(true? (:filter? %)) stats))


(defn cast-result
  "Takes results and casts each value to the intended type"
  [{:keys [file stats units] :as result}]
  (let [f (fn [{:keys [channel value] :as stat}]
            (let [[_ _ t] (get units channel)]
              (assoc
                stat
                :value
                (condp = t
                  ; need 16 bit int container
                  :uint8   (short value)
                  :int16   (short value)
                  :short   (short value)
                  ; need 32 bit int container
                  :uint16  (int value)
                  :int32   (int value)
                  :int     (int value)
                  ; need 64 bit int container
                  :uint32  (long value)
                  :int64   (long value)
                  :long    (long value)
                  ; need 32 bit floating point container
                  :float32 (float value)
                  :float   (float value)
                  ; need 64 bit floating point container
                  :float64 (double value)
                  :double  (double value)
                  ; other containers
                  :string  (str value)
                  ; default
                  (identity value)))))]
    {:file  file
     :stats (map f stats)
     :units units}))


(defn summarize-logs
  "Process files and display output based on instructions in ctx"
  [ctx]
  (let [start-ms     (core/timestamp-now-ms)
        file-parsers (get-parsers-for-files (:files ctx))]
    (cond->> file-parsers
      ; Multithreaded processing of files
      true              (process-files ctx)
      ; Remove results that didn't parse properly
      true              (remove nil?)
      ; Handle any filters
      true              (map filter-result)
      ; AND filters together at the file
      (:all ctx)        (filter filter-file)
      ; Standardize output units
      (:show-units ctx) (map cast-result)
      ; Output results
      true              (print-table ctx))

    ; Show verbose output
    (when (:verbose? ctx)
      (let [duration-ms (- (core/timestamp-now-ms) start-ms)
            count-files (count (:files ctx))
            no-parser   (-> (group-by #(nil? (last %)) file-parsers)
                            (get true))]
        (printf "Processing Time: %d ms\n" duration-ms)
        (printf "Total Files: %d\n" count-files)
        (when-let [failed-files (seq no-parser)]
          (println "Unparsable Files:")
          (doseq [[filename _] failed-files]
            (printf " - %s\n" filename)))
        (println)))))


(defn -main
  [& args]
  (let [ctx (parse-cli-args args)]
    (if (:error? ctx)
      ; Handle bad cli args
      (exit ctx)
      (try
        ; Run the app
        (summarize-logs ctx)
        (catch RuntimeException e
          (println (ex-message e)))))

    ; Shutdown & cleanup
    (when (not= :serial (:threadpool ctx))
      (cp/shutdown (:threadpool ctx)))
    (shutdown-agents)))
