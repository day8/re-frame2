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

  ## The window, and the fact that forced this shape

  A first pass timed each write inside `react-dom/flushSync`, exactly as
  the mount row does. **It measured nothing for two of the four arms**,
  and the DOM verification is what caught it: after every Freehand write
  the DOM still read `0`. A mounted Freehand `v/sub` does not repaint
  inside `flushSync` — its notification rides a MICROTASK, so React
  learns about it only after the current task's stack unwinds. Measured
  directly, and pinned by [[a-freehand-write-does-not-commit-inside-flushsync]]
  below:

  | how the write was driven | did the DOM change? |
  |---|---|
  | `flushSync(write)` | **no** |
  | `write` then a microtask then `flushSync(noop)` | yes, ~1 ms |
  | `write` then `setTimeout 0` | yes, ~2 ms |
  | `write` then `requestAnimationFrame` | yes, ~32 ms (two frames) |

  So every arm is timed through ONE shape, and it is the shape the
  slowest arm requires: **write, yield one microtask, force the
  substrate's own synchronous drain, stop the clock.** Floor and Reagent
  do not need the microtask and pay it anyway. Nothing is measured inside
  an `act` environment — `act` diverts work to its own queue and,
  measured, cost 600 ms a call.

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

(deftest a-freehand-write-does-not-commit-inside-flushsync
  (testing "The window's shape is not a style choice, and this is the
            observation that forced it: a mounted Freehand `v/sub` is NOT
            repainted by a write made inside `react-dom/flushSync`. The
            store is written and the subscription recomputes, but React
            learns about it on a microtask, so the commit lands after the
            flush has returned. Reagent and the floor commit in the same
            window. If this test ever starts failing, Freehand has gained
            a synchronous commit and the update row should be re-taken
            without the microtask yield."
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
            (is (not= "4242" (rows/cell-text container 0))
                "a write inside flushSync has NOT reached the DOM when the flush returns")
            (-> (js/Promise.resolve nil)
                (.then (fn [_]
                         ((:force! arm))
                         (is (= "4242" (rows/cell-text container 0))
                             "and one microtask plus a forced drain later, it has")
                         ((:unmount arm) mounted)
                         (.remove container)
                         (done)))
                (.catch (fn [e]
                          (is false (str "rejected: " e))
                          (.remove container)
                          (done))))))))))

(deftest the-fixture-is-what-it-says-it-is
  (testing "Arithmetic over the fixture, so a reader can check the row's
            premises without re-running anything."
    (is (= 300 rows/cells-n) "300 cells, so a broad write really does move every one")
    (is (= :interpreted (:lowering (v/describe fh/u-grid))) "one Freehand arm is interpreted")
    (is (= :compiled (:lowering (v/describe fhc/u-grid))) "and one is compiled")
    (is (fn? rg/u-grid) "the Reagent arm is an ordinary function returning Hiccup")
    (is (not= (rows/next-gen!) (rows/next-gen!))
        "no two writes ever install the same value")))
