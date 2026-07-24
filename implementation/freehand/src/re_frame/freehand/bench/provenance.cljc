(ns re-frame.freehand.bench.provenance
  "The result record, and the reason a number without one is not evidence.

  D021: *\"Every stored result must name the source revision, build mode,
  browser/runtime, hardware class, warm-up/sample policy, fixture
  parameters, and whether dev instrumentation was enabled. A median
  without its distribution and environment is not release evidence.\"*

  The obligation is enforced at the only door that emits a record. There
  is no lenient path, no partial record, and no default that invents a
  field the run did not actually observe: [[result]] answers a record or
  throws, and [[required-fields]] is the whole ledger it checks — the
  same ledger [[re-frame.freehand.bench/workload]] uses to reject a
  fixture that could never emit one.

  Detection, never a literal. `revision`, `host` and `build` are read
  from the environment the run is actually in ([[detect-revision]],
  [[detect-host]], [[detect-build]]) or supplied by the caller. Hardware
  class in particular is detected or configured — a benchmark record that
  hardcodes the machine it was written on is a record about nothing.

  The shape follows D021's compact-EDN example, with one deliberate
  split. D021 puts every observation under one `:result` key; this record
  separates `:distribution` (evidence, never a verdict) from
  `:properties` (the deterministic gates and their verdicts), because
  keeping the two legible in the artefact is the whole point of the
  ruling.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [clojure.string :as str]
            #?(:clj [clojure.java.shell :as shell])))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The four named baselines
;; ---------------------------------------------------------------------------

(def baselines
  "D021 §Baselines. A result with no comparator is a number; these are
  the four comparators Freehand's evidence is allowed to name.

  Every workload declares one, plus a `:reference` map saying WHICH
  other arm, donor cut, or revision it is measured against."
  {:interpreted-vs-compiled
   "Freehand interpreted versus Freehand compiled — isolates lowering cost under common semantics."

   :absorbed-vs-donor
   "The absorbed tier versus a named donor cut — detects regressions introduced while moving proven machinery."

   :before-vs-after
   "Before versus after an implementation change — the regression comparison."

   :self-vs-end-to-end
   "Substrate self time versus end-to-end host time — stops a substrate-local speedup being sold as an application speedup."})

;; ---------------------------------------------------------------------------
;; Detection
;; ---------------------------------------------------------------------------

(defn env
  "Read environment variable `n`, or nil where the host has no
  environment (a browser)."
  [n]
  #?(:clj  (System/getenv n)
     :cljs (when (and (exists? js/process) (some? (.-env js/process)))
             (aget (.-env js/process) n))))

(defn- blank->nil [s]
  (when (and (string? s) (not (str/blank? s))) s))

#?(:cljs
   ;; The browser has no environment and no git. A bundle that is going to
   ;; be measured names the revision it was built from at COMPILE time:
   ;;
   ;;   :closure-defines {re-frame.freehand.bench.provenance/build-revision "<sha>"}
   ;;
   ;; The `:browser-test-freehand-bench` build in implementation/shadow-cljs.edn
   ;; is the one that does — reading the sha from `RF2_REVISION` through
   ;; `#shadow/env` rather than holding a literal, so the define says which
   ;; commit the bundle was compiled from and not which commit someone last
   ;; edited the config on.
   ;;
   ;; Left empty, a browser run cannot emit a result record — which is the
   ;; correct outcome, not an inconvenience: an unattributable bundle
   ;; measurement is a number about nothing.
   (goog-define build-revision ""))

(defn detect-revision
  "The source revision the measured code was built from.

  `RF2_REVISION` wins (a build pipeline knows better than the checkout it
  happens to be standing in), then `GITHUB_SHA`, then the host's own best
  answer: a compile-time `build-revision` on ClojureScript, `git rev-parse
  HEAD` on the JVM.

  When none of those answer, this answers nil and [[result]] refuses to
  emit. That refusal is the policy working, not a gap in it."
  []
  (or (blank->nil (env "RF2_REVISION"))
      (blank->nil (env "GITHUB_SHA"))
      #?(:clj  (try
                 (let [{:keys [exit out]} (shell/sh "git" "rev-parse" "HEAD")]
                   (when (zero? exit) (blank->nil (str/trim out))))
                 (catch Exception _ nil))
         :cljs (blank->nil build-revision))))

(defn detect-hardware-class
  "The hardware class this run's numbers may be compared within.

  Configured (`RF2_HARDWARE_CLASS`) or detected — never a literal naming
  one machine. In CI without an explicit class the runner is a
  `:shared-ci-runner`, which is honest: a shared runner's distribution is
  comparable with other shared-runner distributions and with nothing
  else. D021's pinned scheduled worker sets `RF2_HARDWARE_CLASS` to say
  so."
  []
  (if-let [configured (blank->nil (env "RF2_HARDWARE_CLASS"))]
    (keyword configured)
    (if (blank->nil (env "CI")) :shared-ci-runner :developer-workstation)))

(defn detect-host
  "The browser/runtime and hardware class this run happened on."
  []
  (merge
    {:hardware-class (detect-hardware-class)}
    #?(:clj
       {:runtime  (str "JVM " (System/getProperty "java.vm.name")
                       " " (System/getProperty "java.version"))
        :cpu-count (.availableProcessors (Runtime/getRuntime))}
       :cljs
       (cond
         (and (exists? js/process) (some? (.-version js/process)))
         {:runtime (str "Node " (.-version js/process))}

         (exists? js/navigator)
         {:runtime (str (.-userAgent js/navigator))}

         :else
         {:runtime "ClojureScript (unidentified host)"}))))

