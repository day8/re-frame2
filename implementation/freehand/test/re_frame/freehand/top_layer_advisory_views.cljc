(ns re-frame.freehand.top-layer-advisory-views
  "rf2-drpa3.173 — the declared views the compiled top-layer DYNAMIC-HANDLER
  advisory matrix mounts, INTERPRETED.

  Each carries a top-layer desired state and a reconciler handler that is a
  DYNAMIC prop expression — `:on-toggle handler`, not `:on-toggle [:evt]` —
  so the authored expression may evaluate to nil at render exactly as the
  bead's reproduction does. The advisory judges reconciliation by
  `(some? (get attrs k))` at commit, so a nil dynamic handler must warn and a
  non-nil one must not — and the same must hold whichever mode built the
  element. The compiled twins live in
  [[re-frame.freehand.top-layer-advisory-views-compiled]]."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.web :as web]))

(v/defview popover
  "A popover whose sole reconciler, `:on-toggle`, is the DYNAMIC prop
  `handler` — nil expresses no handler, a fn is the author's reconciliation
  seam."
  [{:keys [open? handler]}]
  [:div {:id                 "adv-popover"
         :popover            :auto
         ::web/popover-open? open?
         :on-toggle          handler}
   "Account"])

(v/defview popover-literal
  "The control: the same popover with a LITERAL event-vector `:on-toggle`.
  A literal handler is always present, so it must never warn, and its
  behaviour must be unchanged by the dynamic-handler work."
  [{:keys [open?]}]
  [:div {:id                 "adv-popover-lit"
         :popover            :auto
         ::web/popover-open? open?
         :on-toggle          [:adv/toggled]}
   "Account"])

(v/defview dialog-close
  "A modal dialog whose reconciler is the DYNAMIC `:on-close`."
  [{:keys [open? handler]}]
  [:dialog {:id               "adv-dialog"
            ::web/modal-open? open?
            :on-close         handler}
   [:p "Delete this?"]])

(v/defview dialog-cancel
  "A modal dialog whose reconciler is the DYNAMIC `:on-cancel` — the other
  half of the dialog dismissal axis. With `:on-close` absent, a nil
  `:on-cancel` leaves the dialog wholly unreconciled."
  [{:keys [open? handler]}]
  [:dialog {:id               "adv-dialog"
            ::web/modal-open? open?
            :on-cancel        handler}
   [:p "Delete this?"]])
