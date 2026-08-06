(ns re-frame.bench.hicasso.arm1.ratom-activation-dom-cljs-test
  "**ARM 1, MOUNTED UNDER THE STOCK REAGENT ADAPTER**, repaints when its
  subscription moves (rf2-2kshh).

  The mounted counterpart to
  `re-frame.bench.hicasso.arm1.ratom-activation-cljs-test`, which proves
  the notification channel itself. This one proves the outcome the P0
  allocation row measured and could not get: a real React root, a real
  DOM press, and a readout that moves.

  THE REPRODUCTION. rf2-2rtt6.137 drove `:p0/write-all` at lad/hicasso on
  the reagent-subs segment and read the arm's DOM back. It was stale by
  exactly the number of writes the window had driven — every write since
  mount, on every rung, in every round — and its allocation column sat
  flat on the FLOOR's figure, which is what an arm with no subscription
  at all reads. `runtime/wire-cell!` never called
  `interop/activate-derived-value!`, so the cell's watch sat on a
  `reagent.ratom/Reaction` that had never captured its sources and could
  not fire. The arm painted once at mount and was deaf thereafter.

  WHY IT MOUNTS RATHER THAN DRIVING THE SEAM BY HAND. A hand-driven read
  goes through the cell's reaction whatever the notification channel did
  — `Reaction`'s non-reactive `-deref` re-runs the body raw — so a
  scenario that renders on demand reads CURRENT values and stays green
  straight through this bug. Only a repaint the DOM shows can tell a live
  channel from a dead one. That is also why the drain here is the
  segment's own (`reagent.core/flush`, then the empty `flushSync` that
  lets an already-scheduled sync-lane notification commit): the bead's
  falsified hypothesis was that the drain was at fault, and this file
  performs that very drain — it moves nothing at all until the cell is
  activated.

  Every other DOM suite in this arm installs the **UIx** adapter, whose
  React-hook spine is push-based from birth and for which the activate op
  is a routed no-op. This file is the arm's only mounted witness under a
  ratom host, which is the whole reason the defect survived here after
  being repaired in the shipping observation port (rf2-8cnxg).

  `-dom-cljs-test`, so `:browser-test` runs it against a real React DOM;
  under `:node-test` every DOM claim degrades to a stated skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::arm1-ratom-activation-dom)

;; ---------------------------------------------------------------------------
;; The page — one reader, one committed press site
;; ---------------------------------------------------------------------------

(defview readout
  "The boundary under test. Its `sub` read is an ordinary ambient-collector
  read, so the mounted occurrence repaints only if the commit's cell is
  actually notified."
  [_]
  [:output#readout (str (rt/sub [:hic/n]))])

(defview bump
  "A committed `:on-click` site — the operator's press, driven for real."
  [_]
  [:button#bump {:on-click [:hic/bump]} "+"])

(defview page [_]
  [:div#page [readout {}] [bump {}]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- skip! [why]
  (is true (str "a repaint claim needs a real React DOM — " why)))

(defn- fresh! []
  (lane/leave-act-environment!)
  (rt/reset-runtime!)
  (rf/reg-sub :hic/n (fn [db _] (:n db)))
  (rf/reg-event :hic/bump (fn [{:keys [db]} _] {:db (update db :n inc)}))
  (rf/reg-event :hic/set-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
  (rf/reg-event :hic/set-other (fn [{:keys [db]} [_ v]] {:db (assoc db :other v)}))
  (rf/make-frame {:id frame-id :initial-events [[:hic/set-n 0]]})
  (rt/reset-body-runs!)
  frame-id)

(defn- settle!
  "The reagent-subs segment's drain, in two lines rather than one.

  `reagent.core/flush` runs the reactions a write enqueued, which is what
  turns an activated node's recompute into the `notify-w` the cell's watch
  rides; `mount/settle!` is the empty `flushSync` that lets the sync-lane
  `onStoreChange` that raised commit. The bench spells the pair as
  `(flushSync (fn [] (r/flush)))`, which is the same two acts in one
  call."
  []
  (r/flush)
  (mount/settle!)
  nil)

(defn- write! [handle event]
  (rt/dispatch! (:frame handle) event)
  (settle!))

(defn- press! [handle]
  (.click (.querySelector (:container handle) "#bump"))
  (settle!))

(defn- readout-text [handle]
  (some-> (.querySelector (:container handle) "#readout") .-textContent))

;; ===========================================================================
;; 1 — the bead's reproduction, as a gate
;; ===========================================================================

(deftest a-mounted-boundary-repaints-under-the-reagent-adapter
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [page {}])]
        (try
          (is (= "0" (readout-text handle))
              "the first render read the seeded value — it always did; the
               bug was never the mount")
          (write! handle [:hic/bump])
          (is (= 1 (:n (rf/app-db-value frame-id)))
              "precondition — the dispatch LANDS. app-db moved, and it moved
               before the fix too")
          (is (= "1" (readout-text handle))
              "THE READING THAT WAS STALE: the mounted arm repainted from
               the write. Before the fix this stayed at its first render
               forever, which is why the P0 row read the FLOOR's allocation
               figure — an arm that never re-renders allocates nothing per
               read")
          (write! handle [:hic/bump])
          (is (= "2" (readout-text handle))
              "0 → 1 → 2: the channel stays armed rather than firing once")
          (finally
            (mount/release! handle)))))))

;; ===========================================================================
;; 2 — a real press, which is what the operator drives
;; ===========================================================================

(deftest a-real-press-repaints-the-mounted-boundary
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [page {}])]
        (try
          (is (= "0" (readout-text handle)))
          (press! handle)
          (is (= 1 (:n (rf/app-db-value frame-id)))
              "the committed `:on-click` dispatched into the bound frame")
          (is (= "1" (readout-text handle))
              "and the sibling reader repainted off the same movement — the
               whole round trip a page performs, with nothing hand-driven")
          (finally
            (mount/release! handle)))))))

;; ===========================================================================
;; 3 — the adversarial companion: activation must not make it chatty
;; ===========================================================================

(deftest a-write-that-moved-nothing-repaints-no-boundary
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [page {}])]
        (try
          (is (= "0" (readout-text handle)))
          (rt/reset-body-runs!)
          (write! handle [:hic/set-n 0])
          (is (zero? (rt/body-runs))
              "an equal re-write moved nothing, so no body re-ran")
          (write! handle [:hic/set-other :noise])
          (is (zero? (rt/body-runs))
              "…and neither did a write to a key this sub never reads")
          (is (= "0" (readout-text handle))
              "the DOM is untouched by either write")

          (testing "positive control — the silences above are silences, not
                    a dead channel that would make this whole row vacuous"
            (write! handle [:hic/set-n 6])
            (is (pos? (rt/body-runs)))
            (is (= "6" (readout-text handle))))
          (finally
            (mount/release! handle)))))))
