(ns re-frame.bench.hicasso.topo.clock-witness-dom-cljs-test
  "**THE CLOCK DRIVER'S OWN DETERMINISTIC HALF** — the arithmetic
  [[re-frame.bench.hicasso.topo.clock-app]] gates its cells on, and the
  witness in which its read-back refuses (rf2-w01c).

  ## Why this file needs no quiet box, and the clock run does

  [The budgets page](../../../../../../../docs/design/hicasso/product/budgets.md)
  sorts performance rows into two families and its own sentence is the
  fence: a counter *\"reads the same on a loaded box\"*. Everything
  asserted here is an exact integer, a string read out of the DOM, or
  pure arithmetic over the model's seed, so contention cannot move any of
  it. This is an ordinary blocking gate that runs in CI on every PR and is
  repeatable by anyone. Whether the instrument can SEE the differences
  counted here is a timing question and belongs to the quiet-box run.

  ## What it establishes

  1. **The driver's pre-clock gate is the census's published table.**
     `clock-app/markup-expected` is arithmetic, and arithmetic drifts; the
     numbers it must produce are stated here as exact integers, so a
     change to that function that stopped describing
     [§2.2](../../../../../../../docs/design/hicasso/product/topology-tournament.md#22-the-rung-2-teaching-table--rows-of-markup-built)
     reds instead of quietly re-baselining the tournament.
  2. **No operation is verified by a changed probe alone.** Every pair
     holds one cell that must have moved and one that must not, and the
     changed half advances by exactly one per commit. A write that reached
     every row, and a page rebuilt from a stale seed, both satisfy a
     changed probe on its own.
  3. **The read-back bites, on a real page.** A planted source fault
     proves the source changed; it does not prove the runtime observed
     the plant. So the witness below mounts a real arm and drives
     `clock-app/window!` itself — the same function the clock run calls,
     banking into the same `rf.bench.hicasso.lane/tally` — with the ONE difference that
     the commits are dispatched into a frame nothing is mounted over. The
     page therefore never moves while the clock runs and the probes read
     it afterwards, which is the exact fault the read-back exists to
     catch: *the clock measured a page that did not commit what it
     claims*. There is nothing to restore and nothing to hash.
  4. **And it can answer true.** The same window against the page's own
     frame reads `0 unverified`, so the refusal above is a discrimination
     rather than a probe that always fails.

  Runtime: `-dom-cljs-test`. Claims 1 and 2 hold under `:node-test` too —
  they touch no DOM — and every DOM claim degrades to a stated skip
  there."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.arm1.mount :as rf.bench.hicasso.arm1.mount]
            [re-frame.bench.hicasso.arm1.runtime :as rf.bench.hicasso.arm1.runtime]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.topo.arms :as rf.bench.hicasso.topo.arms]
            [re-frame.bench.hicasso.topo.clock-app :as rf.bench.hicasso.topo.clock-app]
            [re-frame.bench.hicasso.topo.model :as rf.bench.hicasso.topo.model]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.bench.hicasso.arm1.runtime/reset-runtime!))}))

(defn- skip! [why]
  (is true (str "the topology clock witness needs a real React DOM — " why)))

;; ---------------------------------------------------------------------------
;; 1 — the plan, and the arm that is deliberately absent from it
;; ---------------------------------------------------------------------------

