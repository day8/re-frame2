(ns helix.login.core
  "The login example, rendered through Helix.

   A login form, a state machine for the submit/auth lifecycle, schemas
   guarding the boundaries, and a stubbed HTTP call. That whole model — the
   machine, the schemas, the form-slice events and subs, the managed-HTTP
   surface — lives in the substrate-free
   [`login.model`](../../../core/login/model.cljs) namespace, the ONE owner of
   the `auth.login` dataflow, `:require`d below for its side-effecting
   registrations. Read it for the ideas; the login flow itself is taught in full
   there and at the Reagent reference example.

   This file is the Helix HALF: the only substrate-specific code. Here the views
   are Helix `defnc` components that read subs through the adapter's
   `use-subscribe` hook, plus the React root and mount. The Reagent
   (`login.core`) and UIx (`uix.login.core`) twins import the identical
   `login.model` and supply their own view layer. Same dataflow, three view
   layers.

   The machine's states wear tags — `:auth/busy`, `:auth/authenticated`,
   `:auth/locked` — and the views ask about them with the `:rf.machine/has-tag?`
   framework sub. Keep guessing the password and the fourth wrong try trips the
   retry guard: the flow lands in the terminal `:locked-out` state, and the form
   is replaced by a dead-end, non-interactive locked-account panel.

   The machines guide (docs/machines/concepts.md) and the form recipe
   (docs/core/how-to/build-a-form.md) cover the two big ideas at length."
  (:require ["react-dom/client" :as react-dom-client]
            [helix.core         :refer [$ defnc]]
            [helix.dom          :as d]
            [re-frame.core :as rf]
            ;; The substrate-free model owner (examples/core/login/model.cljs).
            ;; Requiring it registers every shared `auth.login` schema, fx,
            ;; machine, event, and sub, and hands us `model/frame-config` for the
            ;; mount below. It names no substrate — the Helix code lives only here.
            [login.model :as model]
            [re-frame.adapter.helix :as helix-adapter]))

;; ============================================================================
;; VIEWS  (Helix — defnc + use-subscribe)
;; ============================================================================
;;
;; The Helix view idiom, and the only place this example differs from the
;; Reagent reference. A view is a plain `defnc`. It reads a subscription
;; through the adapter's `use-subscribe` hook, and takes `dispatch` off the
;; `use-frame` hook (capture-frame in hook position).
;; `:rf.machine/has-tag?` reads are *ask, don't tell* state-tag
;; queries; `:auth.login/error` is a named sub. To the call site they're
;; identical — both are just subscriptions.

