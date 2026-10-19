(ns nine-states.stories
  "The **Nine States of UI**, as a Story showcase.

   Nine states laid out side by side is exactly what Story is for — it
   enumerates one view's view-states so you can see them all at once
   instead of clicking your way to each. So this file registers one
   `reg-variant` per keyword the root view `case`s over, and the Story
   controls panel flips the page through every state for you. No
   hand-driving the machine required.

   ## Where the nine come from

   `nine-states.core` collapses the `:ui/nine-states` machine's
   parallel-region tag union into a single render-model keyword via the
   `:ui/render` selector (which reads the `render-priority` table). The
   root view's `case` has ten arms — the nine canonical states plus the
   `:error` branch — and the nine variants below line up 1:1 with the
   canonical render keywords:

     :nothing :loading :empty :one :some :too-many
     :incorrect :correct :done

   The `:error` branch is the tenth arm. It lives on its own
   `:story.nine-states-lifecycle` story (below) next to `:loading` and
   `:some`, so the async **load → loading → loaded/error** cascade reads
   as one walk-through you can step through and inspect in Xray. Parking
   it there keeps the canonical nine a tidy 1:1 with the render keywords.

   ## Fidelity — reach each state the honest way

   Every variant arrives at its state by firing real events into the
   `:ui/nine-states` machine. That's the highest-fidelity path: the Story
   canvas shows precisely what the live app shows, and Xray's Epoch /
   Trace / Side Effects panels record the genuine cascade — not a faked
   end-state.

   - Nothing      — `[:nine-states.app/initialise]` (machine `:reset`).
   - Loading       — `[:ui/nine-states [:fetch-started]]` parks the
                     `:data` region at `:loading` (no reply ever comes).
   - Empty/One/Some/Too Many — the live example's own
                     `:nine-states.demo/load` cascade: a real
                     `:rf.http/managed` fx whose reply folds back through
                     `:fetch-succeeded`, so the `:data` region's
                     `:always`-cascade counts the items and picks the
                     bucket.
   - Incorrect     — type a too-short title and submit → `:submit-invalid`.
   - Correct       — type a valid title and submit → `:submit-valid`.
   - Done          — load, then archive → the `:mode` region reaches `:done`.

   Because every state is reachable through events, no variant needs a
   `:db-seed` or `:sub-overrides`, and the fidelity is uniformly `:event`.

   ## Xray-richness — the managed-HTTP cascade

   Story gives each variant its own frame under `:preset :story`, and that
   preset never lets a request reach the network: one nothing answers fails
   as `:rf.http/transport`. So every variant that loads carries
   `demo-network` (below) as its `:network` — a route map Story installs on
   the variant's frame as a stub transport. It answers `/api/todos?n=4`
   with four todos and `/api/todos/fail` with an unreachable network, so
   the variants fire the live example's own load events and the full
   lifecycle plays out through the real `:rf.http/managed`:

       :nine-states.demo/load
         → [:ui/nine-states [:fetch-started]]        (→ :loading)
         → [:rf.http/managed {...}]                  (a real fx)
             → the :network route's answer           (Side Effects panel)
             → [:nine-states.demo/loaded {:value …}]
                 → [:ui/nine-states [:fetch-succeeded …]]
                     → :resolving → :always cardinality bucket

   The whole chain lands on the Epoch tape and the Trace stream, and the
   `:rf.http/managed` fx shows up in the Side Effects panel. Pick the
   `:some` or `:error` variant and watch the fetch light up Xray end to
   end — that's the payoff for driving these through real events.

   ## Authoring discipline

   Every variant body is plain data — no fn-slots anywhere. The view at
   the heart of each one is the example's own
   `nine-states.core/root-view`, referenced by id. And the canonical Story
   vocabulary installs itself on the first `reg-*` call, so there's no boot
   step to remember."
  (:require [re-frame.story :as rf.story]
            ;; Pull in the example's own registrations — the machine, the
            ;; demo events / subs / views, the `:ui/render` selector. The
            ;; variant bodies below name its event-ids and `root-view` as
            ;; plain keywords, and requiring the ns fires every `reg-*` so
            ;; those keywords actually resolve to something.
            [nine-states.core]))

;; ---------------------------------------------------------------------------
;; demo-network — the variants' fake server
;;
;; The live example answers its requests from `nine-states.core/demo-transport`,
;; which conjures N random todos for `/api/todos?n=N`. A variant's fixture is
;; data instead, and deterministic — fixed ids, so the variant's plan-hash
;; holds still — with one route per N a variant asks for.
;; ---------------------------------------------------------------------------

