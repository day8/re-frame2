(ns re-frame.freehand.bench.b6-update-dom-cljs-test
  "B6's UPDATE row — the half of the Freehand/Reagent question that
  actually matters, and the half the predecessor report returned a null
  result on.

  `docs/design/freehand/studio/compiled-tier-browser-worth-it.md`
  measured a state change through to committed DOM at 0.2–0.5 ms p50 on
  every arm of every witness: indistinguishable at the timer's
  resolution, and therefore no answer at all. Reagent's whole design
  point is fine-grained ratom reactivity on UPDATE, so a mount-only
  comparison would flatter whichever substrate constructs elements
  fastest while saying nothing about the thing Reagent exists for.

  ## The window, and the defect that forced this shape

  A first pass timed each write inside `react-dom/flushSync`, exactly as
  the mount row does. **It measured nothing for two of the four arms**,
  and the DOM verification is what caught it: after every Freehand write
  the DOM still read `0`. A mounted Freehand `v/sub` was not repainted
  inside `flushSync` — its notification rode a MICROTASK, so React learnt
  about it only after the current task's stack unwound. Measured directly:

  | how the write was driven | did the DOM change? | since rf2-w2m25 |
  |---|---|---|
  | `flushSync(write)` | **no** | **yes** |
  | `write` then a microtask then `flushSync(noop)` | yes, ~1 ms | yes |
  | `write` then `setTimeout 0` | yes, ~2 ms | yes |
  | `write` then `requestAnimationFrame` | yes, ~32 ms (two frames) | yes |

  **That was a product defect, and it is fixed.** rf2-w2m25 gave the
  ViewCell pending window a second closer that React can see
  (`re-frame.freehand.checkpoint`), so a write inside `flushSync` now
  commits before the flush returns. The right-hand column is what
  [[a-freehand-write-commits-inside-flushsync]] pins below, and it is
  where a regression in EITHER direction shows up.

  The window this row is measured through is still the shape the defect
  forced: **write, yield one microtask, force the substrate's own
  synchronous drain, stop the clock.** Floor and Reagent do not need the
  microtask and pay it anyway. Every arm is timed through that ONE shape,
  so the comparison remains sound.

  **The yield stays, and that is measured rather than assumed.** This
  docstring used to say the row was re-takeable as `flushSync(write)` on
  all four arms, dropping a microtask boundary every arm pays.
  `rf2-vxfjt` ran the ablation — `b6_yield_app`, six rounds, both rows,
  every window gated on the per-write DOM read-back — and it says two
  things.

  - **Deleting the microtask and changing nothing else is not available.**
    Both Freehand arms then fail the read-back on *every* write: 66 of 66
    per arm on the broad row, 1,320 of 1,320 per arm on the narrow one,
    against 0 on the floor, Reagent and the positive-control arms. The
    write sits outside any flush, so `rf2-w2m25`'s closer never fires and
    the empty `flushSync` after it has nothing to commit.
  - **The window that DOES verify buys nothing.** `flushSync(write)` then
    the arm's drain verifies on all four arms, and reads 3.5 ms
    [3.4–4.7] for interpreted Freehand against the published window's 3.5
    [3.2–4.3] — overlapping ranges, indistinguishable. The same work
    moves out of the gap leg and into the write leg. The extra React
    commit per render batch that `rf2-w2m25` arms is inside that window
    and is not separable from it at this instrument's resolution.

  A caution worth carrying: on the NARROW row the invalid yield-free
  window is *faster* and its range still overlaps the published one, so
  the clock alone would have accepted it. **Only the DOM read-back caught
  it.**

  Nothing is measured inside an `act` environment — `act` diverts work to
  its own queue and, measured, cost 600 ms a call.

  A second instrument fault the same verification caught: an EMPTY
  `flushSync` flushes only React's sync lane, so the floor arm's
  `root.render` — scheduled at the default lane — has to be issued INSIDE
  the flush. Until it was, 80 of 320 floor samples ended with the cell
  still holding its old value, and every other arm's ratio was inflated
  by the difference. Both faults are the same lesson: **verify at the DOM,
  inside the window, or the fastest arm is the one that did nothing.**

  ## Two writes, because they ask different questions

    - **BROAD** — one write every one of 300 cells reads. Every cell
      re-renders. This prices re-render THROUGHPUT, where fine grain buys
      nothing because all the work is genuinely required.
    - **NARROW** — one write ONE cell reads. This prices LOCALISATION,
      and it is where fine grain either shows up or does not. It is well
      above the timer under this window, which is what replaces the
      predecessor report's null result.

  ## The numbers this file takes are not the published ones

  Same reason as the mount row: this build has `goog.DEBUG true`, so the
  Spec 009 instrumentation seam, schema validation and trace emission are
  all live on exactly the path this row measures, and Reagent has no
  counterpart to any of them. The published reading is taken by
  [[re-frame.freehand.bench.b6-prod-app]] under `:advanced`.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["react-dom" :as react-dom]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.freehand :as v]
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.b6-reagent :as rg]
            [re-frame.freehand.bench.b6-rows :as rows]
            [re-frame.freehand.bench.b6-witnesses :as fh]
            [re-frame.freehand.bench.b6-witnesses-compiled :as fhc]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.test-support :as test-support]))

