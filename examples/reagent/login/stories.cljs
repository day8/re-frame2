(ns login.stories
  "Story showcase for the **login** worked example.

   The login form's view-states form a natural Story VARIANT SET —
   Story's core strength is enumerating a single view's view-states
   side by side. This file registers one `reg-variant` per reachable
   state of the example's `:auth.login/flow` machine so the Story
   controls panel flips the page through every state without the
   reader hand-driving the form.

   ## The variant set — grounded in the example's real machine

   `login.core` models the login flow as the five-state
   `:auth.login/flow` machine (`spec/005`):

       :idle → :submitting → {:error-shown | :authed | :locked-out}

   The variants below map onto the states a reader actually reaches,
   driven through REAL events (fidelity `:event`) — the bead's
   highest-fidelity requirement, mirroring the nine_states showcase:

     :story.login/empty               — fresh `:idle` form.
     :story.login/submitting          — request in flight, `:submitting`
                                        (`:auth/busy`), inputs disabled.
     :story.login/invalid-credentials — a malformed `:submit` rejected
                                        at the schema boundary; lights
                                        Xray's Issues ribbon.
     :story.login/auth-error          — `:error-shown` after a 401.
     :story.login/locked-out          — `:locked-out` after the retry
                                        limit is exceeded (the example's
                                        distinctive fourth terminal
                                        state — no testbed counterpart).
     :story.login/success             — `:authed` welcome banner; the
                                        canonical screenshot.

   ### Why no `:filled` variant

   The bead's conceptual list names a `filled` state. In this example
   the form's email/password live in a component-LOCAL Reagent atom
   (idiomatic Form-2; see `login.core/login-form`), NOT in app-db. A
   Story `:setup` drives EVENTS, which cannot seed component-local
   state — so `:filled` (and a free-standing `:validating` /
   `:invalid-field` form state) is not event-reachable here. Rather
   than synthetically seed it (which the fidelity rule forbids), the
   form's input/validation story collapses onto `:invalid-credentials`
   — a real schema-boundary rejection — keeping every variant honest
   at fidelity `:event`.

   ## Xray-richness — the auth-submit cascade

   Story allocates each variant its own frame under `:preset :story`
   (spec/002 §Frame presets), which redirects `:rf.http/managed` to
   the framework-shipped `:rf.http/managed-canned-success` stub. So a
   real submit runs the FULL auth cascade end to end:

       [:auth.login/flow [:auth.login/submit creds]]   (→ :submitting)
         → machine `:issue-request` action
             → [:rf.http/managed {…}]                  (a real fx)
                 → canned-success reply                (Side Effects panel)
                 → [:auth.login/flow [:auth.login/success …]]
                     → :authed

   That whole chain lands on the Epoch tape + the Trace stream, and the
   `:rf.http/managed` fx shows in the Side Effects panel — pick the
   `:success` variant, press Ctrl+Shift+C, and watch the submit cascade
   light up Xray end to end.

   Two variants light Xray's Issues ribbon (the bead's failure-path
   requirement):
     - `:invalid-credentials` — the malformed `:submit` is rejected by
       the `Credentials` schema at the event boundary (`:no-recovery`);
       the handler never runs and an Issue is raised.
     - `:auth-error` / `:locked-out` — a 401 failure cascade; the
       `:rf.http/managed` request fires (Side Effects) and the
       `:auth.login/failure` follow-on records the error.

   ## Authoring discipline

   Per spec/007 §Variants every variant body is plain data — no
   fn-slots. The view at the centre of each variant is the example's
   own `login.core/root-view`, referenced by id. The canonical Story
   tags auto-install on the first `reg-*` call, so no
   explicit boot step is needed.

   This is a parallel SHOWCASE to the gate-side `login_form` testbed
   (`tools/story/testbeds/login_form`), which stays the fixture for
   Story's own tests. Examples are test-free: these
   stories carry NO `:script` / `:rf.assert/*` — they are a showcase,
   not a test surface."
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
;; The live example's machine `:issue-request` action issues a real
;; `:rf.http/managed` request to `/api/login` and routes the reply back
;; through `:auth.login/success` / `:auth.login/failure`. In the live
;; `#/` app, `login.core/run` redirects `:rf.http/managed` to the demo
;; stub. Inside the Story shell each variant frame runs under
;; `:preset :story`, which redirects `:rf.http/managed` to the
;; framework-shipped `:rf.http/managed-canned-success` stub instead —
;; that stub echoes the request's `:value` slot back as the success
;; payload (Spec 014 §Testing).
;;
;; So this Story submit event simply dispatches the real
;; `:auth.login/submit` sub-event: the SAME machine action fires the
;; SAME real `:rf.http/managed` fx (visible in Xray's Side Effects
;; panel), and the canned-success stub resolves it deterministically —
;; no app-side fx-override required. The success reply's `:value` is the
;; stub's echo of the (absent) request `:value`, i.e. nil; the welcome
;; banner keys off the `:auth/authenticated` tag, not the value, so the
;; cascade lands cleanly at `:authed`.
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
;; Wrap every registration in a top-level fn so a hot-reload could
;; re-fire the lot after a clear-all!. The fn fires once at namespace
;; load via the trailing call, so consumers who just `:require` this ns
;; get the side-table populated. (No test fixture — examples are
;; test-free.)
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
  ;; reg-variant — the reachable states, each driven through REAL events
  ;; (fidelity `:event`); no `:db-seed` / `:sub-overrides`.
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
  ;; email) is rejected by the `Credentials` boundary schema BEFORE the
  ;; handler runs (Spec 010 §Validation order step 1; recovery
  ;; `:no-recovery`). The machine never transitions out of `:idle`; the
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

  ;; Locked out — the example's distinctive fourth terminal state. Once
  ;; the flow has had three prior failed attempts the `:under-retry-limit`
  ;; guard fails, so the next failure routes to `:locked-out` (and the
  ;; `:lock-account` action fires a real `:rf.http/managed` lock request).
  ;; The variant sequences submit → failure four times to exhaust the
  ;; limit. No testbed counterpart — unique to this example's machine.
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
               empty → submitting → invalid → auth-error → locked-out
               → success."
     :layout   :grid
     :variants [:story.login/empty
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
