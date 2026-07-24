(ns re-frame.freehand.bench.runner
  "The command line CI runs the B-spine from.

      clojure -M:bench            # the standing evidence suite
      clojure -M:bench --prove    # the two-way falsifiability proof
      clojure -M:bench --help

  ## Output

  Everything goes to stdout, and everything on stdout is valid EDN: the
  human summary is written as `;;` comment lines above the report map, so
  the same bytes are both the thing a reviewer reads and the artefact a
  release cites.

      clojure -M:bench > out/freehand-bench.edn

  ## Exit codes

  0 and 1, and 1 means one thing only: a DETERMINISTIC property was
  violated. A wall-clock or byte distribution cannot reach this number —
  see [[re-frame.freehand.bench/run]] for why, and
  [[re-frame.freehand.bench.falsifiability]] for the proof.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [re-frame.freehand.bench :as bench]
            ;; The registered workloads. A workload registers itself at
            ;; load, so the suite the CLI runs is exactly the set of
            ;; workload namespaces required here — one line per B-slice as
            ;; it lands, and nothing discovered by scanning.
            [re-frame.freehand.bench.b1]
            [re-frame.freehand.bench.b2]
            [re-frame.freehand.bench.b3]
            [re-frame.freehand.bench.falsifiability :as falsifiability]
            #?(:clj [clojure.pprint :as pp] :cljs [cljs.pprint :as pp])))

#?(:clj (set! *warn-on-reflection* true))

(def usage
  ["Freehand B-spine: the measurement harness (D021)."
   ""
   "  clojure -M:bench             run the standing evidence suite"
   "  clojure -M:bench --prove     run the two-way falsifiability proof"
   "  clojure -M:bench --help      this"
   ""
   "Stdout is valid EDN with the summary as leading `;;` comments."
   "Exit 1 means a deterministic property was violated, and nothing else."])

;; ---------------------------------------------------------------------------
;; The two commands
;; ---------------------------------------------------------------------------

(defn- suite-summary
  [{:keys [results gate-failures]}]
  (concat
    [(str "Freehand evidence suite: " (count results)
          (if (= 1 (count results)) " workload" " workloads") " run.")]
    (if (seq results)
      (for [r results]
        (str "  " (:benchmark r) ": " (count (:distribution r)) " published distribution(s), "
             (count (:properties r)) " deterministic gate(s), on "
             (get-in r [:host :runtime]) " / " (get-in r [:host :hardware-class]) "."))
      ["  No workloads registered yet. B1-B5 each arrive with the slice that can run them."])
    (if (seq gate-failures)
      (cons "GATE FAILURES:" (map #(str "  " (:message %)) gate-failures))
      ["All deterministic properties held. Distributions below are evidence: no pass/fail."])))

(defn- round1
  [x]
  (when (number? x) (/ (double (Math/round (* (double x) 10.0))) 10.0)))

(defn- prove-summary
  [{:keys [arms move timing-ratio defects proven?]}]
  (concat
    ["Freehand B-spine falsifiability proof (D021, both directions)."
     (str "  a gate that HOLDS      -> exit " (get-in arms [:honoured-gate :exit-code])
          "   (expected 0)")
     (str "  a gate VIOLATED        -> exit " (get-in arms [:violated-gate :exit-code])
          "   (expected 1)")]
    (map #(str "      " %) (get-in arms [:violated-gate :gate-failures]))
    [(str "  timing baseline        -> exit " (get-in arms [:baseline-timing :exit-code])
          "   (expected 0), p50 " (get-in arms [:baseline-timing :p50-ms]) "ms")
     (str "  timing MOVED           -> exit " (get-in arms [:moved-timing :exit-code])
          "   (expected 0), p50 " (get-in arms [:moved-timing :p50-ms]) "ms")
     ;; The proof's quantity and the reader's quantity, kept apart on
     ;; purpose: one decides the exit code, the other is evidence.
     (str "  the moved arm did " (round1 move) "x the baseline's WORK ("
          (get-in arms [:moved-timing :nodes]) " vs "
          (get-in arms [:baseline-timing :nodes]) " nodes rendered) -- deterministic, "
          "and the only thing this proof reads.")
     (str "  its wall clock moved " (round1 timing-ratio)
          "x -- published evidence, gating nothing.")]
    (if proven?
      ["  PROVEN both ways: a violated deterministic property reds; a wildly moved"
       "  wall-clock distribution does not."]
      (cons "  NOT PROVEN:" (map #(str "    " %) defects)))))

(defn run-cli
  "The whole CLI, as a pure function of `args`.

  Answers `{:exit-code n :summary [line …] :report <edn>}`. `-main` adds
  printing and process exit and nothing else, so the exit-code contract
  is testable without a process.

  `opts` are [[re-frame.freehand.bench/run-workload]]'s provenance
  overrides, for a caller that knows the artefact it built better than
  the process can detect it. `-main` passes none."
  ([args] (run-cli args nil))
  ([args opts]
   (let [flags (set args)]
     (cond
       (or (contains? flags "--help") (contains? flags "-h"))
       {:exit-code 0 :summary usage :report {:command :help}}

       (contains? flags "--prove")
       (let [proof (falsifiability/prove opts)]
         {:exit-code (if (:proven? proof) 0 1)
          :summary   (prove-summary proof)
          :report    (assoc proof :command :prove)})

       :else
       (let [outcome (bench/run (vals (bench/registered)) opts)]
         {:exit-code (:exit-code outcome)
          :summary   (suite-summary outcome)
          :report    (assoc outcome :command :suite)})))))

;; ---------------------------------------------------------------------------
;; Stdout
;; ---------------------------------------------------------------------------

(defn render
  "The exact bytes [[-main]] writes to stdout for a [[run-cli]] answer: the
  human summary as leading `;;` comment lines, a blank `;;` separator, then
  the report map pretty-printed.

  Everything above the map is an EDN comment and the map is the datum, so
  the whole string reads back with plain `clojure.edn/read-string` — no
  `:default` tag handler, no reader registry. Pure, so that round-trip
  contract is a test over a value rather than a claim about a process: a
  future fixture carrying an opaque host value is caught here, at the door,
  rather than by the first reader who tries to slurp the artefact."
  [{:keys [summary report]}]
  (str (reduce str "" (map #(str ";; " % "\n") summary))
       ";;\n"
       (with-out-str (pp/pprint report))))

;; ---------------------------------------------------------------------------
;; Process
;; ---------------------------------------------------------------------------

(defn- exit!
  [code]
  #?(:clj  (System/exit code)
     :cljs (js/process.exit code)))

(defn -main
  [& args]
  (let [{:keys [exit-code] :as answer} (run-cli args)]
    (print (render answer))
    (flush)
    (exit! exit-code)))
