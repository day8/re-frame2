(ns day8.re-frame2-xray.l2-counter-inc-row-cljs-test
  "Regression coverage for rf2-j8m01 — a landed counter-inc epoch must
  surface as a row in Xray's L2 event list.

  ## The bug (rf2-j8m01)

  Live on the parallel-frames testbed: clicking `+` on the `:below`
  frame incremented the counter and created a new epoch (eid 8), but
  NO new cascade appeared in `:rf.xray/event-bundles` — the L2 event list
  showed no new row.

  ## Root cause (rf2-avvwm, #1961) — verified DUP

  Under the per-event epoch model (#1952) a `:frame/created` trace
  event emitted OUTSIDE any dequeued-event run was mis-attributed
  into the NEXT dequeued event's `:rf/epoch-record :trace-events`. So
  eid 8's `:trace-events` began with an orphan `[:frame :frame/created]`
  carrying eid 8's `:dispatch-id`.

  That orphan poisoned Xray's cascade grouping. `group-by-event`
  groups by `[frame dispatch-id]`; the orphan `:frame/created` carried
  the SAME `:dispatch-id` as the real `:rf.event/dispatched`, so it folded
  into the same cascade record — fine. The real failure mode the bead
  observed is the inverse: when the orphan's `:dispatch-id` did NOT
  match (the per-frame harvest split it), the real `:rf.event/dispatched`
  could land in a cascade whose `:event` vector never resolved, so
  `shell/event-bundle-has-event?` returned false and the L2 filter
  (`shell/l2-event-bundle-visible?`) dropped the row entirely.

  avvwm's fix (#1961) keeps out-of-cascade emits UNCORRELATED — the
  `:frame/created` never rides the next epoch's `:trace-events`. So a
  post-fix counter-inc epoch's trace stream begins with the real
  `:rf.event/dispatched` and the cascade surfaces normally.

  ## What this test pins

  Pure-data, no runtime: feed both trace-stream shapes through the
  exact L2-row pipeline Xray uses —

    `projection/group-by-event`
      → `self-noise/xray-internal-event-bundle?` (the shared hard-filter)
      → `shell/l2-event-bundle-visible?` (the L2 visibility predicate)

  and assert the POST-avvwm counter-inc epoch surfaces as exactly one
  visible L2 cascade row carrying the `:counter/inc` event vector. The
  PRE-avvwm shape (leading orphan `:frame/created` with the next
  epoch's `:dispatch-id`) is kept as a contrast case documenting how
  the orphan used to corrupt the grouping.

  Verdict: DUP of rf2-avvwm — the post-fix epoch surfaces correctly."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.self-noise :as self-noise]))

;; ---- fixture builders ---------------------------------------------------

(def ^:private frame-below :below)
(def ^:private dispatch-id-8 8)

(defn- counter-inc-trace-events
  "The trace stream for a clean (POST-avvwm) `:counter/inc` epoch on
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
  "The mis-attributed `:rf.frame/created` orphan that — pre-avvwm —
  leaked into the NEXT epoch's `:trace-events` carrying that epoch's
  `:dispatch-id`. Represents the pre-fix corruption shape."
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
   (->> (projection/group-by-event events)
        (remove self-noise/xray-internal-event-bundle?)
        (filterv #(shell/l2-event-bundle-visible? % show-ungrouped?)))))

;; ---- 1. POST-avvwm: counter-inc epoch surfaces as an L2 row -------------

(deftest counter-inc-epoch-surfaces-as-l2-row
  (testing "a clean post-avvwm :counter/inc epoch surfaces as exactly
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

;; ---- 2. PRE-avvwm contrast: the orphan used to fold in -----------------
;;
;; Pre-avvwm the orphan :frame/created leaked into the epoch's
;; :trace-events carrying the SAME :dispatch-id as the real
;; :rf.event/dispatched. Because group-by-event keys by [frame
;; dispatch-id], the orphan folded into the SAME cascade record — the
;; real :rf.event/dispatched still populated :event, so even the corrupted
;; stream resolves a visible row (the orphan rides in :other). This
;; contrast case documents that grouping is robust even WITH the orphan
;; present — confirming the visible-row defect was upstream (the orphan
;; never reaching the cascade) rather than in group-by-event itself.

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
;; cascade. Post-avvwm such an orphan carries NO :dispatch-id, so it
;; lands in the :ungrouped bucket and is correctly hidden by default.
;; This pins that the drop is scoped to event-less groups, never to a
;; real counter-inc epoch.

(deftest uncorrelated-frame-created-stays-ungrouped-and-hidden
  (testing "a post-avvwm uncorrelated :frame/created (no :dispatch-id)
            lands in :ungrouped and is hidden from L2 by default, while
            the sibling counter-inc epoch still surfaces"
    (let [events (into [{:id 49 :op-type :rf.frame :operation :rf.frame/created
                         :tags {:frame frame-below}}] ; NO :dispatch-id (avvwm)
                       (counter-inc-trace-events))
          all    (projection/group-by-event events)
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
