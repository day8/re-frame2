(ns re-frame.freehand.bench.b7-mount-frame
  "B7 — what does the `:frame` opt cost inside B6's timed mount window?

  `rf2-prjh0` asks whether B6's published mount row overstates the
  substrate. Its premise is that B6's Freehand arm passes no `:frame`, so
  `v/mount` creates a FRESH FRAME per sample inside the `flushSync` the
  harness is timing, while the floor arm — a bare `createRoot` — never
  pays it. If that were true a measurable slice of interpreted W1's
  2.987× would be frame construction rather than view substrate, and a
  release-gate row would be too harsh.

  **The premise does not survive reading `root.cljs`.** `plan-for`
  answers `nil` when `opts` carries no `:frame`, and `preflight!` opens
  `(when (some? plan) …)`. A frameless mount runs no plan, creates no
  frame, and binds none: `root-element` wraps the root form in the frame
  provider only `(if (some? frame-id) …)`. So the published arm is not
  paying for a frame — it is paying for LESS than a mount that names one.

  Which turns the bead's question round, and makes it worth measuring
  rather than merely answering. Four arms, each one step from the last, so
  the direction and the size are both readings rather than deductions:

  | arm | `:frame` opt | what it prices |
  |---|---|---|
  | `floor` | — | React alone; the in-run calibrator |
  | `fh-no-frame` | absent | **exactly the published arm** |
  | `fh-shared-frame` | `:b7mf/shared` (SCOPE) | the studio fixture's shape: ledger read + context provider, no construction |
  | `fh-frame-per-sample` | `{:id …unique…}` (ENSURE) | the counterfactual — what per-sample frame construction WOULD have cost |
  | `reagent` | — | the substrate the row is quoted against |

  `fh-shared-frame − fh-no-frame` is the correction the bead asks for and
  is expected to be NEGATIVE — the published arm is the cheaper shape, so
  moving to a shared frame cannot flatter it.
  `fh-frame-per-sample − fh-no-frame` is the bead's feared term, priced so
  the report can say what the mistake would have been worth had it been
  real.

  Everything else is B6's instrument unchanged — `b6-harness`'s timed
  `flushSync`, sample-level interleaving with the order rotating on the
  sample index, and every figure a ratio to the floor measured in that
  same round. A second copy of the method would be a second place for it
  to drift.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [reagent.dom.client :as rdc]
            [re-frame.freehand :as v]
            [re-frame.freehand.bench.b6-floor :as floor]
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.b6-reagent :as rg]
            [re-frame.freehand.bench.b6-rows :as rows]
            [re-frame.freehand.bench.b6-witnesses :as fh]
            [re-frame.freehand.bench.provenance :as prov]))

(def shared-frame-id :b7mf/shared)

(defonce ^:private frame-owner
  (atom nil))

(defn ensure-shared-frame!
  "Stand a tiny root up over [[shared-frame-id]] and LEAVE IT MOUNTED for
  the run, so the shared-frame arm can SCOPE a frame that already exists
  rather than ensure one. Its view is `w2` at `n = 0` — one `div`, so the
  owner costs a boundary and a frame and contributes nothing to any
  sample."
  []
  (when (nil? @frame-owner)
    (let [c (js/document.createElement "div")]
      (.appendChild js/document.body c)
      (reset! frame-owner
              (v/mount [fh/w2 {:n 0}] c
                       {:disambiguator :b7mf/owner
                        :frame         {:id shared-frame-id}}))))
  @frame-owner)

;; ---------------------------------------------------------------------------
;; Arms
;; ---------------------------------------------------------------------------

(defn- floor-arm [element-of]
  {:id      :floor
   :mount   (fn [container props _n]
              (let [r (react-dom-client/createRoot container)]
                (.render r (element-of props))
                r))
   :unmount (fn [r] (.unmount r))})

(defn- freehand-arm
  "`frame-opt-of` is handed the sample's mount sequence number and answers
  the `:frame` opt — `nil` for the published shape, a keyword for the
  shared scope, a fresh map for the per-sample ensure. It is the ONLY
  difference between the three Freehand arms, which is what makes the
  deltas readings of the frame opt and of nothing else."
  [id view frame-opt-of]
  {:id      id
   :mount   (fn [container props n]
              (v/mount [view props] container
                       (cond-> {:disambiguator (keyword "b7mf" (str (name id) "-" n))}
                         (some? (frame-opt-of n)) (assoc :frame (frame-opt-of n)))))
   :unmount (fn [mounted] (v/unmount! mounted))})

