(ns login.stories
  "A Story showcase for the login example.

   Story does one thing beautifully: it lines up every state of a single
   view, side by side, so you can see them all at once instead of clicking
   your way there one at a time. A login form is a perfect fit, because its
   states are exactly the machine's states. So this file registers one
   `reg-variant` per reachable state of `:auth.login/flow`, and the controls
   panel becomes a remote control that flips the page through the whole flow
   — no typing into the form required. See the Story guide
   (docs/story/index.md).

   The machine has five states:

       :idle → :submitting → {:error-shown | :authed | :locked-out}

   And here are the variants, each one a real, reachable state driven by
   real events (no faking the snapshot):

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

   Watching the auth-submit cascade in Xray

   Here's the part that's genuinely fun. Each variant runs in its own frame
   under `:preset :story`, which quietly redirects `:rf.http/managed` to the
   framework's canned-success stub. So a submit isn't a mock — it's the real
   cascade, running end to end:

       [:auth.login/flow [:auth.login/submit creds]]   (→ :submitting)
         → machine `:issue-request` action
             → [:rf.http/managed {…}]                  (a real fx)
                 → canned-success reply                (Side Effects panel)
                 → [:auth.login/flow [:auth.login/success …]]
                     → :authed

   Every hop lands on the Epoch tape and the Trace stream, and the
   `:rf.http/managed` fx surfaces in the Side Effects panel. Pick the
   `:success` variant, press Ctrl+Shift+C, and watch the whole thing light
   up like a pinball table.

   Two variants deliberately set off Xray's Issues ribbon — because seeing
   the framework *catch* a mistake is half the lesson:
     - `:invalid-credentials` — the malformed `:submit` bounces off the
       `Credentials` schema at the boundary; the handler never runs, and an
       Issue is raised.
     - `:auth-error` / `:locked-out` — a 401 failure cascade; the
       `:rf.http/managed` request fires (Side Effects) and the
       `:auth.login/failure` follow-on records the error.

   One more thing worth noticing: every variant body below is plain data —
   no function slots anywhere. The view at the heart of each is the
   example's own `login.core/root-view`, named by id. And the canonical
   Story vocabulary installs itself on the first `reg-*` call, so there's no
   boot step to remember.

   Examples are test-free: these stories carry no `:script` / `:rf.assert/*`
   — they're a showcase, not a test surface."
  (:require [re-frame.core :as rf]
            [re-frame.story :as story]
            ;; Source the example's registrations (the machine, schemas,
            ;; demo fx, subs, views). The variant bodies below reference
            ;; its event-ids + the `root-view` view-id as plain keywords;
            ;; requiring the ns fires every `reg-*` so those ids resolve.
            [login.core]))

