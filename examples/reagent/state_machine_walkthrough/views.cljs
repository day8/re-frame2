(ns state-machine-walkthrough.views
  "Browser entry-point for the state-machines walkthrough (rf2-vq2s).

  The pure machine, fxs, subs and headless tests live in `core.cljc`
  alongside docs/guide/05-state-machines.md. This namespace is the
  CLJS-only browser layer: views + Reagent mount + a `run` fn that
  installs a per-frame `:fx-overrides` redirecting `:rf.http/managed`
  to the canned-failure stub registered in `core.cljc`.

  Why canned-failure: the chapter's headline scenario is the lockout
  flow — three failed attempts that cycle :submitting → :error-shown
  → :idle, then a fourth submit that fails the `:under-retry-limit`
  guard and lands at `:locked-out`. The Playwright spec walks through
  exactly that path, so the stub needs to fail every request."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [state-machine-walkthrough.core])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ^{:doc "The login form view. Captures email + password in a Reagent
                  atom across renders (Form-2 outer/inner shape) and dispatches
                  :auth.login/flow → :auth.login/submit on submit."}
          login-form []
  (let [state (atom {:email "" :password ""})]
    (fn []
      (let [submitting? @(subscribe [:auth.login/submitting?])
            err         @(subscribe [:auth.login/error])
            login-state @(subscribe [:auth.login/state])
            locked?     (= :locked-out login-state)]
        [:form.login-form
         {:on-submit (fn [e]
                       (.preventDefault e)
                       (when-not (or submitting? locked?)
                         (dispatch [:auth.login/flow
                                    [:auth.login/submit @state]])))}
         [:input  {:type        "email"
                   :placeholder "Email"
                   :disabled    (or submitting? locked?)
                   :on-change   #(swap! state assoc :email (.. % -target -value))}]
         [:input  {:type        "password"
                   :placeholder "Password"
                   :disabled    (or submitting? locked?)
                   :on-change   #(swap! state assoc :password (.. % -target -value))}]
         [:button {:type "submit" :disabled (or submitting? locked?)}
          (if submitting? "Signing in…" "Sign in")]
         (when (and err (not locked?))
           [:div.error-row
            [:p.error err]
            [:button {:type "button"
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
     [:strong.state {:data-state (when state (name state))}
      (if state (name state) "(uninitialised)")]]))

(reg-view ^{:doc "Locked-out terminal panel."}
          locked-panel []
  [:div.locked
   [:h2 "Account locked"]
   [:p "Three failed attempts. Contact support to unlock."]])

(reg-view root-view []
  (let [locked? (= :locked-out @(subscribe [:auth.login/state]))]
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
  ;; rf2-agql: pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  ;; Install the canned-failure override on the default frame so every
  ;; `:rf.http/managed` request resolves :failure. The chapter's
  ;; lockout scenario depends on three consecutive failures.
  (rf/reg-frame :rf/default
    {:doc          "State-machines walkthrough demo frame."
     :fx-overrides {:rf.http/managed :auth.login/canned-failure}})
  (rdc/render react-root [root-view]))
