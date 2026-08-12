(ns re-frame.hicasso.readset-group-census-cljs-test
  "THE CENSUS'S OWN HONESTY (rf2-hic-083).

  [[re-frame.hicasso.readset-group-census]] is about to be believed, so
  this file is the part that attacks it. The pooled figure it produces
  over the witness applications is worth exactly what these rows are
  worth, and they are written to foreclose the four ways a reporter can
  publish a confident wrong number:

  | row | what it forecloses |
  |---|---|
  | [[the-census-answers-non-empty-on-a-real-population]] | a reporter that reports \"clean\" and \"nothing ran\" identically |
  | [[a-shared-multi-key-population-reports-a-positive-saving]] | a reporter that cannot detect coalescence when it is present — whose zero elsewhere therefore means nothing at all |
  | [[a-distinct-query-population-comes-back-clean]] | an OVER-report: a legal population that must not coalesce coming back positive |
  | [[the-entry-side-walk-reproduces-the-cell-side-landmark]] | a new number believed before the landmark beside it is reproduced |

  and the three that follow report rather than skip: an unclaimed entry,
  a duplicated read, a read-free shell.

  ## The seam, and why this needs no browser

  `render-body` + `commit-boundary!` are the published pair — the
  generation-fenced body run, then the same `subscribe` closure
  `useSyncExternalStore` would call, handed the same notifier React
  would hand it. Every table the census reads is populated by exactly
  that path whether React drove it or this file did, so the census's
  ARITHMETIC is decidable on the Node lane. What is not decidable here is
  the POPULATION of a real application — how many instances of each body
  React mounts — and that is the browser lane's, in
  `readset-group-census-dom-cljs-test`. The two files split on that line
  and on nothing else.

  ## The populations are built, not found

  Each row constructs the shape it is about. That is the point: a control
  whose population was discovered could not be a control."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.readset-group-census :as census]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::readset-census)

(doseq [i (range 24)]
  (rf/reg-sub (keyword "rsc" (str "k" i)) (fn [db [_]] (get db i))))

(rf/reg-event :rsc/seed (fn [_ [_ db]] {:db db}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Harness — the published render/commit seam, nothing else
;; ---------------------------------------------------------------------------

(defn- seeded! []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id
    (rf/dispatch-sync [:rsc/seed (zipmap (range 24) (range 24))]))
  frame-id)

(defn- q
  "The `n`th distinct query vector this file can read."
  [n]
  [(keyword "rsc" (str "k" n))])

(defn- reading
  "A body that reads exactly `query-vs`, in read order, through the
  ambient collector."
  [query-vs]
  (fn [_props] [:p (str (mapv collector/sub query-vs))]))

(defn- mount!
  "Render a body and commit it, exactly as React does."
  [body]
  (collector/render-body frame-id body {})
  (collector/commit-boundary! (collector/last-reads) (fn [])))

(defn- mount-n!
  "`n` boundaries, each running `body` — so `n` fibers reach the same
  read-set entry when the reads are identical, which is the whole
  population the proposal is about."
  [n body]
  (mapv (fn [_] (mount! body)) (range n)))

;; ---------------------------------------------------------------------------
;; The arithmetic the criteria froze
;; ---------------------------------------------------------------------------

(deftest the-saving-identity-is-the-one-the-criteria-registered
  (testing "B·R − (R + B) = (B−1)(R−1) − 1, at every rung the verdict turns on"
    (doseq [b (range 1 13)
            r (range 0 9)]
      (is (= (dec (* (dec b) (dec r))) (census/saving b r))
          (str "B=" b " R=" r))))

  (testing "the two rungs that need no measurement at all"
    (doseq [b (range 1 13)]
      (is (= -1 (census/saving b 1))
          "a one-read group of ANY size coalesces to a net loss of one"))
    (doseq [r (range 1 9)]
      (is (= -1 (census/saving 1 r))
          "a singleton of ANY read count coalesces to a net loss of one")))

  (testing "break-even, and the first rung that pays"
    (is (= 0 (census/saving 2 2)))
    (is (= 1 (census/saving 2 3)))
    (is (= 1 (census/saving 3 2)))))

;; ---------------------------------------------------------------------------
;; Proof 1 — NON-EMPTY
;; ---------------------------------------------------------------------------

(deftest the-census-answers-non-empty-on-a-real-population
  (seeded!)
  (mount! (reading [(q 0) (q 1) (q 2)]))
  (mount! (reading [(q 3)]))
  (let [r (census/report)]
    (is (pos? (:entries r))      "it reached entries")
    (is (pos? (:claimed r))      "it reached CLAIMED entries")
    (is (pos? (:memberships r))  "it answered a positive membership count")
    (is (= 4 (:memberships r))
        "three memberships from the three-read boundary and one from the
         one-read boundary — the exact number, so a row that merely
         answered `positive` could not pass")
    (is (= 2 (:claimed r)))))

;; ---------------------------------------------------------------------------
;; Proof 2 — the POSITIVE control: it CAN report coalescence
;; ---------------------------------------------------------------------------

(deftest a-shared-multi-key-population-reports-a-positive-saving
  (seeded!)
  ;; Five boundaries, one identical four-key read set. `entry-for` is
  ;; keyed by the ordered read sequence, so all five reach one entry.
  (mount-n! 5 (reading [(q 0) (q 1) (q 2) (q 3)]))
  (let [r (census/report)]
    (is (= 1 (:claimed r)) "one entry, five boundaries — the premise")
    (is (= 5 (:max-boundaries r)))
    (is (= 4 (:max-reads r)))
    (is (= 20 (:memberships r))   "B·R = 5 × 4")
    (is (= 9 (:grouped r))        "R + B = 4 + 5")
    (is (= 11 (:saved r))         "(B−1)(R−1) − 1 = 4 × 3 − 1")
    (is (= 1 (:paying-entries r)))
    (is (< 0.5 (:coalesced r))
        "and it is REPORTED as a fraction over 50%, so a census that
         could not see coalescence at all would fail here rather than
         silently answer zero everywhere else")
    (is (= 1.0 (:shareable-fraction r))
        "every membership in this population lives in an entry more than
         one boundary holds")))

;; ---------------------------------------------------------------------------
;; Proof 3 — the OVER-REPORT control: a legal population comes back clean
;; ---------------------------------------------------------------------------

(deftest a-distinct-query-population-comes-back-clean
  (seeded!)
  ;; Five boundaries, four reads each, every key distinct — the
  ;; distinct-query rung, and the shape a real per-instance body has.
  (dotimes [i 5]
    (mount! (reading [(q (* 4 i)) (q (+ 1 (* 4 i))) (q (+ 2 (* 4 i))) (q (+ 3 (* 4 i)))])))
  (let [r (census/report)]
    (is (= 5 (:claimed r))       "five entries, one boundary each")
    (is (= 20 (:memberships r))  "the SAME twenty memberships as the shared population")
    (is (= 0 (:shared-entries r)))
    (is (= 0 (:shareable r))
        "not one membership is even a candidate for sharing")
    (is (= 0.0 (:shareable-fraction r)))
    (is (= 0 (:paying-entries r)))
    (is (= -5 (:saved r))
        "grouping COSTS five here — one slot per singleton — and the
         census says so with a sign rather than reporting a zero that a
         reader could mistake for neutrality")
    (is (neg? (:coalesced r)))))

;; ---------------------------------------------------------------------------
;; Proof 4 — CALIBRATION against the landmark
;; ---------------------------------------------------------------------------

(deftest the-entry-side-walk-reproduces-the-cell-side-landmark
  (seeded!)
  (testing "on the shared population"
    (mount-n! 5 (reading [(q 0) (q 1) (q 2) (q 3)]))
    (let [r (census/report)]
      (is (= 20 (:cell-refs (inventory/stats)))
          "the landmark, walked cell-side: `readers.length` summed over
           the cell table")
      (is (= 20 (:entry-side (:landmark r)))
          "and reproduced exactly by the independent entry-side walk,
           `refs × |set|` summed over the entry cache")
      (is (census/calibrated? r))
      (is (= 0 (:divergence (:landmark r))))))

  (testing "and on the distinct-query population, where the same twenty
            memberships are spread over five entries rather than one —
            so a walk that had confused entries with boundaries would
            miss here and not there"
    (collector/reset-runtime!)
    (seeded!)
    (dotimes [i 5]
      (mount! (reading [(q (* 4 i)) (q (+ 1 (* 4 i))) (q (+ 2 (* 4 i))) (q (+ 3 (* 4 i)))])))
    (let [r (census/report)]
      (is (= 20 (:cell-refs (inventory/stats))))
      (is (= 20 (:entry-side (:landmark r))))
      (is (census/calibrated? r)))))

(deftest the-landmark-is-a-real-comparison-and-can-report-a-divergence
  ;; The calibration above is only worth something if the two walks CAN
  ;; disagree. They do, on the one transition that removes a cell from
  ;; under live readers: a destroyed frame. `invalidate-cell!` disposes
  ;; the cell at its microtask checkpoint when the frame did not come
  ;; back, and the registrations go on holding it — so the cell-side walk
  ;; loses memberships the entry-side walk still counts. The census
  ;; REPORTS that difference; it does not reconcile it.
  (seeded!)
  (mount-n! 3 (reading [(q 0) (q 1)]))
  (let [before (census/report)
        held   @collector/!cells]
    (is (= 6 (:entry-side (:landmark before))))
    (is (= 6 (:cell-side (:landmark before))))
    (is (census/calibrated? before))
    (testing "with the cell table lifted out from under them, the two
              walks diverge by the whole population and the census says
              by how much"
      (reset! collector/!cells {})
      (let [after (census/report)]
        (is (= 6 (:entry-side (:landmark after))))
        (is (= 0 (:cell-side (:landmark after))))
        (is (= 6 (:divergence (:landmark after))))
        (is (false? (census/calibrated? after))
            "and `calibrated?` is a REPORT, never a filter: every row is
             still in the answer")
        (is (= (:claimed before) (:claimed after)))))
    (testing "restored — so the red above was the inversion and not the
              state this row leaves behind"
      (reset! collector/!cells held)
      (is (= before (census/report))))))

;; ---------------------------------------------------------------------------
;; What it REPORTS rather than skips
;; ---------------------------------------------------------------------------

(deftest an-unclaimed-entry-is-reported-rather-than-dropped
  (seeded!)
  ;; A render that was never committed — an abandoned attempt, or one
  ;; still inside the 4 ms reap horizon. It mints an entry and holds no
  ;; membership.
  (collector/render-body frame-id (reading [(q 0) (q 1)]) {})
  (let [r (census/report)]
    (is (= 1 (:entries r))     "the entry is in the answer")
    (is (= 0 (:claimed r)))
    (is (= 1 (:unclaimed r))   "counted, and named as unclaimed")
    (is (= 0 (:memberships r)) "and priced at nothing, because it holds nothing")
    (is (= 0 (:saved r))
        "an unclaimed entry can neither be saved nor cost, and a census
         that had charged it either way would move the pooled figure by
         however many renders were abandoned")))

(deftest a-body-that-reads-one-key-twice-is-priced-at-its-key-SET-and-reported
  (seeded!)
  (mount! (reading [(q 0) (q 0) (q 1)]))
  (let [r   (census/report)
        row (first (census/rows))]
    (is (= 3 (:read-slots row))  "three reads reached the scratch")
    (is (= 2 (:reads row))       "and acquired two cells, because `subscribe` walks the SET")
    (is (true? (:duplicate-read? row)))
    (is (= 1 (:duplicate-read-entries r)))
    (is (= 2 (:memberships r))
        "priced at |set|. Pricing the key ARRAY would have answered 3 —
         an over-report on exactly the shape whose read sequence
         duplicates")
    (is (census/calibrated? r)
        "and the landmark agrees, which is what makes the |set| choice a
         measurement rather than a preference")))

(deftest a-read-free-shell-is-reported-because-grouping-would-cost-it-B
  (seeded!)
  ;; `examples.forms/details-form` is a real one: a boundary whose body
  ;; reads nothing, so a keystroke in either field stops at that field's
  ;; row. At R = 0 the grouped cost is B against today's nothing.
  (mount-n! 4 (fn [_props] [:p "no reads"]))
  (let [r (census/report)]
    (is (= 1 (:claimed r)))
    (is (= 1 (:read-free-entries r)))
    (is (= 0 (:memberships r))  "it holds no memberships today")
    (is (= 4 (:grouped r))      "and would hold four notify slots grouped")
    (is (= -4 (:saved r)))
    (is (census/calibrated? r))))

;; ---------------------------------------------------------------------------
;; The rung the verdict turns on, built rather than argued
;; ---------------------------------------------------------------------------

(deftest the-largest-shareable-shape-a-shell-can-make-still-loses
  ;; This is the shape a real application produces: N instances of one
  ;; body whose reads carry no per-instance parameter, so every instance
  ;; reaches the identical entry. `examples.grid` has exactly it — ten
  ;; `grid-row`s and the `grid` itself all read `[::subs/dimensions]` and
  ;; nothing else.
  ;;
  ;; It is the biggest B the corpus offers and it coalesces to a LOSS,
  ;; because the same absence of a per-instance parameter that lets the
  ;; set be shared is what keeps the set small.
  (seeded!)
  (mount-n! 11 (reading [(q 0)]))
  (let [r (census/report)]
    (is (= 11 (:max-boundaries r)) "eleven boundaries on one entry")
    (is (= 11 (:memberships r)))
    (is (= 12 (:grouped r))        "R + B = 1 + 11")
    (is (= -1 (:saved r)))
    (is (= 0 (:paying-entries r)))
    (is (= 1.0 (:shareable-fraction r))
        "every membership is shareable by the generous reading, and
         grouping still costs one — which is why the verdict is taken on
         both denominators and not on this one")))
