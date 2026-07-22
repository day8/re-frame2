(ns re-frame.freehand.bench.falsifiability
  "The harness's own two-way falsifiability proof.

  D021's asymmetry — deterministic properties gate, timing and byte
  distributions never do — is only worth having if it is falsifiable in
  BOTH directions. A harness proven only in the first direction is a
  harness nobody has checked for the failure mode that actually happens:
  a slow run reds the build, someone adds a tolerance, and evidence has
  become a threshold that no one calibrated.

  So [[prove]] runs four workloads over the real interpreted walk and
  asserts four things:

  | arm | expectation | why |
  |---|---|---|
  | `honoured-gate`   | exit 0 | non-vacuity: a gate that holds does not red |
  | `violated-gate`   | exit 1, naming the property | a violated deterministic property reds CI |
  | `baseline-timing` | exit 0 | the reference distribution |
  | `moved-timing`    | exit 0 | a wall-clock reading many times larger still exits 0 |

  and one more, which is what stops the fourth row being a tautology:
  the moved arm's distribution must actually have MOVED, by a large
  multiple of the baseline's. A proof that a timing \"did not red\" is
  worth nothing unless the timing it did not red on was wildly different.

  The four arms are deliberately not registered with
  [[re-frame.freehand.bench/register!]]. They are a proof about the
  harness, not evidence about Freehand, and one of them fails on purpose.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [re-frame.freehand.bench :as bench]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.tree :as tree]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The fixture — the real interpreted walk, not a stand-in
;; ---------------------------------------------------------------------------

(defn- template
  "A finite list template of `n` rows. Plain markup: no declared view, no
  subscription, no host — the proof is about the harness, so its workload
  is the smallest real thing the substrate can already do."
  [n]
  (into [:ul.rows]
        (map (fn [i] [:li.row (str "row " i)]))
        (range n)))

(defn- node-count
  "The exact number of nodes in a rendered structural tree. Text children
  are strings, so `map?` is the whole discriminator."
  [t]
  (count (filter map? (tree-seq map? :children t))))

(def ^:private row-count
  "Rows in the proof template. A fixture parameter, not a constant of the
  language — it appears in every result record this namespace emits."
  200)

