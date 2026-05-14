(ns state-machine-walkthrough.views
  "Browser entry-point for the state-machines walkthrough.

  The pure machine, fxs, subs and headless tests live in `core.cljc`
  alongside docs/guide/09-state-machines.md. This namespace is the
  CLJS-only browser layer: views + Reagent mount + a `run` fn that
  installs a per-frame `:fx-overrides` redirecting `:rf.http/managed`
  to the canned-failure stub registered in `core.cljc`.

  Why canned-failure: the chapter's headline scenario is the lockout
  flow — three failed attempts that cycle :submitting → :error-shown
  → :idle, then a fourth submit that fails the `:under-retry-limit`
  guard and lands at `:locked-out`. The Playwright spec walks through
  exactly that path, so the stub needs to fail every request."
  (:require [reagent2.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [state-machine-walkthrough.core])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ^{:doc "The login form view. Captures email + password in a Reagent
                  atom across renders (Form-2 outer/inner shape) and dispatches
                  :auth.login/flow → :auth.login/submit on submit.

                  View-side discriminators read the machine's runtime-projected
                  `:tags` set (ch.09 §State tags) via `rf/machine-has-tag?`, not boolean
                  state-predicate subs."}
          login-form []
  (let [state (atom {:email "" :password ""})]
    (fn []
      (let [busy?   @(rf/machine-has-tag? :auth.login/flow :auth/busy)
            locked? @(rf/machine-has-tag? :auth.login/flow :auth/locked)
            err     @(subscribe [:auth.login/error])]
        [:form.login-form
         {:data-testid "login-form"
          :on-submit (fn [e]
                       (.preventDefault e)
                       (when-not (or busy? locked?)
                         (dispatch [:auth.login/flow
                                    [:auth.login/submit @state]])))}
         [:input  {:type        "email"
                   :placeholder "Email"
                   :data-testid "login-email"
                   :disabled    (or busy? locked?)
                   :on-change   #(swap! state assoc :email (.. % -target -value))}]
         [:input  {:type        "password"
                   :placeholder "Password"
                   :data-testid "login-password"
                   :disabled    (or busy? locked?)
                   :on-change   #(swap! state assoc :password (.. % -target -value))}]
         [:button {:type "submit"
                   :data-testid "login-submit"
                   :disabled (or busy? locked?)}
          (if busy? "Signing in…" "Sign in")]
         (when (and err (not locked?))
           [:div.error-row
            [:p.error err]
            [:button {:type "button"
                      :data-testid "login-dismiss"
                      :on-click #(dispatch [:auth.login/flow
                                             [:auth.login/dismiss]])}
             "Dismiss"]])]))))

(reg-view ^{:doc "Top banner reflecting the machine's current state. The
                  Playwright spec watches this element to assert the lockout
                  transition."}
          status-banner []
  (let [state @(subscribe [:auth.login/state])]
    [:div.banner
     [:span "State: "]
     [:strong.state {:data-testid "state-banner"
                     :data-state (when state (name state))}
      (if state (name state) "(uninitialised)")]]))

(reg-view ^{:doc "Locked-out terminal panel."}
          locked-panel []
  [:div.locked {:data-testid "locked-panel"}
   [:h2 "Account locked"]
   [:p "Three failed attempts. Contact support to unlock."]])

(reg-view root-view []
  (let [locked? @(rf/machine-has-tag? :auth.login/flow :auth/locked)]
    [:div.app
     [:h1 "State-machines walkthrough — login lockout"]
     [status-banner]
     (if locked?
       [locked-panel]
       [login-form])]))

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-slim-adapter/adapter)
  ;; Install the canned-failure override on the default frame so every
  ;; `:rf.http/managed` request resolves :failure. The chapter's
  ;; lockout scenario depends on three consecutive failures.
  (rf/reg-frame :rf/default
    {:doc          "State-machines walkthrough demo frame."
     :fx-overrides {:rf.http/managed :auth.login/canned-failure}})
  (rdc/render react-root [root-view]))
