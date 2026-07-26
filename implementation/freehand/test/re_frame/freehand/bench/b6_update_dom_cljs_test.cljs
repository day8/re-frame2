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
  and the parity gate is what caught it: after every Freehand write the
  DOM still read `0`. A mounted Freehand `v/sub` does not repaint inside
  `flushSync` — its notification rides a MICROTASK, so the store is
  written, the subscription is recomputed, and React is told about it
  only after the current task's stack unwinds. Measured directly:

  | how the write was driven | did the DOM change? |
  |---|---|
  | `flushSync(write)` | **no** |
  | `write` then a microtask then `flushSync(noop)` | yes, ~1 ms |
  | `write` then `setTimeout 0` | yes, ~2 ms |
  | `write` then `requestAnimationFrame` | yes, ~32 ms (two frames) |

  So every arm is timed through ONE shape, and it is the shape the slowest
  arm requires: **write, yield one microtask, force the substrate's own
  synchronous drain, stop the clock.** Floor and Reagent do not need the
  microtask and pay it anyway, which costs them a constant every arm
  pays. Nothing here is measured inside an `act` environment — `act`
  diverts work to its own queue and, measured, cost 600 ms a call.

  ## Two writes, because they ask different questions

    - **BROAD** — one write every one of 300 cells reads. Every cell
      re-renders. This prices re-render THROUGHPUT, where fine grain buys
      nothing because all the work is genuinely required.
    - **NARROW** — one write ONE cell reads. This prices LOCALISATION,
      and it is where fine grain either shows up or does not. It is well
      above the timer under this window, which is what replaces the
      predecessor report's null result.

  ## The arms, and what each writes

  Every arm is driven by a DIRECT STATE INSTALL through its own public
  write and its own documented synchronous drain, so no arm is charged
  for an event queue another arm does not have:

    - `floor` — no substrate. The whole root is re-rendered top down. On
      a broad write that is the same work everyone does; on a narrow one
      it is 300 re-renders to move one cell, which is what an
      application with no reactive substrate really pays. **The update
      floor is therefore NOT a lower bound**, and a fine-grained arm
      beating it — a ratio under 1.0 — is the result, not a fault.
    - `freehand-interpreted` / `freehand-compiled` — `v/sub` per cell,
      driven by `frame/replace-app-db!` on that arm's OWN frame, so the
      two Freehand arms never re-render into each other's window.
    - `reagent` — `r/cursor` per cell over a `reagent.core/atom`, written
      with `swap!` and drained with `reagent.core/flush`.

  What is excluded, identically from every arm, is the event-dispatch
  leg: a re-frame application adds one `dispatch-sync` on top of any of
  these, and adds it to Reagent and Freehand alike.

  ## Gates

  Every measured write is verified AT THE DOM: the cell it wrote must
  hold the value it wrote by the time the clock stops. A write that
  silently did nothing would be the fastest arm in any benchmark, and
  this row exists because the first version of it was exactly that.
  Canonical-DOM equality across all four arms is checked before and
  after the whole run on top.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.bench.b6-floor :as floor]
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.b6-reagent :as rg]
            [re-frame.freehand.bench.b6-witnesses :as fh]
            [re-frame.freehand.bench.b6-witnesses-compiled :as fhc]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.bench.provenance :as prov]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; re-frame registrations — FIRST, so this ns's own fixture baseline carries
;; them. `make-reset-runtime-fixture` pins the registrar as it stood when the
;; fixture was BUILT; a registration made after that line is wiped before
;; every test in this file.
;; ---------------------------------------------------------------------------

(rf/reg-sub :b6/cell (fn [db [_ i]] (get-in db [:cells i])))
(rf/reg-event :b6/seed (fn [_ [_ n]] {:db {:cells (vec (repeat n 0))}}))

;; ---------------------------------------------------------------------------
;; Fixture parameters
;; ---------------------------------------------------------------------------

(def ^:private cells-n 300)
(def ^:private rounds 5)
(def ^:private sampling {:warmup 4 :samples 12})
(def ^:private total-samples (+ (:warmup sampling) (:samples sampling)))

(defonce ^:private gen-seq (atom 1000))
(defn- next-gen! [] (swap! gen-seq inc))

