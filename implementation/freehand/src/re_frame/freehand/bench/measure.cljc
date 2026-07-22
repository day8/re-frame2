(ns re-frame.freehand.bench.measure
  "What a benchmark OBSERVES — and what observing it is allowed to decide.

  D021 rules that Freehand's deterministic properties are release gates
  and that its wall-clock and byte distributions are required published
  evidence, *never* fixed thresholds. That ruling is one sentence long
  and erodes in one line of code: a `:max-ms 12` on a timing fixture
  quietly mints a threshold the decision forbids, and a timing
  \"gate\" quietly converts runner noise into a red build that the next
  engineer learns to re-run rather than read.

  So the split is not a convention here. It is a total function of what
  a measurement observes:

      observable  ->  class          ->  enforcement
      :count          :property          :gate
      :flag           :property          :gate
      :value          :property          :gate
      :duration-ms    :distribution      :evidence
      :bytes          :distribution      :evidence

  A fixture author picks the observable — the thing they are actually
  looking at. They do not pick the enforcement, and there is no third
  answer. A spec may RESTATE its enforcement (`:enforcement :gate`), and
  restating it wrongly is rejected at construction, naming both the value
  stated and the value the observable compels. That is the mechanism
  behind D021's two-way obligation:

    - a timing or byte measurement CANNOT be registered as a gate. It has
      no path to a pass/fail verdict: `:duration-ms` classes as a
      `:distribution`, a distribution enforces as `:evidence`, and
      [[gate-failure]] refuses anything that is not a `:gate`. Declaring
      any pass/fail key on evidence ([[pass-fail-keys]]) is rejected —
      that is the folklore-threshold non-goal, mechanised.

    - a deterministic property CANNOT degrade to advisory. Restating a
      property as `:evidence` is rejected, and a gate WITHOUT an
      expectation is rejected too: a gate that cannot fail is advisory
      wearing a gate's name.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [clojure.string :as str]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The clock
;; ---------------------------------------------------------------------------

(defn now-ms
  "A monotonic elapsed-time reading in milliseconds, at the finest
  resolution the host offers.

  Deliberately NOT `re-frame.interop/now-ms`: that surface is the
  framework's shared elapsed clock and on the JVM it is
  `System/currentTimeMillis`, whose one-millisecond granularity cannot
  resolve a view render. A measurement harness that reported `0.0` for
  everything faster than a millisecond would publish a distribution of
  rounding error.

  The reading is origin-relative on both hosts and is meaningful only as
  a difference. Nothing durable is written from it."
  []
  #?(:clj  (/ (double (System/nanoTime)) 1e6)
     :cljs (if (and (exists? js/performance) (exists? js/performance.now))
             (js/performance.now)
             (.getTime (js/Date.)))))

;; ---------------------------------------------------------------------------
;; The allocation counter
;; ---------------------------------------------------------------------------

#?(:clj
   (def ^:private thread-allocation-bean
     "The JVM's per-thread allocation counter, or nil where this JVM
     offers none. Resolved once, lazily, so a host without it costs one
     failed lookup rather than one per reading."
     (delay
       (let [bean (java.lang.management.ManagementFactory/getThreadMXBean)]
         (when (and (instance? com.sun.management.ThreadMXBean bean)
                    (.isThreadAllocatedMemorySupported ^com.sun.management.ThreadMXBean bean))
           bean)))))

(defn allocated-bytes
  "A cumulative allocation reading in bytes, for the caller to difference
  across a measured region. The allocation half of D021's B1 requirement,
  and — like [[now-ms]] — meaningful only as a difference.

  What the number IS differs by host, and the difference is real enough
  that a result record's `:host` field is what tells a reader which one
  they are looking at:

    - **JVM** — `ThreadMXBean/getThreadAllocatedBytes`, a MONOTONIC
      per-thread counter of bytes allocated. A difference is therefore
      exactly the bytes the measured code allocated on this thread,
      whether or not a collection ran in the middle.
    - **Node** — `process.memoryUsage().heapUsed`, which is heap
      OCCUPANCY, not a counter. A collection between two readings makes
      the difference smaller than the bytes really allocated, and can
      make it negative. That is a property of the host's instrumentation,
      not of the measurement.
    - **Browser** — `performance.memory.usedJSHeapSize` where the engine
      exposes it, with the same occupancy caveat, quantised.

  Answers `0.0` where the host offers no reading at all. It is published
  as evidence and has no route to a verdict, so an uninformative host
  degrades the value of the number and nothing else."
  []
  #?(:clj  (if-let [^com.sun.management.ThreadMXBean bean @thread-allocation-bean]
             (double (.getThreadAllocatedBytes bean (.threadId (Thread/currentThread))))
             0.0)
     :cljs (cond
             (and (exists? js/process) (some? (.-memoryUsage js/process)))
             (double (.-heapUsed (js/process.memoryUsage)))

             (and (exists? js/performance) (some? (.-memory js/performance)))
             (double (.-usedJSHeapSize (.-memory js/performance)))

             :else 0.0)))

