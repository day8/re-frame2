(ns login.stories
  "Story showcase for the login worked example.

   The login form's view-states make a natural Story variant set —
   Story's strength is enumerating one view's states side by side. This
   file registers one `reg-variant` per reachable state of the example's
   `:auth.login/flow` machine, so the controls panel flips the page
   through every state without the reader hand-driving the form. See the
   Story guide (docs/story/index.md).

   The machine has five states:

       :idle → :submitting → {:error-shown | :authed | :locked-out}

   The variants map onto the states a reader actually reaches, each
   driven through real events:

     :story.login/empty               — fresh `:idle` form.
     :story.login/filled              — a draft typed into the app-db
                                        login-form slice; still `:idle`.
     :story.login/submitting          — request in flight, `:submitting`
                                        (`:auth/busy`), inputs disabled.
     :story.login/invalid-credentials — a malformed `:submit` rejected
                                        at the schema boundary; lights
                                        Xray's Issues ribbon.
     :story.login/auth-error          — `:error-shown` after a 401.
     :story.login/locked-out          — `:locked-out` after the retry
                                        limit is exceeded.
     :story.login/success             — `:authed` welcome banner; the
                                        canonical screenshot.

   The auth-submit cascade in Xray

   Each variant runs in its own frame under `:preset :story`, which
   redirects `:rf.http/managed` to the framework's canned-success stub.
   So a real submit runs the full auth cascade end to end:

       [:auth.login/flow [:auth.login/submit creds]]   (→ :submitting)
         → machine `:issue-request` action
             → [:rf.http/managed {…}]                  (a real fx)
                 → canned-success reply                (Side Effects panel)
                 → [:auth.login/flow [:auth.login/success …]]
                     → :authed

   The whole chain lands on the Epoch tape and the Trace stream, and the
   `:rf.http/managed` fx shows in the Side Effects panel — pick the
   `:success` variant, press Ctrl+Shift+C, and watch it light up.

   Two variants light Xray's Issues ribbon:
     - `:invalid-credentials` — the malformed `:submit` is rejected by
       the `Credentials` schema at the event boundary; the handler never
       runs and an Issue is raised.
     - `:auth-error` / `:locked-out` — a 401 failure cascade; the
       `:rf.http/managed` request fires (Side Effects) and the
       `:auth.login/failure` follow-on records the error.

   Every variant body is plain data — no fn-slots. The view at the
   centre of each is the example's own `login.core/root-view`,
   referenced by id. The canonical Story tags auto-install on the first
   `reg-*` call, so no explicit boot step is needed.

   Examples are test-free: these stories carry no `:script` /
   `:rf.assert/*` — they are a showcase, not a test surface."
  (:require [re-frame.core :as rf]
            [re-frame.story :as story]
            ;; Source the example's registrations (the machine, schemas,
            ;; demo fx, subs, views). The variant bodies below reference
            ;; its event-ids + the `root-view` view-id as plain keywords;
            ;; requiring the ns fires every `reg-*` so those ids resolve.
            [login.core]))

