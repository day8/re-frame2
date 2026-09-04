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

       [:auth.login/submit-form]                       (validates the draft)
         → [:rf.http/managed {… :sensitive? true}]     (a real fx)
         → [:auth.login/flow [:auth.login/submit]]      (→ :submitting)
             → canned-success reply                    (Side Effects panel)
                 → [:auth.login/flow [:auth.login/success …]]
                     → :authed

   Every hop lands on the Epoch tape and the Trace stream, and the
   `:rf.http/managed` fx surfaces in the Side Effects panel. Pick the
   `:success` variant, press Ctrl+Shift+C, and watch the whole thing light
   up like a pinball table.

   A couple of variants show the flow's unhappy paths — because seeing the
   framework *catch* a mistake is half the lesson:
     - `:invalid-credentials` — a bad draft (bad email + too-short password) is
       caught by `submit-form`'s pre-submit `Credentials` validation; field
       errors surface under each input and nothing is dispatched, so the machine
       stays `:idle`.
     - `:auth-error` / `:locked-out` — a 401 failure cascade; the machine is
       driven into `:submitting` with a credential-free `:submit` signal and the
       `:auth.login/failure` follow-on records the error.

   One more thing worth noticing: every variant body below is plain data —
   no function slots anywhere. The view at the heart of each is the
   example's own `login.core/root-view`, named by id. And the canonical
   Story vocabulary installs itself on the first `reg-*` call, so there's no
   boot step to remember.

   Examples are test-free: these stories carry no `:script` / `:rf.assert/*`
   — they're a showcase, not a test surface."
  (:require [re-frame.core :as rf]
            [re-frame.story :as rf.story]
            ;; Source the example's registrations (the machine, schemas,
            ;; demo fx, subs, views). The variant bodies below reference
            ;; its event-ids + the `root-view` view-id as plain keywords;
            ;; requiring the ns fires every `reg-*` so those ids resolve.
            [login.core]))

;; ---------------------------------------------------------------------------
;; The story-side submit — our way of triggering the full, Xray-rich pipeline run.
;;
;; Recall how a login submits: `:auth.login/submit-form` validates the draft,
;; fires the real `:rf.http/managed` request (with `:sensitive? true`), and
;; nudges the machine with a credential-free `:submit` signal. The reply pipes
;; back through `:auth.login/success` / `:auth.login/failure`. And recall the
;; `:preset :story` frame redirects `:rf.http/managed` to the framework's
;; canned-success stub (which just echoes the request's `:value` slot back as
;; the payload).
;;
;; The slice itself is seeded once, up front, by the composed
;; `:fragment.login/form-base` fragment (every variant pulls it in via
;; `:compose`), so this driver need not initialise it — it just walks the
;; real form path: type the draft (email via `:auth.login/edit-field`,
;; password via the classified `:auth.login/edit-password`), then dispatch
;; `:auth.login/submit-form`. Same real fx (right there in Xray's Side
;; Effects panel), and the canned stub answers it — no app-side override
;; needed. The reply `:value` happens to be nil (we sent none), but that's
;; fine: the welcome banner keys off the `:auth/authenticated` tag, not the
;; payload, so the flow still settles happily at `:authed`.
;; ---------------------------------------------------------------------------

