(ns uix.login.core
  "A login flow, rendered through UIx. The same feature lives on Reagent too,
   and that's exactly the point of this file: watch how little has to change
   to move between them.

   Everything below the views — the state machine, the schemas, the form-slice
   events and subs, the managed-HTTP effect — lives in the substrate-free
   [`login.model`](../../../core/login/model.cljs) namespace, the ONE owner of
   the `auth.login` dataflow. This file `:require`s it (registering everything at
   ns-load) and adds only the UIx view layer + mount. The Reagent twin
   (`login.core`) imports the identical model. Same data, two doorways: here a
   view is a UIx `defui` that reads subscriptions through the `use-subscribe`
   hook; the Reagent twin reaches for `reg-view`.

   The machine tags a few of its states — `:auth/busy`, `:auth/authenticated`,
   `:auth/locked` — and views ask about them through the `:rf.machine/has-tag?`
   framework sub. When the flow finally gives up, the terminal `:locked-out`
   state swaps the form for a dead-end locked-account panel.

   For the boundary mechanics — `use-subscribe`, `use-frame`,
   `frame-root` / `frame-provider`, and what stays put across React wrappers — see
   docs/core/how-to/use-uix-or-slim.md."
  (:require [uix.core :as uix :refer [$ defui]]
            [uix.dom  :as uix-dom]
            [re-frame.core :as rf]
            ;; The substrate-free model owner (examples/core/login/model.cljs).
            ;; Requiring it registers every shared `auth.login` schema, fx,
            ;; machine, event, and sub, and hands us `model/frame-config` for the
            ;; mount below. It names no substrate — the UIx code lives only here.
            [login.model :as model]
            [re-frame.adapter.uix :as uix-adapter]))

;; ============================================================================
;; VIEWS  (UIx — defui + use-subscribe)
;; ============================================================================
;;
;; Here, at last, is the substrate seam — and it's a thin one. A UIx view is
;; just a `defui`: it reads each subscription through the `use-subscribe` hook
;; and gets `dispatch` off the `use-frame` hook (capture-frame in hook
;; position). The Reagent twin registers the same views with `reg-view` and is
;; simply handed `dispatch`/`subscribe` — the same hold primitive in its other
;; spelling. The subscription vectors and event vectors don't change one
;; character between them; all that differs is how a React component reaches
;; the wires. See docs/core/how-to/use-uix-or-slim.md.
;;
;; The inputs are controlled: each `:value` reads the draft from
;; `:auth.login/draft`, and `:on-change` dispatches an edit event
;; (`:auth.login/edit-field` for email, `:auth.login/edit-password` for the
;; secret). The draft lives in app-db, which is exactly why you won't find a
;; `uix/use-state` anywhere in here.
(defui login-form []
  (let [draft     (uix-adapter/use-subscribe [:auth.login/draft])
        busy?     (uix-adapter/use-subscribe [:rf.machine/has-tag?
                                              :auth.login/flow :auth/busy])
        err       (uix-adapter/use-subscribe [:auth.login/error])
        email-err (uix-adapter/use-subscribe [:auth.login/field-error :email])
        pw-err    (uix-adapter/use-subscribe [:auth.login/field-error :password])
        {:keys [dispatch]} (uix-adapter/use-frame)]
    ($ :form.login-form
       {:data-testid "login-form"
        :on-submit (fn [e]
                     (.preventDefault e)
                     (when-not busy?
                       (dispatch [:auth.login/submit-form])))}
       ($ :input  {:type        "email"
                   :placeholder "Email"
                   :disabled    busy?
                   :data-testid "login-email"
                   :value       (:email draft)
                   :on-change   #(dispatch [:auth.login/edit-field :email (.. % -target -value)])})
       (when email-err ($ :p.error {:data-testid "login-email-error"} email-err))
       ($ :input  {:type        "password"
                   :placeholder "Password"
                   :disabled    busy?
                   :data-testid "login-password"
                   :value       (:password draft)
                   :on-change   #(dispatch [:auth.login/edit-password {:value (.. % -target -value)}])})
       (when pw-err ($ :p.error {:data-testid "login-password-error"} pw-err))
       ($ :button {:type "submit" :disabled busy?
                   :data-testid "login-submit"}
          (if busy? "Signing in…" "Sign in"))
       (when err ($ :p.error {:data-testid "login-error"} err)))))

;; The dead-end panel, shown once the flow reaches :locked-out (tagged
;; :auth/locked). That state has nowhere left to go, so we swap the form out
;; for this rather than leave a form on screen that no longer does anything.
(defui locked-panel []
  ($ :div.locked {:data-testid "locked-panel"}
     ($ :h2 "Account locked")
     ($ :p "Too many failed attempts. Contact support to unlock.")))

(defui login-banner []
  (let [authed? (uix-adapter/use-subscribe [:rf.machine/has-tag?
                                            :auth.login/flow :auth/authenticated])
        locked? (uix-adapter/use-subscribe [:rf.machine/has-tag?
                                            :auth.login/flow :auth/locked])]
    ($ :div.banner {:data-testid "login-banner"}
       (cond
         authed? ($ :span "Welcome!")
         locked? ($ locked-panel)
         :else   ($ login-form)))))

(defui root-view []
  ($ :div.app
     ($ :h1 "Sign in")
     ($ login-banner)))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; We stash the React root in an atom and only build it lazily inside `run`,
;; never at namespace load. The reason (examples/TESTING.md §Example
;; mount-isolation convention): loading a namespace must touch no DOM, so two
;; example namespaces loaded side by side can't both race to call `create-root`
;; on the shared `#app`.
(defonce react-root (atom nil))

;; DOM setup lives in `mount!`, tagged `^:dev/after-load` so shadow-cljs re-runs
;; it after each hot reload — edited views re-render into the same root and frame.
(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (when-not @react-root
      (reset! react-root (uix-dom/create-root el)))
    ;; Frame setup, all in one spot. The `frame-root` at the render root
    ;; owns the frame: on the first mount it creates the `:rf/default` frame,
    ;; applies the config (`model/frame-config` points `:rf.http/managed` at our
    ;; demo stub and seeds the slice via `:initial-events`), and runs those
    ;; events once. On a hot reload it finds the frame already there, reuses it,
    ;; and skips the events. The `:id :rf/default` names the frame that
    ;; `use-subscribe` and the `use-frame` hook inside `login-form` resolve
    ;; against — which is why those calls need a provider somewhere above them in
    ;; the tree.
    ;;
    ;; `model/frame-config` is the substrate-free half, shared with the Reagent
    ;; mount; we add the `:id` / `:doc` for this frame. The machine
    ;; asks for nothing here: its `:initial` and `:data` seed the snapshot in
    ;; runtime-db the first time the flow runs
    ;; (see docs/machines/glossary.md#snapshot).
    (uix-dom/render-root
      ($ uix-adapter/frame-root {:id  :rf/default
                                     :doc "Login (UIx) demo frame."
                                     ;; `:&` spreads the substrate-free
                                     ;; `model/frame-config` (demo-stub
                                     ;; `:fx-overrides` + slice-seed
                                     ;; `:initial-events`) into these props.
                                     :&   model/frame-config}
         ($ root-view))
      @react-root)))

(defn run []
  ;; Tell the runtime to render through UIx. (This installs the adapter; it does
  ;; not create a frame — the frame-root below does that.)
  (rf/init! uix-adapter/adapter)
  (mount!))
