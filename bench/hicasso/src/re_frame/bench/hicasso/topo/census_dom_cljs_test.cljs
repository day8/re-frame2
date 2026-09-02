(ns re-frame.bench.hicasso.topo.census-dom-cljs-test
  "**THE TOURNAMENT'S DETERMINISTIC HALF** — the work census, and the
  eligibility control that decides whether any clock figure guarded by it
  may publish (rf2-hic-036).

  ## Why this half needed no quiet box

  [The budgets page](../../../../../../../docs/design/hicasso/product/budgets.md)
  sorts every performance row into two families with opposite operational
  rules, and the sentence that matters here is its own: a counter *\"reads
  the same on a loaded box\"*. Boundary body runs and rows of markup are
  integers on monotone counters, so contention cannot move them. This
  file is therefore an ordinary blocking gate, runs in CI on every PR,
  and is repeatable by anyone — which is exactly what a one-shot clock
  session on one machine is not.

  It is **not** a substitute for the clock, and the tournament page
  pre-registers that refusal before any measurement was taken: a cell
  whose clock control refuses is handed to `rf2-hic-080` phase 2 as
  *unaddressed*, never as a work census wearing a clock's clothes.

  ## What each row establishes

  1. **The arm is the topology it claims** — boundaries, read edges and
     elements against arithmetic computed from `B`, never against a
     previous run's numbers.
  2. **The read sets do not oscillate**, which is a GATE on the coarse
     arm rather than a tiebreak.
  3. **The work each operation causes**, per arm, at each row count.
  4. **The census bites** — [[sabotage-list]] is a real arm with one real
     defect, mounted for real, and the eligibility check must reject it.

  ## The sabotage control is an ARM, not a patched file

  A planted source fault proves the source changed; it does not prove the
  runtime ever observed the plant, and a watcher that served a pre-plant
  compile would return green from a sabotage run and be read as *the
  control does not bite*. So the defect here is compiled, mounted and
  exercised on the same door as every other arm: [[sabotage-list]] is
  `fine` with one extra read per row — the shared view-model — so a
  ONE-ROW write invalidates all `B` rows. `the-census-rejects-a-broken-arm`
  asserts the census reds on it. There is nothing to restore afterwards
  and nothing to hash.

  ## The sparse/edit target is row 0, in every arm

  Stated because it is a design choice and not an accident: row 0 is the
  only index guaranteed to be in the windowed arm's DOM at every row
  count, so it is the only target on which all four arms are comparable
  at all. A middle-index target would measure the windowed arm doing
  nothing, which is true and useless.

  Runtime: `-dom-cljs-test`. Under `:node-test` every claim degrades to a
  stated skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt :refer [sub]]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.topo.arms :as arms]
            [re-frame.bench.hicasso.topo.model :as m]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::topo)

(def ^:private target
  "The row `sparse` and `edit` move. See the namespace docstring."
  0)

(defn- skip! [why]
  (is true (str "the topology census needs a real React DOM — " why)))

;; ---------------------------------------------------------------------------
;; The sabotage arm — `fine`, with one real defect
;; ---------------------------------------------------------------------------

(defview sabotage-row
  "A `fine` row that also reads the COARSE arm's view-model. Its markup,
  its DOM and its key scheme are `fine`'s; its read set is not, and the
  extra read moves whenever any row moves."
  [{:keys [i]}]
  (swap! arms/counters update :row inc)
  (let [_shared (sub [:topo/view-model])]
    (arms/row-markup (assoc (sub [:topo/row i]) :draft (sub [:topo/draft i])))))

(defview sabotage-list
  [_]
  (swap! arms/counters update :list inc)
  (into [:ul.topo-list {:data-testid "list"}]
        (map (fn [i] [sabotage-row {:key i :i i}]))
        (sub [:topo/order])))

;; ---------------------------------------------------------------------------
;; The door
;; ---------------------------------------------------------------------------

(defn- fresh! [b]
  (lane/leave-act-environment!)
  (m/make-frame! frame-id b)
  (m/reseed! frame-id b)
  (arms/reset-counters!)
  (rt/reset-body-runs!)
  frame-id)

(defn- mount! [view]
  (mount/root! (mount/fresh-container!) frame-id [view {}]))

(defn- restore!
  "Return the frame to its seed and let the commit land, WITHOUT charging
  it to any operation. Counters are zeroed after the settle, never
  before."
  [b]
  (rt/dispatch! frame-id [:topo/seed b])
  (mount/settle!)
  (arms/reset-counters!)
  (rt/reset-body-runs!)
  nil)

(defn- operate!
  "Dispatch one operation and settle. Answers the work it caused, on both
  instruments."
  [op b]
  (case op
    :sparse  (rt/dispatch! frame-id [:topo/bump target])
    :bulk    (rt/dispatch! frame-id [:topo/bump-all])
    :reorder (rt/dispatch! frame-id [:topo/rotate])
    :edit    (rt/dispatch! frame-id [:topo/edit target (str "k" b)])
    :noop    (rt/dispatch! frame-id [:topo/noop-write]))
  (mount/settle!)
  (let [{:keys [list row chunk markup]} (arms/runs)]
    {:bodies     (+ list row chunk)
     :list       list
     :row-bodies (+ row chunk)
     :markup     markup
     :runtime    (rt/body-runs)}))

