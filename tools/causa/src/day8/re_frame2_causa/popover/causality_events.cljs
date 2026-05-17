(ns day8.re-frame2-causa.popover.causality-events
  "Events for the Causality popover (rf2-dqnuu).

  Per `tools/causa/spec/018-Event-Spine.md` §10 + §11 the popover is
  driven by three actions:

    `:rf.causa/causality-popover-open`           ; idempotent open
    `:rf.causa/causality-popover-close`          ; idempotent close
    `:rf.causa/causality-popover-toggle`         ; c-key toggle
    `:rf.causa/causality-popover-toggle-layout`  ; LR ↔ TB

  All events run against Causa's `:rf/causa` frame; the storage
  slots live at `:causality-popover-open?` and `:causality-popover-
  layout` (default `:tb`).

  The toggle event also fires when a popover node is clicked — per
  spec §10 §Interaction 'Click any node → spine rebinds (`:rf.causa
  /focus-cascade <id>`) + popover closes'. The view's on-click
  dispatches both events sequentially; this ns owns the close-half."
  (:require [re-frame.core :as rf]))

(def ^:const default-layout
  "Layout direction when the popover first opens. Per spec §10 Q12 ELK
  takes a single direction per graph; v1 ships TB (the descendants
  tree dominates the visual weight, so the LR ancestor chain stacks
  above as a vertical pre-amble). The footer toggle flips to `:lr`."
  :tb)

(defn install!
  "Install the popover's events. Idempotent under re-frame's
  replace-in-place registrar — second + subsequent calls are
  harmless beyond the `:rf.warning/handler-replaced` trace which
  the orchestrator's `registered?` sentinel already protects against."
  []

  (rf/reg-event-db :rf.causa/causality-popover-open
    (fn [db _event]
      (cond-> db
        true (assoc :causality-popover-open? true)
        (not (contains? db :causality-popover-layout))
        (assoc :causality-popover-layout default-layout))))

  (rf/reg-event-db :rf.causa/causality-popover-close
    (fn [db _event]
      (assoc db :causality-popover-open? false)))

  (rf/reg-event-db :rf.causa/causality-popover-toggle
    (fn [db _event]
      (if (get db :causality-popover-open? false)
        (assoc db :causality-popover-open? false)
        (-> db
            (assoc :causality-popover-open? true)
            (update :causality-popover-layout
                    (fn [v] (or v default-layout)))))))

  (rf/reg-event-db :rf.causa/causality-popover-toggle-layout
    (fn [db _event]
      (update db :causality-popover-layout
              (fn [cur] (if (= :lr cur) :tb :lr)))))

  ;; Layout-pulse — internal no-op that the ELK layout `.then` callback
  ;; dispatches so the popover view recomputes once positions are
  ;; populated. Writes `[:causality-popover-layout-tick]` so the
  ;; reactive graph notices a db change without altering observable
  ;; state. Tagged `:rf.trace/no-emit?` so the dev console isn't
  ;; spammed by trace events on every async layout settle.
  (rf/reg-event-db :rf.causa/causality-popover-layout-pulse
    {:rf.trace/no-emit? true}
    (fn [db _event]
      (update db :causality-popover-layout-tick (fnil inc 0))))

  nil)
