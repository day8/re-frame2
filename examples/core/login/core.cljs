(ns login.core
  "A login feature, end to end — the Reagent entry point.

   The whole login model — the state machine, the schemas, the form-slice
   events and subs, the demo HTTP stub, and the shared frame config — lives in
   the substrate-free [`login.model`](model.cljc) namespace, required below for
   its side-effecting registrations. That namespace is the ONE owner of the
   `auth.login` dataflow; read it for the ideas (the five-state login machine,
   the machine + slice split, the three password-egress boundaries).

   This file is the Reagent HALF: the only substrate-specific code. Below it are
   the `reg-view` views, the React root, the adapter init, and the mount — the
   parts that actually name Reagent. The UIx twin (`uix.login.core`) and the
   Hicasso twin (`hicasso.login.core`) import the
   identical `login.model` and supply their own view layer instead. One model,
   three view layers; the cross-view-layer comparison is exactly this
   substrate-specific half, held against a model that never changes. See
   examples/substrates/README.md for the comparison laid out.

   Examples are test-free: login's behaviour is covered by the substrate
   contract suite (`npm run test:cljs`) and the framework gates, not by a test
   alongside this file."
  ;; This example runs on stock Reagent. It's also the cross-view-layer
  ;; reference base — mirrored as `login-uix` and `login-hicasso` over the same
  ;; `login.model` — so staying on Reagent keeps the trio an honest
  ;; apples-to-apples comparison.
  (:require [re-frame.core :as rf]
            ;; The substrate-free model owner. Requiring it registers every
            ;; shared `auth.login` schema, fx, machine, event, and sub, and
            ;; hands us `model/frame-config` for the mount below. It names no
            ;; substrate — see model.cljc.
            [login.model :as model]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; ============================================================================
;; VIEWS  (Reagent — reg-view)
;; ============================================================================
;;
;; `reg-view` is `defn` with a superpower: inside the body, `dispatch` and
;; `subscribe` are already in scope, pre-bound to whichever frame the view
;; is rendering under. No threading a frame argument through every call.
;; That's also what lets the very same view mount in several isolated frames
;; at once (docs/core/views.md).

;; The login form. Read it and notice the absence — there is no
;; `reagent.core/atom`, no local state hiding anywhere. Each input's
;; `:value` comes from the `:auth.login/draft` sub; each `:on-change`
;; dispatches an edit event (`:auth.login/edit-field` for email,
;; `:auth.login/edit-password` for the secret). The submit button dispatches
;; `:auth.login/submit-form`, which fetches the draft from app-db, issues the
;; request, and feeds the machine. And "are we busy?" / "what went wrong?" are answered by the
;; machine — the `:auth/busy` tag and the `:auth.login/error` sub. Slice
;; owns the draft; machine owns the status; the view just renders them.
(rf/reg-view ^{:doc "The login form view: email + password + submit button + error display."}
          login-form []
  (let [draft     @(subscribe [:auth.login/draft])
        busy?     @(subscribe [:rf.machine/has-tag? :auth.login/flow :auth/busy])
        err       @(subscribe [:auth.login/error])
        email-err @(subscribe [:auth.login/field-error :email])
        pw-err    @(subscribe [:auth.login/field-error :password])]
    [:form.login-form
     {:data-testid "login-form"
      :on-submit (fn [e]
                   (.preventDefault e)
                   (when-not busy?
                     (dispatch [:auth.login/submit-form])))}
     [:input  {:type        "email"
               :placeholder "Email"
               :disabled    busy?
               :data-testid "login-email"
               :value       (:email draft)
               :on-change   #(dispatch [:auth.login/edit-field :email (.. % -target -value)])}]
     (when email-err [:p.error {:data-testid "login-email-error"} email-err])
     [:input  {:type        "password"
               :placeholder "Password"
               :disabled    busy?
               :data-testid "login-password"
               :value       (:password draft)
               :on-change   #(dispatch [:auth.login/edit-password {:value (.. % -target -value)}])}]
     (when pw-err [:p.error {:data-testid "login-password-error"} pw-err])
     [:button {:type "submit" :disabled busy?
               :data-testid "login-submit"}
      (if busy? "Signing in…" "Sign in")]
     (when err [:p.error {:data-testid "login-error"} err])]))

;; What you see once the flow hits :locked-out (tagged :auth/locked). That
;; state has no way out, so we replace the form wholesale instead of leaving
;; a zombie form on screen that takes input and ignores it.
(rf/reg-view ^{:doc "Locked-account panel shown when the login flow reaches :locked-out."}
          locked-panel []
  [:div.locked {:data-testid "locked-panel"}
   [:h2 "Account locked"]
   [:p "Too many failed attempts. Contact support to unlock."]])

;; The top-level switch. It reads two tags off the machine and shows one of
;; three faces: a welcome when authed, the locked panel when locked, and the
;; form the rest of the time.
(rf/reg-view ^{:doc "Picks what to show by login state: welcome / locked panel / the form."}
          login-banner []
  (let [authed? @(subscribe [:rf.machine/has-tag? :auth.login/flow :auth/authenticated])
        locked? @(subscribe [:rf.machine/has-tag? :auth.login/flow :auth/locked])]
    [:div.banner {:data-testid "login-banner"}
     (cond
       authed? [:span "Welcome!"]
       locked? [locked-panel]
       :else   [login-form])]))

(rf/reg-view root-view []
  [:div.app
   [:h1 "Sign in"]
   [login-banner]])

;; ============================================================================
;; MOUNT  (CLJS reference; client-only)
;; ============================================================================

;; We park the React root in an atom and create it lazily, inside `run` —
;; never at namespace load. The rule is: requiring a namespace should touch
;; the DOM exactly zero times. Otherwise, the moment another example
;; co-requires this one, two `create-root` calls race for `#app` and you get
;; the kind of bug that only shows up on Tuesdays.
(defonce app-root (reagent-adapter/client-root))

;; `mount!` is browser setup: create the root lazily, then render the view tree
;; inside the frame-root. `^:dev/after-load` is shadow's cue to re-run it on
;; each reload so your edited views re-render into the same root and same frame.
;; This is the canonical mount/boot shape, spelled the same in the counter and
;; todomvc examples. See `docs/core/how-to/boot-and-mount-an-app.md`.
(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    ;; Frame setup, all in one breath. `frame-root {:id …}` is the "make sure
    ;; this frame exists" shape (ENSURE): on first mount it creates
    ;; `:rf/default` at commit, applies the config below, runs `:initial-events`
    ;; once, and scopes the frame into React. On hot reload it finds the frame
    ;; already there and leaves it alone — no double-seed. See docs/core/frames.md.
    ;;
    ;; `model/frame-config` supplies the substrate-free half — the demo-stub
    ;; `:fx-overrides` and the slice-seeding `:initial-events` shared by all
    ;; three login mounts — and we add the `:id` / `:doc` for this frame.
    ;;
    ;; And the root isn't optional scenery: its scope is the reason the
    ;; views' injected `dispatch`/`subscribe` (and the machine reads) know to
    ;; talk to `:rf/default`. Render a `reg-view` outside any frame scope and it
    ;; fails loud rather than guessing.
    (reagent-adapter/render! app-root
      [rf/frame-root (merge {:id  :rf/default
                             :doc "Login demo frame."}
                            model/frame-config)
       [root-view]]
      el)))

(defn run []
  ;; Tell re-frame2 to render through Reagent. The adapter is just a spec
  ;; map; hand it straight to `init!`.
  (rf/init! reagent-adapter/adapter)
  (mount!))
