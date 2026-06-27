(ns nine-states.stories
  "Story showcase for the **Nine States of UI** worked example.

   The nine canonical UI states (Nothing / Loading / Empty / One /
   Some / Too Many / Incorrect / Correct / Done) form a natural Story
   variant set — Story enumerates a single view's view-states side by
   side. This file registers one `reg-variant` per render-model keyword
   the root view `case`s over, so the Story controls panel flips the page
   through every state without the reader hand-driving the machine.

   ## Where the nine come from

   `nine-states.core` collapses the `:ui/nine-states` machine's
   parallel-region tag union into one render-model keyword via the
   `:ui/render` selector sub (consulting the `render-priority` table).
   The root view's `case` has exactly ten arms — the nine canonical
   states plus the `:error` branch. The nine canonical variants below
   map 1:1 onto the nine canonical render keywords:

     :nothing :loading :empty :one :some :too-many
     :incorrect :correct :done

   The `:error` branch is the tenth render arm; it lives on a separate
   `:story.nine-states-lifecycle` story (below) alongside `:loading` /
   `:some`, so the async **load → loading → loaded/error** cascade reads
   as one lifecycle the reader can step through and inspect in Xray.
   Keeping it off the canonical nine keeps that set a clean 1:1 with the
   render keywords.

   ## Fidelity — reach each state via real events

   Every variant drives its state through real events into the
   `:ui/nine-states` machine — the highest-fidelity path, so the Story
   canvas shows exactly what the live app shows and Xray's Epoch / Trace
   / Side Effects panels carry the genuine cascade:

   - Nothing      — `[:nine-states.app/initialise]` (machine `:reset`).
   - Loading       — `[:ui/nine-states [:fetch-started]]` parks the
                     `:data` region at `:loading` (no reply dispatched).
   - Empty/One/Some/Too Many — the `:nine-states.story/load` cascade
                     below: a real `:rf.http/managed` fx whose reply
                     folds back through `:fetch-succeeded`, so the
                     `:data` region's `:always`-cascade picks the
                     cardinality bucket from the item count.
   - Incorrect     — edit a too-short title + submit → `:submit-invalid`.
   - Correct       — edit a valid title + submit → `:submit-valid`.
   - Done          — load + archive → the `:mode` region reaches `:done`.

   Every state is reachable through an event path, so no `:db-seed` /
   `:sub-overrides` are needed and the fidelity is uniformly `:event`.

   ## Xray-richness — the managed-HTTP cascade

   Story allocates each variant its own frame under `:preset :story`.
   That preset redirects `:rf.http/managed` to the framework's
   `:rf.http/managed-canned-success` stub by default — there is no
   automatic failure branch. A variant that wants the failure path
   stamps its own
   `:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}`,
   which wins over the preset default (see the lifecycle `…/error`
   variant below). `:nine-states.story/load` issues a real
   `:rf.http/managed` request carrying the synthetic todos on the
   request's `:value` slot (the slot the canned-success stub echoes
   back), so the full fetch lifecycle runs:

       :nine-states.story/load
         → [:ui/nine-states [:fetch-started]]        (→ :loading)
         → [:rf.http/managed {...}]                  (a real fx)
             → canned-success reply                  (Side Effects panel)
             → [:nine-states.demo/loaded {:value …}]
                 → [:ui/nine-states [:fetch-succeeded …]]
                     → :resolving → :always cardinality bucket

   That whole chain lands on the Epoch tape and the Trace stream, and
   the `:rf.http/managed` fx shows in the Side Effects panel — so a
   reader can pick the `:some` or `:error` variant and watch the fetch
   light up Xray end to end.

   ## Authoring discipline

   Every variant body is plain data — no fn-slots. The view at the centre
   of each variant is the example's own `nine-states.core/root-view`,
   referenced by id. The canonical Story tags auto-install on the first
   `reg-*` call, so no explicit boot step is needed."
  (:require [re-frame.core :as rf]
            [re-frame.story :as story]
            ;; Source the example's registrations (the machine, the demo
            ;; events / subs / views, the `:ui/render` selector). The
            ;; variant bodies below reference its event-ids and the
            ;; `root-view` view-id as plain keywords; requiring the ns
            ;; fires every `reg-*` so those ids resolve.
            [nine-states.core]))