;; ---------------------------------------------------------------------------
;; The record every row appends to
;; ---------------------------------------------------------------------------

(defonce ^:private !record (atom []))

(defn- record! [arm b op work]
  (swap! !record conj (merge {:arm arm :b b :op op} work))
  work)

;; ---------------------------------------------------------------------------
;; 1 — every arm is the topology it claims (the eligibility control)
;; ---------------------------------------------------------------------------

(defn- structure-of
  "What the mounted page actually is."
  [handle]
  (let [{:keys [boundaries edges]} (rt/stats)]
    {:boundaries    boundaries
     :edges         edges
     :elements      (lane/element-count (:container handle))
     ;; `.-length`, not `count`: a `NodeList` implements no CLJS protocol,
     ;; so `count` throws `ICounted` rather than answering — which errored
     ;; all twelve structural cells on this file's first run and is worth a
     ;; comment because the same call reads perfectly in a REPL over a seq.
     :rendered-rows (.-length (.querySelectorAll (:container handle) "li.topo-row"))}))

(deftest every-arm-is-the-topology-it-claims
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (doseq [b   m/row-counts
            arm arms/arm-ids]
      (testing (str arm " at " b " rows")
        (fresh! b)
        (let [handle (mount! (arms/view-of arm))]
          (try
            (is (= (arms/expected arm b) (structure-of handle))
                (str arm " at B=" b " must hold exactly the boundaries, read edges,
                     elements and rendered rows its arithmetic predicts — a number
                     accepted from the DOM would re-baseline the tournament instead
                     of gating it"))
            (finally (mount/release! handle))))))))

(deftest the-row-family-read-set-cannot-oscillate
  (testing "set stability is a GATE on the coarse arm, not a tiebreak — so it
           is asserted rather than argued: the same page after every one of the
           four operations holds the same read-edge count it mounted with"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (doseq [arm arms/arm-ids]
        (let [b 100]
          (fresh! b)
          (let [handle (mount! (arms/view-of arm))
                mounted (:edges (rt/stats))]
            (try
              (doseq [op [:sparse :bulk :reorder :edit]]
                (restore! b)
                (operate! op b)
                (is (= mounted (:edges (rt/stats)))
                    (str arm "'s read set moved under " op " — the coarse arm's
                         precondition is broken and no arm's figure may publish")))
              (finally (mount/release! handle)))))))))

;; ---------------------------------------------------------------------------
;; 2 — the census itself: work per arm, per operation, per row count
;; ---------------------------------------------------------------------------

(defn- run-census!
  "Mount each arm once per row count and run all four operations on the
  standing mount, restoring the seed between them. One mount per
  `[arm b]` rather than one per cell, because a re-mount would put a
  fresh page under every operation and the tournament is about what a
  commit costs on a page that is already there."
  []
  (reset! !record [])
  (doseq [b   m/row-counts
          arm arms/arm-ids]
    (fresh! b)
    (let [handle (mount! (arms/view-of arm))]
      (try
        (doseq [op [:sparse :bulk :reorder :edit]]
          (restore! b)
          (record! arm b op (operate! op b)))
        (finally (mount/release! handle)))))
  @!record)

(defn- ensure-census!
  "Run the census if it has not run in this process.

  cljs.test does not promise to run a namespace's vars in definition
  order, so a findings row that assumed the census had already run would
  read `nil` from an empty record and fail — or, worse, pass vacuously on
  a `nil` comparison. Every consumer calls this first."
  []
  (when (empty? @!record) (run-census!))
  @!record)

(deftest the-work-census
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (ensure-census!)
      (doseq [{:keys [arm b op bodies markup runtime]} @!record]
        (is (= bodies runtime)
            (str arm "/" op " at B=" b ": this file's body count and the
                 runtime's own `body-runs` are two instruments sharing no
                 traversal, and they must agree"))
        (is (<= markup (m/rendered-rows arm b))
            (str arm "/" op " at B=" b " built more rows of markup than the page
                 renders, which is not a topology — it is a bug"))))))

;; ---------------------------------------------------------------------------
;; 3 — the four findings the census is FOR, asserted rather than eyeballed
;; ---------------------------------------------------------------------------

(defn- work-at [arm b op]
  (first (filter #(and (= arm (:arm %)) (= b (:b %)) (= op (:op %))) (ensure-census!))))

(deftest the-sparse-finding
  (testing "a one-row write builds ONE row of markup under `fine`, and every
           rendered row under `coarse`. That gap is the tournament's whole
           sparse result and it grows exactly with B"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (doseq [b m/row-counts]
        (is (= 1 (:markup (work-at :fine b :sparse)))
            (str "fine/sparse at B=" b))
        (is (= b (:markup (work-at :coarse b :sparse)))
            (str "coarse/sparse at B=" b " — the coarse arm has no way to build
                 fewer, which is the price its cheap bulk is bought with"))
        (is (<= (:markup (work-at :chunked b :sparse)) m/chunk-size)
            (str "chunked/sparse at B=" b " is bounded by k, not by B"))))))

(deftest the-bulk-finding
  (testing "an all-row write builds every rendered row under EVERY un-windowed
           arm — so on markup alone bulk cannot separate them, and whatever
           separates them there is not the number of rows built"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (doseq [b m/row-counts]
        (doseq [arm [:fine :coarse :chunked]]
          (is (= b (:markup (work-at arm b :bulk)))
              (str arm "/bulk at B=" b)))
        (is (= (min b m/window-size) (:markup (work-at :virtual b :bulk)))
            (str "virtual/bulk at B=" b " builds only its window — a different
                 page, and the only arm whose bulk cost does not scale with B"))))))

(deftest the-reorder-finding
  (testing "a permutation moves NO row's reads. Under `fine` the list re-runs
           and every row memo-bails, so zero rows of markup are built for a
           table that visibly reorders; under `coarse` all B are rebuilt"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (doseq [b m/row-counts]
        (is (= 0 (:markup (work-at :fine b :reorder)))
            (str "fine/reorder at B=" b " — React moves DOM nodes and runs no
                 body, which is the strongest single result in this census"))
        (is (= b (:markup (work-at :coarse b :reorder)))
            (str "coarse/reorder at B=" b))))))

(deftest the-edit-finding
  (testing "the write a controlled field's change intent performs lands on the
           row's own draft cell, so it separates the arms exactly as `sparse`
           does — the pipeline in front of it is rf2-hic-045's, not this
           tournament's"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (doseq [b m/row-counts]
        (is (= 1 (:markup (work-at :fine b :edit))) (str "fine/edit at B=" b))
        (is (= b (:markup (work-at :coarse b :edit))) (str "coarse/edit at B=" b))))))

;; ---------------------------------------------------------------------------
;; 4 — the controls
;; ---------------------------------------------------------------------------

(deftest the-negative-control-a-write-no-arm-reads
  (testing "the counts above are counting rather than failing to look: a commit
           that moves a key no arm holds moves no body in any arm"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (doseq [arm arms/arm-ids]
        (let [b 100]
          (fresh! b)
          (let [handle (mount! (arms/view-of arm))]
            (try
              (restore! b)
              (let [work (operate! :noop b)]
                (is (= 0 (:bodies work)) (str arm " ran a body on an unread write"))
                (is (= 0 (:markup work)) (str arm " built markup on an unread write")))
              (finally (mount/release! handle)))))))))

(deftest the-census-rejects-a-broken-arm
  (testing "THE SABOTAGE CONTROL. `sabotage-list` is `fine` with one extra read
           per row — the shared view-model — so a one-row write invalidates all
           B. Its DOM is byte-identical to `fine`'s, so only the census can tell
           them apart, and it must"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (let [b 100]
        (fresh! b)
        (let [handle (mount! sabotage-list)]
          (try
            (is (not= (:edges (arms/expected :fine b)) (:edges (rt/stats)))
                "the structural check rejects it: the broken arm holds more read
                 edges than fine's arithmetic allows")
            (restore! b)
            (let [work (operate! :sparse b)]
              (is (= b (:markup work))
                  "and the work census rejects it too — a one-row write built all
                   B rows, where fine builds exactly one")
              (is (not= 1 (:markup work))
                  "which is the assertion `the-sparse-finding` would have made
                   about a healthy arm, failing here as it must"))
            (finally (mount/release! handle))))))))

(deftest the-sabotage-arm-really-is-fine-in-every-other-respect
  (testing "without this the control proves only that a DIFFERENT PAGE behaves
           differently. The two arms build the same DOM, so the census's red
           above is about the read topology and nothing else"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (let [b 100]
        (lane/leave-act-environment!)
        (m/make-frame! frame-id b)
        (m/reseed! frame-id b)
        (let [healthy (mount! (arms/view-of :fine))
              a       (lane/canonical (:container healthy))
              _       (mount/release! healthy)
              _       (m/reseed! frame-id b)
              broken  (mount! sabotage-list)
              c       (lane/canonical (:container broken))]
          (mount/release! broken)
          (is (= a c) "same markup, same keys, same attributes")
          (is (re-find #"topo-row" a) "and the comparison is of a real table"))))))

;; ---------------------------------------------------------------------------
;; 5 — the record, emitted for the tournament page
;; ---------------------------------------------------------------------------

(deftest emit-the-census
  (testing "the table this file exists to produce, printed as EDN so the
           tournament page transcribes rather than retypes it"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (js/console.log (str ";; TOPO-CENSUS "
                             (pr-str (sort-by (juxt :op :b :arm) (ensure-census!)))))
        (is (= (* (count arms/arm-ids) (count m/row-counts) 4) (count @!record))
            "every arm x row count x operation cell is present — a gap in the
             table must fail here rather than be read as a pass")))))
