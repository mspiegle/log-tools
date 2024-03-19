(defproject mspiegle/summarize-logs "0.2.0-SNAPSHOT"
  :min-lein-version "2.7.0"
  :parent-project {:path "../../project.clj"
                   :inherit [:managed-dependencies :url :license]}
  :description "Summarize your logs with configurable output"
  :dependencies [[org.clojure/clojure]
                 [org.clojure/tools.cli]
                 [org.clojure/tools.logging]
                 [techascent/tech.ml.dataset]
                 [org.clj-commons/claypoole]
                 [table]]
  :plugins [[lein-parent "0.3.9"]]
  :source-paths ["src"
                 "../../libs/core/src"
                 "../../libs/ecu/src"]
  :main summarize-logs.main
  :target-path "target/%s"
  :global-vars {*warn-on-reflection* true}
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