;; ---------------------------------------------------------------------------
;; The story-side submit — our way of triggering the full, Xray-rich pipeline run.
;;
;; Recall what the machine does on submit: its `:issue-request` action fires
;; a real `:rf.http/managed` request and pipes the reply back through
;; `:auth.login/success` / `:auth.login/failure`. And recall the `:preset
;; :story` frame redirects `:rf.http/managed` to the framework's
;; canned-success stub (which just echoes the request's `:value` slot back
;; as the payload).
;;
;; Put those together and this event has almost nothing to do: it just
;; dispatches the real `:auth.login/submit`. Same machine action, same real
;; fx (right there in Xray's Side Effects panel), and the canned stub
;; answers it — no app-side override needed. The reply `:value` happens to
;; be nil (we sent none), but that's fine: the welcome banner keys off the
;; `:auth/authenticated` tag, not the payload, so the flow still settles
;; happily at `:authed`.
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
;; We gather every registration into one top-level fn for a practical
;; reason: hot-reload can then re-fire the whole set in one go. The trailing
;; call at the bottom runs it once on load, so merely requiring this ns is
;; enough to populate the Story registry.
;; ---------------------------------------------------------------------------

(def ^:private good-creds
  "Credentials the demo would accept (they mirror `login.core/good-password`).
   We only need them to get a submit payload past the `Credentials` boundary
   schema — once it's through, the canned-success stub doesn't even look at
   them."
  {:email "ada@example.com" :password "correct-horse"})

(defn register-all!
  "Register all of the login example's Story artefacts. Safe to call twice —
   it's idempotent, and the canonical vocabulary installs itself on the first
   `reg-*` call, so there's no separate boot step to remember."
  []

  ;; -------------------------------------------------------------------------
  ;; reg-tag — one project tag, marking which variant is the "hero" shot.
  ;; -------------------------------------------------------------------------

  (story/reg-tag :login/canonical
    {:doc "Marks the variant that ships as the example's canonical
          screenshot — the `:success` welcome-banner state."})

  ;; -------------------------------------------------------------------------
  ;; reg-story — the parent. Think of it as the shared backdrop: its
  ;; `:component` (the example's `root-view`) and `:tags` flow down to every
  ;; variant, so each variant only has to describe what makes it different.
  ;; -------------------------------------------------------------------------

  (story/reg-story :story.login
    {:doc        "The login form — every reachable state of the
                 `:auth.login/flow` machine, as runnable variants."
     :component  :login.core/root-view
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; reg-variant — the reachable states, one per variant. The house style
  ;; here: drive each into place with real events, never by hand-poking the
  ;; db (`:db-seed`) or faking subs (`:sub-overrides`). What you see is what
  ;; the real flow produces.
  ;; -------------------------------------------------------------------------

  ;; Empty — the state a user first lands on. The one bit of stage-setting:
  ;; we fire a harmless `:auth.login/dismiss` to nudge the machine's
  ;; `:initial` cascade into seeding the snapshot. Without that nudge the
  ;; `[:rf.runtime/machines :snapshots :auth.login/flow]` slot sits nil until
  ;; the first real event, and there'd be nothing to render.
  (story/reg-variant :story.login/empty
    {:doc        "Fresh form, nothing typed, no submit clicked — the
                 `:idle` entry state a user lands on. Email + password
                 inputs + an enabled 'Sign in' button."
     :setup      [[:auth.login/flow [:auth.login/dismiss]]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Filled — both fields typed, nothing submitted. We get there honestly:
  ;; by dispatching the very same `:auth.login/edit-field` events a real
  ;; keystroke fires, which write into the app-db slice. The machine stays
  ;; `:idle`, and the inputs show the seeded draft as their `:value`. No
  ;; shortcuts.
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

  ;; Submitting — frozen mid-request, on purpose. The trick is
  ;; `force-fx-stub`: it catches the `:rf.http/managed` fx that
  ;; `:issue-request` emits, records it (you'll see it in Side Effects), and
  ;; then deliberately answers nothing. With no reply, no `:success` /
  ;; `:failure` ever fires, so the canvas is stuck in `:submitting`
  ;; (`:auth/busy`) — inputs disabled, button reading "Signing in…". A
  ;; freeze-frame of the in-flight moment.
  (story/reg-variant :story.login/submitting
    {:doc        "First submit; the HTTP request is in flight. Inputs
                 are disabled and the button reads 'Signing in…'. The
                 fx-stub records the `:rf.http/managed` request and
                 resolves nothing, so the canvas locks in `:submitting`."
     :setup      [[:auth.login/flow [:auth.login/submit good-creds]]]
     :decorators [[story/force-fx-stub-id :rf.http/managed {}]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Invalid credentials — the schema doing its job. We submit a deliberately
  ;; bad payload (short password), and the `Credentials` boundary schema
  ;; turns it away before the handler runs. The machine never leaves `:idle`,
  ;; and the rejection raises an Issue you can watch light up Xray's ribbon.
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

  ;; Auth error — the 401. We stub out the real request (still recorded in
  ;; Side Effects), then drive the `:auth.login/failure` ourselves — the one
  ;; the request would have triggered had we let it answer. Driving the
  ;; outcome by hand is the standard Story move for pinning a precise terminal
  ;; state without being at the mercy of timing. The flow settles at
  ;; `:error-shown`, error message and all.
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

  ;; Locked out — the dead end. After three failures the `:under-retry-limit`
  ;; guard gives out, so the fourth failure routes to `:locked-out` and fires
  ;; the `:lock-account` request (a real `:rf.http/managed` call). There's no
  ;; shortcut to "three strikes already used", so this variant simply walks
  ;; the path: submit → failure, four times over, until the limit is spent.
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

  ;; Success — the hero shot, and the most satisfying one to inspect. The
  ;; whole real pipeline run flows through `:login.story/submit`: submit →
  ;; `:issue-request` → real `:rf.http/managed` fx → canned reply →
  ;; `:auth.login/success` → `:authed`. Open this one, hit Ctrl+Shift+C, and
  ;; watch the full chain march across Xray's Epoch / Trace / Side Effects
  ;; panels.
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
  ;; reg-workspace — two ways to look at the same set of variants.
  ;;
  ;; The `:grid` one is hand-curated: it pins the states in narrative order
  ;; for the README screenshot. The `:variants-grid` one is the lazy
  ;; (read: maintainable) twin — it auto-enumerates every variant of the
  ;; parent story, so a new variant shows up here on its own without anyone
  ;; editing this workspace.
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

;; And go: register everything, once, the moment this ns loads.
(register-all!)