(rf/reg-event :login.story/submit
  {:doc "Story-shell driver for the auth-submit cascade. Runs the REAL form
         path: seed the draft, then dispatch `:auth.login/submit-form`, which
         validates the draft, fires the (sensitive) `:rf.http/managed` request,
         and nudges the machine with a credential-free `:submit` signal. Under
         the `:preset :story` frame the canned-success stub resolves it and the
         `:auth.login/success` follow-on lands the flow at `:authed`. The whole
         chain shows in Xray's Epoch / Trace / Side Effects panels."}
  (fn handler-story-submit [_ [_ creds]]
    {:fx [[:dispatch [:auth.login/edit-field :email (:email creds)]]
          [:dispatch [:auth.login/edit-password {:value (:password creds)}]]
          [:dispatch [:auth.login/submit-form]]]}))

;; ---------------------------------------------------------------------------
;; register-all!
;;
;; We gather every registration into one top-level fn for a practical
;; reason: hot-reload can then re-fire the whole set in one go. The trailing
;; call at the bottom runs it once on load, so merely requiring this ns is
;; enough to populate the Story registry.
;; ---------------------------------------------------------------------------

(def ^:private good-creds
  "Credentials the demo would accept (they mirror `login.model/good-password`).
   The story driver types them into the draft and submits through the real form
   path; the canned-success stub doesn't even look at them, it just answers
   `:ok`."
  {:email "ada@example.com" :password "correct-horse"})

(defn- failure-reply
  "The canonical managed-HTTP failure envelope the machine's `:record-error`
   action reads: `{:status :error :error <classified-failure-map>}`, the message
   riding under `:error`. This is exactly what `:rf.http/managed-canned-failure`
   delivers on a real 4xx, so driving `:auth.login/failure` with it is faithful
   to the live cascade. A bare `{:failure …}` map — the retired shape — leaves
   `:record-error` reading a nil `:error`, so the flow falls back to its generic
   \"Login failed.\" message instead of the server's."
  [status message]
  {:status :error
   :error  {:kind :rf.http/http-4xx :status status :message message}})

(defn register-all!
  "Register all of the login example's Story artefacts. Safe to call twice —
   it's idempotent, and the canonical vocabulary installs itself on the first
   `reg-*` call, so there's no separate boot step to remember."
  []

  ;; -------------------------------------------------------------------------
  ;; reg-tag — one project tag, marking which variant is the "hero" shot.
  ;; -------------------------------------------------------------------------

  (rf.story/reg-tag :login/canonical
    {:doc "Marks the variant that ships as the example's canonical
          screenshot — the `:success` welcome-banner state."})

  ;; -------------------------------------------------------------------------
  ;; reg-story — the parent. Think of it as the shared backdrop: its
  ;; `:component` (the example's `root-view`) and `:tags` flow down to every
  ;; variant, so each variant only has to describe what makes it different.
  ;; -------------------------------------------------------------------------

  (rf.story/reg-story :story.login
    {:doc        "The login form — every reachable state of the
                 `:auth.login/flow` machine, as runnable variants."
     :component  :login.core/root-view
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; reg-fragment — the shared form-base. Every login variant runs in its own
  ;; fresh `:preset :story` frame with no application `:initial-events`, so
  ;; each must seed the login-form slice itself before its variant-specific
  ;; setup runs — exactly as the live app does at boot. Rather than copy the
  ;; initializer into seven `:setup` vectors, we register it ONCE here and
  ;; `:compose` it into every variant below. The plan compiler appends a
  ;; composed fragment's `:setup` BEFORE the variant's own, so the slice is
  ;; fully initialised (controlled inputs, complete bookkeeping fields) the
  ;; moment the variant's own events run. The event stays the single source
  ;; of the defaults — no defaults map is duplicated in Story, no `:db-seed`.
  ;; -------------------------------------------------------------------------

  (rf.story/reg-fragment :fragment.login/form-base
    {:doc   "Seeds the login-form slice to its standard form-recipe defaults
            via the example's own `:auth.login/initialise-form` (the single
            source of those defaults — the same event the live app runs at
            boot). Composed into every login variant so each isolated frame
            boots with a fully-initialised, controlled-input slice before its
            variant-specific setup runs."
     :setup [[:auth.login/initialise-form]]})

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
  (rf.story/reg-variant :story.login/empty
    {:compose    [:fragment.login/form-base]
     :doc        "Fresh form, nothing typed, no submit clicked — the
                 `:idle` entry state a user lands on. Email + password
                 inputs + an enabled 'Sign in' button."
     :setup      [[:auth.login/flow [:auth.login/dismiss]]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Filled — both fields typed, nothing submitted. We get there honestly: by
  ;; dispatching the very same edit events a real keystroke fires (email via
  ;; `:auth.login/edit-field`, password via the classified
  ;; `:auth.login/edit-password`), which write into the app-db slice. The
  ;; machine stays `:idle`, and the inputs show the seeded draft as their
  ;; `:value`. No shortcuts.
  (rf.story/reg-variant :story.login/filled
    {:compose    [:fragment.login/form-base]
     :doc        "Both fields filled in, nothing submitted yet — the form
                 mid-edit. The draft was typed into the app-db login-form slice
                 via real edit events (email via `:auth.login/edit-field`,
                 password via the classified `:auth.login/edit-password`), so
                 the inputs show their `:value` and the flow is still `:idle`."
     :setup      [[:auth.login/flow [:auth.login/dismiss]]
                  [:auth.login/edit-field :email "ada@example.com"]
                  [:auth.login/edit-password {:value "correct-horse"}]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Submitting — frozen mid-request, on purpose. It runs the real form path
  ;; (`:login.story/submit` → `submit-form`), which fires the `:rf.http/managed`
  ;; request and moves the machine to `:submitting`. The trick is
  ;; `force-fx-stub`: it catches that request, records it (you'll see it in Side
  ;; Effects), and then deliberately answers nothing. With no reply, no
  ;; `:success` / `:failure` ever fires, so the canvas is stuck in `:submitting`
  ;; (`:auth/busy`) — inputs disabled, button reading "Signing in…". A
  ;; freeze-frame of the in-flight moment.
  (rf.story/reg-variant :story.login/submitting
    {:compose    [:fragment.login/form-base]
     :doc        "First submit; the HTTP request is in flight. Inputs
                 are disabled and the button reads 'Signing in…'. The
                 fx-stub records the `:rf.http/managed` request and
                 resolves nothing, so the canvas locks in `:submitting`."
     :setup      [[:login.story/submit good-creds]]
     :decorators [[rf.story/force-fx-stub-id :rf.http/managed {}]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Invalid credentials — validation doing its job. We type a deliberately bad
  ;; draft (bad email + too-short password) and dispatch `submit-form`, whose
  ;; pre-submit `Credentials` check turns it away: the field errors land in the
  ;; slice, `:submit-attempted?` latches, and nothing is dispatched — so the
  ;; machine never leaves `:idle` and the form shows what's wrong under each
  ;; input.
  (rf.story/reg-variant :story.login/invalid-credentials
    {:compose    [:fragment.login/form-base]
     :doc        "A bad draft (bad email + too-short password) is caught by
                 `submit-form`'s pre-submit `Credentials` validation: field
                 errors surface under each input, `:submit-attempted?` latches,
                 and nothing is dispatched, so the flow stays at `:idle`."
     :setup      [[:auth.login/flow [:auth.login/dismiss]]
                  [:auth.login/edit-field :email "nope"]
                  [:auth.login/edit-password {:value "short"}]
                  [:auth.login/submit-form]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Auth error — the 401. We move the machine into `:submitting` with a
  ;; credential-free `:submit` signal, then drive the `:auth.login/failure`
  ;; ourselves — the one the real request would have triggered. Driving the
  ;; outcome by hand is the standard Story move for pinning a precise terminal
  ;; state without being at the mercy of timing. The flow settles at
  ;; `:error-shown`, error message and all.
  (rf.story/reg-variant :story.login/auth-error
    {:compose    [:fragment.login/form-base]
     :doc        "Server rejected the credentials. The form is re-enabled and
                 the error message surfaces under the submit button
                 (`:error-shown`). The machine is driven into `:submitting` with
                 a credential-free `:submit` signal and the manually-driven
                 `:failure` follow-on records the error."
     :setup      [[:auth.login/flow [:auth.login/submit]]
                  [:auth.login/flow [:auth.login/failure
                                     (failure-reply 401 "Invalid credentials.")]]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Locked out — the dead end. After three failures the `:under-retry-limit`
  ;; guard gives out, so the fourth failure routes to `:locked-out` and fires
  ;; the `:lock-account` request (a real `:rf.http/managed` call). There's no
  ;; shortcut to "three strikes already used", so this variant simply walks
  ;; the path: submit → failure, four times over, until the limit is spent.
  (rf.story/reg-variant :story.login/locked-out
    {:compose    [:fragment.login/form-base]
     :doc        "Too many failed attempts — the `:under-retry-limit`
                 guard fails on the fourth failure and the flow reaches
                 the terminal `:locked-out` state, firing the
                 `:lock-account` request (visible in Xray's Side Effects
                 panel). The account is locked; the form no longer
                 retries."
     :setup      [[:auth.login/flow [:auth.login/submit]]
                  [:auth.login/flow [:auth.login/failure
                                     (failure-reply 401 "Invalid credentials.")]]
                  [:auth.login/flow [:auth.login/submit]]
                  [:auth.login/flow [:auth.login/failure
                                     (failure-reply 401 "Invalid credentials.")]]
                  [:auth.login/flow [:auth.login/submit]]
                  [:auth.login/flow [:auth.login/failure
                                     (failure-reply 401 "Invalid credentials.")]]
                  [:auth.login/flow [:auth.login/submit]]
                  [:auth.login/flow [:auth.login/failure
                                     (failure-reply 423 "Account locked.")]]]
     :decorators [[rf.story/force-fx-stub-id :rf.http/managed {}]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; Success — the hero shot, and the most satisfying one to inspect. The
  ;; whole real pipeline run flows through `:login.story/submit`: submit-form →
  ;; real `:rf.http/managed` fx → canned reply → `:auth.login/success` →
  ;; `:authed`. Open this one, hit Ctrl+Shift+C, and watch the full chain march
  ;; across Xray's Epoch / Trace / Side Effects panels.
  (rf.story/reg-variant :story.login/success
    {:compose    [:fragment.login/form-base]
     :doc        "Server accepted the credentials. The form is replaced
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

  (rf.story/reg-workspace :Workspace.login/all-states
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

  (rf.story/reg-workspace :Workspace.login/auto-grid
    {:doc     "Auto-enumerated grid — pulls every variant off
              :story.login. New variants appear here without touching
              this workspace."
     :layout  :variants-grid
     :for     :story.login
     :columns 3
     :tags    #{:docs}}))

;; And go: register everything, once, the moment this ns loads.
(register-all!)
