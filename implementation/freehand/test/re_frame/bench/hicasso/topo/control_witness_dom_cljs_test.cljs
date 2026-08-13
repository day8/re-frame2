(ns re-frame.bench.hicasso.topo.control-witness-dom-cljs-test
  "**THE CONTROL'S OWN DETERMINISTIC HALF** — why the changed-set control
  was degenerate, why the rendered-scale one is not, and the witness in
  which its read-back refuses (rf2-m6i0).

  ## Why this file needs no quiet box, and the clock run does

  [The budgets page](../../../../../../../docs/design/hicasso/product/budgets.md)
  sorts performance rows into two families, and its own sentence is the
  fence: a counter *\"reads the same on a loaded box\"*. Everything
  asserted here is an integer on a monotone counter or a string read out
  of the DOM, so contention cannot move any of it. This file is an
  ordinary blocking gate that runs in CI on every PR and is repeatable by
  anyone.

  What it establishes is exactly the half a clock session cannot
  establish for itself:

  1. **The degeneracy premise, at source.** `:topo/bump-stride` — the
     write the refused control used — builds the same rows of markup at
     stride 10 and stride 5 on `coarse` and `chunked`. The claim that
     those two arms had no positive control is a MEASUREMENT here, not an
     argument, and it stays measured.
  2. **The replacement discriminates on all four arms**, in exact
     integers, before any clock is started.
  3. **The read-back bites.** A page whose writes do not commit is
     mounted for real and the control refuses it.

  It is **not** a substitute for the clock. Whether the instrument can
  SEE the asymmetry counted here is a timing question and belongs to the
  quiet-box run.

  ## The non-commit witness is a real page, not a patched file

  A planted source fault proves the source changed; it does not prove the
  runtime observed the plant. So the witness below mounts a real arm and
  drives [[re-frame.bench.hicasso.topo.control-app/window!]] itself — the
  same function the clock run calls, banking into the same
  `lane/tally` — with the ONE difference that the write's `limit` is
  zero, so `(range 0 0 stride)` is empty and no row moves. Everything
  else is the control: twenty dispatches, twenty settles, one clock, and
  the probes read afterwards. There is nothing to restore and nothing to
  hash.

  Runtime: `-dom-cljs-test`. Under `:node-test` every claim degrades to a
  stated skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.topo.arms :as arms]
            [re-frame.bench.hicasso.topo.control-app :as ctl]
            [re-frame.bench.hicasso.topo.model :as m]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))}))

(defn- skip! [why]
  (is true (str "the topology control witness needs a real React DOM — " why)))

(def ^:private frame-id ::witness)

(defn- fresh! [b w]
  (lane/leave-act-environment!)
  (m/make-frame! frame-id b w)
  (m/reseed! frame-id b w)
  (arms/reset-counters!)
  frame-id)

(defn- reseed! [b w]
  (rt/dispatch! frame-id [:topo/seed b w])
  (mount/settle!)
  (arms/reset-counters!)
  nil)

(defn- markup-caused-by
  "Rows of markup ONE dispatch of `event` builds on a standing mount."
  [event]
  (arms/reset-counters!)
  (rt/dispatch! frame-id event)
  (mount/settle!)
  (:markup (arms/runs)))

;; ---------------------------------------------------------------------------
;; 1 — the degeneracy premise, measured rather than asserted
;; ---------------------------------------------------------------------------

(def ^:private premise-b
  "The premise is about the SHAPE of each arm's response, which is the
  same at every row count, so it is measured at the cheapest one."
  100)

