(ns panel-gallery.app-db-diff-stories
  "Story coverage for the Causa app-db-diff panel under gallery
  framing (rf2-5nvk2, Phase 1b).

  Ten variants, each one render of `app-db-diff-view` against a
  variant frame whose `:epoch-history` (and optionally
  `:selected-epoch-id`, `:pinned-slices-store`, `:focused-slice-path`)
  has been seeded by REAL Causa init events fired into the variant
  frame.

  ## Why real init events

  Variant `:events` are dispatched via `(rf/dispatch-sync ev {:frame
  variant-id})` per `tools/story/spec/002-Runtime.md`. Causa's
  registered handlers (`:rf.causa/sync-epoch-history`,
  `:rf.causa/focus-slice-path`, `:rf.causa/pin-slice`) write via
  `(assoc db ...)` — so Story's `:rf.story/*` runtime slots survive
  untouched. Direct app-db assoc would wipe the lifecycle / loaders-
  complete / assertions slots and corrupt the variant.

  ## Why frame-provider {:frame variant-id} not :rf/causa

  The Story canvas wraps each variant in `[frame-provider {:frame
  variant-id}]` (per `tools/story/src/re_frame/story/ui/canvas.cljs`).
  Subscriptions inside the rendered tree resolve to the variant
  frame. `:rf.causa/app-db-diff` reads from the current frame's
  app-db — exactly the variant-frame slots the seed events wrote.
  Each variant therefore observes its own bespoke epoch history in
  isolation; no two variants share state."
  (:require [re-frame.story :as story]
            [panel-gallery.app-db-diff-fixtures :as fixtures]
            [panel-gallery.gallery-views :as gallery-views]))

(defn register-gallery-view! []
  (gallery-views/register!))

(defn register-all!
  "Register the app-db-diff Story surface. Idempotent under
  `register-canonical-vocabulary!` resets so the namespace is reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-app-db-diff
    {:axis :feature
     :doc  "Causa app-db-diff panel — changed slices, pinned slices,
            reserved-keys group, and the 'Show me when this changed'
            walker."})

  (story/reg-story :story.causa.app-db-diff
    {:doc        "Visual gallery of the Causa app-db-diff panel under
                 varying state magnitude. Each variant seeds its frame's
                 :epoch-history via :rf.causa/sync-epoch-history; the
                 rendered panel reads from the variant frame in
                 isolation."
     :component  :panel-gallery.app-db-diff/Panel
     :tags       #{:dev :feature/causa-app-db-diff}
     :substrates #{:reagent}})

  ;; ----- 1. empty db --------------------------------------------------
  (story/reg-variant :story.causa.app-db-diff/empty-db
    {:doc        "No epochs in history. Panel renders the empty-state
                 + pinned + reserved scaffolding only."
     :events     [[:rf.causa/sync-epoch-history (fixtures/empty-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. single-key change ----------------------------------------
  (story/reg-variant :story.causa.app-db-diff/single-key-change
    {:doc        "One epoch with a single top-level key mutation
                 (`:counter` from 5 → 6). Panel renders one
                 changed-slice card."
     :events     [[:rf.causa/sync-epoch-history (fixtures/single-key-change-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. five-key changes -----------------------------------------
  (story/reg-variant :story.causa.app-db-diff/five-key-changes
    {:doc        "One epoch mutating five top-level keys — scalar,
                 nested map, vector slice, added flash, removed
                 legacy flag. Mid-size diff fits one screen."
     :events     [[:rf.causa/sync-epoch-history (fixtures/five-key-changes-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 4. nested deep ----------------------------------------------
  (story/reg-variant :story.causa.app-db-diff/nested-deep
    {:doc        "Six-level-deep nested path (`[:tenant :acme :department
                 :eng :team :platform :project :causa :status]`).
                 Exercises the path-pr-str sort and the path rendering
                 in slice cards."
     :events     [[:rf.causa/sync-epoch-history (fixtures/nested-deep-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 5. large flat (100 keys) ------------------------------------
  (story/reg-variant :story.causa.app-db-diff/large-flat
    {:doc        "One epoch mutating ~100 top-level keys at once.
                 Exercises the overflow / scroll behaviour of the
                 changed-slices stack under storm load."
     :events     [[:rf.causa/sync-epoch-history (fixtures/large-flat-buffer)]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 6. cyclic (deep+wide acyclic stress) ------------------------
  ;;
  ;; True Clojure cycles via mutable refs are not representable in
  ;; immutable maps and never reach the panel; the axis here is the
  ;; legitimate 'deep + wide, print-bounded' stress.
  (story/reg-variant :story.causa.app-db-diff/cyclic
    {:doc        "Deep + wide acyclic structure — 12 tiers each with
                 eight nested keys. The named 'cyclic' axis exercises
                 the print-bounded stress; the diff and renderer
                 must not loop forever on a one-tier replacement."
     :events     [[:rf.causa/sync-epoch-history (fixtures/cyclic-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 7. redacted -------------------------------------------------
  (story/reg-variant :story.causa.app-db-diff/redacted
    {:doc        "Epoch whose mutated `:auth` slice carries
                 `:rf/redacted` markers on `:password` and `:totp`
                 slots. The slice renderer surfaces the marker
                 verbatim per Spec 009 §Privacy."
     :events     [[:rf.causa/sync-epoch-history (fixtures/redacted-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 8. loading (in-flight marker) -------------------------------
  (story/reg-variant :story.causa.app-db-diff/loading
    {:doc        "Epoch whose `:profile` slice transitioned to
                 `:loading` with an in-flight request id. The diff
                 card shows the loader-state slot visibly."
     :events     [[:rf.causa/sync-epoch-history (fixtures/loading-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 9. error ----------------------------------------------------
  (story/reg-variant :story.causa.app-db-diff/error
    {:doc        "Epoch whose `:profile` slice transitioned to
                 `:error` with a populated `:error` map. The slice
                 card surfaces the error shape."
     :events     [[:rf.causa/sync-epoch-history (fixtures/error-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 10. mixed ops (panel-specific axis A) ----------------------
  (story/reg-variant :story.causa.app-db-diff/mixed-ops
    {:doc        "One epoch demonstrating every diff op
                 (`:added` / `:modified` / `:removed`) side-by-side.
                 Panel-specific axis: surfaces the op-colour ladder
                 uniformly in a single card."
     :events     [[:rf.causa/sync-epoch-history (fixtures/mixed-ops-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 11. reserved keys (panel-specific axis B) ------------------
  ;;
  ;; Panel-specific axis: `app-db-diff` distinctly routes reserved-key
  ;; diffs through `partition-reserved` into a separate `[runtime]`
  ;; group. No other panel renders this split.
  (story/reg-variant :story.causa.app-db-diff/reserved-keys
    {:doc        "Epoch mutating reserved app-db keys (`:rf/route`,
                 `:rf/machines`, `:rf/spawned`). Panel-specific axis:
                 the `[runtime]` group surfaces these separately
                 from user-key slices per Spec Conventions §Reserved
                 app-db keys."
     :events     [[:rf.causa/sync-epoch-history (fixtures/reserved-keys-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.causa.app-db-diff/all
    {:doc      "All eleven app-db-diff variants in one auto-grid.
                Scroll to see the panel's response across empty / one
                key / five keys / deep nesting / 100 keys / wide
                structure / redacted / loading / error / mixed-ops /
                reserved-keys."
     :layout   :variants-grid
     :story    :story.causa.app-db-diff
     :columns  2
     :tags     #{:dev}}))

(register-all!)
