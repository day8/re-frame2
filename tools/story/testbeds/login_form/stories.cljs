(ns login-form.stories
  "Story variants for the login-form testbed (rf2-0sg12).

  Promotes the five-state login-form scenario from the Story
  tutorial's index page (`docs/story/index.md:13-26`) to runnable
  variants. The five states from the tutorial are exactly the
  variants below:

    :story.login/idle              → the empty form
    :story.login/submitting        → first submit, request in flight
    :story.login/error             → server rejected creds
    :story.login/submitting-retry  → user fixed the typo, re-submitted
    :story.login/authenticated     → welcome banner

  Every variant body is plain EDN — `:events`, `:decorators`, `:play`,
  `:tags`, `:substrates`. No function-slots; no closures except in the
  decorator registration (the `:counter-with-stories/log-decorator`
  pattern). This is the canonical agent-consumable shape (round-trips
  through MCP, the recorder, the visual-regression service).

  Each variant exercises a different authoring shape, between them
  covering five of the seven `:rf.assert/*` shapes:

    :path-equals     — :idle (initial state slot equals :idle)
    :sub-equals      — :authenticated (the :login/email sub returns the
                       expected handle)
    :state-is        — every variant (`:rf.assert/state-is :login/flow ...`)
    :dispatched?     — :error (the failure event was dispatched
                       during the play sequence)
    :effect-emitted  — :submitting (the :rf.http/managed fx fired)

  The `force-fx-stub` decorator is wired on every variant that hits the
  network — the variant body never makes a real HTTP request; the stub
  resolves the request with the canned-success / canned-failure shape
  per Spec 014 §Testing."
  (:require [re-frame.story :as story]
            ;; Sourcing these via :require fires the registrations.
            [login-form.events]
            [login-form.subs]
            [login-form.views]))