(def ^:private rounds 3)
(def ^:private sampling {:warmup 3 :samples 8})

(def ^:private runtime-fixture
  ;; The UIx adapter, because Freehand's `v/sub` needs a non-ratom
  ;; substrate (rf2-8cnxg). The Reagent arm is untouched by the choice: a
  ;; `reagent.core/atom` and a `cursor` over it are pure Reagent and know
  ;; nothing about which re-frame adapter is installed, which is exactly
  ;; what lets both substrates run on one page in one interleaved run.
  ;; `:async? true` selects the `{:before :after}` RETURN SHAPE only.
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(use-fixtures :each
  {:before (fn []
             (root/reset-registry!)
             (fr/reset-boundaries!)
             ((:before runtime-fixture))
             (h/leave-act-environment!))
   :after  (fn []
             ((:after runtime-fixture))
             (root/reset-registry!)
             (fr/reset-boundaries!))})

;; ---------------------------------------------------------------------------
;; The row
;; ---------------------------------------------------------------------------

(defn- row! [kind doc done]
  (let [mounts (rows/mount-update-arms! (rows/make-update-arms))]
    (-> (rows/write-and-compare! mounts)
        (.then (fn [{:keys [ids before after]}]
                 (is (apply = after)
                     (str (name kind)
                          ": every arm's page agrees after one broad write, compared as
                           canonical DOM"
                          (when-not (apply = after)
                            (str " — arms " (pr-str ids) " produced "
                                 (count (distinct after)) " distinct pages"))))
                 (doseq [[id b a] (map vector ids before after)]
                   (is (not= b a)
                       (str (name kind) " / " (name id)
                            ": the write actually changed this arm's DOM")))
                 (rows/measure-update! mounts kind doc rounds sampling)))
        (.then (fn [{:keys [bad norm summary samples-per-round]}]
                 (is (zero? bad)
                     (str (name kind)
                          ": every measured write was verified AT THE DOM before the clock
                           stopped — " bad " of " (* rounds samples-per-round)
                          " did not land"))
                 (is (= rounds (count norm)) "every round produced a reading")
                 (is (every? (fn [{:keys [p50]}] (pos? (:floor p50))) norm)
                     "the floor arm took measurable time in every round")
                 (is (every? #(pos? (:mean %)) (vals summary))
                     "every arm produced a positive ratio")
                 nil))
        (.catch (fn [e] (is false (str (name kind) " row rejected: " e))))
        (.finally (fn [] (rows/release-update-arms! mounts) (done))))))

(deftest broad-update-throughput
  (testing "One write that every one of 300 cells reads, so every cell
            re-renders. This prices re-render throughput, where fine grain
            buys nothing because all the work is genuinely required. Every
            sample is verified at the DOM before its clock stops."
    (if-not (h/browser?)
      (is true "a real browser is required — the browser job runs this row")
      (async done (row! :broad "one write that all 300 cells read" done)))))

(deftest narrow-update-localisation
  (testing "One write that exactly ONE of 300 cells reads. This is where
            fine-grained reactivity either shows up or does not: the floor
            re-renders 300 cells to move one, and a substrate that
            re-renders one should beat it outright. Under this window a
            single narrow write is well above the timer, which is what
            replaces the predecessor report's null result."
    (if-not (h/browser?)
      (is true "a real browser is required — the browser job runs this row")
      (async done (row! :narrow "one write exactly one of 300 cells reads" done)))))

;; ===========================================================================
;; Non-vacuity
;; ===========================================================================

(deftest a-freehand-write-commits-inside-flushsync
  (testing "This row was the observation that forced the window's shape,
            and it now records the opposite fact. It USED to assert that a
            mounted Freehand `v/sub` is NOT repainted by a write made
            inside `react-dom/flushSync` — the store written, the
            subscription recomputed, and React told on a microtask, so the
            commit landed after the flush had returned while Reagent and
            the floor both committed in the same window.

            rf2-w2m25 fixed that: the ViewCell pending window gained a
            SECOND closer React can see, so the write commits inside the
            flush. The assertion is flipped rather than deleted or
            loosened, because it is the only coverage of this case in the
            bench and it has to catch a regression in EITHER direction —
            a Freehand arm that silently stopped committing here is
            exactly the fault that cost this row a whole measurement pass.

            Both legs are asserted: the flush window (the new guarantee)
            and the microtask window (the shape this row is still measured
            through, so its non-vacuity does not rest on the new one)."
    (if-not (h/browser?)
      (is true "a real browser is required — the browser job runs this row")
      (async done
        (let [arm       (first (filter #(= :freehand-interpreted (:id %))
                                       (rows/make-update-arms)))
              container (js/document.createElement "div")]
          (.appendChild js/document.body container)
          (let [mounted ((:mount arm) container)]
            (is (= "0" (rows/cell-text container 0)) "the grid mounted and the cell reads 0")
            (react-dom/flushSync (fn [] ((:write! arm) 0 4242)))
            (is (= "4242" (rows/cell-text container 0))
                "a write inside flushSync HAS reached the DOM when the flush
                 returns — no yield of any kind between the two")
            ;; The second leg: the window this row is actually measured
            ;; through — write, yield one microtask, force the drain — still
            ;; carries a write to the DOM. This is the shape whose
            ;; non-vacuity matters, and rf2-vxfjt established that it is
            ;; the only one available: the same window with the microtask
            ;; deleted fails its read-back on every Freehand write.
            ((:write! arm) 0 8484)
            (-> (js/Promise.resolve nil)
                (.then (fn [_]
                         ((:force! arm))
                         (is (= "8484" (rows/cell-text container 0))
                             "and the measured window — write, one microtask,
                              forced drain — still lands its write too")
                         ;; ASYMMETRIC, so it stays put: the rejection arm never
                         ;; unmounted this root, and the detach both arms DID
                         ;; share rides the single trailing step below.
                         ((:unmount arm) mounted)))
                ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done`
                ;; runs the whole remainder of the run synchronously, so a
                ;; `.catch` downstream of it would claim a later namespace's
                ;; throw as this row's and fire `done` a second time.
                (.catch (fn [e] (is false (str "rejected: " e)) nil))
                (.then (fn [_] (.remove container) (done))))))))))

(deftest the-fixture-is-what-it-says-it-is
  (testing "Arithmetic over the fixture, so a reader can check the row's
            premises without re-running anything."
    (is (= 300 rows/cells-n) "300 cells, so a broad write really does move every one")
    (is (= :interpreted (:lowering (v/describe fh/u-grid))) "one Freehand arm is interpreted")
    (is (= :compiled (:lowering (v/describe fhc/u-grid))) "and one is compiled")
    (is (fn? rg/u-grid) "the Reagent arm is an ordinary function returning Hiccup")
    (is (not= (rows/next-gen!) (rows/next-gen!))
        "no two writes ever install the same value")))
