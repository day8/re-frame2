(ns re-frame.freehand.top-layer-views-compiled
  "The [[re-frame.freehand.top-layer-views]] declarations, PROMOTED.

  Every declaration below is its twin in
  [[re-frame.freehand.top-layer-views]] with `{:compiled true}` added and
  nothing else changed. Both modes reach
  [[re-frame.freehand.node/element]], which is where the desired-state pair
  is recognised, so `FH-TOPLAYER-001` renders against BOTH and the
  intrinsics are proven to be common semantics rather than a front-end
  quirk of the interpreted walk.

  Separate namespaces because a view id is derived from where a
  declaration LIVES, so two declarations of one name cannot share a
  namespace."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.web :as web]))

(v/defview menu
  "A popover whose desired state is open. `:popover :auto` is the mode that
  makes the property legal at all."
  {:compiled true}
  [_]
  [:div#account-menu {:popover            :auto
                      ::web/popover-open? true
                      :on-toggle          [:menu/toggled]}
   "Account"])

(v/defview menu-closed
  "The same popover, desired CLOSED — the property's value is the state, so
  `false` is a declaration, not an absence."
  {:compiled true}
  [_]
  [:div#account-menu {:popover            :auto
                      ::web/popover-open? false
                      :on-toggle          [:menu/toggled]}
   "Account"])

(v/defview confirm
  "A modal dialog whose desired state is open."
  {:compiled true}
  [_]
  [:dialog#confirm {::web/modal-open? true
                    :on-close         [:confirm/closed]}
   [:p "Delete this?"]])

(v/defview unexpressed
  "A popover carrying NO desired state — the property's value is nil, which
  expresses nothing and leaves the element an ordinary popover."
  {:compiled true}
  [_]
  [:div#account-menu {:popover            :auto
                      ::web/popover-open? nil}
   "Account"])

(def by-name
  "Fixture view-name keyword -> the declared view. Keyed identically to
  `top-layer-views/by-name`, so one fixture table drives both."
  {:menu        menu
   :menu-closed menu-closed
   :confirm     confirm
   :unexpressed unexpressed})