;; ---------------------------------------------------------------------------
;; The arms
;; ---------------------------------------------------------------------------

(defn- floor-arm
  "The floor's `force!` renders INSIDE the `flushSync`, and that is not a
  convenience. `root.render` called outside a React event schedules at
  React's default lane, and an EMPTY `flushSync` flushes only the sync
  lane — so a floor arm that rendered in `write!` and flushed in `force!`
  would have its commit land outside the measured window entirely. The
  DOM verification caught exactly that: 80 of 320 floor samples ended
  with the cell still holding its old value. Freehand and Reagent are
  unaffected, because a `useSyncExternalStore` notification and a
  `reagent.core/flush` both put their work on the sync lane."
  []
  (let [state (atom (vec (repeat cells-n 0)))
        rt    (volatile! nil)]
    {:id      :floor
     :mount   (fn [container]
                (let [r (react-dom-client/createRoot container)]
                  (vreset! rt r)
                  (react-dom/flushSync (fn [] (.render r (floor/u-grid @state))))
                  r))
     :write!  (fn [i val]
                (if (= i :all)
                  (reset! state (vec (repeat cells-n val)))
                  (swap! state assoc i val)))
     :force!  (fn [] (react-dom/flushSync
                       (fn [] (.render ^js @rt (floor/u-grid @state)))))
     :unmount (fn [r] (react-dom/flushSync (fn [] (.unmount r))))}))

(defn- freehand-arm [id view]
  (let [fid (keyword "b6-update" (name id))]
    {:id      id
     :mount   (fn [container]
                (react-dom/flushSync
                  (fn [] (v/mount [view {:n cells-n}] container
                                  {:frame         {:id fid :initial-events [[:b6/seed cells-n]]}
                                   :disambiguator (keyword "b6u" (name id))}))))
     :write!  (fn [i val]
                (if (= i :all)
                  (frame/replace-app-db! fid {:cells (vec (repeat cells-n val))})
                  (frame/replace-app-db!
                    fid (update (frame/frame-app-db-value fid) :cells assoc i val))))
     ;; Freehand's notification has already been queued by the write; the
     ;; empty flushSync is what makes React commit it in this window rather
     ;; than on its own scheduler two milliseconds later.
     :force!  (fn [] (react-dom/flushSync (fn [] nil)))
     :unmount (fn [mounted] (react-dom/flushSync (fn [] (v/unmount! mounted))))}))

(defn- reagent-arm []
  {:id      :reagent
   :mount   (fn [container]
              (reset! rg/cells (vec (repeat cells-n 0)))
              (let [rt (rdc/create-root container)]
                (react-dom/flushSync (fn [] (rdc/render rt [rg/u-grid cells-n])))
                rt))
   :write!  (fn [i val]
              (if (= i :all)
                (reset! rg/cells (vec (repeat cells-n val)))
                (swap! rg/cells assoc i val)))
   ;; `reagent.core/flush` is Reagent's own documented synchronous render
   ;; drain — the counterpart of the empty `flushSync` the other arms use.
   :force!  (fn [] (react-dom/flushSync (fn [] (r/flush))))
   :unmount (fn [rt] (react-dom/flushSync (fn [] (rdc/unmount rt))))})

(defn- make-arms []
  [(floor-arm)
   (freehand-arm :freehand-interpreted fh/u-grid)
   (freehand-arm :freehand-compiled fhc/u-grid)
   (reagent-arm)])

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

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
;; One timed write
;; ---------------------------------------------------------------------------

(defn- cell-text
  [container i]
  (some-> (.querySelector container (str "[data-i=\"" i "\"]")) (.-textContent)))

(defn- timed-write!
  "Write, yield ONE microtask, force the arm's own synchronous drain, stop
  the clock — then VERIFY at the DOM that the cell holds what was written.
  Answers a promise of `{:ms … :ok? …}`."
  [{:keys [arm container]} i val]
  (let [t0 (m/now-ms)]
    ((:write! arm) i val)
    (-> (js/Promise.resolve nil)
        (.then (fn [_]
                 ((:force! arm))
                 (let [ms    (- (m/now-ms) t0)
                       probe (if (= i :all) 0 i)]
                   {:ms  ms
                    :ok? (= (str val) (cell-text container probe))
                    :ok2? (or (not= i :all)
                              (= (str val) (cell-text container (dec cells-n))))}))))))

