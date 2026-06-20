(ns login-form.stories
  "Story variants for the login-form testbed.

  Promotes the five-state login-form scenario from the Story
  tutorial's index page (`docs/story/index.md:13-26`) to runnable
  variants. The five states from the tutorial are exactly the
  variants below:

    :story.login/idle              → the empty form
    :story.login/submitting        → first submit, request in flight
    :story.login/error             → server rejected creds
    :story.login/submitting-retry  → user fixed the typo, re-submitted
    :story.login/authenticated     → welcome banner

  Every variant body is plain EDN — `:setup`, `:decorators`, `:script`,
  `:tags`, `:substrates`. No function-slots; no closures except in the
  decorator registration (the `:counter-with-stories/log-decorator`
  pattern). This is the canonical agent-consumable shape (round-trips
  through MCP, the recorder, the visual-regression service).

  Every variant pins its terminal state with `:rf.assert/state-is
  :login/flow ...`, the runtime-db-aware machine checkpoint. Machine
  snapshots live in the frame's runtime-db partition (EP-0001); the
  play-runner's `:rf.assert/sub-equals` / `:rf.assert/path-equals`
  evaluate against app-db, so a machine projection isn't observable
  through them. The machine-snapshot `:data` values (`:error` /
  `:attempts` / `:email`) are therefore verified in the testbed's CLJS
  unit test (`stories_cljs_test.cljs`), which can read the variant
  frame's runtime-db directly. The variant `:script` slots exercise the
  play-runner shapes that ARE supported:

    :state-is        — every variant (`:rf.assert/state-is :login/flow ...`)
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
  clear-all! per test. The canonical vocabulary auto-installs on the
  first `reg-*` call below — no explicit boot step required."
  []
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
  ;; The simplest variant body — a single bootstrap dispatch in :setup,
  ;; no decorators of its own. Story's per-variant frame allocation
  ;; seeds the machine to `:initial :idle` on the FIRST dispatch into
  ;; the machine, whatever that event is; the :script checkpoints prove
  ;; the state is what the variant's name says it is.
  ;; -------------------------------------------------------------------------

  (story/reg-variant :story.login/idle
    {:doc    "Fresh form, no inputs typed, no submit clicked. The
             entry state the user lands on when they navigate to
             `#/login` for the first time.

             The `:setup` fires `:login/dismiss` purely to BOOTSTRAP
             the machine: the first dispatch into a machine runs its
             `:initial` cascade (spec/005 §Initial cascade), seeding
             the `[:rf.runtime/machines :snapshots :login/flow]`
             snapshot in the frame's runtime-db partition — without it
             that slot is nil and the view's state-pill renders empty.
             The choice of `:login/dismiss` is incidental: in `:idle` it
             is an UNHANDLED no-op (it is only a real transition out of
             `:error`), so it bootstraps without moving the machine off
             `:idle`. Any event would do — do NOT read meaning into
             dispatching `:dismiss` here, and do NOT copy 'dispatch any
             event to bootstrap' as an idiom."
     :setup [[:login/flow [:login/dismiss]]]
     ;; EP-0001: the machine snapshot lives in the frame's runtime-db
     ;; partition, so the state checkpoint reads it via
     ;; `:rf.assert/state-is` (which evaluates against runtime-db) —
     ;; NOT a raw `:assert-db` path-equals, which only sees app-db.
     :script [[:assert [:rf.assert/state-is :login/flow :idle]]]
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
     :setup [[:login/flow [:login/submit {:email    "ada@example.com"
                                            :password "correct-horse"}]]]
     :decorators [[story/force-fx-stub-id :rf.http/managed {}]]
     :script [[:assert [:rf.assert/state-is      :login/flow :submitting]]
              [:assert [:rf.assert/effect-emitted :rf.http/managed]]]
     :tags   #{:dev :docs :test}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; Variant 3 — :error (server rejected creds)
  ;;
  ;; The fx-stub still intercepts the request fx, but the variant
  ;; body manually drives the failure sub-event in the same `:setup`
  ;; sequence — equivalent to the server having returned 401. The
  ;; machine transitions idle → submitting → error and lands with the
  ;; error message surfaced.
  ;; -------------------------------------------------------------------------

  (story/reg-variant :story.login/error
    {:doc    "Server rejected credentials. Form re-enabled; the error
             message is surfaced under the submit button; the
             'Cancel' button lets the user back out without retrying."
     :setup [[:login/flow [:login/submit {:email    "ada@example.com"
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
     ;; EP-0001: the machine snapshot lives in the frame's runtime-db
     ;; partition, so the state checkpoint reads it via
     ;; `:rf.assert/state-is` (which evaluates against runtime-db).
     ;; The error-message text in the snapshot's `:data` slot is verified
     ;; in the testbed's CLJS unit test (`stories_cljs_test.cljs`): the
     ;; play-runner's `:rf.assert/sub-equals` evaluates subs against app-db
     ;; only, so a runtime-db machine projection isn't observable through it.
     :script [[:assert [:rf.assert/state-is :login/flow :error]]]
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
     :setup [[:login/flow [:login/submit
                            {:email "ada@example.com" :password "wrong"}]]
              [:login/flow [:login/failure
                            {:failure {:status 401 :message "Invalid credentials."}}]]
              [:login/flow [:login/retry
                            {:email "ada@example.com" :password "correct-horse"}]]]
     :decorators [[story/force-fx-stub-id :rf.http/managed {}]]
     ;; EP-0001: the attempt counter lives in the runtime-db snapshot's
     ;; `:data`; the state checkpoint reads the
     ;; snapshot via `:rf.assert/state-is`. The attempt-count value is
     ;; verified in the testbed's CLJS unit test (`stories_cljs_test.cljs`)
     ;; — the play-runner's `:rf.assert/sub-equals` only sees app-db, so a
     ;; runtime-db machine projection isn't observable through it.
     :script [[:assert [:rf.assert/state-is :login/flow :submitting-retry]]]
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
     :setup [[:login/flow [:login/submit {:email    "ada@example.com"
                                            :password "correct-horse"}]]
              [:login/flow [:login/success
                            {:value {:user  {:email "ada@example.com"}
                                     :token "story-token"}}]]]
     :decorators [[story/force-fx-stub-id :rf.http/managed {}]]
     ;; EP-0001: the welcome-banner email lives in the runtime-db
     ;; snapshot's `:data`; the state checkpoint reads
     ;; the snapshot via `:rf.assert/state-is`. The email value is verified
     ;; in the testbed's CLJS unit test (`stories_cljs_test.cljs`) — the
     ;; play-runner's `:rf.assert/sub-equals` only sees app-db, so a
     ;; runtime-db machine projection isn't observable through it.
     :script [[:assert [:rf.assert/state-is :login/flow :authenticated]]]
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