(defnc login-form []
  (let [draft     (helix-adapter/use-subscribe [:auth.login/draft])
        busy?     (helix-adapter/use-subscribe [:rf.machine/has-tag?
                                                :auth.login/flow :auth/busy])
        err       (helix-adapter/use-subscribe [:auth.login/error])
        email-err (helix-adapter/use-subscribe [:auth.login/field-error :email])
        pw-err    (helix-adapter/use-subscribe [:auth.login/field-error :password])
        ;; Take `dispatch` off the render-time `use-frame` frame api. It
        ;; resolves to this frame (`:rf/default`) through the provider in
        ;; `mount!`. Every dispatch goes to a frame; there is no global
        ;; dispatch.
        {:keys [dispatch]} (helix-adapter/use-frame)]
    ;; Controlled inputs: each input's `:value` reads the draft from the
    ;; `:auth.login/draft` sub, and `:on-change` dispatches an edit event
    ;; (`:auth.login/edit-field` for email, `:auth.login/edit-password` for the
    ;; secret). The draft lives in app-db, not in a `use-state` hook. It is
    ;; validated and issued as the login request at the :on-submit dispatch of
    ;; `:auth.login/submit-form`.
    (d/form
       {:class "login-form"
        :data-testid "login-form"
        :on-submit (fn [e]
                     (.preventDefault e)
                     (when-not busy?
                       (dispatch [:auth.login/submit-form])))}
       (d/input  {:type        "email"
                  :placeholder "Email"
                  :disabled    busy?
                  :data-testid "login-email"
                  :value       (:email draft)
                  :on-change   #(dispatch [:auth.login/edit-field :email (.. % -target -value)])})
       (when email-err (d/p {:class "error" :data-testid "login-email-error"} email-err))
       (d/input  {:type        "password"
                  :placeholder "Password"
                  :disabled    busy?
                  :data-testid "login-password"
                  :value       (:password draft)
                  :on-change   #(dispatch [:auth.login/edit-password {:value (.. % -target -value)}])})
       (when pw-err (d/p {:class "error" :data-testid "login-password-error"} pw-err))
       (d/button {:type "submit" :disabled busy?
                  :data-testid "login-submit"}
          (if busy? "Signing in…" "Sign in"))
       (when err (d/p {:class "error" :data-testid "login-error"} err)))))

;; Terminal lockout panel — rendered once the flow reaches :locked-out
;; (tagged :auth/locked). The state has no transitions, so the form is
;; swapped out entirely rather than left enabled-but-dead.
(defnc locked-panel []
  (d/div
     {:class "locked"
      :data-testid "locked-panel"}
     (d/h2 "Account locked")
     (d/p "Too many failed attempts. Contact support to unlock.")))

(defnc login-banner []
  (let [authed? (helix-adapter/use-subscribe [:rf.machine/has-tag?
                                              :auth.login/flow :auth/authenticated])
        locked? (helix-adapter/use-subscribe [:rf.machine/has-tag?
                                              :auth.login/flow :auth/locked])]
    (d/div
       {:class "banner"
        :data-testid "login-banner"}
       (cond
         authed? (d/span "Welcome!")
         locked? ($ locked-panel)
         :else   ($ login-form)))))

(defnc root-view []
  (d/div
     {:class "app"}
     (d/h1 "Sign in")
     ($ login-banner)))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; Hold the React root in an atom and create it lazily inside `run`, not at
;; ns-load. Loading the namespace must produce no DOM side effects, so that
;; co-loaded example namespaces don't race `createRoot` onto the shared `#app`.
(defonce react-root (atom nil))

;; DOM setup lives in `mount!`, tagged `^:dev/after-load` so shadow-cljs re-runs
;; it after each hot reload — edited views re-render into the same root and frame.
(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (when-not @react-root
      (reset! react-root (react-dom-client/createRoot el)))
    ;; Frame setup in one spot. The `frame-root`'s `{:id …}` ENSURE creates
    ;; the `:rf/default` frame on first mount and applies the config
    ;; (`:doc`, plus the `model/frame-config` spread below). On hot reload it
    ;; reuses the existing frame and its state, so the demo survives a reload.
    ;; The machine snapshot needs no seeding: it self-initialises from the spec's
    ;; `:initial`/`:data` when the flow first runs.
    ;;
    ;; The provider also scopes the frame into React context, so the
    ;; `use-subscribe` hook and the `use-frame` capture in `login-form`
    ;; resolve to it. The provider is required — without it those reads raise
    ;; `:rf.error/no-frame-context`.
    ;;
    ;; `:&` spreads the substrate-free `model/frame-config` — shared with the
    ;; Reagent and UIx mounts — into these props: the demo-stub `:fx-overrides`
    ;; routing `:rf.http/managed` to the stub, and the `:initial-events` that
    ;; seed the form slice ([:auth.login/initialise-form]) to its empty shape, so
    ;; the controlled inputs read an empty draft — not nil — on that first render.
    ;; Skip that seed and each input's `:value` reads `(:email nil)` = nil on
    ;; first paint, which React treats as an UNCONTROLLED input, then flips to
    ;; controlled the moment the slice is seeded — the exact warning this seeding
    ;; avoids.
    (.render @react-root
             ($ helix-adapter/frame-root
                {:id  :rf/default
                 :doc "Login (Helix) demo frame."
                 :&   model/frame-config}
                ($ root-view)))))

(defn run []
  ;; Install the Helix adapter once, before the first render.
  (rf/init! helix-adapter/adapter)
  (mount!))
