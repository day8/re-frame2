(ns re-frame.warn-once-clear-governance-test
  "JVM source-enumeration half of the warn-once-clear governance gate
  (rf2-z79p8). The CLJS half
  (`re-frame.warn-once-clear-governance-cljs-test`) proves, at runtime,
  that firing the canonical `:adapter/clear-warn-once-caches!` chain wipes
  every cache enrolled in the `warn-once-clear-registry`. This half proves,
  at the source level, that there is exactly ONE way to enrol a cache into
  that chain — the chokepoint `register-warn-once-clear-fn!` — so a future
  5th cache cannot quietly chain itself with a bare `chain-fn!` (which
  would chain it WITHOUT recording it in the registry, re-opening the
  rf2-4edk/9hoos/qy6cl/z79p8 defect class the CLJS gate can no longer see).

  SINGLE CHOKEPOINT assertion — no source file other than
  `re-frame.late-bind` itself (which DEFINES the chokepoint) may call
  `(chain-fn! :adapter/clear-warn-once-caches! ...)` directly. Every
  contributor goes through `register-warn-once-clear-fn!` (core/views) or
  `install-clear-warn-once-step!` (the spine/adapter seam, itself a thin
  delegator). This guarantees enrolment-and-chaining are atomic: you
  cannot chain without registering.

  (A second assertion once enumerated standalone `clear-*warned*!`-shaped
  late-bind hooks — the historical straggler shape, a `defonce` cache with
  a hand-published clear-fn — and checked each was routed through the
  chokepoint. The last such hook, `:views/clear-plain-fn-warned-pairs!`,
  was removed in rf2-k4xous once its warning was retired per EP-0002, so
  that shape no longer exists in source and the assertion had no subject.)

  Walks the same source tree as `re-frame.late-bind-drift-test`.")

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is testing]])

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

(defn- ns-name-of
  "Best-effort `(ns ...)` symbol of a source file (first ns form)."
  [content]
  (when-let [m (re-find #"\(ns\s+([a-zA-Z][a-zA-Z0-9.\-]*)" content)]
    (second m)))

;; ---------------------------------------------------------------------------
;; 1. Single chokepoint — only re-frame.late-bind may chain the key directly
;; ---------------------------------------------------------------------------

(def ^:private raw-chain-re
  "Match a direct `chain-fn!` (qualified or not) on the warn-once-clear
  key. The chokepoint `register-warn-once-clear-fn!` (in re-frame.late-bind)
  is the ONLY legitimate such call site."
  #"\((?:late-bind/)?chain-fn!\s+:adapter/clear-warn-once-caches!")

(deftest only-the-chokepoint-chains-the-warn-once-clear-key
  (testing "no source file other than re-frame.late-bind calls
            (chain-fn! :adapter/clear-warn-once-caches! ...) directly —
            every contributor enrols through register-warn-once-clear-fn!
            so chaining and registry-enrolment are atomic (rf2-z79p8)"
    (let [offenders
          (for [^java.io.File f (source-files)
                :let [content (slurp f)]
                :when (re-find raw-chain-re content)
                :let [ns-sym (ns-name-of content)]
                :when (not= "re-frame.late-bind" ns-sym)]
            (str ns-sym " (" (.getPath f) ")"))]
      (is (empty? offenders)
          (str "These source files chain :adapter/clear-warn-once-caches! "
               "with a RAW chain-fn! instead of the canonical chokepoint "
               "re-frame.late-bind/register-warn-once-clear-fn! (or the "
               "spine seam install-clear-warn-once-step!). A raw chain-fn! "
               "wires the cache into the fixture chain WITHOUT recording it "
               "in the warn-once-clear-registry, so the CLJS governance "
               "assertion can no longer see it — re-opening the "
               "rf2-4edk/9hoos/qy6cl/z79p8 defect class. Route through the "
               "chokepoint:\n  "
               (str/join "\n  " (sort offenders)))))))
