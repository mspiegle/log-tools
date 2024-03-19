(ns log-tools.ecu
  (:require [log-tools.ecu.haltech :as haltech]))


(def parser-priority
  [[haltech/detect-nsp haltech/parse-nsp]])


(defn resolve-parsers
  "Takes a seq of java.io.File and returns:
  [java.io.File parser-function]"
  [files]
  (map
    (fn detect-f
      [file]
      (let [f (fn [[detect-f parse-f]]
                (if (detect-f file)
                  [file parse-f]
                  [file nil]))]
        (some f parser-priority)))
    files))
