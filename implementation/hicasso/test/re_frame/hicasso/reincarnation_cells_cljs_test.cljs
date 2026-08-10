(ns re-frame.hicasso.reincarnation-cells-cljs-test
  "SAME-PUBLIC-ID FRAME REINCARNATION, COMMITTED SIDE — the held cell, the
  number React re-reads, and the repair that lands before control returns
  to the event loop (rf2-hic-013, rescheduled by rf2-2l17).

  `reincarnation_routing_cljs_test` takes the transition on the WRITE
  path. This file takes it on the committed READ path, where the delayed
  operation is the runtime's own: frame teardown disposes the frame's
  cached reactions, `re-frame.hicasso.impl.collector/invalidate-cell!`
  drops the reference SYNCHRONOUSLY and defers rebuilding the durable
  attachment to a MICROTASK — and that deferred callback has to decide,
  at the checkpoint, which incarnation it is rebuilding against. Its own
  docstring names the case: *a frame destruction, whose teardown disposes
  the frame's cached reactions — including across a same-id
  reincarnation*.

  **The deferral used to be a `setTimeout 0`, and rf2-2l17 moved it.**
  Design law React 3 requires a render/commit tear to be corrected
  before visible paint, and a later task carries no such ordering
  promise. So every wait below is [[re-frame.hicasso.checkpoint-support/drain-checkpoint]]
  rather than a timer: it asserts the repair completed *inside the
  current microtask checkpoint*, which is the property the law needs and
  a duration cannot express. `reincarnation_paint_dom_cljs_test` is the
  same claim read in a real browser, where the paint is.

  ## Why the DOM cannot see this and `snapshot-of` can

  A committed boundary re-renders when React's `useSyncExternalStore`
  decides the store moved, which it decides by comparing the number
  `getSnapshot` returns. [[re-frame.hicasso.impl.collector/snapshot-of]]
  is that exact number, and reading it is, in the collector's own words,
  *the witness's way of performing React's own `checkIfSnapshotChanged`
  without a browser*.

  Across a same-id reincarnation that changed the value, **the number
  does not move** — because it is built on
  `re-frame.hicasso.impl.generation/commit-basis`, whose three terms are
  all structurally blind to the transition. So at the instant the
  successor seats, React has been told nothing, the committed boundary
  still holds the predecessor's value, and no re-render is scheduled. At
  the microtask checkpoint the deferred repair fires and the number moves
  — still inside the task that seated the successor.

  That window is the whole fault, and it is invisible to a rendered-markup
  assertion in both directions: read the DOM inside the window and a
  re-render has not happened yet, so a naive harness sees the stale value
  and blames its own timing; read it after the window and the repair has
  already run, so it sees the correct value and reports green. Only the
  snapshot number, the notification count and the cell's reaction
  reference distinguish *repaired* from *never broken* — at this seam.
  Where the value can actually reach a screen, the DOM read at the first
  rendering opportunity distinguishes them too, and that is what the
  `_paint_dom_` companion is for.

  ## The harness is the commit seam, not a browser

  [[re-frame.hicasso.impl.collector/commit-boundary!]] exists for exactly
  this: hand a read set and a notifier to the same `subscribe` closure
  `useSyncExternalStore` would call, and hold the same cleanup React would
  hold. The cell table, the reader memberships and the deferred repair are
  the real ones."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::reincarnation-cells)

(rf/reg-event :reinc-cell/seed (fn [_ [_ who]] {:db {:who who}}))
(rf/reg-sub   :reinc-cell/who  (fn [db _] (:who db)))

;; `:async? true` — `cljs.test` hard-errors on a fn-form fixture in a namespace
;; containing an `(async done …)` test, and the deferred repair under witness
;; here is only observable across a promise turn.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(def ^:private sub-key [frame-id [:reinc-cell/who]])

(defn- incarnate! [who]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:reinc-cell/seed who]))
  (frame/frame-incarnation-token frame-id))

(defn- body [_props] (collector/sub [:reinc-cell/who]))

(defn- render+commit!
  "Run the body under the generation fence and take React's place at the
  commit seam. Answers the entry, the notification counter and the
  release fn."
  []
  (let [value    (collector/render-body frame-id body {})
        entry    (collector/last-reads)
        notified (volatile! 0)
        release  (collector/commit-boundary! entry (fn [] (vswap! notified inc)))]
    {:value value :entry entry :notified notified :release release}))

(def ^:private at-the-checkpoint
  "Every wait in this file. See
  [[re-frame.hicasso.checkpoint-support/at-the-checkpoint]] — it is the
  30 ms timer's replacement, and the reason for the replacement is that a
  duration was green for both schedulings."
  support/at-the-checkpoint)

;; ---------------------------------------------------------------------------
;; 1. React's own change-detection number ties across the transition
;; ---------------------------------------------------------------------------