;; ---------------------------------------------------------------------------
;; Story-side load event — the Xray-rich managed-HTTP cascade
;;
;; The live example's `:nine-states.demo/load` builds its request with
;; `:query {:n N}` and lets the app's own `:nine-states.http/managed-demo`
;; fx-override read `:n` to synthesise todos. Inside the Story shell the
;; per-variant frame runs under `:preset :story`, which redirects
;; `:rf.http/managed` to the framework's `:rf.http/managed-canned-success`
;; stub instead — that stub echoes the request's `:value` slot back as the
;; success payload. So the Story load event puts the synthetic todos
;; directly on `:value`, keeping the same real `:rf.http/managed` fx
;; cascade the live app uses (visible in Xray's Side Effects panel) while
;; staying deterministic under the canned stub.
;; ---------------------------------------------------------------------------

(defn- gen-todos
  "Synthesise N demo todos. Mirrors the live example's private
   generator; kept local so the story file does not reach into
   `nine-states.core`'s implementation namespace."
  [n]
  (vec (for [i (range n)]
         {:id (random-uuid) :title (str "Todo #" (inc i)) :done? false})))

(rf/reg-event :nine-states.story/load
  {:doc "Story-shell variant of `:nine-states.demo/load`. Drives the
         same real fetch cascade — `:fetch-started` → `:rf.http/managed`
         → reply → `:fetch-succeeded` — but carries the synthetic todos
         on the request's `:value` slot so the `:preset :story` frame's
         canned-success stub returns them. `n` items lands the `:data`
         region in the matching cardinality bucket via the
         `:always`-cascade."}
  (fn handler-story-load [_ [_ {:keys [n]}]]
    {:fx [[:dispatch [:ui/nine-states [:fetch-started]]]
          [:rf.http/managed
           {:request    {:method :get :url "/api/todos" :query {:n (or n 0)}}
            :value      (gen-todos (or n 0))
            :decode     :json
            :on-success [:nine-states.demo/loaded]
            :on-failure [:nine-states.demo/load-failed]}]]}))

(rf/reg-event :nine-states.story/load-failing
  {:doc "Story-shell variant of `:nine-states.demo/load-with-failure`.
         Drives the same real fetch cascade — `:fetch-started` →
         `:rf.http/managed` → reply → `:fetch-failed` — into the
         `:error` branch.

         The `:story` preset routes `:rf.http/managed` to the
         canned-success stub by default, which would take the
         `:on-success` path. So the error variant stamps its own
         `:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}`
         (a variant-owned `:fx-overrides` wins over the preset's), and
         this same event runs against the canned-failure stub instead —
         that stub reads the top-level `:kind` / `:tags` slots below,
         synthesises a failure reply, and dispatches `:on-failure`,
         landing the `:data` region at `:error`."}
  (fn handler-story-load-failing [_ _]
    {:fx [[:dispatch [:ui/nine-states [:fetch-started]]]
          [:rf.http/managed
           ;; The canned-failure stub reads `:kind` / `:tags` from the
           ;; top level (not a nested `:failure` map), so this failure
           ;; category carries through to `:nine-states.demo/load-failed`.
           {:request    {:method :get :url "/api/todos/fail"}
            :kind       :rf.http/transport
            :tags       {:message "Network unreachable."}
            :decode     :json
            :on-success [:nine-states.demo/loaded]
            :on-failure [:nine-states.demo/load-failed]}]]}))

;; ---------------------------------------------------------------------------
;; register-all!
;;
;; Every registration lives in one top-level fn so a hot-reload can re-fire
;; the lot at once. The fn also fires once at namespace load via the
;; trailing call, so consumers who just `:require` this ns get the
;; registrations populated.
;; ---------------------------------------------------------------------------

(defn register-all!
  "Register the nine-states example's Story artefacts. Idempotent.
   The canonical vocabulary auto-installs on the first `reg-*` call
   — no explicit boot step required."
  []

  ;; -------------------------------------------------------------------------
  ;; reg-tag — a project tag marking the canonical screenshot variant.
  ;; -------------------------------------------------------------------------

  (story/reg-tag :nine-states/canonical
    {:doc "Marks the variant that ships as the example's canonical
          screenshot — the `:some` standard-list state."})

  ;; -------------------------------------------------------------------------
  ;; reg-story — the parent story. Its `:component` (the example's
  ;; `root-view`) and `:tags` inherit down to every variant below; the
  ;; per-state setup lives on the variants.
  ;; -------------------------------------------------------------------------

  (story/reg-story :story.nine-states
    {:doc        "The Nine States of UI — every canonical view-state of
                 the todos page, driven through the `:ui/nine-states`
                 parallel machine."
     :component  :nine-states.core/root-view
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  (story/reg-story :story.nine-states-lifecycle
    {:doc        "The RemoteData fetch lifecycle as a stepped cascade —
                 load → loading → loaded / error. Kept separate from the
                 canonical nine so the async path (and its Xray Epoch /
                 Trace / Side Effects trail) reads as one story the
                 reader can step through."
     :component  :nine-states.core/root-view
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; reg-variant — the nine canonical states, one per render keyword.
  ;;
  ;; Each `:setup` drives the machine to its state through REAL events
  ;; (fidelity `:event`); no `:db-seed` / `:sub-overrides`.
  ;; -------------------------------------------------------------------------

  ;; State 1 — Nothing. Initialise the form slice + reset the machine.
  (story/reg-variant :story.nine-states/nothing
    {:doc        "State 1 — Nothing. Never fetched; the `:data` region
                 sits at `:nothing`. The blank-slate welcome view with a
                 'Get started' CTA."
     :setup      [[:nine-states.app/initialise]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; State 2 — Loading. `:fetch-started` parks the `:data` region at
  ;; `:loading`; no reply is dispatched so it stays there for the
  ;; screenshot.
  (story/reg-variant :story.nine-states/loading
    {:doc        "State 2 — Loading. A first fetch is in flight; the
                 `:data` region is parked at `:loading` (the
                 `:fetch-started` event with no reply). Spinner view."
     :setup      [[:nine-states.app/initialise]
                  [:ui/nine-states [:fetch-started]]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; State 3 — Empty. Load zero todos; the `:always`-cascade picks `:empty`.
  (story/reg-variant :story.nine-states/empty
    {:doc        "State 3 — Empty. Fetched, but the result is the empty
                 list. The `:always`-cascade picks `:empty` from a
                 zero count."
     :setup      [[:nine-states.app/initialise]
                  [:nine-states.story/load {:n 0}]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; State 4 — One. Exactly one todo; the cascade picks `:one`.
  (story/reg-variant :story.nine-states/one
    {:doc        "State 4 — One. Exactly one todo; the focused
                 single-item layout (`:one` cardinality bucket)."
     :setup      [[:nine-states.app/initialise]
                  [:nine-states.story/load {:n 1}]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; State 5 — Some. A small, manageable list; the canonical screenshot.
  (story/reg-variant :story.nine-states/some
    {:doc        "State 5 — Some. A small, manageable list rendered
                 plainly (`:some` cardinality bucket). The example's
                 canonical screenshot."
     :setup      [[:nine-states.app/initialise]
                  [:nine-states.story/load {:n 4}]]
     :tags       #{:dev :docs :nine-states/canonical}
     :substrates #{:reagent}})

  ;; State 6 — Too Many. Beyond the threshold; search + truncation.
  (story/reg-variant :story.nine-states/too-many
    {:doc        "State 6 — Too Many. More items than the too-many
                 threshold (7); the view shows search + truncation
                 (`:too-many` cardinality bucket)."
     :setup      [[:nine-states.app/initialise]
                  [:nine-states.story/load {:n 25}]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; State 7 — Incorrect. A too-short title + submit → `:submit-invalid`.
  (story/reg-variant :story.nine-states/incorrect
    {:doc        "State 7 — Incorrect. A too-short title fails the form
                 validator on submit; the `:form` region lands at
                 `:incorrect` (`:submit-invalid`), showing the inline
                 per-field error."
     :setup      [[:nine-states.app/initialise]
                  [:new-todo/edit-field :title "no"]
                  [:new-todo/submit]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; State 8 — Correct. A valid title + submit → `:submit-valid`.
  (story/reg-variant :story.nine-states/correct
    {:doc        "State 8 — Correct. A valid title passes the form
                 validator on submit; the `:form` region lands at
                 `:correct` (`:submit-valid`), showing the success
                 acknowledgement."
     :setup      [[:nine-states.app/initialise]
                  [:new-todo/edit-field :title "Buy milk"]
                  [:new-todo/submit]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; State 9 — Done. Load some items, then archive → `:mode` at `:done`.
  (story/reg-variant :story.nine-states/done
    {:doc        "State 9 — Done. After loading some todos the list is
                 archived; the `:mode` region reaches the terminal,
                 read-only `:done` state and the form + controls
                 disable themselves."
     :setup      [[:nine-states.app/initialise]
                  [:nine-states.story/load {:n 4}]
                  [:ui/nine-states [:archive {}]]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; The :error branch — the tenth render arm. Lives on the lifecycle
  ;; story so the async failure cascade reads alongside :loading / :some.
  ;; -------------------------------------------------------------------------

  (story/reg-variant :story.nine-states-lifecycle/loading
    {:doc        "Lifecycle — the in-flight `:loading` step. Same as the
                 canonical Loading variant, shown here as the first arm
                 of the fetch cascade."
     :setup      [[:nine-states.app/initialise]
                  [:ui/nine-states [:fetch-started]]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  (story/reg-variant :story.nine-states-lifecycle/loaded
    {:doc        "Lifecycle — the resolved `:some` step. The full
                 `:rf.http/managed` cascade runs in `:setup`; pick this
                 variant and inspect the fetch in Xray's Epoch / Trace /
                 Side Effects panels."
     :setup      [[:nine-states.app/initialise]
                  [:nine-states.story/load {:n 4}]]
     :tags       #{:dev :docs}
     :substrates #{:reagent}})

  (story/reg-variant :story.nine-states-lifecycle/error
    {:doc           "Lifecycle — the `:error` branch. This variant stamps
                    `:fx-overrides {:rf.http/managed
                    :rf.http/managed-canned-failure}` so its
                    `:rf.http/managed` request runs through the canned-
                    FAILURE stub (overriding the `:story` preset's
                    canned-success default); `:fetch-failed` then lands
                    the `:data` region at `:error`. The failure cascade
                    lights up Xray's Side Effects + Trace panels
                    alongside the success path."
     ;; This variant-owned `:fx-overrides` wins over the `:story` preset's
     ;; canned-success default, giving the failing variant its failure
     ;; stub — the success variants keep the preset's canned-success.
     :fx-overrides  {:rf.http/managed :rf.http/managed-canned-failure}
     :setup         [[:nine-states.app/initialise]
                     [:nine-states.story/load-failing]]
     :tags          #{:dev :docs}
     :substrates    #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; reg-workspace — two layouts over the canonical nine.
  ;;
  ;; `:variants-grid` auto-enumerates every variant of the parent story
  ;; (new variants appear without touching the workspace); `:grid` pins
  ;; the nine in canonical order for the README screenshot.
  ;; -------------------------------------------------------------------------

  (story/reg-workspace :Workspace.nine-states/all-states
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

  (story/reg-workspace :Workspace.nine-states/auto-grid
    {:doc     "Auto-enumerated grid — pulls every variant off
              :story.nine-states. New variants appear here without
              touching this workspace."
     :layout  :variants-grid
     :for     :story.nine-states
     :columns 3
     :tags    #{:docs}})

  (story/reg-workspace :Workspace.nine-states/lifecycle
    {:doc      "The RemoteData fetch lifecycle as a left-to-right
              stepped row: loading → loaded → error."
     :layout   :grid
     :variants [:story.nine-states-lifecycle/loading
                :story.nine-states-lifecycle/loaded
                :story.nine-states-lifecycle/error]
     :columns  3
     :tags     #{:docs}}))

;; Fire the registrations once at namespace load.
(register-all!)