(deftest the-windowed-arm-is-unaddressed-and-says-why
  (testing "rf2-4t36 ruled the windowed arm's clock cells unresolvable at the
           tournament's committed window. A driver that measured it anyway and
           labelled the number would publish a figure a reader can quote, so it
           is absent from the roster and its reason travels in the file"
    (is (= [:fine :coarse :chunked] rf.bench.hicasso.topo.clock-app/measured-arms))
    (is (= #{:virtual} (set (keys rf.bench.hicasso.topo.clock-app/unaddressed))))
    (is (re-find #"rf2-4t36" (:virtual rf.bench.hicasso.topo.clock-app/unaddressed))
        "the reason names the ruling that made it, not this file")
    (is (= (set rf.bench.hicasso.topo.arms/arm-ids)
           (into (set rf.bench.hicasso.topo.clock-app/measured-arms) (keys rf.bench.hicasso.topo.clock-app/unaddressed)))
        "every arm of the tournament is either measured or unaddressed — an arm
         that fell out of both lists would vanish from the table silently")))

(deftest the-floor-row-is-not-one-of-the-tournaments-operations
  (testing "`:noop` is measured beside the cells and is not a cell. Folding it
           into `operations` would put a row in the published table that the
           tournament never registered"
    (is (= [:sparse :bulk :reorder :edit] rf.bench.hicasso.topo.clock-app/operations))
    (is (not (contains? (set rf.bench.hicasso.topo.clock-app/operations) rf.bench.hicasso.topo.clock-app/floor-op)))
    (is (= (conj rf.bench.hicasso.topo.clock-app/operations rf.bench.hicasso.topo.clock-app/floor-op) rf.bench.hicasso.topo.clock-app/rows))))

;; ---------------------------------------------------------------------------
;; 2 — the pre-clock gate is the census's published table
;; ---------------------------------------------------------------------------

(def ^:private published-markup
  "[§2.2](../../../../../../../docs/design/hicasso/product/topology-tournament.md#22-the-rung-2-teaching-table--rows-of-markup-built),
  transcribed as exact integers over the three measured arms.

  Stated here rather than derived, deliberately: `clock-app/markup-expected`
  is the derivation, and a witness that re-derived it would agree with it
  by construction whatever either of them said."
  {[:sparse  100]  {:fine 1 :coarse 100  :chunked 25}
   [:sparse  300]  {:fine 1 :coarse 300  :chunked 25}
   [:sparse  1000] {:fine 1 :coarse 1000 :chunked 25}
   [:bulk    100]  {:fine 100  :coarse 100  :chunked 100}
   [:bulk    300]  {:fine 300  :coarse 300  :chunked 300}
   [:bulk    1000] {:fine 1000 :coarse 1000 :chunked 1000}
   [:reorder 100]  {:fine 0 :coarse 100  :chunked 100}
   [:reorder 300]  {:fine 0 :coarse 300  :chunked 300}
   [:reorder 1000] {:fine 0 :coarse 1000 :chunked 1000}
   [:edit    100]  {:fine 1 :coarse 100  :chunked 25}
   [:edit    300]  {:fine 1 :coarse 300  :chunked 25}
   [:edit    1000] {:fine 1 :coarse 1000 :chunked 25}})

(deftest the-drivers-pre-clock-gate-is-the-published-census
  (testing "a clock cell is only taken on a page that builds the markup the
           census publishes for it, so the arithmetic that decides it must be
           that census and not a near neighbour of it"
    (doseq [b  rf.bench.hicasso.topo.model/row-counts
            op rf.bench.hicasso.topo.clock-app/operations]
      (is (= (get published-markup [op b])
             (into {} (map (fn [arm] [arm (rf.bench.hicasso.topo.clock-app/markup-expected arm op b)]))
                   rf.bench.hicasso.topo.clock-app/measured-arms))
          (str op " at B=" b)))))

(deftest the-floor-row-builds-nothing-in-any-arm
  (testing "`[:topo/noop-write]` moves a key no arm reads, so the floor window
           holds the per-commit cost and no row of markup. A floor that built
           markup would be measuring an operation"
    (doseq [b   rf.bench.hicasso.topo.model/row-counts
            arm rf.bench.hicasso.topo.clock-app/measured-arms]
      (is (= 0 (rf.bench.hicasso.topo.clock-app/markup-expected arm rf.bench.hicasso.topo.clock-app/floor-op b))
          (str arm " at B=" b)))))

;; ---------------------------------------------------------------------------
;; 3 — the probe pairs, before any page exists
;; ---------------------------------------------------------------------------

(deftest every-operation-carries-a-cell-that-must-not-have-moved
  (testing "a changed probe alone is satisfied by a write that reached every
           row and by a page rebuilt from a stale seed. The second half of
           every pair is what refuses both"
    (doseq [b  rf.bench.hicasso.topo.model/row-counts
            op rf.bench.hicasso.topo.clock-app/rows]
      (let [es (rf.bench.hicasso.topo.clock-app/expectations op b 20)]
        (is (= 2 (count es)) (str op " at B=" b " must carry exactly two probes"))
        (is (not= #{:changed} (set (map :probe es)))
            (str op " at B=" b " is verified by changed probes alone"))
        (is (apply distinct? (map :cell es))
            (str op " at B=" b " reads one cell twice, so one probe is free"))))))

(deftest the-changed-probe-advances-by-exactly-one-per-commit
  (testing "the read-back's whole arithmetic: whatever the operation moves must
           be a pure function of the commit count, or a window that committed
           nineteen of its twenty writes reads as verified"
    (doseq [b  rf.bench.hicasso.topo.model/row-counts
            op rf.bench.hicasso.topo.clock-app/operations]
      (let [changed (fn [n] (->> (rf.bench.hicasso.topo.clock-app/expectations op b n)
                                 (remove (comp #{:unchanged} :probe))
                                 (map :want)
                                 vec))]
        (is (not= (changed 20) (changed 21))
            (str op " at B=" b ": one more commit must be visible in the probe"))
        (is (= (changed 20) (changed 20))
            (str op " at B=" b ": and the same commit count must read the same"))))))

(deftest the-floor-rows-pair-is-two-cells-that-must-not-move
  (testing "the negative control, written as a read-back: a window over a
           commit no arm reads must leave the page exactly where the seed put
           it, and BOTH probes say so"
    (doseq [b rf.bench.hicasso.topo.model/row-counts]
      (let [es (rf.bench.hicasso.topo.clock-app/expectations rf.bench.hicasso.topo.clock-app/floor-op b 20)]
        (is (= [:unchanged :unchanged] (mapv :probe es)) (str "at B=" b))
        (is (= (mapv :want es) (mapv :want (rf.bench.hicasso.topo.clock-app/expectations rf.bench.hicasso.topo.clock-app/floor-op b 40)))
            (str "at B=" b ": and the expectation does not depend on how many
                 no-op commits ran, because none of them may reach the page"))))))

;; ---------------------------------------------------------------------------
;; 4 — the read-back, on a real page
;; ---------------------------------------------------------------------------

(def ^:private page-frame ::witness)
(def ^:private decoy-frame ::witness-decoy)
(def ^:private witness-b 100)

(defn- mount-witness!
  "One real `fine` page at [[witness-b]] rows, seeded. Answers the page map
  `clock-app/window!` takes."
  []
  (rf.bench.hicasso.lane/leave-act-environment!)
  (rf.bench.hicasso.topo.model/make-frame! page-frame witness-b)
  (rf.bench.hicasso.topo.model/reseed! page-frame witness-b)
  (rf.bench.hicasso.topo.arms/reset-counters!)
  (let [handle (rf.bench.hicasso.arm1.mount/root! (rf.bench.hicasso.arm1.mount/fresh-container!) page-frame
                            [(rf.bench.hicasso.topo.arms/view-of :fine) {}])]
    {:handle    handle
     :frame-id  page-frame
     :container (:container handle)
     :b         witness-b
     :committed (atom 0)}))

(deftest the-window-verifies-a-page-that-did-commit
  (testing "the refusal below has to be a discrimination, so the same window
           over the same page's own frame must read 0 unverified — a probe pair
           that always failed would prove nothing about the one that does"
    (if-not (rf.bench.hicasso.arm1.mount/browser?)
      (skip! ":node-test has no DOM")
      (doseq [op rf.bench.hicasso.topo.clock-app/rows]
        (let [page (mount-witness!)
              t    (rf.bench.hicasso.lane/tally)]
          (try
            (rf.bench.hicasso.topo.clock-app/window! t page op)
            (is (= {:writes 2 :unverified 0} (rf.bench.hicasso.lane/tally-value t))
                (str op ": every probe of a page that committed what it claims
                     must read back, or the instrument refuses healthy runs"))
            (finally (rf.bench.hicasso.arm1.mount/release! (:handle page)))))))))

(deftest the-window-refuses-a-page-that-did-not-commit
  (testing "THE FAULT THE READ-BACK EXISTS TO CATCH. The commits are dispatched
           into a frame nothing is mounted over, so `batch-k` writes and
           `batch-k` settles happen and the observed page never moves. The
           clock still returns a plausible number; the probes are what refuse
           it"
    (if-not (rf.bench.hicasso.arm1.mount/browser?)
      (skip! ":node-test has no DOM")
      ;; `:noop` is excluded and the exclusion is the point: its pair is two
      ;; UNCHANGED cells, so a page that committed nothing satisfies it by
      ;; construction. That is what a floor row is, and it is why the floor is
      ;; reported beside the cells rather than trusted as one.
      (doseq [op rf.bench.hicasso.topo.clock-app/operations]
        (let [page (mount-witness!)
              _    (rf.bench.hicasso.topo.model/make-frame! decoy-frame witness-b)
              t    (rf.bench.hicasso.lane/tally)]
          (try
            (let [ms     (rf.bench.hicasso.topo.clock-app/window! t (assoc page :frame-id decoy-frame) op)
                  misses (rf.bench.hicasso.topo.clock-app/probe-misses (:container page) op witness-b
                                             @(:committed page))]
              (is (number? ms)
                  (str op ": the clock still answers — a withheld commit does not
                       announce itself in milliseconds, which is the whole reason
                       the read-back is not optional"))
              ;; The CHANGED probe is the one that has to bite, and it is
              ;; named rather than counted. An unchanged probe is SATISFIED by a
              ;; page that committed nothing — that is what makes it the second
              ;; half of a pair rather than a second detector — so asserting a
              ;; miss count here would be asserting how many probes an operation
              ;; happens to have that can see a dead page.
              (is (contains? (set (map :probe misses)) :changed)
                  (str op ": the probe that must have moved must refuse a page
                       that never moved"))
              (is (= {:writes 2 :unverified (count misses)} (rf.bench.hicasso.lane/tally-value t))
                  (str op ": and the window banks exactly what the probes found —
                       a tally that disagreed with a direct read is the accounting
                       fault, one level out from the page"))
              (is (thrown? js/Error
                    (rf.bench.hicasso.lane/assert-verified! t (str "topo clock witness " (name op))))
                  (str op ": and the lane's ONE adjudication of a read-back must
                       throw on it — a count that is published and never
                       adjudicated is the fault `assert-verified!` exists for")))
            (finally (rf.bench.hicasso.arm1.mount/release! (:handle page)))))))))

;; ---------------------------------------------------------------------------
;; 5 — and the gate the driver runs before any clock, live
;; ---------------------------------------------------------------------------

(deftest a-real-page-builds-the-markup-the-driver-predicts
  (testing "`markup-expected` is checked against the published census above;
           here it is checked against a page. Both are needed: the first says
           the driver states the tournament's numbers, this says the tournament's
           numbers still describe the arm"
    (if-not (rf.bench.hicasso.arm1.mount/browser?)
      (skip! ":node-test has no DOM")
      (doseq [arm rf.bench.hicasso.topo.clock-app/measured-arms
              op  rf.bench.hicasso.topo.clock-app/rows]
        (rf.bench.hicasso.lane/leave-act-environment!)
        (rf.bench.hicasso.topo.model/make-frame! page-frame witness-b)
        (rf.bench.hicasso.topo.model/reseed! page-frame witness-b)
        (let [handle (rf.bench.hicasso.arm1.mount/root! (rf.bench.hicasso.arm1.mount/fresh-container!) page-frame
                                  [(rf.bench.hicasso.topo.arms/view-of arm) {}])]
          (try
            (rf.bench.hicasso.topo.arms/reset-counters!)
            (rf.bench.hicasso.topo.clock-app/write! page-frame op 0)
            (rf.bench.hicasso.arm1.mount/settle!)
            (is (= (rf.bench.hicasso.topo.clock-app/markup-expected arm op witness-b) (:markup (rf.bench.hicasso.topo.arms/runs)))
                (str arm "/" op " at B=" witness-b))
            (finally (rf.bench.hicasso.arm1.mount/release! handle))))))))
