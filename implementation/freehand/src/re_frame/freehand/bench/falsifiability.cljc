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
  the moved arm must actually have DONE more work than the baseline. A
  proof that a timing \"did not red\" is worth nothing if the timing had
  no reason to move in the first place.

  That last claim is read off a DETERMINISTIC signal — the nodes each arm
  actually rendered, which the repeat knob scales by exactly its own
  factor — and never off the wall clock. The distinction is the whole
  ruling applied to the ruling's own proof: a harness that mechanises
  \"no numeric wall-clock thresholds\" must not contain one, and a proof
  that gated on a measured ratio would flake for precisely the reason
  D021 bans such gates. It did: under concurrent load a 50x workload was
  once observed returning an 8.79x wall-clock ratio. The workload ratio
  is 50x always. The wall-clock ratio is still reported, because it is
  what a reader wants to see — and it decides nothing.

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

;; The template renders one `:ul` node holding `rows` `:li` nodes; the row
;; text is a string child, not a node. The expectation is DECLARED from the
;; fixture parameter rather than read back from the observation — a gate
;; that expects whatever it saw is not a gate.
(def ^:private correct-node-count (inc row-count))

(defn- arm
  "One proof arm: render the template `repeats` times, observe the node
  count of the first tree, the total nodes rendered across the burst, and
  the self time of the whole burst.

  `expect` is the node count a correct run answers. `repeats` is the
  knob, and it moves the three observations differently — which is
  exactly the set of levers the two directions of the proof need:

    - the first tree's node count, NOT AT ALL. The deterministic property
      is untouched by the knob, so the moved arm is a test of the timing
      rather than of the property.
    - the total nodes rendered, by EXACTLY its own factor. That is the
      fixture's workload size: deterministic, reproducible on any host
      under any load, and the signal the proof reads to know the moved
      arm really did more work.
    - the duration, by ROUGHLY its own factor. Roughly is the whole
      point: a wall clock under load is why D021 publishes this as
      evidence instead of gating it, and why the proof does not read it.

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
                  {:id         :falsifiability/render-nodes
                   :doc        "the exact nodes rendered across the whole burst — the fixture's workload size"
                   :observable :count
                   :expect     (* repeats correct-node-count)}
                  {:id         :falsifiability/render-ms
                   :doc        "self time for the fixture's burst of interpreted renders"
                   :observable :duration-ms}]
   :run          (fn [{:keys [rows repeats]}]
                   (let [form  (template rows)
                         t0    (m/now-ms)
                         trees (doall (repeatedly repeats #(tree/render form)))
                         t1    (m/now-ms)]
                     {:falsifiability/node-count   (node-count (first trees))
                      :falsifiability/render-nodes (reduce + (map node-count trees))
                      :falsifiability/render-ms    (- t1 t0)}))})

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
  "How many times the baseline's WORK the moved arm must do for the
  evidence direction to have proven anything.

  This is a threshold on the PROOF, not on Freehand, and it is read off a
  DETERMINISTIC signal — the nodes each arm actually rendered — never off
  the wall clock. It exists to stop the fourth row of the table being
  satisfied by a moved arm whose timing had no reason to move.

  A threshold on the wall clock here would be the very shape D021 forbids
  everywhere else, and it flaked for exactly the reason D021 gives:
  under concurrent load a 50x workload was observed returning an 8.79x
  wall-clock ratio. The workload ratio is 50x on every host under any
  load, so this bound sits far below the multiple actually produced and
  has nothing to flake against."
  10)

(defn- p50
  [result measurement]
  (get-in result [:distribution measurement :p50]))

(defn- observed
  "The value a GATE measurement answered — read from `:properties`, which
  is where a deterministic property lands, and where a timing never does."
  [result measurement]
  (get-in result [:properties measurement :observed]))

(defn- check
  [ok? sentence]
  (when-not ok? sentence))

(defn prove
  "Run the four arms and answer the proof report.

      {:arms         {…}     ; each arm's outcome, for the transcript
       :move         <ratio> ; times the baseline's WORK the moved arm did — deterministic
       :timing-ratio <ratio> ; times its wall clock — published evidence, gates nothing
       :defects      […]     ; empty when the asymmetry holds both ways
       :proven?      true}

  `:move` is the proof's own quantity and is read off the arms' rendered
  node counts, so it is 50 on every host under any load. `:timing-ratio`
  is the same comparison over the wall clock: reported because it is the
  thing a reader wants to see, and load-bearing on nothing, because a
  proof that read it would be the threshold this harness forbids.

  Answers a report rather than throwing, so a test can assert on it, a
  CLI can print it, and a failure says which of the four expectations
  broke instead of which line threw.

  `opts` are [[re-frame.freehand.bench/run-workload]]'s provenance
  overrides. A host that cannot detect its own revision — a browser, or a
  ClojureScript test process — supplies one here; a JVM checkout needs
  nothing."
  ([] (prove nil))
  ([opts]
   (let [holds   (bench/run [honoured-gate] opts)
         reds    (bench/run [violated-gate] opts)
         green   (bench/run [baseline-timing] opts)
         moved   (bench/run [moved-timing] opts)
         named   (some #(= :falsifiability/node-count (:measurement %)) (:gate-failures reds))
         base-r  (first (:results green))
         moved-r (first (:results moved))

         ;; The proof's own quantity: how much more WORK the moved arm did,
         ;; counted in nodes it actually rendered. Deterministic on every
         ;; host under any load.
         base-work  (observed base-r :falsifiability/render-nodes)
         moved-work (observed moved-r :falsifiability/render-nodes)
         work-ratio (when (and (number? base-work) (pos? base-work) (number? moved-work))
                      (/ moved-work base-work))

         ;; The same comparison over the wall clock. Reported, never read.
         base       (p50 base-r :falsifiability/render-ms)
         big        (p50 moved-r :falsifiability/render-ms)
         ratio      (when (and (number? base) (pos? base) (number? big)) (/ big base))

         ;; The timing is EVIDENCE: a published distribution and not a
         ;; judged property. Asserted structurally rather than inferred
         ;; from an exit code, so the arm that proves "a moved timing does
         ;; not red" also proves the thing that moved was never a gate.
         timing-is-evidence?
         (and (contains? (:distribution moved-r) :falsifiability/render-ms)
              (not (contains? (:properties moved-r) :falsifiability/render-ms)))

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

                (check timing-is-evidence?
                       (str "The moved arm's :falsifiability/render-ms did not route as "
                            "EVIDENCE: a timing must be a published distribution and never a "
                            "judged property. Wired as a gate, it turns runner noise into a red "
                            "build — the failure mode this whole proof exists to detect."))

                (check (and work-ratio (>= work-ratio minimum-move))
                       (str "The moved-timing arm did no more work than the baseline — it "
                            "rendered " (pr-str moved-work) " nodes against the baseline's "
                            (pr-str base-work) " (" (pr-str work-ratio) "x, needed "
                            minimum-move "x). Proving that a timing with no reason to move did "
                            "not red the build proves nothing."))])]
     {:arms    {:honoured-gate   {:exit-code (:exit-code holds)}
                :violated-gate   {:exit-code     (:exit-code reds)
                                  :gate-failures (mapv :message (:gate-failures reds))}
                :baseline-timing {:exit-code (:exit-code green)
                                  :p50-ms    base
                                  :nodes     base-work}
                :moved-timing    {:exit-code     (:exit-code moved)
                                  :p50-ms        big
                                  :nodes         moved-work
                                  :gate-failures (mapv :message (:gate-failures moved))}}
      :move         work-ratio
      :timing-ratio ratio
      :defects      defects
      :proven?      (empty? defects)})))