(defn- arm
  "One proof arm: render the template `repeats` times, observe the node
  count of the first tree and the self time of the whole burst.

  `expect` is the node count a correct run answers. `repeats` is the
  timing knob: it changes the duration by roughly its own factor and
  changes the node count not at all, which is exactly the pair of levers
  the two directions of the proof need.

  `sampling` is per-arm because the arms are deliberately unequal in
  cost. The cheap arms buy a warm engine with many discarded iterations;
  the expensive one is warm after a single discarded iteration and does
  not need forty."
  [{:keys [id doc repeats expect sampling]}]
  {:id           id
   :doc          doc
   :fixture      {:rows row-count :repeats repeats}
   :baseline     {:kind      :self-vs-end-to-end
                  :reference {:arm  :interpreted
                              :note "the walk's own self time inside the harness's iteration time"}}
   :sampling     sampling
   :measurements [{:id         :falsifiability/node-count
                   :doc        "the exact node count of the interpreted structural tree"
                   :observable :count
                   :expect     expect}
                  {:id         :falsifiability/render-ms
                   :doc        "self time for the fixture's burst of interpreted renders"
                   :observable :duration-ms}]
   :run          (fn [{:keys [rows repeats]}]
                   (let [form  (template rows)
                         t0    (m/now-ms)
                         trees (doall (repeatedly repeats #(tree/render form)))
                         t1    (m/now-ms)]
                     {:falsifiability/node-count (node-count (first trees))
                      :falsifiability/render-ms  (- t1 t0)}))})

;; The template renders one `:ul` node holding `rows` `:li` nodes; the row
;; text is a string child, not a node. The expectation is DECLARED from the
;; fixture parameter rather than read back from the observation — a gate
;; that expects whatever it saw is not a gate.
(def ^:private correct-node-count (inc row-count))

(def ^:private light
  "The cheap arms' policy. The generous warm-up is not politeness: with a
  cold engine the first renders cost several times a warm one, and a
  baseline distribution measured cold would understate how far the moved
  arm actually moved."
  {:warmup 10 :samples 5})

(def honoured-gate
  "Positive control. The deterministic property holds, so the run is
  green — without this arm, criterion 2 could be satisfied by a harness
  that reds on everything."
  (arm {:id       :falsifiability/honoured-gate
        :doc      "a deterministic property that holds"
        :repeats  4
        :sampling light
        :expect   correct-node-count}))

(def violated-gate
  "The gate direction. The same workload with an expectation a correct
  run cannot meet, so the deterministic property is violated and the run
  must red, naming the property."
  (arm {:id       :falsifiability/violated-gate
        :doc      "a deterministic property deliberately violated"
        :repeats  4
        :sampling light
        :expect   (+ correct-node-count 1000)}))

(def baseline-timing
  "The evidence direction's reference distribution."
  (arm {:id       :falsifiability/baseline-timing
        :doc      "the reference wall-clock distribution"
        :repeats  4
        :sampling light
        :expect   correct-node-count}))

(def moved-timing
  "The evidence direction. Identical work, done fifty times over, so the
  wall-clock distribution moves by a large multiple while every
  deterministic property answers exactly what it answered before."
  (arm {:id       :falsifiability/moved-timing
        :doc      "the same work with a wildly larger wall-clock reading"
        :repeats  200
        :sampling {:warmup 1 :samples 3}
        :expect   correct-node-count}))

;; ---------------------------------------------------------------------------
;; The proof
;; ---------------------------------------------------------------------------

(def ^:private minimum-move
  "How many times the baseline distribution the moved arm must reach for
  the evidence direction to have proven anything.

  This is a threshold on the PROOF, not on Freehand. It exists to stop
  the fourth row of the table being satisfied by a timing that did not
  move — the opposite of a performance budget, and the reason it is set
  far below the ~50× the fixture actually produces."
  10)

(defn- p50
  [result measurement]
  (get-in result [:distribution measurement :p50]))

(defn- check
  [ok? sentence]
  (when-not ok? sentence))

(defn prove
  "Run the four arms and answer the proof report.

      {:arms     {…}     ; each arm's outcome, for the transcript
       :move     <ratio> ; how far the moved arm's distribution actually moved
       :defects  […]     ; empty when the asymmetry holds both ways
       :proven?  true}

  Answers a report rather than throwing, so a test can assert on it, a
  CLI can print it, and a failure says which of the four expectations
  broke instead of which line threw."
  []
  (let [holds (bench/run [honoured-gate])
        reds  (bench/run [violated-gate])
        green (bench/run [baseline-timing])
        moved (bench/run [moved-timing])
        named (some #(= :falsifiability/node-count (:measurement %)) (:gate-failures reds))
        base  (p50 (first (:results green)) :falsifiability/render-ms)
        big   (p50 (first (:results moved)) :falsifiability/render-ms)
        ratio (when (and (number? base) (pos? base) (number? big)) (/ big base))
        defects
        (into []
              (keep identity)
              [(check (zero? (:exit-code holds))
                      (str "A deterministic property that HOLDS red the run (exit "
                           (:exit-code holds) "): " (pr-str (:gate-failures holds))
                           ". The gate direction is vacuous if the harness reds on everything."))

               (check (= 1 (:exit-code reds))
                      (str "A deterministic property registered as a GATE was violated and the "
                           "run exited " (:exit-code reds)
                           ". A gate that does not red CI is not a gate."))

               (check named
                      (str "The violated run failed without naming the property "
                           ":falsifiability/node-count — got "
                           (pr-str (mapv :measurement (:gate-failures reds)))
                           ". A gate failure that cannot say what broke is not evidence."))

               (check (zero? (:exit-code green))
                      (str "The reference timing arm exited " (:exit-code green)
                           ": " (pr-str (:gate-failures green))))

               (check (zero? (:exit-code moved))
                      (str "A wall-clock measurement registered as EVIDENCE moved and the run "
                           "exited " (:exit-code moved)
                           ". D021 sets no numeric threshold on timing: a harness that fails on "
                           "a slow run has silently become the threshold it forbids."))

               (check (empty? (:gate-failures moved))
                      (str "The moved-timing arm reported gate failures: "
                           (pr-str (:gate-failures moved))
                           ". Only deterministic properties may produce a verdict."))

               (check (and ratio (>= ratio minimum-move))
                      (str "The moved-timing arm's distribution did not actually move — p50 "
                           (pr-str big) "ms against a baseline of " (pr-str base) "ms ("
                           (pr-str ratio) "×, needed " minimum-move "×). Proving that an "
                           "unchanged timing does not red the build proves nothing."))])]
    {:arms    {:honoured-gate   {:exit-code (:exit-code holds)}
               :violated-gate   {:exit-code     (:exit-code reds)
                                 :gate-failures (mapv :message (:gate-failures reds))}
               :baseline-timing {:exit-code (:exit-code green)
                                 :p50-ms    base}
               :moved-timing    {:exit-code     (:exit-code moved)
                                 :p50-ms        big
                                 :gate-failures (mapv :message (:gate-failures moved))}}
     :move    ratio
     :defects defects
     :proven? (empty? defects)}))
