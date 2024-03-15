(ns log-tools.ecu.haltech
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [log-tools.core :as core]
            [tech.v3.dataset :as ds]
            [clojure.set :as cs]))


(defn parse-metadata
  [lines]
  (let [line-fn (fn [line]
                  (some->> (string/split line #":" 2)
                           (not-empty)
                           (mapv string/trim)))]
    (->> lines
         (map line-fn)
         (filter #(= (count %) 2))
         (into {}))))


(defn string-matches-list?
  "If needle is a string contained in haystack, return true, else false"
  [haystack needle]
  (if (string? needle)
    (boolean (some (partial string/includes? needle) haystack))
    false))


(defn is-valid-system-meta?
  "Is the line part of system meta?"
  [line]
  (let [valid-lines ["%DataLog%"
                     "DataLogVersion"
                     "Software"
                     "SoftwareVersion"
                     "DownloadDateTime"]]
    (string-matches-list? valid-lines line)))


(defn is-valid-channel?
  "Is the line part of a channel definition?"
  [line]
  (let [valid-lines ["Channel"
                     "ID"
                     "Type"
                     "DisplayMaxMin"]]
    (string-matches-list? valid-lines line)))


(defn is-valid-log-meta?
  "Is the line part of log meta?"
  [line]
  (let [valid-lines ["Log Source"
                     "Log Number"
                     "Log"]]
    (string-matches-list? valid-lines line)))


(defn is-valid-log-data?
  "Does the line look like log data (as opposed to a part of the header)?"
  [line]
  (and
    (string? line)
    (boolean
      (re-find #"^\d{2}:\d{2}:\d{2}\.\d{3}," line))))


(defn field-in-buffer?
  "Does this 'key : value' field already exist in the buffer?"
  [line buffer]
  (let [field (-> line
                  (string/split #":" 2)
                  first
                  string/trim)]
    (some #(string/starts-with? % field) buffer)))


(defn update-state
  "Maintain state for header parser"
  ([state]
   (update state :header-lines inc))
  ([state k v]
   (-> state
       (update-state)
       (assoc k v)))
  ([state k f v]
   (-> state
       (update-state)
       (update k f v))))


(defn nsp-header-parser
  "This function expects a seq of lines to parse.  It will read enough lines
  to parse the header, then it will return a map of the parsed data.  If a
  lazy seq is used, this function should not realize data past the header plus
  1 line."
  [lines]
  ; Define the starting condition for the state parser, then parse the header
  ; line by line while updating the state.  Parsing is complete once we have
  ; completed parsing the following parts of the log file, and we find our
  ; first data entry:
  ; 1) System Metadata
  ; 2) Channel Definitions
  ; 3) Log Metadata
  ; 4) Done (encountered first log data line)
  (loop [step   :system-meta
         buffer []
         lines  lines
         state  {:metadata     {}
                 :channels     []
                 :header-lines 0}]
    (let [line (first lines)]
      (case step
        ; *** SYSTEM METADATA ***
        :system-meta
        (if (is-valid-system-meta? line)
          (recur
            :system-meta
            (conj buffer line)
            (rest lines)
            (update-state state))
          (if (is-valid-channel? line)
            (recur
              :channels
              [line]
              (rest lines)
              (update-state state :metadata (parse-metadata buffer)))
            (recur
              :parse-error
              (conj buffer line)
              (rest lines)
              (update-state state :metadata nil))))

        ; *** CHANNEL DEFINITIONS ***
        :channels
        (if (is-valid-channel? line)
          ; If this is a new type, parse old one and recur
          ; We check this by seeing if the key already exists in the buffer
          (if (field-in-buffer? line buffer)
            ; This must be a new channel to parse
            (recur
              :channels
              [line]
              (rest lines)
              (update-state state :channels conj (parse-metadata buffer)))
            ; We're still parsing the current channel's definition
            (recur
              :channels
              (conj buffer line)
              (rest lines)
              (update-state state)))
          (if (is-valid-log-meta? line)
            (recur
              :log-meta
              [line]
              (rest lines)
              (update-state state :channels conj (parse-metadata buffer)))
            (recur
              :parse-error
              (conj buffer line)
              (rest lines)
              (update-state state :channels nil))))

        ; *** LOG METADATA ***
        :log-meta
        (if (is-valid-log-meta? line)
          (recur
            :log-meta
            (conj buffer line)
            (rest lines)
            (update-state state))
          (if (is-valid-log-data? line)
            ; Header parsing is complete upon encountering first data line
            (recur
              :done
              [line]
              (rest lines)
              (update state :metadata merge (parse-metadata buffer)))
            (recur
              :parse-error
              (conj buffer line)
              (rest lines)
              (update-state state :metadata nil))))

        ; *** DONE ***
        :done
        ; We are done parsing, so return the map of parsed data
        state

        ; If we have a parser error, try to return some useful information to
        ; aid debugging
        :parse-error
        (throw
          (ex-info
            "Failure to parse file"
            {:last-step step
             :line      line
             :buffer    buffer
             :state     state}))))))


(defn detect-nsp
  "Receives a java file object and returns boolean if the file matches
  the expected type"
  [file]
  (string/includes? (core/sniff-file file 128) "Haltech NSP"))


(defn nsp-parse-header
  [filename]
  (with-open [reader (io/reader (io/input-stream filename))]
    (-> reader
        (line-seq)
        (nsp-header-parser))))


(defn column-id->channel-name
  "Translate dataset's default column name into a channel name"
  [channels col-id]
  (let [i (-> col-id
              (string/split #"-")
              (last)
              (Integer/parseInt))]
    (nth channels i)))


(defn parser-fn-map
  "Takes type-info and header, returns a map that satisfies
  ->dataset's :parser-fn option"
  [type-info {:keys [channels] :as header}]
  (into
    {}
    (for [c channels]
      (let [[_ _ dt f] (get type-info (get c "Type"))
            chan-name  (get c "Channel")]
        (when (and chan-name dt f)
          [chan-name [dt (comp f #(Long/parseLong %))]])))))


(defn nsp->dataset
  "Parse a java.io.File as CSV into a dataset"
  [file header {:keys [parse-channels native-units type-info] :as opts}]
  (let [; A seq of channel names parsed from the header in the same order
        all-chans (->> header
                       :channels
                       (map #(get % "Channel"))
                       ; Time is always the first field, but it isn't
                       ; defined in the header
                       (into ["Time"]))
        ; Take seq of [:a :b :c] channel names, and return a lookup map of:
        ; {"Channel Name 1": Position1
        ;  "Channel Name 2": Position2}
        chan-idx  (cs/map-invert all-chans)
        ; If caller wants specific channels, get a unique seq of the
        ; channels ordered by header position.  Ignore anything that
        ; isn't in the header.
        channels  (or
                    (->> parse-channels
                         (dedupe)
                         (filter #(contains? chan-idx %))
                         (sort-by #(get chan-idx %))
                         (seq))
                    all-chans)
        ds-opts   (merge
                    {:dataset-name file
                     :file-type    :csv
                     :header-row?  false
                     :key-fn       #(column-id->channel-name channels %)}

                    (when parse-channels
                      {:column-allowlist
                       (map #(get chan-idx %) channels)})

                    (when (and native-units type-info)
                      {:parser-fn (parser-fn-map type-info header)}))]

    (with-open [reader (io/reader (io/input-stream file))]
      ; Skip the header lines, then parse as regular CSV into dataset
      (dotimes [_ (:header-lines header)] (.readLine reader))
      (ds/->dataset reader ds-opts))))


(comment
  ; List of channels from a log - not necessarilly conclusive
  ["AVI1 Switch State"
   "AVI1 Voltage"
   "AVI10 Voltage"
   "AVI2 Voltage"
   "AVI3 Switch State"
   "AVI3 Voltage"
   "AVI4 Switch State"
   "AVI4 Voltage"
   "AVI5 Resistance"
   "AVI5 Switch State"
   "AVI5 Voltage"
   "AVI6 Switch State"
   "AVI6 Voltage"
   "AVI7 Resistance"
   "AVI7 Voltage"
   "AVI8 Resistance"
   "AVI8 Voltage"
   "AVI9 Switch State"
   "AVI9 Voltage"
   "Accumulated Engine Running Time"
   "Accumulated Engine Running Time Status"
   "Air Temperature Ignition Correction"
   "Angular Velocity"
   "Base Fuel Tuning"
   "Base Ignition Angle"
   "Battery Voltage"
   "Battery Voltage derivative"
   "Boost Control Actual Pressure"
   "Boost Control Closed Loop Base Output"
   "Boost Control Delay"
   "Boost Control Derivative Gain"
   "Boost Control Derivative Output"
   "Boost Control Integral Gain"
   "Boost Control Integral Output"
   "Boost Control Long Term Trim"
   "Boost Control Long Term Trim Gain"
   "Boost Control Output"
   "Boost Control Output Air Temperature Correction"
   "Boost Control Output Coolant Temperature Correction"
   "Boost Control Output Throttle Position Correction"
   "Boost Control Proportional Gain"
   "Boost Control Proportional Output"
   "Boost Control Short Term Trim"
   "Boost Control Solenoid Duty Cycle"
   "Boost Control State"
   "Boost Control Target Offset Pressure"
   "Boost Control Target Pressure"
   "Boost Control Target Pressure (Corrected)"
   "Boost Pressure Error"
   "Bootmode Reason"
   "Calculated Air Temperature"
   "Calculated Air Temperature Coolant Temperature Bias"
   "Check Engine Light Cause"
   "Check Engine Light Output State"
   "Coolant Temperature"
   "Coolant Temperature Ignition Correction"
   "Corrected Air Mass Per Cylinder"
   "Cranking Ignition Angle"
   "Cut Percentage"
   "Cut Percentage Function"
   "Cut Percentage Method"
   "Cylinder 1 Injected Fuel Volume"
   "Cylinder 2 Injected Fuel Volume"
   "Cylinder 3 Injected Fuel Volume"
   "Cylinder 4 Injected Fuel Volume"
   "Cylinder 5 Injected Fuel Volume"
   "Cylinder 6 Injected Fuel Volume"
   "Cylinder Period"
   "Data Log Current Log Percentage"
   "Data Log Current Log Size"
   "Data Log Memory State"
   "Data Log Number"
   "Data Log Status"
   "Data Log Total Memory"
   "Decel Detected"
   "Decel Min RPM"
   "Diagnostic Analogue 5V rail"
   "Diagnostic absolute voltage reference error"
   "Diagnostic absolute-ratiometric discrepancy"
   "Diagnostic ratiometric voltage reference error"
   "Digital Pulse Output 1 Active Time"
   "Digital Pulse Output 1 Duty"
   "Digital Pulse Output 1 Frequency"
   "Digital Pulse Output 1 Output State"
   "Digital Pulse Output 1 Period"
   "Digital Pulse Output 2 Active Time"
   "Digital Pulse Output 2 Duty"
   "Digital Pulse Output 2 Frequency"
   "Digital Pulse Output 2 Output State"
   "Digital Pulse Output 2 Period"
   "Digital Pulse Output 3 Active Time"
   "Digital Pulse Output 3 Duty"
   "Digital Pulse Output 3 Frequency"
   "Digital Pulse Output 3 Output State"
   "Digital Pulse Output 3 Period"
   "Digital Pulse Output 4 Active Time"
   "Digital Pulse Output 4 Duty"
   "Digital Pulse Output 4 Frequency"
   "Digital Pulse Output 4 Output State"
   "Digital Pulse Output 4 Period"
   "Digital Pulse Output 5 Active Time"
   "Digital Pulse Output 5 Duty"
   "Digital Pulse Output 5 Frequency"
   "Digital Pulse Output 5 Output State"
   "Digital Pulse Output 5 Period"
   "Drive By Wire 1 Pin 1 Duty Cycle"
   "Drive By Wire 1 Pin 1 Frequency"
   "Drive By Wire 1 Pin 1 On Time"
   "Drive By Wire 1 Pin 1 Output State"
   "Drive By Wire 1 Pin 1 Period"
   "Drive By Wire 1 Pin 2 Duty Cycle"
   "Drive By Wire 1 Pin 2 Frequency"
   "Drive By Wire 1 Pin 2 On Time"
   "Drive By Wire 1 Pin 2 Output State"
   "Drive By Wire 1 Pin 2 Period"
   "ECU On Time"
   "ECU Temperature"
   "Engine Angle"
   "Engine Cycle Count"
   "Engine Cycle Period"
   "Engine Demand"
   "Engine Limiter Active"
   "Engine Limiter Max RPM"
   "Engine Limiting Function"
   "Engine Limiting Method"
   "Engine Pressure Ratio"
   "Engine Protection Boost Correction"
   "Engine Protection Cause"
   "Engine Protection Ignition Retard"
   "Engine Protection Lambda Fuel Enrichment"
   "Engine Protection RPM Limit"
   "Engine Protection Severity Level"
   "Engine Running Time"
   "Engine State"
   "Exhaust Gas Temperature Max Value"
   "Feature List"
   "Filtered RPM"
   "Firmware State"
   "Fuel - Load"
   "Fuel - Primary Fuel Density"
   "Fuel Active Table"
   "Fuel Cranking"
   "Fuel Flow Estimated"
   "Fuel Mass Flow"
   "Fuel Mass Per Cylinder"
   "Fuel Post Start Correction"
   "Fuel Pressure Expected"
   "Fuel Prime Mass Per Cylinder"
   "Fuel Prime Pulse"
   "Fuel Pump Output State"
   "Fuel Tuning Current Fuel Density"
   "Fuel Tuning Current Stoichiometry"
   "Home Angle"
   "Home Arming Voltage"
   "Home Input State"
   "Home Tooth Count At Error"
   "Home Voltage"
   "Home percentage of valid travel"
   "Idle Control Base Output"
   "Idle Control Derivative Gain"
   "Idle Control Derivative Output"
   "Idle Control Ignition Correction"
   "Idle Control Integral Gain"
   "Idle Control Integral Output"
   "Idle Control Long Term Trim"
   "Idle Control Long Term Trim Gain"
   "Idle Control Min Output"
   "Idle Control Output"
   "Idle Control Proportional Gain"
   "Idle Control Proportional Output"
   "Idle Control RPM error"
   "Idle Control Short Term Trim"
   "Idle Control Start Base Offset"
   "Idle Control Start Target Offset"
   "Idle Control State"
   "Idle Control target RPM"
   "Idle Control target RPM Table Output"
   "Ignition - Load"
   "Ignition 1 Angle"
   "Ignition 1 Duty Cycle"
   "Ignition 1 Frequency"
   "Ignition 1 On Time"
   "Ignition 1 Output State"
   "Ignition 1 Period"
   "Ignition 2 Angle"
   "Ignition 2 Duty Cycle"
   "Ignition 2 Frequency"
   "Ignition 2 On Time"
   "Ignition 2 Output State"
   "Ignition 2 Period"
   "Ignition 3 Angle"
   "Ignition 3 Duty Cycle"
   "Ignition 3 Frequency"
   "Ignition 3 On Time"
   "Ignition 3 Output State"
   "Ignition 3 Period"
   "Ignition 4 Angle"
   "Ignition 4 Duty Cycle"
   "Ignition 4 Frequency"
   "Ignition 4 On Time"
   "Ignition 4 Output State"
   "Ignition 4 Period"
   "Ignition 5 Angle"
   "Ignition 5 Duty Cycle"
   "Ignition 5 Frequency"
   "Ignition 5 On Time"
   "Ignition 5 Output State"
   "Ignition 5 Period"
   "Ignition 6 Angle"
   "Ignition 6 Duty Cycle"
   "Ignition 6 Frequency"
   "Ignition 6 On Time"
   "Ignition 6 Output State"
   "Ignition 6 Period"
   "Ignition 7 Duty Cycle"
   "Ignition 7 Frequency"
   "Ignition 7 On Time"
   "Ignition 7 Output State"
   "Ignition 7 Period"
   "Ignition 8 Duty Cycle"
   "Ignition 8 Frequency"
   "Ignition 8 On Time"
   "Ignition 8 Output State"
   "Ignition 8 Period"
   "Ignition Active Table"
   "Ignition Angle"
   "Ignition Angle Bank 1"
   "Ignition Angle Bank 2"
   "Ignition Coil Power Supply"
   "Ignition Correction Total"
   "Ignition Dwell Time"
   "Ignition Switch Double Tap Counter"
   "Ignition Switch State"
   "Injection Active Stages"
   "Injection Percentage of Total Flow Used"
   "Injection Stage 1 Average Duty Cycle"
   "Injection Stage 1 Average Injection Time"
   "Injection Stage 1 Dead Time"
   "Injection Stage 1 End of Injection Angle"
   "Injection Stage 1 Firing Angle BTDC"
   "Injection Stage 1 Flow Rate"
   "Injection Stage 1 Outputs Highest Duty Cycle"
   "Injection Stage 1 Start of Injection Angle"
   "Injector 1 Duty Cycle"
   "Injector 1 Frequency"
   "Injector 1 On Time"
   "Injector 1 Output State"
   "Injector 1 Period"
   "Injector 1 Volume"
   "Injector 2 Duty Cycle"
   "Injector 2 Frequency"
   "Injector 2 On Time"
   "Injector 2 Output State"
   "Injector 2 Period"
   "Injector 2 Volume"
   "Injector 3 Duty Cycle"
   "Injector 3 Frequency"
   "Injector 3 On Time"
   "Injector 3 Output State"
   "Injector 3 Period"
   "Injector 3 Volume"
   "Injector 4 Duty Cycle"
   "Injector 4 Frequency"
   "Injector 4 On Time"
   "Injector 4 Output State"
   "Injector 4 Period"
   "Injector 4 Volume"
   "Injector 5 Duty Cycle"
   "Injector 5 Frequency"
   "Injector 5 On Time"
   "Injector 5 Output State"
   "Injector 5 Period"
   "Injector 5 Volume"
   "Injector 6 Duty Cycle"
   "Injector 6 Frequency"
   "Injector 6 On Time"
   "Injector 6 Output State"
   "Injector 6 Period"
   "Injector 6 Volume"
   "Injector 7 Duty Cycle"
   "Injector 7 Frequency"
   "Injector 7 On Time"
   "Injector 7 Output State"
   "Injector 7 Period"
   "Injector 8 Duty Cycle"
   "Injector 8 Frequency"
   "Injector 8 On Time"
   "Injector 8 Output State"
   "Injector 8 Period"
   "Injector Power Supply"
   "Injector Pressure Differential"
   "Intake Air Temperature"
   "Knock Control Bank 1 Ignition Correction"
   "Knock Control Bank 1 Long Term Trim"
   "Knock Control Bank 2 Ignition Correction"
   "Knock Control Bank 2 Long Term Trim"
   "Knock Detection Active State"
   "Knock Input 1 FFT."
   "Knock Input 2 FFT"
   "Knock Sensor 1 Knock Count"
   "Knock Sensor 1 Knock Level"
   "Knock Sensor 1 Knock Signal"
   "Knock Sensor 2 Knock Count"
   "Knock Sensor 2 Knock Level"
   "Knock Sensor 2 Knock Signal"
   "Knock Start Spectrogram"
   "Knock State"
   "Knock Threshold"
   "Load - Normalised Air Mass Flow"
   "Main RPM Limiter End RPM"
   "Main RPM Limiter RPM Before Cut"
   "Manifold Pressure"
   "Manifold Pressure Derivative"
   "Manifold Pressure Derivative Filter"
   "Manifold Pressure Filter Scale"
   "Mass Air Flow"
   "Mass Air Flow 1"
   "Mass Air Flow Filter Scale"
   "Max Mass Air Flow"
   "Measured Manifold Pressure"
   "Memory Writes Pending"
   "O2 Control Bank 1 Error"
   "O2 Control Bank 1 Long Term Fuel Trim"
   "O2 Control Bank 1 Output"
   "O2 Control Bank 1 Short Term Fuel Trim"
   "O2 Control Bank 1 Short Term Fuel Trim Derivative"
   "O2 Control Bank 1 Short Term Fuel Trim Integral"
   "O2 Control Bank 1 Short Term Fuel Trim Proportional"
   "O2 Control Bank 1 Target"
   "O2 Control Bank 2 Error"
   "O2 Control Bank 2 Long Term Fuel Trim"
   "O2 Control Bank 2 Output"
   "O2 Control Bank 2 Short Term Fuel Trim"
   "O2 Control Bank 2 Short Term Fuel Trim Derivative"
   "O2 Control Bank 2 Short Term Fuel Trim Integral"
   "O2 Control Bank 2 Short Term Fuel Trim Proportional"
   "O2 Control Bank 2 Target"
   "O2 Control Delay"
   "O2 Control Derivative Gain"
   "O2 Control Integral Gain"
   "O2 Control Long Term Fuel Trim Gain"
   "O2 Control Long Term Fuel Trim Max Disenrichment"
   "O2 Control Long Term Fuel Trim Max Enrichment"
   "O2 Control Proportional Gain"
   "O2 Control Short Term Fuel Trim Max Disenrichment"
   "O2 Control Short Term Fuel Trim Max Enrichment"
   "O2 Control State"
   "Overboost Cut Max Pressure"
   "Pedal Position Source"
   "Previous Tooth Period At Error"
   "RPM"
   "RPM Derivative"
   "RPM Derivative Filter"
   "RPM Limit - Fuel"
   "RPM Limit - Ignition"
   "RPM Limiter Active"
   "RPM Limiter Fuel Amount Correction"
   "RPM Limiter Ignition Correction"
   "RPM Limiting Function"
   "RPM Limiting Method"
   "Raw Air Mass Per Cylinder"
   "Reset Required"
   "Stepper 1 Pin 1 Duty Cycle"
   "Stepper 1 Pin 1 Frequency"
   "Stepper 1 Pin 1 On Time"
   "Stepper 1 Pin 1 Output State"
   "Stepper 1 Pin 1 Period"
   "Stepper 1 Pin 2 Duty Cycle"
   "Stepper 1 Pin 2 Frequency"
   "Stepper 1 Pin 2 On Time"
   "Stepper 1 Pin 2 Output State"
   "Stepper 1 Pin 2 Period"
   "Stepper 1 Pin 3 Duty Cycle"
   "Stepper 1 Pin 3 Frequency"
   "Stepper 1 Pin 3 On Time"
   "Stepper 1 Pin 3 Output State"
   "Stepper 1 Pin 3 Period"
   "Stepper 1 Pin 4 Duty Cycle"
   "Stepper 1 Pin 4 Frequency"
   "Stepper 1 Pin 4 On Time"
   "Stepper 1 Pin 4 Output State"
   "Stepper 1 Pin 4 Period"
   "Sync Offset Difference At Error"
   "Synced Pulse Input 1 Active Time"
   "Synced Pulse Input 1 Duty"
   "Synced Pulse Input 1 Frequency"
   "Synced Pulse Input 1 Input Angle"
   "Synced Pulse Input 1 Input State"
   "Synced Pulse Input 1 Period"
   "Synced Pulse Input 1 Resistance"
   "Synced Pulse Input 1 Voltage"
   "Synced Pulse Input 2 Active Time"
   "Synced Pulse Input 2 Duty"
   "Synced Pulse Input 2 Frequency"
   "Synced Pulse Input 2 Input Angle"
   "Synced Pulse Input 2 Input State"
   "Synced Pulse Input 2 Period"
   "Synced Pulse Input 2 Voltage"
   "Synced Pulse Input 3 Active Time"
   "Synced Pulse Input 3 Duty"
   "Synced Pulse Input 3 Frequency"
   "Synced Pulse Input 3 Input Angle"
   "Synced Pulse Input 3 Input State"
   "Synced Pulse Input 3 Period"
   "Synced Pulse Input 3 Voltage"
   "Synced Pulse Input 4 Active Time"
   "Synced Pulse Input 4 Duty"
   "Synced Pulse Input 4 Frequency"
   "Synced Pulse Input 4 Input Angle"
   "Synced Pulse Input 4 Input State"
   "Synced Pulse Input 4 Period"
   "Synced Pulse Input 4 Resistance"
   "Synced Pulse Input 4 Voltage"
   "Tacho Output Frequency"
   "Target Lambda"
   "Target Lambda Air Temperature Correction"
   "Target Lambda Coolant Correction"
   "Target Lambda Table Output"
   "Thermofan 1 Idle Up Active"
   "Thermofan 1 Output State"
   "Thermofan 1 Source Temperature"
   "Throttle Position"
   "Throttle Position - Cable"
   "Throttle Position Derivative"
   "Throttle Position Derivative - Cable"
   "Throttle Position Derivative Filter"
   "Throttle Position Filter Scale"
   "Time Since Last Engine Run"
   "Tooth Period At Error"
   "Total Fuel Used Since Engine Startup - Calculated"
   "Total Fuel Used Since Engine Startup RAW"
   "Transient Throttle Coolant Temperature Fuel Correction"
   "Transient Throttle Current Ignition Correction"
   "Transient Throttle Enrichment Decay Rate"
   "Transient Throttle Enrichment Load Derivative"
   "Transient Throttle Enrichment Load Derivative Max"
   "Transient Throttle Enrichment Start Load"
   "Transient Throttle Fuel Enrichment Rate"
   "Transient Throttle Fuel Enrichment Sync Amount"
   "Transient Throttle Fuel Peak Synchronous Output"
   "Transient Throttle Ignition Correction"
   "Transient Throttle Load Derivative"
   "Trigger Arming Voltage"
   "Trigger Input State"
   "Trigger Sync Level Status"
   "Trigger Sync Offset"
   "Trigger Synchronisation Level"
   "Trigger Synchronisation State"
   "Trigger System Error Count"
   "Trigger System Errors"
   "Trigger Tooth Count"
   "Trigger Tooth Count At Error"
   "Trigger Voltage"
   "WBC1 Lambda"
   "WBC2 A Input 1 Lambda"
   "WBC2 A Input 2 Lambda"
   "WBC2 B Input 1 Lambda"
   "WBC2 B Input 2 Lambda"
   "WBC2 C Input 1 Lambda"
   "WBC2 C Input 2 Lambda"
   "WBC2 D Input 1 Lambda"
   "WBC2 D Input 2 Lambda"
   "Wideband Maximum"
   "Wideband O2 1"
   "Wideband O2 1 Derivative"
   "Wideband O2 2"
   "Wideband O2 2 Derivative"
   "Wideband O2 Bank 1"
   "Wideband O2 Bank 1 Derivative"
   "Wideband O2 Bank 2"
   "Wideband O2 Bank 2 Derivative"
   "Wideband O2 Overall"
   "Wideband O2 Overall Derivative"
   "Worst Home percentage of valid travel"
   "Zero Demand Ignition Angle"])


(defn divide-by-1000
  [value]
  (double (/ value 1000)))


(defn divide-by-100
  [value]
  (double (/ value 100)))


(defn divide-by-10
  [value]
  (double (/ value 10)))


(def type-info
  ; map key is Channel Type, map value is tuple of:
  ; [Symbol, Description, Type, Function]
  {"AFR"
   ["λ"
    "Lambda"
    :float64
    divide-by-1000]

   "AbsPressure"
   ["kPa"
    "Kilopascal (Absolute)"
    :float32
    divide-by-10]

   "Angle"
   ["°"
    "Degrees BTDC"
    :float64
    divide-by-10]

   "AngularVelocity"
   ["°/s"
    "Degrees Per Second"
    :float32
    divide-by-10]

   "BatteryVoltage"
   ["v"
    "Volts"
    :float32
    divide-by-1000]

   "ByteCount"
   ["MB"
    "Megabytes"
    :float64
    #(float (/ % 1048576))]

   "Decibel"
   ["dB"
    "Decibels"
    :float32
    divide-by-100]

   "Density"
   ["kg/m³"
    "Kilograms Per Cubic Meter"
    :float32
    divide-by-10]

   "EngineSpeed"
   ["RPM"
    "RPM"
    :uint32
    identity]

   "EngineVolume"
   ["cc"
    "Cubic Centimeters"
    :uint16
    identity]

   "Flow"
   ["cc/min"
    "Cubic Centimeters Per Minute"
    :uint16
    identity]

   "Frequency"
   ["Hz"
    "Hertz"
    :uint32
    identity]

   "FuelVolume"
   ["L"
    "Liters"
    :float32
    divide-by-100]

   "InjFuelVolume"
   ["μL"
    "Microliters"
    :float64
    divide-by-100]

   "MassOverTime"
   ["g/s"
    "Grams Per Second"
    :float64
    divide-by-100]

   "MassPerCyl"
   ["g/Cyl"
    "Grams Per Cylinder"
    :float32
    divide-by-1000]

   "PercentPerEngineCycle"
   ["%/ECyc"
    "Percent Per Engine Cycle"
    :float32
    divide-by-10]

   "PercentPerKPa"
   ["%/kPa"
    "Percent Per Kilopascal"
    :float32
    divide-by-10]

   "PercentPerLambda"
   ["%/λ"
    "Percent Per Lambda"
    :float32
    divide-by-10]

   "PercentPerRpm"
   ["% / 100 RPM"
    "Percent Per RPM"
    :float32
    divide-by-100]

   "Percentage"
   ["%"
    "Percent"
    :float64
    divide-by-10]

   "Pressure"
   ["kPa"
    "Kilopascal"
    :float32
    #(- (divide-by-10 %) 101)]

   "Ratio"
   [""
    "Ratio"
    :float32
    divide-by-1000]

   "Raw"
   ["Raw"
    "Raw"
    :uint32
    identity]

   "Resistance"
   ["Ω"
    "Ohms"
    :float64
    divide-by-10]

   "Stoichiometry"
   ["AFR"
    "Air Fuel Ratio"
    :float32
    divide-by-1000]

   "Temperature"
   ["°C"
    "Degrees Celcius"
    :float32
    #(- (divide-by-10 %) 273.15)]

   "Time_ms"
   ["ms"
    "Milliseconds"
    :uint32
    identity]

   "Time_ms_as_s"
   ["s"
    "Seconds"
    :float64
    divide-by-1000]

   "Time_s"
   ["s"
    "Seconds"
    :uint32
    identity]

   "Time_us"
   ["ms"
    "Milliseconds"
    :float64
    divide-by-1000]})


(defn convert-value
  "Takes channel-type and value, returns map of standardized value,
  units, and description:
  {:value 123
   :units \"s\"
   :description \"Seconds\"}"
  [channel-type value]
  (let [[unit _ _ f] (get type-info channel-type)]
    (if (some? f)
      [(f value) unit]
      [value nil])))


(defn convert
  "Returns dataset with values converted to native units"
  [ds {:keys [channels]}]
  (let [; Map from "Channel" to "Type"
        ct-fn   (juxt #(get % "Channel") #(get % "Type"))
        ct-map  (into {} (mapv ct-fn channels))
        conv-fn (fn [r]
                  (->> (for [[k v] r]
                         ; Get Type of Channel
                         (let [t (get ct-map k)]
                           [k (convert-value t v)]))
                       (into (empty r))))]
    (ds/row-map ds conv-fn {})))


(defn header->units
  "Takes a type-info map, and log header.  Returns a map of:
  {\"Channel-1\": [Unit, Description, :Type]}"
  [type-info {:keys [channels] :as header}]
  (into
    {}
    (for [c channels]
      (let [c-name (get c "Channel")
            c-type (get c "Type")
            t-info (get type-info c-type)]
        [c-name [(first t-info) (second t-info) (nth t-info 2)]]))))


(defn parse
  "Parse java.io.File as Haltech Log.  Returns map of parsed data.
  Opts is a map of the following keys:
  |-----------------+------------+-----------------------------------|
  | key             | type       | description                       |
  |-----------------+------------+-----------------------------------|
  | :parse-channels | seq string | Speeds up processing by only      |
  |                 |            | parsing specified channels        |
  |-----------------+------------+-----------------------------------|
  | :native-units   | bool       | Convert raw data points to native |
  |                 |            | units and return :units key       |
  |-----------------+------------+-----------------------------------|

  Shape of return map is:
  {:dataset <parsed dataset>
   ; Only returned when (true? :native-units)
   :units   {\"Channel-1\": [\"String Unit\", \"String Description\"],
             \"Channel-N\": [\"String Unit\", \"String Description\"]}}"
  ([file]
   (parse file {}))

  ([file {:keys [parse-channels native-units] :as opts}]
   (let [header (nsp-parse-header file)
         opts   (merge
                  {}
                  (when parse-channels
                    {:parse-channels parse-channels})

                  (when (true? native-units)
                    {:native-units true
                     :type-info    type-info}))
         ds     (nsp->dataset file header opts)]
     (merge
       {:dataset ds}
       (when (true? native-units)
         {:units (header->units type-info header)})))))
