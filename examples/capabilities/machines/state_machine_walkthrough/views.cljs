(ns state-machine-walkthrough.views
  "The browser entry-point for the walkthrough — the part you can actually click.

  All the interesting, portable stuff (the pure machine, the fxs, the subs)
  lives in `core.cljc`. This namespace is purely the CLJS browser skin on top:
  the views, the Reagent mount, and a `run` fn that points `:rf.http/managed` at
  the canned-failure stub through per-frame :fx-overrides.

  The demo tells one story — the lockout. Three failed attempts walk the machine
  round the loop :submitting -> :error-shown -> :idle, and then a fourth submit
  trips the `:under-retry-limit` guard and the machine settles into :locked-out.
  For that to play out, every request has to fail, which is exactly why we wire
  in the canned-FAILURE stub here."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [state-machine-walkthrough.core])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ^{:doc "The login form. Two threads of state run through it, and the
                  whole view is easier to read once you separate them.

                  Thread one is the DRAFT — the email and password being typed.
                  That's application state, so it lives in app-db under the
                  `:auth.login/draft` slice: read through the `:auth.login/draft`
                  sub, written through the `:auth.login/edit-field` event. The
                  inputs are CONTROLLED off it — `:value` comes from the sub,
                  `:on-change` dispatches an edit-field event — and on submit we
                  read the draft back out of the sub and hand it to the machine
                  via :auth.login/flow → :auth.login/submit.

                  Thread two is STATUS — busy? locked? — and that belongs to the
                  machine, not the slice (docs/core/how-to/build-a-form.md). The
                  view never asks 'which state are we in?'; it asks 'is this tag
                  set?' via `rf/machine-has-tag?`. Branch on the `:tags` set, not
                  on state keywords, and adding a brand-new busy state down the
                  line touches exactly zero views
                  (docs/machines/glossary.md#state-tag)."}
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

(reg-view ^{:doc "A little banner up top that names the machine's current state —
                  handy for watching the transitions happen live. The
                  `data-state` attribute mirrors the state keyword into the DOM
                  so a test can assert the lockout transition without squinting
                  at pixels."}
          status-banner []
  (let [state @(subscribe [:auth.login/state])]
    [:div.banner
     [:span "State: "]
     [:strong.state {:data-testid "state-banner"
                     :data-state (when state (name state))}
      (if state (name state) "(uninitialised)")]]))

(reg-view ^{:doc "The end of the road: what you see once the account locks out."}
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

;; We stash the React root in an atom and create it lazily inside `run` — never
;; at ns-load. The rule: loading a namespace must touch no DOM. Otherwise two
;; examples co-required into one page would both race to `create-root` onto the
;; shared `#app`, and exactly one would win. See examples/TESTING.md, "Convention:
;; defer DOM mount to `run`".
(defonce react-root (atom nil))

(defn run []
  ;; Tell re-frame2 which substrate to render through by handing init! the
  ;; Reagent adapter's spec map. One call, done.
  (rf/init! reagent-adapter/adapter)
  ;; `frame-provider {:id …}` is the do-everything entry point for the frame:
  ;; it creates it, configures it, and seeds it. First mount creates the
  ;; `:rf/default` frame, applies the config, and runs `:initial-events` once.
  ;; A hot reload reuses that same frame and skips the re-seed — which is the
  ;; small kindness that keeps the draft you'd just typed from vanishing on
  ;; save. The `dispatch`/`subscribe` that `reg-view` quietly injects into the
  ;; views all resolve to this frame.
  ;;
  ;; `:fx-overrides` redirects `:rf.http/managed` to the canned-FAILURE stub, so
  ;; every login attempt fails on purpose — the lockout demo can't lock anyone
  ;; out without three failures to work with.
  ;;
  ;; One subtlety worth pausing on: the machine seeds itself on its first event,
  ;; but the DRAFT slice does not. So `:initial-events` seeds the draft before
  ;; the first render. The controlled inputs read `:value` off
  ;; `[:auth :login-form :draft]`, and that slot has to already hold empty
  ;; strings — leave a nil there and React decides the inputs are uncontrolled,
  ;; which is a confusing afternoon you can skip by seeding up front.
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id              :rf/default
                                    :doc             "State-machines walkthrough demo frame."
                                    :fx-overrides    {:rf.http/managed :auth.login/canned-failure}
                                    :initial-events  [[:auth.login/initialise-form]]}
                 [root-view]])))