(deftest the-snapshot-does-not-move-when-the-successor-seats
  (async done
    (incarnate! "A")
    (let [{:keys [value entry notified release]} (render+commit!)
          snapshot-a (collector/snapshot-of entry)]
      (is (= "A" value) "the committed boundary read the predecessor's value")
      (is (some? (inventory/cell-reaction sub-key))
          "and it holds a live cell for the key")

      ;; NEGATIVE CONTROL, taken FIRST so the instrument is proven live before
      ;; it is asked to report a tie. An ordinary write inside the incarnation
      ;; must move the number and notify the boundary.
      (rf/with-frame frame-id (rf/dispatch-sync [:reinc-cell/seed "A2"]))
      (is (> (collector/snapshot-of entry) snapshot-a)
          "an ordinary in-incarnation write MOVES the snapshot — so a tie
           below is the transition's property, not a dead instrument")
      (is (= 1 @notified) "and notifies the committed boundary exactly once")

      (let [snapshot-before (collector/snapshot-of entry)
            notified-before @notified]
        (rf/destroy-frame! frame-id)
        (rf/make-frame {:id frame-id})
        (rf/with-frame frame-id (rf/dispatch-sync [:reinc-cell/seed "B"]))

        (testing "at the instant the successor seats, React has been told
                  nothing — the number it re-reads is unchanged and no
                  notification has fired, so a committed boundary goes on
                  showing the predecessor's value"
          (is (= snapshot-before (collector/snapshot-of entry))
              "the snapshot TIES across the reincarnation")
          (is (= notified-before @notified) "and no re-render was scheduled"))

        (testing "the cell's reaction reference was dropped synchronously by
                  the teardown, which is what makes the read fall back to the
                  cold probe rather than deref a retired computation"
          (is (nil? (inventory/cell-reaction sub-key))))

        (testing "a body re-run right now already reads the SUCCESSOR — the
                  read path is address-directed on the public id, which is
                  precisely why rendered markup is a green instrument
                  throughout this window"
          (is (= "B" (collector/render-body frame-id body {}))))

        (at-the-checkpoint
          #(some? (inventory/cell-reaction sub-key))
          "the reincarnation repair"
          done
          (fn [_turns]
            (testing "inside the SAME microtask checkpoint — so before the
                      event loop can take a rendering opportunity — the
                      deferred repair has routed to the LIVE incarnation: the
                      attachment is rebuilt, the number moves, and the
                      boundary is notified so it can correct what it painted"
              (is (some? (inventory/cell-reaction sub-key))
                  "the cell is re-wired rather than left deaf")
              (is (> (collector/snapshot-of entry) snapshot-before)
                  "and the number React re-reads has finally moved")
              (is (> @notified notified-before)
                  "the committed boundary is notified — the repair is
                   delivered, not merely performed"))
            (release)))))))

;; ---------------------------------------------------------------------------
;; 2. No successor — the same delayed callback routes to disposal instead
;; ---------------------------------------------------------------------------

(deftest with-no-successor-the-delayed-repair-disposes-exactly
  (async done
    (incarnate! "A")
    (let [{:keys [entry release]} (render+commit!)]
      (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1}
             (dissoc (inventory/residue) :entries))
          "the commit acquired exactly one cell and one reader membership")

      (rf/destroy-frame! frame-id)

      (testing "synchronously the cell is still in the table, holding no
                reaction — the repair's two phases, mid-flight"
        (is (nil? (inventory/cell-reaction sub-key)))
        (is (= 1 (:cells (inventory/residue)))))

      (at-the-checkpoint
        #(zero? (:cells (inventory/residue)))
        "the no-successor disposal"
        done
        (fn [_turns]
          (testing "at the checkpoint, with nothing to rebuild against, the
                    same deferred callback DISPOSES rather than re-wiring — the
                    exact, testable teardown invariant 5 requires, with zero
                    residue after quiescence, and the rescheduling in rf2-2l17
                    moved when that happens without changing what happens"
            (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0}
                   (dissoc (inventory/residue) :entries))
                "no cell, no reader membership, no dependency edge survives a
                 frame that did not come back")
            (is (nil? (inventory/cell-reaction sub-key))))
          (release))))))

;; ---------------------------------------------------------------------------
;; 3. The reincarnation branch is chosen by the frame's LIVENESS, not by the
;;    passage of time — the negative control for section 2 against section 1
;; ---------------------------------------------------------------------------

(deftest the-deferred-repair-branches-on-the-successor-existing
  ;; Sections 1 and 2 run the identical deferred callback and reach opposite
  ;; outcomes. This asserts they are opposite for the stated reason — the
  ;; successor's existence when the deferred phase fires — rather than by two
  ;; coincidences. The frame is recreated INSIDE the window, after the
  ;; synchronous phase has already dropped the reference and before the
  ;; deferred phase runs.
  ;;
  ;; rf2-2l17 narrowed that window from a whole macrotask to the current
  ;; microtask checkpoint, and the row keeps its meaning unchanged because it
  ;; seats the successor SYNCHRONOUSLY: only the fire time moved earlier. A
  ;; successor seated in a later task now finds the cell disposed and recovers
  ;; through `cold-read!`'s probe on the next render instead, which is the
  ;; recovery a key that never had a cell already gets.
  (async done
    (incarnate! "A")
    (let [{:keys [entry release]} (render+commit!)]
      (rf/destroy-frame! frame-id)
      (is (nil? (inventory/cell-reaction sub-key))
          "synchronous phase: reference dropped")
      ;; Seat the successor while the deferred phase is still pending.
      (rf/make-frame {:id frame-id})
      (rf/with-frame frame-id (rf/dispatch-sync [:reinc-cell/seed "B"]))
      (at-the-checkpoint
        #(some? (inventory/cell-reaction sub-key))
        "the liveness branch"
        done
        (fn [_turns]
          (testing "a successor seated INSIDE the deferral window flips the
                    branch from dispose to re-wire — so the two outcomes are
                    the frame's liveness, read when the deferred phase fires"
            (is (some? (inventory/cell-reaction sub-key)))
            (is (= 1 (:cells (inventory/residue)))))
          (testing "and the re-wired cell answers for the SUCCESSOR"
            (is (= "B" (collector/render-body frame-id body {}))))
          (release))))))