;; ---------------------------------------------------------------------------
;; Rounds
;; ---------------------------------------------------------------------------

(defn- chain
  "Fold `xs` into a serial promise chain, threading an accumulator."
  [init xs f]
  (reduce (fn [p x] (.then p (fn [acc] (f acc x)))) (js/Promise.resolve init) xs))

(defn- round!
  "One round, arms interleaved at the sample level with the order rotating
  on the sample index."
  [mounts kind]
  (let [k (count mounts)]
    (chain {:readings (zipmap (map #(:id (:arm %)) mounts) (repeat []))
            :bad      0}
           (for [s (range total-samples) j (range k)] [s j])
           (fn [acc [s j]]
             (let [mnt (nth mounts (mod (+ j s) k))
                   val (next-gen!)
                   i   (if (= kind :broad) :all (mod val cells-n))]
               (-> (timed-write! mnt i val)
                   (.then (fn [{:keys [ms ok? ok2?]}]
                            (cond-> acc
                              (not (and ok? ok2?)) (update :bad inc)
                              (>= s (:warmup sampling))
                              (update-in [:readings (:id (:arm mnt))] conj ms))))))))))

(defn- seed-all! [mounts]
  (chain nil mounts (fn [_ mnt] (-> (timed-write! mnt :all 0) (.then (fn [_] nil))))))

(defn- canon-of [mounts] (mapv (fn [m] (h/canonical (:container m))) mounts))

(defn- assert-agreement!
  [mounts label]
  (let [before (canon-of mounts)
        val    (next-gen!)]
    (-> (chain nil mounts (fn [_ mnt] (-> (timed-write! mnt :all val) (.then (fn [_] nil)))))
        (.then (fn [_]
                 (let [after (canon-of mounts)
                       ids   (mapv #(:id (:arm %)) mounts)]
                   (is (apply = after)
                       (str label ": every arm's page agrees after the write, compared as
                             canonical DOM"
                            (when-not (apply = after)
                              (str " — arms " (pr-str ids) " produced "
                                   (count (distinct after)) " distinct pages"))))
                   (doseq [[id b a] (map vector ids before after)]
                     (is (not= b a)
                         (str label " / " (name id)
                              ": the write actually changed this arm's DOM")))
                   nil))))))

;; ---------------------------------------------------------------------------
;; The rows
;; ---------------------------------------------------------------------------

(defn- mount-all! [arms]
  (mapv (fn [a]
          (let [c (js/document.createElement "div")]
            (.appendChild js/document.body c)
            {:arm a :container c :handle ((:mount a) c)}))
        arms))

(defn- release-all! [mounts]
  (doseq [{:keys [arm handle container]} mounts]
    (try ((:unmount arm) handle) (catch :default _ nil))
    (.remove container)))

(defn- measure! [kind doc done]
  (let [arms   (make-arms)
        mounts (mount-all! arms)]
    (-> (assert-agreement! mounts (str (name kind) " (before)"))
        (.then (fn [_]
                 (chain [] (range rounds)
                        (fn [acc _]
                          (-> (seed-all! mounts)
                              (.then (fn [_] (round! mounts kind)))
                              (.then (fn [rd] (conj acc rd))))))))
        (.then (fn [rds]
                 (let [bad  (reduce + (map :bad rds))
                       norm (mapv #(h/normalise (:readings %) :floor) rds)
                       summ (h/across-rounds (mapv :ratio norm))]
                   (is (zero? bad)
                       (str (name kind) ": every measured write was verified AT THE DOM "
                            "before the clock stopped — " bad " of "
                            (* rounds total-samples (count arms))
                            " did not land"))
                   (h/publish!
                     (str "update / " (name kind))
                     {:benchmark    (keyword "B6" (str "update-" (name kind)))
                      :doc          doc
                      :revision     (prov/detect-revision)
                      :build        (prov/detect-build)
                      :host         (prov/detect-host)
                      :fixture      {:cells         cells-n
                                     :kind          kind
                                     :arms          (mapv :id arms)
                                     :adapter       :rf.adapter/uix
                                     :reagent-version "2.0.1"
                                     :unverified-writes bad
                                     :measurement-method
                                     (str "per write: t0; the arm's own state install; ONE "
                                          "microtask yield; the arm's own synchronous drain "
                                          "(empty react-dom/flushSync for the floor and both "
                                          "Freehand arms, reagent.core/flush inside one for "
                                          "Reagent); t1 — then the written cell is read back "
                                          "out of the DOM and the sample is rejected if it "
                                          "does not hold the written value. The microtask is "
                                          "STRUCTURAL: a mounted Freehand v/sub does not "
                                          "repaint inside flushSync, its notification rides a "
                                          "microtask, and a first pass that timed inside "
                                          "flushSync measured Freehand writes that never "
                                          "reached the DOM at all. Arms interleaved at the "
                                          "sample level, order rotating on the sample index; "
                                          (str rounds) " rounds of " (:warmup sampling)
                                          " warmup + " (:samples sampling) " samples; every "
                                          "figure a ratio to the floor measured in that same "
                                          "round. The UPDATE floor is a plain top-down React "
                                          "re-render and is NOT a lower bound")}
                      :sampling     sampling
                      :baseline     {:kind      :cross-substrate
                                     :reference {:arm  :floor
                                                 :note "plain top-down React re-render, no substrate"}}
                      :per-round    {:p50 (mapv :p50 norm) :ratio (mapv :ratio norm)}
                      :ratio-to-floor summ
                      :status       :evidence})
                   (is (= rounds (count norm)) "every round produced a reading")
                   (is (every? (fn [{:keys [p50]}] (pos? (:floor p50))) norm)
                       "the floor arm took measurable time in every round")
                   (is (every? #(pos? (:mean %)) (vals summ))
                       "every arm produced a positive ratio")
                   nil)))
        (.catch (fn [e] (is false (str (name kind) " row rejected: " e))))
        (.finally (fn [] (release-all! mounts) (done))))))

(deftest broad-update-throughput
  (testing "One write that every one of 300 cells reads, so every cell
            re-renders. This prices re-render throughput, where fine grain
            buys nothing because all the work is genuinely required. Every
            sample is verified at the DOM before its clock stops."
    (if-not (h/browser?)
      (is true "a real browser is required — the browser job runs this row")
      (async done (measure! :broad "one write that all 300 cells read" done)))))

(deftest narrow-update-localisation
  (testing "One write that exactly ONE of 300 cells reads. This is where
            fine-grained reactivity either shows up or does not: the floor
            re-renders 300 cells to move one, and a substrate that
            re-renders one should beat it outright. Under this window a
            single narrow write is well above the timer, which is what
            replaces the predecessor report's null result."
    (if-not (h/browser?)
      (is true "a real browser is required — the browser job runs this row")
      (async done (measure! :narrow "one write exactly one of 300 cells reads" done)))))

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
        (let [arm       (freehand-arm :freehand-interpreted fh/u-grid)
              container (js/document.createElement "div")]
          (.appendChild js/document.body container)
          (let [mounted ((:mount arm) container)]
            (is (= "0" (cell-text container 0)) "the grid mounted and the cell reads 0")
            (react-dom/flushSync (fn [] ((:write! arm) 0 4242)))
            (is (not= "4242" (cell-text container 0))
                "a write inside flushSync has NOT reached the DOM when the flush returns")
            (-> (js/Promise.resolve nil)
                (.then (fn [_]
                         ((:force! arm))
                         (is (= "4242" (cell-text container 0))
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
    (is (= 300 cells-n) "300 cells, so a broad write really does move every one")
    (is (= :interpreted (:lowering (v/describe fh/u-grid))) "one Freehand arm is interpreted")
    (is (= :compiled (:lowering (v/describe fhc/u-grid))) "and one is compiled")
    (is (fn? rg/u-grid) "the Reagent arm is an ordinary function returning Hiccup")
    (is (not= (next-gen!) (next-gen!)) "no two writes ever install the same value")))
