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

(reg-view ^{:doc "The login form view. The email + password DRAFT is application
                  state, so it lives in app-db (the `:auth.login/draft` slice):
                  read via the `:auth.login/draft` sub, written via the
                  `:auth.login/edit-field` event. The inputs are CONTROLLED —
                  `:value` reads from the draft sub, `:on-change` dispatches an
                  edit-field event. Submit reads the draft back out of the sub
                  and hands it to the machine via :auth.login/flow →
                  :auth.login/submit.

                  The machine owns submit/auth STATUS; the slice owns the DRAFT
                  (Pattern-Forms §Variations: machine + slice). View-side
                  discriminators ask the machine for a tag via
                  `rf/machine-has-tag?` (chapter §State tags), reading its
                  runtime-projected `:tags` set rather than the current state
                  keyword — so adding a new busy-ish state changes no view."}
          login-form []
  (let [busy?   @(rf/machine-has-tag? :auth.login/flow :auth/busy)
        locked? @(rf/machine-has-tag? :auth.login/flow :auth/locked)
        draft   @(subscribe [:auth.login/draft])
        err     @(subscribe [:auth.login/error])]
    [:form.login-form
     {:data-testid "login-form"
      :on-submit (fn [e]
                   (.preventDefault e)
                   (when-not (or busy? locked?)
                     (dispatch [:auth.login/flow
                                [:auth.login/submit draft]])))}
     [:input  {:type        "email"
               :placeholder "Email"
               :data-testid "login-email"
               :value       (:email draft)
               :disabled    (or busy? locked?)
               :on-change   #(dispatch [:auth.login/edit-field :email (.. % -target -value)])}]
     [:input  {:type        "password"
               :placeholder "Password"
               :data-testid "login-password"
               :value       (:password draft)
               :disabled    (or busy? locked?)
               :on-change   #(dispatch [:auth.login/edit-field :password (.. % -target -value)])}]
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
         "Dismiss"]])]))

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
  ;; Install the Reagent adapter — pass its spec map straight to init!.
  (rf/init! reagent-adapter/adapter)
  ;; `frame-provider {:id …}` is the one spot that creates, configures and
  ;; seeds the app frame. On first mount it creates the `:rf/default` frame,
  ;; applies the config, and runs `:initial-events` once. On hot reload it
  ;; reuses that frame and skips re-seeding, so your typed-in draft survives.
  ;; The `reg-view`-injected `dispatch`/`subscribe` (and the login machine
  ;; reads) resolve to this frame.
  ;;
  ;; `:fx-overrides` swaps `:rf.http/managed` for the canned-failure stub, so
  ;; every login request fails — the chapter's lockout scenario needs three
  ;; failures in a row.
  ;;
  ;; The login machine spawns itself on its first event. The form DRAFT slice
  ;; does not, so `:initial-events` seeds it before first render: the controlled
  ;; inputs read `:value` off `[:auth :login-form :draft]`, and that slot must
  ;; already hold empty strings (a nil there makes React treat the inputs as
  ;; uncontrolled).
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id              :rf/default
                                    :doc             "State-machines walkthrough demo frame."
                                    :fx-overrides    {:rf.http/managed :auth.login/canned-failure}
                                    :initial-events  [[:auth.login/initialise-form]]}
                 [root-view]])))