;; ---------------------------------------------------------------------------
;; Story-side submit event — the Xray-rich auth-submit cascade.
;;
;; The machine's `:issue-request` action issues a real `:rf.http/managed`
;; request and routes the reply back through `:auth.login/success` /
;; `:auth.login/failure`. Each variant frame runs under `:preset :story`,
;; which redirects `:rf.http/managed` to the framework's canned-success
;; stub (that stub echoes the request's `:value` slot back as the success
;; payload).
;;
;; So this event simply dispatches the real `:auth.login/submit`
;; sub-event: the same machine action fires the same real
;; `:rf.http/managed` fx (visible in Xray's Side Effects panel), and the
;; canned-success stub resolves it — no app-side fx-override needed. The
;; reply `:value` is nil (the request carries none), but the welcome
;; banner keys off the `:auth/authenticated` tag, so the cascade lands
;; cleanly at `:authed`.
;; ---------------------------------------------------------------------------

(rf/reg-event :login.story/submit
  {:doc "Story-shell driver for the auth-submit cascade. Dispatches the
         real `:auth.login/submit` sub-event so the machine's
         `:issue-request` action fires the real `:rf.http/managed` fx;
         under the `:preset :story` frame the canned-success stub
         resolves it and the `:auth.login/success` follow-on lands the
         flow at `:authed`. The whole chain shows in Xray's Epoch /
         Trace / Side Effects panels."}
  (fn handler-story-submit [_ [_ creds]]
    {:fx [[:dispatch [:auth.login/flow [:auth.login/submit creds]]]]}))

;; ---------------------------------------------------------------------------
;; register-all!
;;
;; Every registration lives in one top-level fn so a hot-reload can
;; re-fire the lot. The fn runs once at namespace load via the trailing
;; call, so requiring this ns populates the Story registry.
;; ---------------------------------------------------------------------------

(def ^:private good-creds
  "Credentials the demo accepts (mirrors `login.core/good-password`).
   Only used to shape the submit payload past the `Credentials`
   boundary schema; the canned-success stub ignores them."
  {:email "ada@example.com" :password "correct-horse"})

(defn register-all!
  "Register the login example's Story artefacts. Idempotent.
   The canonical vocabulary auto-installs on the first `reg-*` call
   — no explicit boot step required."
  []

  ;; -------------------------------------------------------------------------
  ;; reg-tag — a project tag marking the canonical screenshot variant.
  ;; -------------------------------------------------------------------------

  (story/reg-tag :login/canonical
    {:doc "Marks the variant that ships as the example's canonical
          screenshot — the `:success` welcome-banner state."})

  ;; -------------------------------------------------------------------------
  ;; reg-story — the parent story. Its `:component` (the example's
  ;; `root-view`) and `:tags` inherit down to every variant below; the
  ;; per-state setup lives on the variants.
  ;; -------------------------------------------------------------------------

  (story/reg-story :story.login
    {:doc        "The login form — every reachable state of the
                 `:auth.login/flow` machine, as runnable variants."
     :component  :login.core/root-view
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; reg-variant — the reachable states, each driven through real events;
  ;; no `:db-seed` / `:sub-overrides`.
  ;; -------------------------------------------------------------------------

  ;; Empty — the entry state. The variant fires a no-op
  ;; `:auth.login/dismiss` so the machine's `:initial` cascade seeds the
  ;; snapshot; without it the `[:rf.runtime/machines :snapshots
  ;; :auth.login/flow]` runtime-db slot is nil until the first real event.
  (story/reg-variant :story.login/empty
    {:doc        "Fresh form, nothing typed, no submit clicked — the
                 `:idle` entry state a user lands on. Email + password
                 inputs + an enabled 'Sign in' button."
     :setup      [[:auth.login/flow [:auth.login/dismiss]]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Filled — the user has typed into both fields but not yet submitted.
  ;; The draft is seeded by dispatching the same `:auth.login/edit-field`
  ;; events the controlled inputs fire on keystroke, writing into the
  ;; app-db login-form slice. The machine stays `:idle` (no submit yet);
  ;; the inputs render the seeded draft as their `:value`.
  (story/reg-variant :story.login/filled
    {:doc        "Both fields filled in, nothing submitted yet — the form
                 mid-edit. The draft was typed into the app-db login-form
                 slice via real `:auth.login/edit-field` events, so the
                 inputs show their `:value` and the flow is still `:idle`."
     :setup      [[:auth.login/flow [:auth.login/dismiss]]
                  [:auth.login/edit-field :email "ada@example.com"]
                  [:auth.login/edit-field :password "correct-horse"]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Submitting — a submit is in flight. `force-fx-stub` intercepts the
  ;; `:rf.http/managed` fx the `:issue-request` action emits: the stub
  ;; records the call (visible in Side Effects) but resolves NOTHING, so
  ;; no `:success` / `:failure` follow-on fires and the canvas locks at
  ;; `:submitting` (`:auth/busy`) — inputs disabled, button reads
  ;; "Signing in…".
  (story/reg-variant :story.login/submitting
    {:doc        "First submit; the HTTP request is in flight. Inputs
                 are disabled and the button reads 'Signing in…'. The
                 fx-stub records the `:rf.http/managed` request and
                 resolves nothing, so the canvas locks in `:submitting`."
     :setup      [[:auth.login/flow [:auth.login/submit good-creds]]]
     :decorators [[story/force-fx-stub-id :rf.http/managed {}]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Invalid credentials — a malformed `:submit` (short password, bad
  ;; email) is rejected by the `Credentials` boundary schema before the
  ;; handler runs. The machine never transitions out of `:idle`; the
  ;; rejection raises an Issue that lights Xray's Issues ribbon.
  (story/reg-variant :story.login/invalid-credentials
    {:doc        "A malformed submit (too-short password) is rejected at
                 the event-schema boundary — the `:auth.login/flow`
                 handler never runs and the flow stays at `:idle`. Open
                 Xray (Ctrl+Shift+C): the rejection lights the Issues
                 ribbon, showing schema enforcement at the boundary."
     :setup      [[:auth.login/flow [:auth.login/submit {:email    "nope"
                                                          :password "short"}]]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Auth error — the server rejected the credentials. The fx-stub
  ;; intercepts the real request (recorded in Side Effects), then the
  ;; variant manually drives the `:auth.login/failure` sub-event the
  ;; stubbed-out request would otherwise have triggered — the canonical
  ;; Story shape for pinning a specific terminal state regardless of
  ;; timing. The machine lands at `:error-shown` with the error surfaced.
  (story/reg-variant :story.login/auth-error
    {:doc        "Server rejected the credentials. The form is
                 re-enabled and the error message surfaces under the
                 submit button (`:error-shown`). The real
                 `:rf.http/managed` request shows in Xray's Side Effects
                 panel; the manually-driven `:failure` follow-on records
                 the error."
     :setup      [[:auth.login/flow [:auth.login/submit good-creds]]
                  [:auth.login/flow [:auth.login/failure
                                     {:failure {:status  401
                                                :message "Invalid credentials."}}]]]
     :decorators [[story/force-fx-stub-id :rf.http/managed {}]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Locked out — the fourth terminal state. Once the flow has had three
  ;; prior failed attempts the `:under-retry-limit` guard fails, so the
  ;; next failure routes to `:locked-out` (and the `:lock-account` action
  ;; fires a real `:rf.http/managed` lock request). The variant sequences
  ;; submit → failure four times to exhaust the limit.
  (story/reg-variant :story.login/locked-out
    {:doc        "Too many failed attempts — the `:under-retry-limit`
                 guard fails on the fourth failure and the flow reaches
                 the terminal `:locked-out` state, firing the
                 `:lock-account` request (visible in Xray's Side Effects
                 panel). The account is locked; the form no longer
                 retries."
     :setup      [[:auth.login/flow [:auth.login/submit good-creds]]
                  [:auth.login/flow [:auth.login/failure
                                     {:failure {:status 401 :message "Invalid credentials."}}]]
                  [:auth.login/flow [:auth.login/submit good-creds]]
                  [:auth.login/flow [:auth.login/failure
                                     {:failure {:status 401 :message "Invalid credentials."}}]]
                  [:auth.login/flow [:auth.login/submit good-creds]]
                  [:auth.login/flow [:auth.login/failure
                                     {:failure {:status 401 :message "Invalid credentials."}}]]
                  [:auth.login/flow [:auth.login/submit good-creds]]
                  [:auth.login/flow [:auth.login/failure
                                     {:failure {:status 423 :message "Account locked."}}]]]
     :decorators [[story/force-fx-stub-id :rf.http/managed {}]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Success — the canonical screenshot. The full real auth cascade runs
  ;; through `:login.story/submit`: real submit → `:issue-request` →
  ;; real `:rf.http/managed` fx → canned-success reply →
  ;; `:auth.login/success` → `:authed`. Pick this variant + Ctrl+Shift+C
  ;; to watch the whole cascade light up Xray's Epoch / Trace / Side
  ;; Effects panels.
  (story/reg-variant :story.login/success
    {:doc        "Server accepted the credentials. The form is replaced
                 by the 'Welcome!' banner (`:authed`). The full
                 auth-submit cascade — submit → `:rf.http/managed` →
                 canned reply → `:success` — runs through real events;
                 inspect it in Xray. The canonical screenshot."
     :setup      [[:login.story/submit good-creds]]
     :tags       #{:dev :docs :login/canonical}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; reg-workspace — two layouts over the variant set.
  ;;
  ;; `:grid` pins the states in narrative order for the README
  ;; screenshot; `:variants-grid` auto-enumerates every variant of the
  ;; parent story (new variants appear without touching the workspace).
  ;; -------------------------------------------------------------------------

  (story/reg-workspace :Workspace.login/all-states
    {:doc      "Every login state, side by side in narrative order:
               empty → filled → submitting → invalid → auth-error →
               locked-out → success."
     :layout   :grid
     :variants [:story.login/empty
                :story.login/filled
                :story.login/submitting
                :story.login/invalid-credentials
                :story.login/auth-error
                :story.login/locked-out
                :story.login/success]
     :columns  3
     :tags     #{:docs}})

  (story/reg-workspace :Workspace.login/auto-grid
    {:doc     "Auto-enumerated grid — pulls every variant off
              :story.login. New variants appear here without touching
              this workspace."
     :layout  :variants-grid
     :for     :story.login
     :columns 3
     :tags    #{:docs}}))

;; Fire the registrations once at namespace load.
(register-all!)
