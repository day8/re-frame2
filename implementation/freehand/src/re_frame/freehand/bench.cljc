(ns re-frame.freehand.bench
  "The B-spine: one small repeatable harness that runs a named workload
  and emits a result record.

  This is the scaffolding B1–B5 hang off. It measures nothing on its own
  — each of the five workloads arrives with the slice that can actually
  run it, registers itself with [[register!]], and is measured here under
  one sampling policy, one provenance record, and one rule about what a
  measurement is allowed to decide.

  ## The rule

  D021 splits Freehand's performance policy in two and this harness is
  where that split is mechanical rather than cultural:

    - **deterministic properties are hard gates.** A violated property
      fails the run, names itself, and exits non-zero.
    - **wall-clock and byte distributions are published evidence.** They
      have no threshold, no comparator and no verdict. They cannot fail
      the run however far they move.

  Both directions matter, and the second is the one that erodes quietly.
  A harness that reds on a slow run has silently become a threshold —
  which D021 forbids, because engine, hardware, timer granularity and
  measurement-boundary drift would then make an otherwise-correct release
  flap red. So [[run]]'s exit code is derived from ONE source: gate
  verdicts over `:property`-class measurements. A distribution has no
  path to it. See [[re-frame.freehand.bench.measure]] for why a fixture
  cannot file a timing as a gate in the first place, and
  [[re-frame.freehand.bench.falsifiability]] for the proof that the
  asymmetry holds in both directions.

  ## A workload

      {:id           :B1/finite-template
       :doc          \"one boundary renders a finite 10³-node template\"
       :fixture      {:nodes 1000}
       :baseline     {:kind :interpreted-vs-compiled :reference {:arm :interpreted}}
       :sampling     {:warmup 5 :samples 40}
       :measurements [{:id :B1/node-count :doc \"…\" :observable :count :expect 1000}
                      {:id :B1/self-time-ms :doc \"…\" :observable :duration-ms}]
       :run          (fn [fixture] {:B1/node-count … :B1/self-time-ms …})}

  `:run` is called once per iteration and answers its observations. It
  owns its own measurement boundary — the harness never decides where a
  workload's self time starts, because \"substrate self time versus
  end-to-end host time\" is one of D021's four baselines and a harness
  that imposed one boundary would have picked the answer. The harness
  does add its own end-to-end reading, [[iteration-ms]], to every
  workload: that is the outer bound the workload's self time sits inside,
  and it is why every result record has a distribution to publish.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.bench.provenance :as prov]))

#?(:clj (set! *warn-on-reflection* true))

(defn- reject!
  [sentence data]
  (throw (ex-info sentence data)))

;; ---------------------------------------------------------------------------
;; The harness's own reading
;; ---------------------------------------------------------------------------

(def iteration-ms
  "The harness's end-to-end wall time for one iteration, added to every
  workload.

  Evidence, like every other duration. Its job is to bound the
  workload's own self-time readings from the outside: when self time
  falls and this does not, the cost moved rather than went away — which
  is D021's fourth baseline stated as a number instead of a warning."
  (m/measurement
    {:id         :bench/iteration-ms
     :doc        "the harness's end-to-end wall time for one workload iteration"
     :observable :duration-ms}))

;; ---------------------------------------------------------------------------
;; Workloads
;; ---------------------------------------------------------------------------

(def ^:private workload-provenance-paths
  "The provenance fields a WORKLOAD is responsible for — the rest are
  detected at run time. Checked at declaration so a fixture that could
  never emit a record is rejected before it burns a sampling run."
  [[:benchmark] [:fixture] [:sampling :warmup] [:sampling :samples]
   [:baseline :kind] [:baseline :reference]])

(defn workload
  "Answer the validated, normalised `spec`, or throw.

  Normalisation validates every measurement (which is where the
  gate/evidence split is enforced) and appends [[iteration-ms]]. It is
  idempotent, so registering and then running a workload does not
  measure the harness twice."
  [{:keys [id doc measurements run fixture sampling baseline] :as spec}]
  (when-not (keyword? id)
    (reject! (str "A benchmark workload needs a keyword `:id` — got " (pr-str id) ".")
             {:bench/defect :workload-id :spec spec}))
  (when-not (and (string? doc) (seq doc))
    (reject! (str "Workload " id " needs a one-line `:doc` saying what it measures.")
             {:bench/defect :workload-doc :workload id}))
  (when-not (ifn? run)
    (reject! (str "Workload " id " needs a `:run` function of the fixture, answering a map of "
                  "observations keyed by measurement id.")
             {:bench/defect :workload-run :workload id}))
  (when-not (seq measurements)
    (reject! (str "Workload " id " declares no `:measurements`. A workload that observes nothing "
                  "publishes nothing.")
             {:bench/defect :workload-measurements :workload id}))
  (let [ds (prov/defects {:benchmark id :fixture fixture :sampling sampling :baseline baseline}
                         workload-provenance-paths)]
    (when (seq ds)
      (throw (ex-info (prov/defects-message ds)
                      {:bench/defect :incomplete-workload :workload id :defects ds}))))
  (let [declared (into [] (remove #(= (:id iteration-ms) (:id %))) measurements)
        norm     (mapv m/measurement declared)
        ids      (mapv :id norm)]
    (when-not (apply distinct? (:id iteration-ms) ids)
      (reject! (str "Workload " id " declares a measurement id twice: " (pr-str ids) ". One id "
                    "names one observation.")
               {:bench/defect :duplicate-measurement-id :workload id}))
    (assoc spec :measurements (conj norm iteration-ms))))

(defonce ^:private registry (atom {}))

(defn register!
  "Register `w` as a workload of the standing evidence suite, answering
  its id. Re-registering an id replaces it — a fixture is edited in
  place, not accumulated."
  [w]
  (let [{:keys [id] :as norm} (workload w)]
    (swap! registry assoc id norm)
    id))

(defn registered
  "The registered evidence workloads, by id.

  Empty until the first B-workload lands: B1–B5 are separate slices and
  each arrives with the implementation that can run it."
  []
  @registry)

;; ---------------------------------------------------------------------------
;; Running one workload
;; ---------------------------------------------------------------------------

(defn- observation
  [obs mid workload-id]
  (let [v (get obs mid ::missing)]
    (if (= ::missing v)
      (reject! (str "Workload " workload-id " declares measurement " mid " but its `:run` "
                    "answered no observation for it. Every declared measurement is observed "
                    "every iteration, or the distribution is over a different thing each time.")
               {:bench/defect :missing-observation :workload workload-id :measurement mid})
      v)))

(defn- sample-observations
  "Run `warmup` discarded iterations then `samples` measured ones,
  answering the measured observation maps in order."
  [{:keys [id run sampling fixture]}]
  (let [{:keys [warmup samples]} sampling]
    (dotimes [_ warmup] (run fixture))
    (into []
          (for [_ (range samples)]
            (let [t0  (m/now-ms)
                  obs (run fixture)
                  t1  (m/now-ms)]
              (when-not (map? obs)
                (reject! (str "Workload " id "'s `:run` must answer a map of observations keyed "
                              "by measurement id — got " (pr-str obs) ".")
                         {:bench/defect :non-map-observations :workload id}))
              (assoc obs (:id iteration-ms) (- t1 t0)))))))

(defn- distribution-entry
  "Summarise one evidence measurement. Answers a summary and nothing
  else — there is no verdict in this branch, by construction."
  [wid observations {:keys [id doc observable]}]
  [id (assoc (m/summarise (mapv #(observation % id wid) observations))
             :doc doc
             :observable observable)])

(defn- property-entry
  "Judge one gate measurement. Answers `[id entry failure-or-nil]`; the
  failure is the ONLY thing in this harness that can turn a run red."
  [wid observations measurement]
  (let [{:keys [id doc expect]} measurement
        values  (mapv #(observation % id wid) observations)
        failure (or (m/instability-failure measurement values)
                    (m/gate-failure measurement (first values)))]
    [id
     {:doc      doc
      :expected (if (fn? expect) :predicate expect)
      :observed (first values)
      :pass?    (nil? failure)}
     failure]))

(defn run-workload
  "Run `w` and answer its validated result record.

  `opts` may override the detected `:revision`, `:host` and `:build` —
  a build pipeline that knows the artefact it shipped should say so
  rather than let the harness guess from the process it happens to be
  running in.

  The record carries `:gate-failures`, empty when every deterministic
  property held. `:status` is always `:evidence`: the RECORD is
  evidence, and the pass/fail lives in `:properties`, sourced only from
  deterministic gates."
  ([w] (run-workload w nil))
  ([w opts]
   (let [{:keys [id fixture sampling baseline measurements] :as norm} (workload w)
         observations (sample-observations norm)
         evidence     (filterv m/evidence? measurements)
         gates        (filterv m/gate? measurements)
         judged       (mapv #(property-entry id observations %) gates)]
     (prov/result
       {:benchmark     id
        :doc           (:doc norm)
        :revision      (or (:revision opts) (prov/detect-revision))
        :fixture       fixture
        :build         (or (:build opts) (prov/detect-build))
        :host          (or (:host opts) (prov/detect-host))
        :sampling      sampling
        :baseline      baseline
        :distribution  (into {} (map #(distribution-entry id observations %)) evidence)
        :properties    (into {} (map (fn [[k v _]] [k v])) judged)
        :gate-failures (into [] (keep (fn [[_ _ f]] f)) judged)
        :status        :evidence}))))

;; ---------------------------------------------------------------------------
;; Running a suite
;; ---------------------------------------------------------------------------

(defn run
  "Run `workloads` (default: the registered evidence suite) and answer
  the run outcome.

      {:results       [<result record> …]
       :gate-failures [<failure> …]
       :exit-code     0 | 1}

  `:exit-code` is 1 when and only when a DETERMINISTIC property was
  violated. That is the single line this whole namespace exists to make
  true: `:gate-failures` is concatenated from the records' own gate
  verdicts, each of which came from
  [[re-frame.freehand.bench.measure/gate-failure]], which refuses any
  measurement that is not a gate. A wall-clock or byte distribution has
  no route into this number, however far it moves."
  ([] (run (vals (registered)) nil))
  ([workloads] (run workloads nil))
  ([workloads opts]
   (let [results  (mapv #(run-workload % opts) workloads)
         failures (into [] (mapcat :gate-failures) results)]
     {:results       results
      :gate-failures failures
      :exit-code     (if (seq failures) 1 0)})))
