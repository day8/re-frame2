(ns day8.re-frame2-xray.panels.cancellation-cascade-subs
  "Composite subs for the Cancellation-cascade visualiser (rf2-59e7k).

  Three reactive surfaces:

    `:rf.xray/cancellation-cascade-popover-open?`
    `:rf.xray/cancellation-cascade-popover-focus`
    `:rf.xray/cancellation-cascade-expanded?`

  Two cascade-projection composites:

    `:rf.xray/cancellation-cascade-for-focused-machine`
       ; project the cascade for the focused machine's most-recent
       ; cancellation-anchor (if any in the trace window).

    `:rf.xray/cancellation-cascade-for-focused-event`
       ; project the cascade for the focused cascade's dispatch-id
       ; (if its drain contained a cancellation-anchor).

  Both composites are pure-data → pure-data; the heavy lifting lives
  in `cancellation_cascade_helpers/extract-cascade`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panels.cancellation-cascade-helpers :as h]))

(defn install!
  "Install the cancellation-cascade visualiser's subs. Idempotent."
  []

  ;; ---- popover slots --------------------------------------------------

  (rf/reg-sub :rf.xray/cancellation-cascade-popover-open?
    (fn [db _query]
      (boolean (get db :cancellation-cascade-popover-open?))))

  (rf/reg-sub :rf.xray/cancellation-cascade-popover-focus
    (fn [db _query]
      (get db :cancellation-cascade-popover-focus)))

  (rf/reg-sub :rf.xray/cancellation-cascade-expanded?
    (fn [db _query]
      (boolean (get db :cancellation-cascade-expanded?))))

  ;; ---- cascade for focused machine ------------------------------------
  ;;
  ;; The Machines tab side-panel mount reads this. We compose against
  ;; the existing `:rf.xray/selected-machine-id` (the picker's
  ;; selection) + the trace buffer. When no machine is selected the
  ;; composite returns a `:no-trigger` shape so the view can branch
  ;; cleanly.

  (rf/reg-sub :rf.xray/cancellation-cascade-for-focused-machine
    {:inputs [[:rf.xray/trace-buffer] [:rf.xray/selected-machine-id]]}
    (fn [[buffer machine-id] _query]
      (h/extract-cascade buffer
                         (when machine-id
                           {:kind :machine-id :id machine-id}))))

  ;; ---- cascade for focused event --------------------------------------
  ;;
  ;; The Trace popover (and any caller passing an explicit
  ;; dispatch-id focus) reads this. We compose against the
  ;; popover-focus slot first (explicit user-driven focus), falling
  ;; back to the spine's `:rf.xray/focus` so a 'just show me the
  ;; cancellation for the current focus' subscribe stays useful.

  (rf/reg-sub :rf.xray/cancellation-cascade-for-focused-event
    {:inputs [[:rf.xray/trace-buffer]
              [:rf.xray/cancellation-cascade-popover-focus]
              [:rf.xray/focus]]}
    (fn [[buffer popover-focus spine-focus] _query]
      ;; rf2-y8doi.15 — carry the focused FRAME, not the dispatch-id alone.
      ;; Dispatch ids are unique only within a frame and Xray's buffer is
      ;; every host frame's ring merged, so a dispatch-id-only focus let
      ;; another frame's aborts fold into this cascade (and could anchor the
      ;; popover on the wrong frame's destroy). Same frame-strict keying
      ;; rf2-bz7flo gave the managed-fx and routing panels. An explicit frame
      ;; on the popover focus wins; otherwise the spine's focused frame is
      ;; supplied. A focus with no frame scopes nothing — the prior behaviour.
      (let [focus (or popover-focus
                      (when-let [d (:dispatch-id spine-focus)]
                        {:kind :dispatch-id :id d}))]
        (h/extract-cascade
          buffer
          (some-> focus (update :frame #(or % (:frame spine-focus))))))))

  nil)
