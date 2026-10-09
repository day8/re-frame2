(ns re-frame.warn-once-clear-governance-test
  "The source half of the warn-once-clear governance gate. Only
  `re-frame.late-bind` may `chain-fn!` the `:adapter/clear-warn-once-caches!`
  key directly; every contributor enrols through `register-warn-once-clear-fn!`
  (or the `install-clear-warn-once-step!` seam), so chaining and registry
  enrolment are atomic. A raw `chain-fn!` would chain a cache without recording
  it, out of sight of `re-frame.warn-once-clear-governance-cljs-test`, which
  proves at runtime that the chain wipes every ENROLLED cache."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private repo-implementation-root
  (-> (io/file "..") .getCanonicalFile))

(defn- source-files
  "Every `.clj{,c,s}` under `implementation/**/src/` (skips `test/`)."
  []
  (->> (file-seq repo-implementation-root)
       (filter #(.isFile ^java.io.File %))
       (filter (fn [^java.io.File f]
                 (let [n (.getName f)]
                   (or (str/ends-with? n ".clj")
                       (str/ends-with? n ".cljc")
                       (str/ends-with? n ".cljs")))))
       (filter (fn [^java.io.File f]
                 (let [norm (str/replace (.getPath f) "\\" "/")]
                   (and (str/includes? norm "/src/")
                        (not (str/includes? norm "/test/"))))))))

(defn- ns-name-of [content]
  (second (re-find #"\(ns\s+([a-zA-Z][a-zA-Z0-9.\-]*)" content)))

;; Both alias spellings, `rf.late-bind/` and `late-bind/`: a spelling the gate
;; cannot see is an offender it would wave through.
(def ^:private raw-chain-re
  #"\((?:(?:rf\.)?late-bind/)?chain-fn!\s+:adapter/clear-warn-once-caches!")

(deftest only-the-chokepoint-chains-the-warn-once-clear-key
  (let [offenders
        (for [^java.io.File f (source-files)
              :let [content (slurp f)]
              :when (re-find raw-chain-re content)
              :let [ns-sym (ns-name-of content)]
              :when (not= "re-frame.late-bind" ns-sym)]
          (str ns-sym " (" (.getPath f) ")"))]
    (is (empty? offenders)
        (str "These source files chain :adapter/clear-warn-once-caches! with a "
             "RAW chain-fn!, so the cache is chained without being recorded in "
             "the warn-once-clear-registry. Route through "
             "re-frame.late-bind/register-warn-once-clear-fn!:\n  "
             (str/join "\n  " (sort offenders))))))
