(ns re-frame.freehand.top-layer-advisory-views-compiled
  "The [[re-frame.freehand.top-layer-advisory-views]] declarations, PROMOTED.

  Every declaration below is its interpreted twin with `{:compiled true}`
  added and nothing else changed — the one-line change promotion is meant to
  be. The dynamic `:on-toggle` / `:on-close` / `:on-cancel` reconciler is a
  runtime SITE on the compiled tier, so its presence in the advisory context
  must be the runtime `(some? …)`-verdict, not a compile-time `true`
  (rf2-drpa3.173). This namespace is what makes the browser matrix able to
  assert the two modes agree.

  Separate namespace because a view id is derived from where a declaration
  LIVES, so two declarations of one name cannot share a namespace."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.web :as web]))

(v/defview popover
  "A popover whose sole reconciler, `:on-toggle`, is the DYNAMIC prop
  `handler`."
  {:compiled true}
  [{:keys [open? handler]}]
  [:div {:id                 "adv-popover"
         :popover            :auto
         ::web/popover-open? open?
         :on-toggle          handler}
   "Account"])

(v/defview popover-literal
  "The control: a LITERAL event-vector `:on-toggle`, always present."
  {:compiled true}
  [{:keys [open?]}]
  [:div {:id                 "adv-popover-lit"
         :popover            :auto
         ::web/popover-open? open?
         :on-toggle          [:adv/toggled]}
   "Account"])

(v/defview dialog-close
  "A modal dialog whose reconciler is the DYNAMIC `:on-close`."
  {:compiled true}
  [{:keys [open? handler]}]
  [:dialog {:id               "adv-dialog"
            ::web/modal-open? open?
            :on-close         handler}
   [:p "Delete this?"]])

(v/defview dialog-cancel
  "A modal dialog whose reconciler is the DYNAMIC `:on-cancel`."
  {:compiled true}
  [{:keys [open? handler]}]
  [:dialog {:id               "adv-dialog"
            ::web/modal-open? open?
            :on-cancel        handler}
   [:p "Delete this?"]])