(deftest the-changed-set-control-is-degenerate-on-the-coarse-family
  (testing "rf2-hic-036 built rf2-7iqb5's prescribed changed-set doubling over
           `:topo/bump-stride` and predicted 2.00x. This is the measurement
           that says it could never have certified two of the four arms:
           `coarse` and `chunked` rebuild every rendered row whichever stride
           runs, so doubling the changed set doubles nothing they do. A
           control predicting 2.00x there refuses a healthy instrument; one
           predicting 1.00x discriminates nothing"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (doseq [arm arms/arm-ids]
        (fresh! premise-b m/window-size)
        (let [handle (mount/root! (mount/fresh-container!) frame-id [(arms/view-of arm) {}])]
          (try
            (reseed! premise-b m/window-size)
            (let [d10 (markup-caused-by [:topo/bump-stride 10])
                  _   (reseed! premise-b m/window-size)
                  d5  (markup-caused-by [:topo/bump-stride 5])
                  rendered (m/rendered-rows arm premise-b)]
              (if (contains? #{:coarse :chunked} arm)
                (do (is (= rendered d10 d5)
                        (str arm "/bump-stride at B=" premise-b " must build every rendered
                             row at BOTH strides — that is the degeneracy, and it is why
                             this arm had no positive control"))
                    (is (= 1 (/ d5 d10))
                        (str arm "'s changed-set ratio is 1.00 — the manipulation moves
                             nothing this arm does")))
                (is (= 2 (/ d5 d10))
                    (str arm "/bump-stride does double its markup (" d10 " -> " d5 "),
                         which is the half of the premise that holds"))))
            (finally (mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 2 — the replacement discriminates on every arm
;; ---------------------------------------------------------------------------

(deftest every-arm-carries-a-prediction-bounded-away-from-one
  (testing "the acceptance criterion, as arithmetic: each arm's predicted
           factor is derived from `model/elements-for` and is bounded away
           from 1.00. Neither number is 2.00, because the `ul` does not
           double — which is `census_clock_arms/ctl-predicted`'s discipline"
    (doseq [arm arms/arm-ids]
      (let [p (ctl/predicted arm)
            {:keys [lo hi]} (ctl/band-of arm)]
        (is (> p 1.9) (str arm "'s predicted factor is " p))
        (is (< p 2.0) (str arm "'s prediction must be BELOW 2.00 — the chrome does
                           not double, and a control printing a round 2.00 is one
                           that did not derive it"))
        (is (> lo 1.0)
            (str arm "'s band floor " lo " must exclude 1.00, or the control admits
                 an instrument that saw nothing"))
        (is (< 1.471 lo)
            (str arm "'s band must still REFUSE the changed-set run this replaces,
                 whose worst round was 1.471. A band that admitted it would be a
                 widening wearing a new control's clothes"))))))

(deftest the-rendered-scale-control-doubles-the-work-on-every-arm
  (testing "THE REPLACEMENT'S DISCRIMINATING POWER, in exact integers and
           before any clock. On each arm the control's large page builds
           exactly twice the rows of markup its small page does — including
           `coarse` and `chunked`, where the changed-set control built the
           same number twice"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (doseq [arm arms/arm-ids]
        (let [[small large] (ctl/sizes arm)]
          (doseq [[tag size] [[:small small] [:large large]]]
            (let [{:keys [b w]} size
                  limit (ctl/rendered-of arm size)]
              (fresh! b w)
              (let [handle (mount/root! (mount/fresh-container!) frame-id
                                        [(arms/view-of arm) {}])]
                (try
                  (reseed! b w)
                  (let [built (markup-caused-by [:topo/bump-indexed limit ctl/stride])]
                    (is (= (ctl/markup-expected arm size) built)
                        (str arm "/" tag " must build the markup its arithmetic
                             predicts — this is the number the clock's prediction
                             rests on, and the control checks it on the page before
                             it starts a clock")))
                  (finally (mount/release! handle))))))
          (is (= 2 (/ (ctl/markup-expected arm large) (ctl/markup-expected arm small)))
              (str arm "'s control doubles the rows of markup a commit builds —
                   the property the changed-set control lacked on this arm")))))))

(deftest the-indexed-write-moves-only-what-it-claims
  (testing "the write the whole control rests on: every `stride`-th rendered row
           advances by one per commit and no other row moves. Read out of the
           DOM, on the same cells the control's probes address"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (let [b 100]
        (fresh! b m/window-size)
        (let [handle (mount/root! (mount/fresh-container!) frame-id
                                  [(arms/view-of :fine) {}])
              container (:container handle)]
          (try
            (reseed! b m/window-size)
            (dotimes [_ 3]
              (rt/dispatch! frame-id [:topo/bump-indexed b ctl/stride])
              (mount/settle!))
            (is (= (str (+ (:n (m/row ctl/changed-probe)) 3))
                   (ctl/n-at container ctl/changed-probe))
                "the changed probe advanced by exactly one per commit")
            (is (= (str (:n (m/row ctl/unchanged-probe)))
                   (ctl/n-at container ctl/unchanged-probe))
                "the unchanged probe did not move at all")
            (is (empty? (ctl/probe-misses container 3))
                "so the control's own read-back reports no miss on a page that
                 committed what it claims")
            (finally (mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 3 — the read-back bites
;; ---------------------------------------------------------------------------

(deftest the-control-refuses-a-page-whose-writes-do-not-commit
  (testing "THE NON-COMMIT WITNESS, and the audit obligation on this control
           (rf2-m6i0). The predecessor's `window!` said the read-back happened
           after the clock and then returned elapsed time without reading
           anything, so it could have adjudicated a no-op from event and
           subscription work alone.

           Here a real `fine` page is mounted and the control's OWN `window!`
           drives it — same twenty dispatches, same settles, same clock, same
           tally — with the write's `limit` at zero, so nothing commits. The
           tally must report every probe unverified and `assert-verified!`
           must throw"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (let [b 100]
        (fresh! b m/window-size)
        (let [handle (mount/root! (mount/fresh-container!) frame-id
                                  [(arms/view-of :fine) {}])
              container (:container handle)]
          (try
            (reseed! b m/window-size)
            (let [t    (lane/tally)
                  page {:frame-id frame-id :container container
                        :limit 0 :committed (atom 0)}
                  _ms  (ctl/window! t page)
                  v    (lane/tally-value t)]
              (is (= 2 (:writes v)) "both probes were read")
              (is (= 1 (:unverified v))
                  "the CHANGED probe must miss — twenty commits were counted and
                   the row's `n` never moved. The unchanged probe is correctly
                   satisfied, which is what makes the pair discriminate between
                   `nothing committed` and `everything committed`")
              (is (thrown? js/Error (lane/assert-verified! t "non-commit witness"))
                  "and the lane's ONE adjudication of a read-back turns that into
                   a throw, which the driver turns into a non-zero exit")
              (is (false? (:ok? (ctl/verdict :fine [1.99 1.99 1.99] v)))
                  "so a verdict over perfectly in-band, correctly-signed round
                   ratios still REFUSES on the read-back — the numbers describe a
                   page that did not commit what it claims")
              (is (re-find #"READ-BACK" (:why (ctl/verdict :fine [1.99 1.99 1.99] v)))
                  "and it says which of the three gates refused"))
            (finally (mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 4 — the verdict arithmetic, without a browser
;; ---------------------------------------------------------------------------

(def ^:private clean {:writes 120 :unverified 0})

(deftest the-verdict-refuses-on-band-sign-and-read-back
  (testing "three gates, any of which refuses. The sign gate is PR #7634's fix:
           a band alone admits a control certifying that MORE WORK READS
           FASTER, which rf2-7iqb5 captured live"
    (let [{:keys [predicted]} (ctl/band-of :fine)]
      (is (:ok? (ctl/verdict :fine (repeat 5 predicted) clean))
          "a run measuring exactly its prediction passes")
      (is (not (:ok? (ctl/verdict :fine [predicted predicted 1.40 predicted predicted] clean)))
          "ONE round below the band refuses the whole control — the strict rule,
           so a good round cannot vouch for a bad one")
      (is (not (:ok? (ctl/verdict :fine (repeat 5 0.5) clean)))
          "a negative-sign run refuses on the sign, not merely on the band")
      (is (re-find #"SIGN" (:why (ctl/verdict :fine (repeat 5 0.5) clean))))
      (is (not (:ok? (ctl/verdict :fine [] clean)))
          "and a control that measured nothing certifies nothing"))))

(deftest the-refused-changed-set-run-would-still-refuse-here
  (testing "the one test that says this control is not a widening. The run this
           replaces measured 1.331 / 1.387 / 1.424 / 1.471 / 1.325 and refused.
           Fed to the NEW verdict on every arm, it refuses again"
    (let [measured [1.331 1.387 1.424 1.471 1.325]]
      (doseq [arm arms/arm-ids]
        (is (not (:ok? (ctl/verdict arm measured clean)))
            (str "the replacement's band on " arm " must not retro-admit the
                 refusal it replaces"))))))