(defn detect-build
  "The build mode and whether dev instrumentation was compiled in.

  On CLJS both answers come from `goog.DEBUG`, which is the switch this
  repository's dev/tool code elides against, so a production bundle
  reports `{:optimizations :advanced :instrumentation? false}` without
  being told. The JVM has no optimization level and no elision, so it
  reports `:jvm` and an instrumented build — which is true, and is
  exactly why JVM timings and browser timings are never pooled."
  []
  #?(:clj  {:optimizations :jvm :instrumentation? true}
     :cljs (let [debug? (boolean goog/DEBUG)]
             {:optimizations    (if debug? :none :advanced)
              :instrumentation? debug?})))

;; ---------------------------------------------------------------------------
;; The required-field ledger
;; ---------------------------------------------------------------------------

(defn- non-blank? [v] (and (string? v) (not (str/blank? v))))
(defn- populated-map? [v] (and (map? v) (seq v)))

(def required-fields
  "Every provenance field a result record must name, as data.

  A vector, not a `when-not` cascade, for three reasons: the omission
  test enumerates it rather than restating it; the workload validator
  reuses a SUBSET of it so a fixture is rejected at declaration rather
  than after a ten-minute run; and a reader can see the whole obligation
  in one screen and check it against D021."
  [{:path     [:benchmark]
    :names    "the workload id"
    :valid?   keyword?
    :expected "a keyword naming the workload, e.g. :B1/finite-template"}

   {:path     [:revision]
    :names    "the source revision"
    :valid?   non-blank?
    :expected "the revision the measured code was built from — set RF2_REVISION, or run inside a git checkout"}

   {:path     [:fixture]
    :names    "the fixture parameters"
    :valid?   populated-map?
    :expected "a non-empty map of the workload's parameters — sizes are fixture parameters, not language constants"}

   {:path     [:build :optimizations]
    :names    "the build mode"
    :valid?   keyword?
    :expected "the optimization level the measured code was compiled at, e.g. :advanced / :none / :jvm"}

   {:path     [:build :instrumentation?]
    :names    "the instrumentation mode"
    :valid?   boolean?
    :expected "true or false — whether dev instrumentation was compiled in; an instrumented build is not a production build"}

   {:path     [:host :runtime]
    :names    "the browser/runtime"
    :valid?   non-blank?
    :expected "the browser or runtime name and version the workload ran on"}

   {:path     [:host :hardware-class]
    :names    "the hardware class"
    :valid?   keyword?
    :expected "the detected or configured hardware class — never a literal naming one machine"}

   {:path     [:sampling :warmup]
    :names    "the warm-up policy"
    :valid?   nat-int?
    :expected "how many discarded iterations preceded the measured ones"}

   {:path     [:sampling :samples]
    :names    "the sample policy"
    :valid?   pos-int?
    :expected "how many measured iterations the distribution summarises"}

   {:path     [:distribution]
    :names    "the distribution"
    :valid?   populated-map?
    :expected "the sampled distributions this run observed — a median without its distribution is not release evidence"}

   {:path     [:baseline :kind]
    :names    "the baseline"
    :valid?   #(contains? baselines %)
    :expected (str "one of " (pr-str (vec (sort (keys baselines)))))}

   {:path     [:baseline :reference]
    :names    "the baseline reference"
    :valid?   populated-map?
    :expected "which arm, donor cut, or revision this result is measured against"}])

(def all-paths
  "The path of every entry in [[required-fields]], in ledger order."
  (mapv :path required-fields))

(defn defects
  "Answer a vector of provenance defects in `record` — empty when it is
  emittable.

  A field is `:missing` when its path is absent and `:invalid` when the
  value there cannot be what the field means. Both refuse the record;
  the distinction is only there so the message can be useful.

  The two-arity form checks a subset of [[all-paths]], which is how a
  workload is validated at declaration time against the fields it is
  responsible for."
  ([record] (defects record all-paths))
  ([record paths]
   (let [wanted (set paths)]
     (into []
           (keep (fn [{:keys [path names valid? expected]}]
                   (when (contains? wanted path)
                     (let [v (get-in record path ::missing)]
                       (cond
                         (= ::missing v)
                         {:defect :missing :path path :names names :expected expected}

                         (not (valid? v))
                         {:defect :invalid :path path :names names :expected expected :observed v})))))
           required-fields))))

(defn- defect-line
  [{:keys [defect path names expected observed]}]
  (str (pr-str path) " (" names ") is "
       (if (= :missing defect) "missing" (str "invalid — " (pr-str observed)))
       "; expected " expected))

(defn defects-message
  "The one-line human sentence a refused record throws with."
  [ds]
  (str "This benchmark result cannot be emitted: " (count ds)
       (if (= 1 (count ds)) " provenance field is" " provenance fields are")
       " missing or malformed — "
       (str/join "; " (map defect-line ds))
       ". A median without its distribution and environment is not release evidence (D021)."))

(defn result
  "Answer `record` when it names every provenance field D021 requires,
  otherwise throw naming each field that does not hold.

  This is the ONLY door that emits a result record. There is no lenient
  variant and no `:strict?` flag, because a benchmark result that may be
  emitted without its environment is a benchmark result that eventually
  is."
  [record]
  (let [ds (defects record)]
    (when (seq ds)
      (throw (ex-info (defects-message ds)
                      {:bench/defect :incomplete-provenance
                       :defects      ds})))
    record))
