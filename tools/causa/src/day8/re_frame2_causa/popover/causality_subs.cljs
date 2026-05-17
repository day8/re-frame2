(ns day8.re-frame2-causa.popover.causality-subs
  "Subscriptions for the Causality popover (rf2-dqnuu).

  Sub tree:

    :rf.causa/causality-popover-open?   — boolean. Drives the modal mount.
    :rf.causa/causality-popover-layout  — :lr | :tb (footer toggle state).
    :rf.causa/causality-popover-payload — `build-payload` shape over
                                          the focused dispatch + cascade
                                          buffer.

  The payload sub depends on `:rf.causa/cascades` (the shared
  projection) + `:rf.causa/trace-buffer` (for `enrich-cascades`) +
  `:rf.causa/focus` (for the focused dispatch-id). Recomputes
  whenever any of the three change; idle (popover closed) consumers
  pay nothing because the view's reg-view body short-circuits on
  `:rf.causa/causality-popover-open?` before subscribing to the
  payload."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.causality-graph-helpers :as panel-h]
            [day8.re-frame2-causa.popover.causality-graph-helpers :as h]))

(defn install!
  "Install the popover's subs. Idempotent under re-frame's replace-
  in-place registrar."
  []

  (rf/reg-sub :rf.causa/causality-popover-open?
    (fn [db _query]
      (boolean (get db :causality-popover-open? false))))

  (rf/reg-sub :rf.causa/causality-popover-layout
    (fn [db _query]
      (or (get db :causality-popover-layout) :tb)))

  ;; Composite — folds the focused-dispatch from the spine with the
  ;; enriched cascade list into the popover payload shape.
  (rf/reg-sub :rf.causa/causality-popover-payload
    :<- [:rf.causa/cascades]
    :<- [:rf.causa/trace-buffer]
    :<- [:rf.causa/focus]
    (fn [[cascades buffer focus] _query]
      (let [enriched   (panel-h/enrich-cascades cascades buffer)
            focused-id (:dispatch-id focus)]
        (h/build-payload enriched focused-id))))

  nil)