(defn- reagent-arm [form-of]
  {:id      :reagent
   :mount   (fn [container props _n]
              (let [r (rdc/create-root container)]
                (rdc/render r (form-of props))
                r))
   :unmount (fn [r] (rdc/unmount r))})

(defn- arms-for [fh-view floor-of reagent-of]
  [(floor-arm floor-of)
   (freehand-arm :fh-no-frame        fh-view (fn [_] nil))
   (freehand-arm :fh-shared-frame    fh-view (fn [_] shared-frame-id))
   (freehand-arm :fh-frame-per-sample fh-view (fn [n] {:id (keyword "b7mf" (str "f" n))}))
   (reagent-arm reagent-of)])

(def witnesses
  "W1 and W2 — the two mount witnesses whose Freehand arms the bead
  names. W3 is omitted: its absolute times are 0.3–1.0 ms against a
  0.1 ms clock quantum, so it cannot resolve a term this small and
  quoting it would only add a number nobody may read."
  [{:id       :W1
    :doc      "a large template — 300 rows under one boundary each, 1,203 elements"
    :props    {:rows rows/w1-rows}
    :elements (rows/w1-elements rows/w1-rows)
    :arms     (arms-for fh/w1
                        (fn [{:keys [rows]}] (floor/w1 rows))
                        (fn [{:keys [rows]}] [rg/w1 rows]))}
   {:id       :W2
    :doc      "300 sub-free leaf boundaries, 301 elements"
    :props    {:n rows/w2-n}
    :elements (rows/w2-elements rows/w2-n)
    :arms     (arms-for fh/w2
                        (fn [{:keys [n]}] (floor/w2 n))
                        (fn [{:keys [n]}] [rg/w2 n]))}])

;; ---------------------------------------------------------------------------
;; The measurement
;; ---------------------------------------------------------------------------

(defn parity-problems
  "Mount every arm at once and answer what disagrees — empty when all five
  build one page with the written element count. Run before any clock,
  because three Freehand arms differing only in a `:frame` opt MUST build
  identical DOM and an arm that did not would be measuring a different
  page."
  [{:keys [id arms props elements]}]
  (let [{:keys [mounts agree? counts disagree]} (h/parity arms props)]
    (try
      (cond-> []
        (not agree?)
        (conj {:witness id :problem :canonical-dom-disagreement :arms disagree})

        (not (every? #(= elements %) (vals counts)))
        (conj {:witness id :problem :element-count :expected elements :got counts}))
      (finally (doseq [m mounts] (h/release! m))))))

(defn measure!
  "Measure one witness across `rounds` interleaved rounds and answer
  `{:id … :summary … :record …}`."
  [{:keys [id doc props arms elements]} rounds sampling]
  (let [raw     (mapv (fn [_] (h/round! arms props sampling)) (range rounds))
        norm    (mapv #(h/normalise % :floor) raw)
        summary (h/across-rounds (mapv :ratio norm))
        record
        {:benchmark      (keyword "B7" (str "mount-frame-" (name id)))
         :doc            doc
         :bead           :rf2-prjh0
         :question       (str "does B6's published mount row overstate the substrate because "
                              "v/mount creates a frame per sample inside the timed window?")
         :revision       (prov/detect-revision)
         :build          (prov/detect-build)
         :host           (prov/detect-host)
         :fixture        {:witness  id
                          :props    props
                          :elements elements
                          :arms     (mapv :id arms)
                          :arm-notes
                          {:fh-no-frame         "EXACTLY the published B6 arm: no :frame opt at all"
                           :fh-shared-frame     "SCOPE a frame stood up before the run — the studio fixture's shape"
                           :fh-frame-per-sample "ENSURE a FRESH frame id per sample — the bead's feared counterfactual"}
                          :measurement-method
                          (str "b6-harness unchanged: wall time across react-dom/flushSync "
                               "around each arm's own mount door into a container attached "
                               "before the window; arms interleaved at the SAMPLE level with "
                               "the order rotating on the sample index; " rounds " rounds of "
                               (:warmup sampling) " warmup + " (:samples sampling)
                               " samples per arm per round; every figure a ratio to the FLOOR "
                               "measured in that same round")}
         :sampling       sampling
         :baseline       {:kind      :within-freehand-ablation
                          :reference {:arm  :fh-no-frame
                                      :note "the published B6 arm; the other two Freehand arms differ from it in the :frame opt and nothing else"}}
         :per-round      {:p50 (mapv :p50 norm) :ratio (mapv :ratio norm)}
         :ratio-to-floor summary
         :status         :evidence}]
    (h/publish! (str "mount-frame / " (name id)) record)
    {:id id :summary summary :norm norm :record record}))