(defn register-all!
  "Register the login-form testbed's Story artefacts. Idempotent.
  Fired once at namespace load; the test fixture re-fires after a
  clear-all! per test."
  []
  (story/install-canonical-vocabulary!)

  ;; -------------------------------------------------------------------------
  ;; Project tag — the screenshot pinned to the tutorial.
  ;; -------------------------------------------------------------------------

  (story/reg-tag :login-form/tutorial
    {:doc "Variants the Story tutorial points at by name. The
          authenticated variant is the canonical screenshot — the
          welcome banner that lands on `docs/story/01-first-story.md`."})

  ;; -------------------------------------------------------------------------
  ;; Modes — light + dark theme axis. The tutorial walk-through cites
  ;; "five variants side-by-side"; pairing each variant against light
  ;; and dark exercises the toolbar's `:axis :theme` single-select
  ;; semantics on a real shape.
  ;; -------------------------------------------------------------------------

  (story/reg-mode :Mode.login/light
    {:doc  "Light theme — the default. Matches the live page."
     :axis :theme
     :args {:theme :light}})

  (story/reg-mode :Mode.login/dark
    {:doc  "Dark theme — for the design-review workspace."
     :axis :theme
     :args {:theme :dark}})

  ;; -------------------------------------------------------------------------
  ;; Parent story — the five variants below all inherit its
  ;; decorators, args, and tags.
  ;; -------------------------------------------------------------------------

  (story/reg-story :story.login
    {:doc        "The login form — every state from the tutorial's
                 five-state scenario, as runnable variants."
     :component  :login-form.views/login-card
     :args       {:heading "Sign in"}
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; Variant 1 — :idle (the empty form)
  ;;
  ;; The simplest variant body — no events, no decorators of its own.
  ;; Story's per-variant frame allocation seeds the machine to
  ;; `:initial :idle` on first dispatch; the assertion proves the
  ;; state is what the variant's name says it is.
  ;; -------------------------------------------------------------------------

  (story/reg-variant :story.login/idle
    {:doc    "Fresh form, no inputs typed, no submit clicked. The
             entry state the user lands on when they navigate to
             `#/login` for the first time. The variant fires a
             no-op `:login/dismiss` so the machine's `:initial`
             cascade seeds the snapshot — without it, the
             `[:rf/machines :login/flow]` slot is nil and the
             state-pill in the view renders empty."
     :events [[:login/flow [:login/dismiss]]]
     :play-script [[:dispatch-sync [:rf.assert/path-equals  [:rf/machines :login/flow :state] :idle]]
              [:dispatch-sync [:rf.assert/state-is :login/flow :idle]]]
     :tags   #{:dev :docs :test}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; Variant 2 — :submitting (request in flight)
  ;;
  ;; force-fx-stub intercepts the `:rf.http/managed` dispatch the
  ;; machine's :issue-request action emits. The stub records the call
  ;; (so `:rf.assert/effect-emitted` passes) and the on-success /
  ;; on-failure events never fire — the canvas locks at :submitting,
  ;; which is exactly what the tutorial's second state describes.
  ;; -------------------------------------------------------------------------

  (story/reg-variant :story.login/submitting
    {:doc    "First submit; HTTP request in flight; inputs disabled,
             button reads 'Signing in…'. The fx-stub records the
             request and resolves nothing so the canvas locks in
             :submitting."
     :events [[:login/flow [:login/submit {:email    "ada@example.com"
                                            :password "correct-horse"}]]]
     :decorators [[story/force-fx-stub-id :rf.http/managed {}]]
     :play-script [[:dispatch-sync [:rf.assert/state-is      :login/flow :submitting]]
              [:dispatch-sync [:rf.assert/effect-emitted :rf.http/managed]]]
     :tags   #{:dev :docs :test}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; Variant 3 — :error (server rejected creds)
  ;;
  ;; The fx-stub still intercepts the request fx, but the variant
  ;; body manually drives the failure sub-event in the same `:events`
  ;; sequence — equivalent to the server having returned 401. The
  ;; machine transitions idle → submitting → error and lands with the
  ;; error message surfaced.
  ;; -------------------------------------------------------------------------

  (story/reg-variant :story.login/error
    {:doc    "Server rejected credentials. Form re-enabled; the error
             message is surfaced under the submit button; the
             'Cancel' button lets the user back out without retrying."
     :events [[:login/flow [:login/submit {:email    "ada@example.com"
                                            :password "wrong"}]]
              ;; Manually fire the failure sub-event the stubbed-out
              ;; request would otherwise have triggered. This is the
              ;; canonical Story shape — variants drive the FSM
              ;; through direct event dispatches so they pin a
              ;; specific terminal state regardless of timing.
              [:login/flow [:login/failure
                            {:failure {:status  401
                                       :message "Invalid credentials."}}]]]
     :decorators [[story/force-fx-stub-id :rf.http/managed {}]]
     :play-script [[:dispatch-sync [:rf.assert/state-is :login/flow :error]]
              [:dispatch-sync [:rf.assert/path-equals
               [:rf/machines :login/flow :data :error]
               "Invalid credentials."]]]
     :tags   #{:dev :docs :test}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; Variant 4 — :submitting-retry (user fixed the typo and re-submitted)
  ;;
  ;; Sequences submit → failure → retry, with the second request
  ;; pending. attempts is incremented by the :record-error action so
  ;; the retry button reads "Retrying…" and the attempt counter
  ;; surfaces under the error.
  ;; -------------------------------------------------------------------------

  (story/reg-variant :story.login/submitting-retry
    {:doc    "The user corrected the typo and re-submitted. Distinct
             from :submitting because attempts > 0; the variant body
             sequences the failure then the retry, leaving the
             retry request pending in the stub."
     :events [[:login/flow [:login/submit
                            {:email "ada@example.com" :password "wrong"}]]
              [:login/flow [:login/failure
                            {:failure {:status 401 :message "Invalid credentials."}}]]
              [:login/flow [:login/retry
                            {:email "ada@example.com" :password "correct-horse"}]]]
     :decorators [[story/force-fx-stub-id :rf.http/managed {}]]
     :play-script [[:dispatch-sync [:rf.assert/state-is :login/flow :submitting-retry]]
              [:dispatch-sync [:rf.assert/path-equals
               [:rf/machines :login/flow :data :attempts]
               1]]]
     :tags   #{:dev :docs :test}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; Variant 5 — :authenticated (welcome banner)
  ;;
  ;; The canonical screenshot. Submit → success drives the machine
  ;; through to :authenticated; the view swaps the form for the
  ;; welcome banner. The `:rf.assert/sub-equals` assertion pins the
  ;; email the banner greets the user with — exactly the round-trip
  ;; the EDN-first contract is built for.
  ;; -------------------------------------------------------------------------

  (story/reg-variant :story.login/authenticated
    {:doc    "Server accepted credentials. The form is replaced by
             a welcome banner addressing the user by email; the
             :sign-out button routes back to :idle. This is the
             canonical screenshot the tutorial pins to."
     :events [[:login/flow [:login/submit {:email    "ada@example.com"
                                            :password "correct-horse"}]]
              [:login/flow [:login/success
                            {:value {:user  {:email "ada@example.com"}
                                     :token "story-token"}}]]]
     :decorators [[story/force-fx-stub-id :rf.http/managed {}]]
     :play-script [[:dispatch-sync [:rf.assert/state-is :login/flow :authenticated]]
              [:dispatch-sync [:rf.assert/sub-equals [:login/email] "ada@example.com"]]]
     :tags   #{:dev :docs :test :login-form/tutorial}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; Workspaces — the "five side-by-side" workspace from the tutorial.
  ;; -------------------------------------------------------------------------

  (story/reg-workspace :Workspace.login/all-states
    {:doc      "The five states side-by-side. This is the design-
                review surface from the tutorial's scenario step 3
                — 'open a workspace that mounts all five side-by-side'."
     :layout   :grid
     :variants [:story.login/idle
                :story.login/submitting
                :story.login/error
                :story.login/submitting-retry
                :story.login/authenticated]
     :columns  3
     :tags     #{:docs}})

  (story/reg-workspace :Workspace.login/auto-grid
    {:doc     "Auto-enumerated grid — pulls every variant off
              :story.login. New variants land here without touching
              this workspace."
     :layout  :variants-grid
     :for     :story.login
     :columns 3
     :tags    #{:docs}}))

;; Fire the registrations once at namespace load.
(register-all!)