;; ---------------------------------------------------------------------------
;; The observable vocabulary — the whole of D021's split
;; ---------------------------------------------------------------------------

(def observables
  "The closed set of things a Freehand benchmark may observe.

  `:class` is the load-bearing field. `:property` is a deterministic fact
  about the run — an exact count, a correctness flag, an exact value —
  that is reproducible on any host and therefore safe to gate on.
  `:distribution` is a sampled magnitude whose spread is a property of
  the engine, the timer, the hardware and the moment, and which D021
  therefore publishes rather than judges."
  {:count       {:class :distribution-free
                 :doc   "an exact attributable count"}
   :flag        {:class :distribution-free
                 :doc   "a boolean correctness property"}
   :value       {:class :distribution-free
                 :doc   "an exact value, compared by ="}
   :duration-ms {:class :distribution
                 :doc   "wall-clock elapsed milliseconds"}
   :bytes       {:class :distribution
                 :doc   "shipped or retained size in bytes"}})

;; `:distribution-free` says exactly the right thing about the first
;; three: they have no distribution to publish, because a correct run
;; answers one value. That is the same fact as "deterministic", stated in
;; the vocabulary of the thing being enforced.

(def enforcement-by-class
  "D021's ruling as a total function. Two classes, two enforcements, no
  third answer and no author-supplied override."
  {:distribution-free :gate
   :distribution      :evidence})

(defn enforcement-for
  "The enforcement `observable` compels, or nil when it is not one of
  [[observables]]."
  [observable]
  (some-> (get observables observable) :class enforcement-by-class))

(def pass-fail-keys
  "Keys that turn a measurement into a pass/fail test. Legal on a gate
  (`:expect` is REQUIRED there); every one of them is rejected on an
  evidence measurement, because an evidence measurement carrying a
  budget is precisely the folklore threshold D021 forbids."
  #{:expect :threshold :budget :budget-ms :target :tolerance :within
    :limit :max :min :max-ms :min-ms :max-bytes :min-bytes})

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(defn- reject!
  [sentence data]
  (throw (ex-info sentence data)))

(defn- blank-doc? [d]
  (or (not (string? d)) (str/blank? d)))