(def demo-network
  "The `:network` route map every loading variant carries: `/api/todos?n=N`
   answers N demo todos for each N below, and `/api/todos/fail` fails as an
   unreachable network."
  (into {[:get "/api/todos/fail"]
         {:failure {:kind :rf.http/transport :message "Network unreachable."}}}
        (for [n [0 1 4 25]]
          [[:get (str "/api/todos?n=" n)]
           {:body (vec (for [index (range n)]
                         {:id    (str "todo-" (inc index))
                          :title (str "Todo #" (inc index))
                          :done? false}))}])))

;; ---------------------------------------------------------------------------
;; register-all!
;;
;; All the registrations live in one top-level fn so a hot reload can re-run
;; the whole batch in one go. The trailing call at the bottom of the file
;; also fires it once at namespace load, so anyone who merely `:require`s
;; this ns gets a fully-populated set of stories for free.
;; ---------------------------------------------------------------------------

(defn register-all!
  "Register the nine-states example's Story artefacts. Safe to call again
   — it's idempotent. The canonical vocabulary installs itself on the first
   `reg-*` call, so there's nothing to boot first."
  []

  ;; -------------------------------------------------------------------------
  ;; reg-tag — a project tag that flags the one variant we screenshot.
  ;; -------------------------------------------------------------------------

  (rf.story/reg-tag :nine-states/canonical
    {:doc "Marks the variant that ships as the example's canonical
          screenshot — the `:some` standard-list state. The face of the
          example, so to speak."})

  ;; -------------------------------------------------------------------------
  ;; reg-story — the parent story. Its `:component` (the example's
  ;; `root-view`) and `:tags` flow down to every variant below, so the
  ;; variants only have to spell out their own per-state setup.
  ;; -------------------------------------------------------------------------

  (rf.story/reg-story :story.nine-states
    {:doc        "The Nine States of UI — every canonical view-state of
                 the todos page, each driven through the `:ui/nine-states`
                 parallel machine."
     :component  :nine-states.core/root-view
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  (rf.story/reg-story :story.nine-states-lifecycle
    {:doc        "The RemoteData fetch lifecycle as a stepped cascade —
                 load → loading → loaded / error. Kept apart from the
                 canonical nine so the async path (and its Xray Epoch /
                 Trace / Side Effects trail) reads as one story you can
                 step through arm by arm."
     :component  :nine-states.core/root-view
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; reg-variant — the nine canonical states, one per render keyword.
  ;;
  ;; Each `:setup` is just a list of real events that drive the machine to
  ;; the state in question (so fidelity is `:event` across the board — no
  ;; `:db-seed`, no `:sub-overrides`, no shortcuts).
  ;; -------------------------------------------------------------------------

  ;; State 1 — Nothing. Initialise the form slice and reset the machine.
  (rf.story/reg-variant :story.nine-states/nothing
    {:doc        "State 1 — Nothing. Never fetched; the `:data` region
                 sits at `:nothing`. The blank-slate welcome screen with a
                 'Get started' nudge."
     :setup      [[:nine-states.app/initialise]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; State 2 — Loading. `:fetch-started` parks the `:data` region at
  ;; `:loading`, and since no reply is ever sent it stays put — perfect for
  ;; a screenshot of a spinner that never resolves.
  (rf.story/reg-variant :story.nine-states/loading
    {:doc        "State 2 — Loading. A first fetch is in flight; the
                 `:data` region is parked at `:loading` (a `:fetch-started`
                 with no reply behind it). The spinner view."
     :setup      [[:nine-states.app/initialise]
                  [:ui/nine-states [:fetch-started]]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; State 3 — Empty. Load zero todos and the `:always`-cascade picks `:empty`.
  (rf.story/reg-variant :story.nine-states/empty
    {:doc        "State 3 — Empty. Fetched, but the result came back empty.
                 The `:always`-cascade reads a count of zero and picks
                 `:empty`."
     :network    demo-network
     :setup      [[:nine-states.app/initialise]
                  [:nine-states.demo/load {:n 0}]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; State 4 — One. Exactly one todo, so the cascade picks `:one`.
  (rf.story/reg-variant :story.nine-states/one
    {:doc        "State 4 — One. Exactly one todo; the focused
                 single-item layout (the `:one` cardinality bucket)."
     :network    demo-network
     :setup      [[:nine-states.app/initialise]
                  [:nine-states.demo/load {:n 1}]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; State 5 — Some. A small, manageable list — and the canonical screenshot.
  (rf.story/reg-variant :story.nine-states/some
    {:doc        "State 5 — Some. A small, manageable list, rendered
                 plainly (the `:some` cardinality bucket). This is the
                 example's canonical screenshot."
     :network    demo-network
     :setup      [[:nine-states.app/initialise]
                  [:nine-states.demo/load {:n 4}]]
     :tags       #{:dev :docs :nine-states/canonical}
     :substrates #{:reagent}})

  ;; State 6 — Too Many. Push past the threshold and get search + truncation.
  (rf.story/reg-variant :story.nine-states/too-many
    {:doc        "State 6 — Too Many. More items than the too-many
                 threshold (7), so the view switches to search +
                 truncation (the `:too-many` cardinality bucket)."
     :network    demo-network
     :setup      [[:nine-states.app/initialise]
                  [:nine-states.demo/load {:n 25}]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; State 7 — Incorrect. Type a too-short title, submit → `:submit-invalid`.
  (rf.story/reg-variant :story.nine-states/incorrect
    {:doc        "State 7 — Incorrect. A too-short title trips the form
                 validator on submit; the `:form` region lands at
                 `:incorrect` (via `:submit-invalid`) and the inline
                 per-field error appears."
     :setup      [[:nine-states.app/initialise]
                  [:new-todo/edit-field :title "no"]
                  [:new-todo/submit]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; State 8 — Correct. Type a valid title, submit → `:submit-valid`.
  (rf.story/reg-variant :story.nine-states/correct
    {:doc        "State 8 — Correct. A valid title sails through the form
                 validator on submit; the `:form` region lands at
                 `:correct` (via `:submit-valid`) and the success
                 acknowledgement appears."
     :setup      [[:nine-states.app/initialise]
                  [:new-todo/edit-field :title "Buy milk"]
                  [:new-todo/submit]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; State 9 — Done. Load some items, then archive → `:mode` reaches `:done`.
  (rf.story/reg-variant :story.nine-states/done
    {:doc        "State 9 — Done. Load a few todos, then archive: the
                 `:mode` region reaches its terminal, read-only `:done`
                 state and the form and controls all grey themselves out."
     :network    demo-network
     :setup      [[:nine-states.app/initialise]
                  [:nine-states.demo/load {:n 4}]
                  [:ui/nine-states [:archive {}]]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; The :error branch — the tenth render arm. It lives on the lifecycle
  ;; story so the failure pipeline run reads as a step in a sequence, right
  ;; alongside :loading and :some.
  ;; -------------------------------------------------------------------------

  (rf.story/reg-variant :story.nine-states-lifecycle/loading
    {:doc        "Lifecycle — the in-flight `:loading` step. Same as the
                 canonical Loading variant, shown here as the opening arm
                 of the fetch cascade."
     :setup      [[:nine-states.app/initialise]
                  [:ui/nine-states [:fetch-started]]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  (rf.story/reg-variant :story.nine-states-lifecycle/loaded
    {:doc        "Lifecycle — the resolved `:some` step. The full
                 `:rf.http/managed` cascade runs in `:setup`, so pick this
                 variant and watch the fetch play out across Xray's Epoch /
                 Trace / Side Effects panels."
     :network    demo-network
     :setup      [[:nine-states.app/initialise]
                  [:nine-states.demo/load {:n 4}]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  (rf.story/reg-variant :story.nine-states-lifecycle/error
    {:doc        "Lifecycle — the `:error` branch. This variant fires the
                 live example's rigged-to-fail load, whose `/api/todos/fail`
                 route answers with an unreachable network. `:fetch-failed`
                 then lands the `:data` region at `:error`, and the failure
                 cascade lights up Xray's Side Effects + Trace panels right
                 next to the success path."
     :network    demo-network
     :setup      [[:nine-states.app/initialise]
                  [:nine-states.demo/load-with-failure]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; reg-workspace — a few different ways to arrange the same variants.
  ;;
  ;; Two styles on show here. A `:variants-grid` auto-enumerates every
  ;; variant of its parent story, so new variants just show up — no edit
  ;; needed. A plain `:grid` pins a hand-picked set in a fixed order, which
  ;; is what you want for a stable README screenshot.
  ;; -------------------------------------------------------------------------

  (rf.story/reg-workspace :Workspace.nine-states/all-states
    {:doc      "Every canonical state, side by side in render order."
     :layout   :grid
     :variants [:story.nine-states/nothing
                :story.nine-states/loading
                :story.nine-states/empty
                :story.nine-states/one
                :story.nine-states/some
                :story.nine-states/too-many
                :story.nine-states/incorrect
                :story.nine-states/correct
                :story.nine-states/done]
     :columns  3
     :tags     #{:docs}})

  (rf.story/reg-workspace :Workspace.nine-states/auto-grid
    {:doc     "The set-it-and-forget-it grid — auto-pulls every variant off
              :story.nine-states, so new variants land here on their own."
     :layout  :variants-grid
     :for     :story.nine-states
     :columns 3
     :tags    #{:docs}})

  (rf.story/reg-workspace :Workspace.nine-states/lifecycle
    {:doc      "The RemoteData fetch lifecycle as a left-to-right
              stepped row: loading → loaded → error."
     :layout   :grid
     :variants [:story.nine-states-lifecycle/loading
                :story.nine-states-lifecycle/loaded
                :story.nine-states-lifecycle/error]
     :columns  3
     :tags     #{:docs}}))

;; And fire them once, right now, as the namespace loads.
(register-all!)
