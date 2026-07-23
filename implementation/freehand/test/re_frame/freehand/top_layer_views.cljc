(ns re-frame.freehand.top-layer-views
  "The declared views the `FH-TOPLAYER` structural rows render, INTERPRETED.

  Each one is the smallest declaration that carries one of the two
  desired-state properties, so the fixture can pin the structural
  projection literally — the semantic element, the `:rf.ui/top-layer`
  fact, and the absence of the qualified property from `:attrs`.

  Host-neutral throughout: nothing here is a browser fact, which is
  exactly the point. The tree records what was ASKED FOR; whether anything
  was promoted to a top layer is a browser question the structural host
  never answers."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.web :as web]))

(v/defview menu
  "A popover whose desired state is open. `:popover :auto` is the mode that
  makes the property legal at all."
  [_]
  [:div#account-menu {:popover           :auto
                      ::web/popover-open? true
                      :on-toggle         [:menu/toggled]}
   "Account"])

(v/defview menu-closed
  "The same popover, desired CLOSED — the property's value is the state, so
  `false` is a declaration, not an absence."
  [_]
  [:div#account-menu {:popover            :auto
                      ::web/popover-open? false
                      :on-toggle          [:menu/toggled]}
   "Account"])

(v/defview confirm
  "A modal dialog whose desired state is open."
  [_]
  [:dialog#confirm {::web/modal-open? true
                    :on-close         [:confirm/closed]}
   [:p "Delete this?"]])

(v/defview unexpressed
  "A popover carrying NO desired state — the property's value is nil, which
  expresses nothing and leaves the element an ordinary popover."
  [_]
  [:div#account-menu {:popover            :auto
                      ::web/popover-open? nil}
   "Account"])

(def by-name
  "Fixture view-name keyword -> the declared view. A fixture is EDN, so it
  names a view rather than carrying one."
  {:menu        menu
   :menu-closed menu-closed
   :confirm     confirm
   :unexpressed unexpressed})