(defn measurement
  "Answer the validated, normalised measurement `spec`, or throw.

  A spec names what it observes and says, in one line, what claim the
  observation supports:

      {:id         :B2/omitted-cells
       :doc        \"the exact omitted-ViewCell count on the mass mount\"
       :observable :count
       :expect     26}

  `:enforcement` and `:class` are ADDED by this function from
  `:observable`. Supplying `:enforcement` yourself is allowed and is
  checked, not honoured — a restatement that disagrees with the
  observable is the exact mistake D021 exists to prevent, so it is
  rejected rather than silently corrected.

  On a gate, `:expect` is required and is either a literal value
  (compared with `=`) or a predicate of the observed value.

  Normalisation is idempotent: re-running a normalised spec through this
  function answers an equal spec."
  [{:keys [id doc observable] :as spec}]
  (when-not (qualified-keyword? id)
    (reject! (str "A benchmark measurement needs a namespaced `:id` so two workloads cannot "
                  "collide on one name — got " (pr-str id) ".")
             {:bench/defect :measurement-id :spec spec}))
  (when (blank-doc? doc)
    (reject! (str "Measurement " id " needs a one-line `:doc` naming the property or claim it "
                  "supports — a gate failure that cannot say what broke is a stack trace, not "
                  "evidence.")
             {:bench/defect :measurement-doc :spec spec}))
  (when-not (contains? observables observable)
    (reject! (str "Measurement " id " declares `:observable " (pr-str observable) "`, which is "
                  "not one of " (pr-str (vec (sort (keys observables)))) ". The observable is "
                  "what decides whether a measurement gates or is published as evidence, so it "
                  "cannot be an open vocabulary.")
             {:bench/defect :measurement-observable :spec spec}))
  (let [klass   (:class (get observables observable))
        derived (enforcement-by-class klass)
        stated  (:enforcement spec)]
    (when (and (some? stated) (not= stated derived))
      (reject! (str "Measurement " id " observes " observable ", which D021 enforces as "
                    derived ", but the spec states `:enforcement " (pr-str stated) "`. "
                    (if (= :gate stated)
                      (str "Wall-clock and byte distributions are published evidence with no "
                           "automatic numeric pass/fail; a timing gate turns runner noise into "
                           "a red build.")
                      (str "Deterministic properties are hard release gates; letting one "
                           "degrade to advisory is how a correctness regression hides behind "
                           "benchmark noise."))
                    " Change the observable or drop the restatement.")
               {:bench/defect :enforcement-restated
                :measurement  id
                :observable   observable
                :stated       stated
                :compelled    derived}))
    (case derived
      :gate
      (when-not (contains? spec :expect)
        (reject! (str "Measurement " id " is a deterministic property and therefore a gate, but "
                      "declares no `:expect`. A gate with nothing to violate is advisory wearing "
                      "a gate's name — give it the value (or predicate) a correct run answers.")
                 {:bench/defect :gate-without-expectation :measurement id}))

      :evidence
      (when-let [k (first (filter #(contains? spec %) (sort pass-fail-keys)))]
        (reject! (str "Measurement " id " observes " observable " and is published evidence, but "
                      "declares `" k "`. D021 sets no numeric threshold on wall-clock or byte "
                      "distributions: an adverse trend is attributed and dispositioned by a "
                      "human, never failed automatically by a comparator nobody calibrated.")
                 {:bench/defect :threshold-on-evidence
                  :measurement  id
                  :offending-key k})))
    (assoc spec :enforcement derived :class klass)))

(defn gate?
  "True when `m` is a normalised gate measurement."
  [m]
  (= :gate (:enforcement m)))

(defn evidence?
  "True when `m` is a normalised evidence measurement."
  [m]
  (= :evidence (:enforcement m)))

;; ---------------------------------------------------------------------------
;; Gate evaluation — the ONLY producer of a failing verdict in this harness
;; ---------------------------------------------------------------------------

(defn- satisfied?
  [expect observed]
  (if (fn? expect)
    (boolean (expect observed))
    (= expect observed)))

(defn- expectation-str
  [expect]
  (if (fn? expect) "the declared predicate" (pr-str expect)))

(defn gate-failure
  "Answer nil when the gate `m` holds for `observed`, otherwise a failure
  map NAMING the property, its claim, what was expected and what was
  observed.

  Refuses a measurement that is not a gate. That refusal is what makes
  criterion 3 structural rather than tested: there is no argument to this
  function that turns a `:duration-ms` or `:bytes` observation into a
  failing verdict."
  [{:keys [id doc expect] :as m} observed]
  (when-not (gate? m)
    (reject! (str "Measurement " id " is " (:enforcement m) " and cannot produce a pass/fail "
                  "verdict. Only deterministic properties gate.")
             {:bench/defect :verdict-on-evidence :measurement id}))
  (when-not (satisfied? expect observed)
    {:measurement id
     :doc         doc
     :expected    (if (fn? expect) :predicate expect)
     :observed    observed
     :message     (str "GATE FAILED: " id " (" doc "). Expected " (expectation-str expect)
                       ", observed " (pr-str observed) ".")}))

(defn instability-failure
  "Answer nil when the gate `m` answered ONE value across the sample set,
  otherwise a failure naming the property and the values it gave.

  A deterministic property that is not deterministic cannot gate anything:
  either the workload has a hidden input, or the observable was misfiled
  and is really a distribution."
  [{:keys [id doc] :as m} observed-values]
  (when-not (gate? m)
    (reject! (str "Measurement " id " is " (:enforcement m) " and has no stability obligation — "
                  "spread is what a distribution is for.")
             {:bench/defect :stability-on-evidence :measurement id}))
  (let [vs (vec (distinct observed-values))]
    (when (< 1 (count vs))
      {:measurement id
       :doc         doc
       :expected    :one-value-per-run
       :observed    vs
       :message     (str "GATE FAILED: " id " (" doc ") is registered as a deterministic "
                         "property but answered " (count vs) " distinct values across the "
                         "sample set: " (pr-str vs) ". Either the workload has an undeclared "
                         "input, or this is a distribution filed as a property.")})))

;; ---------------------------------------------------------------------------
;; Distribution summary — evidence, and only evidence
;; ---------------------------------------------------------------------------

(defn- round4 [x]
  (/ (double (Math/round (* (double x) 10000.0))) 10000.0))

(defn- percentile
  "Nearest-rank percentile over an ascending vector."
  [sorted p]
  (let [n (count sorted)]
    (nth sorted (max 0 (min (dec n) (dec (int (Math/ceil (* p n)))))))))

(defn summarise
  "Summarise `samples` as the distribution D021 requires a published
  result to carry — never a median on its own.

  Answers nothing that can fail. This function has no expectation, no
  comparator and no verdict, because there is no numeric threshold in
  Freehand's performance policy to compare against."
  [samples]
  (when-not (and (seq samples) (every? number? samples))
    (reject! (str "A distribution needs at least one numeric sample — got " (pr-str samples) ".")
             {:bench/defect :non-numeric-samples}))
  (let [sorted (vec (sort samples))
        n      (count sorted)]
    {:n    n
     :min  (round4 (first sorted))
     :p50  (round4 (percentile sorted 0.50))
     :p95  (round4 (percentile sorted 0.95))
     :p99  (round4 (percentile sorted 0.99))
     :max  (round4 (peek sorted))
     :mean (round4 (/ (reduce + 0.0 sorted) n))}))
