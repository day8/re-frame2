(ns state-machine-walkthrough.views
  "Browser entry-point for the state-machines walkthrough.

  The pure machine, fxs and subs live in `core.cljc` alongside
  docs/machines/concepts.md; the headless tests live in the framework
  test tree (the `state-machine-walkthrough-runs-headless` deftest in
  `implementation/core/test/re_frame/examples_test.clj`). This namespace
  is the CLJS-only browser layer: views + Reagent mount + a `run` fn that
  installs a per-frame `:fx-overrides` redirecting `:rf.http/managed`
  to the canned-failure stub registered in `core.cljc`.

  Why canned-failure: the chapter's headline scenario is the lockout
  flow — three failed attempts that cycle :submitting → :error-shown
  → :idle, then a fourth submit that fails the `:under-retry-limit`
  guard and lands at `:locked-out`. That path is exactly what the
  `state-machine-walkthrough-runs-headless` deftest walks through,
  so the stub needs to fail every request."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [state-machine-walkthrough.core])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ^{:doc "The login form view. Captures email + password in a plain
                  atom across renders (Form-2 outer/inner shape) — the inputs
                  are uncontrolled, so the atom is only read in the submit
                  handler, never in the render body, and needs no reactivity.
                  Dispatches :auth.login/flow → :auth.login/submit on submit.

                  View-side discriminators read the machine's runtime-projected
                  `:tags` set (chapter §State tags) via `rf/machine-has-tag?`, not boolean
                  state-predicate subs — so adding a new busy-ish state changes no view."}
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
                  `data-state` attribute surfaces the state keyword so a
                  test can assert the lockout transition."}
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

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
(defonce react-root (atom nil))

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  ;; Install the canned-failure override on the default frame so every
  ;; `:rf.http/managed` request resolves :failure. The chapter's
  ;; lockout scenario depends on three consecutive failures.
  ;; EP-0002: the runtime never synthesises a frame from absence —
  ;; register the app frame explicitly and wrap the render in a `frame-provider`
  ;; so the `reg-view`-injected `dispatch`/`subscribe` (and the login machine
  ;; reads) resolve to it (a no-provider render reads the no-provider sentinel
  ;; and raises :rf.error/no-frame-context). The login machine is
  ;; self-initialising, so there is no boot `dispatch-sync` to scope here.
  (rf/reg-frame :rf/default
    {:doc          "State-machines walkthrough demo frame."
     :fx-overrides {:rf.http/managed :auth.login/canned-failure}})
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame :rf/default}
                 [root-view]])))
