(ns day8.re-frame2-xray.l2-counter-inc-row-cljs-test
  "Coverage that a landed counter-inc epoch surfaces as a row in Xray's
  L2 event list.

  ## The shape at risk

  Under the per-event epoch model a `:frame/created` trace event emitted
  OUTSIDE any dequeued-event run stays UNCORRELATED — it never rides the
  NEXT dequeued event's `:rf/epoch-record :trace-events`. So a
  counter-inc epoch's trace stream begins with the real
  `:rf.event/dispatched` and the cascade surfaces normally.

  Were such an emit mis-attributed, the next epoch's `:trace-events`
  would begin with an orphan `[:frame :frame/created]` carrying that
  epoch's `:dispatch-id`. `group-by-event` groups by
  `[frame dispatch-id]`, so an orphan carrying the SAME `:dispatch-id`
  as the real `:rf.event/dispatched` folds into the same cascade record
  — harmless. The failure mode is the inverse: an orphan whose
  `:dispatch-id` did NOT match (a per-frame harvest splitting it) could
  leave the real `:rf.event/dispatched` in a cascade whose `:event`
  vector never resolved, so `shell/event-bundle-has-event?` would
  return false and the L2 filter (`shell/l2-event-bundle-visible?`)
  would drop the row entirely — a counter increment with no L2 row.

  ## What this test pins

  Pure-data, no runtime: feed both trace-stream shapes through the
  exact L2-row pipeline Xray uses —

    `rf.trace.projection/group-by-event`
      → `self-noise/xray-internal-event-bundle?` (the shared hard-filter)
      → `shell/l2-event-bundle-visible?` (the L2 visibility predicate)

  and assert a clean counter-inc epoch surfaces as exactly one visible
  L2 cascade row carrying the `:counter/inc` event vector. The
  mis-attributed shape (leading orphan `:frame/created` with the
  epoch's `:dispatch-id`) is a contrast case showing how the orphan
  folds into the grouping."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.trace.projection :as rf.trace.projection]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.self-noise :as self-noise]))

;; ---- fixture builders ---------------------------------------------------

(def ^:private frame-below :below)
(def ^:private dispatch-id-8 8)

(defn- counter-inc-trace-events
  "The trace stream for a clean `:counter/inc` epoch on
  the `:below` frame. Begins with the real `:rf.event/dispatched` (the
  cascade root) — NO leading orphan `:frame/created`. Mirrors the
  six-domino shape the framework emits per Spec 009 §`:op-type`
  vocabulary."
  []
  [{:id 50 :op-type :rf.event :operation :rf.event/dispatched
    :tags {:rf.trace/dispatch-id dispatch-id-8 :rf.event/v [:counter/inc] :frame frame-below}}
   {:id 51 :op-type :rf.event :operation :rf.event/run-start
    :tags {:rf.trace/dispatch-id dispatch-id-8 :rf.trace/phase :run-start :frame frame-below}}
   {:id 52 :op-type :rf.event :operation :rf.event/run-end
    :tags {:rf.trace/dispatch-id dispatch-id-8 :rf.trace/phase :run-end :frame frame-below}}
   {:id 53 :op-type :rf.fx :operation :rf.fx/do-fx
    :tags {:rf.trace/dispatch-id dispatch-id-8 :frame frame-below}}
   {:id 54 :op-type :rf.fx :operation :rf.fx/handled
    :tags {:rf.trace/dispatch-id dispatch-id-8 :rf.fx/id :db :frame frame-below}}
   {:id 55 :op-type :rf.sub :operation :rf.sub/run
    :tags {:rf.trace/dispatch-id dispatch-id-8 :rf.sub/id :counter/value :frame frame-below}}
   {:id 56 :op-type :rf.view :operation :rf.view/render
    :tags {:rf.trace/dispatch-id dispatch-id-8 :rf.view/render-key [:counter/root nil] :frame frame-below}}])

(defn- orphan-frame-created-event
  "A mis-attributed `:rf.frame/created` orphan — a frame-lifecycle emit
  leaked into the NEXT epoch's `:trace-events` carrying that epoch's
  `:dispatch-id`. The corruption shape the contrast case feeds in."
  []
  {:id 49 :op-type :rf.frame :operation :rf.frame/created
   :tags {:rf.trace/dispatch-id dispatch-id-8 :frame frame-below}})

(defn- visible-l2-rows
  "Run the exact Xray L2-row pipeline over a raw trace stream and
  return the cascades that would render as L2 rows: project →
  hard-filter Xray-internal cascades → keep L2-visible cascades.
  `show-ungrouped?` defaults to false (the user-facing default)."
  ([events] (visible-l2-rows events false))
  ([events show-ungrouped?]
   (->> (rf.trace.projection/group-by-event events)
        (remove self-noise/xray-internal-event-bundle?)
        (filterv #(shell/l2-event-bundle-visible? % show-ungrouped?)))))

;; ---- 1. a counter-inc epoch surfaces as an L2 row ------------------------

(deftest counter-inc-epoch-surfaces-as-l2-row
  (testing "a clean :counter/inc epoch surfaces as exactly
            one visible L2 cascade row carrying the event vector"
    (let [rows (visible-l2-rows (counter-inc-trace-events))]
      (is (= 1 (count rows))
          "the counter-inc cascade is NOT dropped from the L2 list")
      (let [c (first rows)]
        (is (= [:counter/inc] (:event c))
            ":event slot holds the dispatched event vector")
        (is (= frame-below (:frame c))
            "the cascade is attributed to the :below frame")
        (is (= dispatch-id-8 (:dispatch-id c))
            "the cascade carries the epoch's dispatch-id (eid 8)")
        (is (true? (shell/event-bundle-has-event? c))
            "event-bundle-has-event? is true — the L2 filter keeps the row")
        (is (false? (shell/ungrouped-event-bundle? c))
            "the row is a real cascade, not the :ungrouped pseudo-bucket")))))

;; ---- 2. contrast: a mis-attributed orphan folds in ----------------------
;;
;; A mis-attributed orphan :frame/created in the epoch's :trace-events
;; carries the SAME :dispatch-id as the real :rf.event/dispatched. Because
;; group-by-event keys by [frame dispatch-id], the orphan folds into the
;; SAME cascade record — the real :rf.event/dispatched still populates
;; :event, so even the corrupted stream resolves a visible row (the orphan
;; rides in :other). This contrast case shows grouping is robust even WITH
;; the orphan present — what keeps an L2 row from going missing is
;; upstream (the orphan never reaching the cascade), not group-by-event
;; itself.

(deftest pre-avvwm-orphan-folds-into-same-cascade-still-visible
  (testing "with the leading orphan :frame/created sharing the epoch's
            dispatch-id, the cascade still resolves one visible L2 row —
            the orphan rides in :other, :event is still populated"
    (let [events (into [(orphan-frame-created-event)]
                       (counter-inc-trace-events))
          rows   (visible-l2-rows events)]
      (is (= 1 (count rows))
          "still exactly one visible L2 row (orphan folds into the cascade)")
      (let [c (first rows)]
        (is (= [:counter/inc] (:event c))
            ":event slot is still the dispatched event vector")
        (is (some (fn [ev] (= :rf.frame/created (:operation ev)))
                  (:other c))
            "the orphan :rf.frame/created lands in the cascade's :other slot")))))

;; ---- 3. The classifier: a frame-lifecycle-ONLY group is the only drop --
;;
;; The only shape the L2 filter legitimately drops is a group with NO
;; :rf.event/dispatched — i.e. a frame-lifecycle emit with no in-flight
;; cascade. Such an uncorrelated orphan carries NO :dispatch-id, so it
;; lands in the :ungrouped bucket and is correctly hidden by default.
;; This pins that the drop is scoped to event-less groups, never to a
;; real counter-inc epoch.

(deftest uncorrelated-frame-created-stays-ungrouped-and-hidden
  (testing "an uncorrelated :frame/created (no :dispatch-id)
            lands in :ungrouped and is hidden from L2 by default, while
            the sibling counter-inc epoch still surfaces"
    (let [events (into [{:id 49 :op-type :rf.frame :operation :rf.frame/created
                         :tags {:frame frame-below}}] ; NO :dispatch-id
                       (counter-inc-trace-events))
          all    (rf.trace.projection/group-by-event events)
          rows   (visible-l2-rows events)]
      (is (= 2 (count all))
          "two groups: the :ungrouped frame-lifecycle bucket + the cascade")
      (is (some #(= :ungrouped (:dispatch-id %)) all)
          "the uncorrelated :frame/created lands in :ungrouped")
      (is (= 1 (count rows))
          "only the counter-inc cascade is L2-visible by default")
      (is (= [:counter/inc] (:event (first rows)))
          "the visible row is the counter-inc epoch")
      (testing "the :ungrouped bucket becomes visible only on opt-in"
        (is (= 2 (count (visible-l2-rows events true)))
            "with show-ungrouped? on, the frame-lifecycle bucket also shows")))))
