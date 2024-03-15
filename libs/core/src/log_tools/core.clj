(ns log-tools.core
  (:require [clojure.java.io :as io]))


(defn sniff-file
  "Returns the first size bytes of file"
  ([file]
   (sniff-file file 4096))
  ([file size]
   (with-open [reader (io/reader file)]
     (let [buffer (char-array size)]
       (.read reader buffer 0 size)
       (apply str buffer)))))


(defn timestamp-now-ms
  []
  (-> (java.time.Instant/now)
      (.toEpochMilli)))
