(ns panel-gallery.causality-graph-stories
  "Story coverage for the Causa causality-graph panel under gallery
  framing (rf2-8r20i, Phase 2).

  Ten variants, each one render of `causality-graph/Panel` against a
  variant frame whose `:trace-buffer` (and optionally
  `:selected-dispatch-id`) has been seeded by REAL Causa init events
  fired into the variant frame.

  ## Why real init events

  Variant `:events` are dispatched via `(rf/dispatch-sync ev {:frame
  variant-id})` per `tools/story/spec/002-Runtime.md`. Causa's
  registered handlers (`:rf.causa/sync-trace-buffer`,
  `:rf.causa/select-dispatch-id`) write via `(assoc db ...)` —
  Story's `:rf.story/*` runtime slots survive untouched.

  ## Why frame-provider {:frame variant-id} not :rf/causa

  The Story canvas wraps each variant in `[frame-provider {:frame
  variant-id}]`. Subscriptions inside the rendered tree resolve to
  the variant frame. `:rf.causa/causality-graph-data` reads from the
  current frame's app-db (the seeded buffer + selection slots).
  Each variant therefore observes its own bespoke graph in isolation;
  no two variants share state."
  (:require [re-frame.story :as story]
            [panel-gallery.causality-graph-fixtures :as fixtures]
            [panel-gallery.gallery-views :as gallery-views]))

(defn register-gallery-view! []
  (gallery-views/register!))

(defn register-all!
  "Register the causality-graph Story surface. Idempotent under
  `register-canonical-vocabulary!` resets so the namespace is reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-causality-graph
    {:axis :feature
     :doc  "Causa causality-graph panel — SVG DAG of dispatch
            cascades with parent/child edges + origin / status
            colour-coding."})

  (story/reg-story :story.causa.causality-graph
    {:doc        "Visual gallery of the Causa causality-graph panel
                 under varying graph topology. Each variant seeds its
                 frame's :trace-buffer via :rf.causa/sync-trace-buffer;
                 the rendered panel reads from the variant frame in
                 isolation."
     :component  :panel-gallery.causality-graph/Panel
     :tags       #{:dev :feature/causa-causality-graph}
     :substrates #{:reagent}})

  ;; ----- 1. empty (no cascades) -------------------------------------
  (story/reg-variant :story.causa.causality-graph/empty
    {:doc        "No cascades. Panel renders the empty-state copy
                 ('No cascades yet')."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/empty-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. one-node graph ------------------------------------------
  (story/reg-variant :story.causa.causality-graph/one-node
    {:doc        "Single root cascade. Graph renders one node, zero
                 arrows. The node fills with the `:app` violet."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/one-node-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. five-node graph -----------------------------------------
  (story/reg-variant :story.causa.causality-graph/five-nodes
    {:doc        "Five cascades — one root + four children. Layered
                 layout demonstrates the parent → child arrow
                 rendering."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/five-node-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 4. fifty-node graph ----------------------------------------
  (story/reg-variant :story.causa.causality-graph/fifty-nodes
    {:doc        "Fifty cascades — a two-level fan-out. Exercises the
                 panel's layout discipline under realistic dashboard
                 load; the SVG is scrollable."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/fifty-node-buffer)]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 5. cyclic (distinct-node by dispatch-id) -------------------
  (story/reg-variant :story.causa.causality-graph/cyclic
    {:doc        "Two cascades with identical event-vectors but
                 distinct dispatch-ids. Per spec §What this doesn't
                 do — true cycles can't exist; node identity is
                 always the dispatch-id."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/cyclic-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 6. collapsed (resting state) -------------------------------
  (story/reg-variant :story.causa.causality-graph/collapsed
    {:doc        "Root + three children, no selection. The panel's
                 resting state — every node renders without the
                 selection-stroke highlight."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/collapsed-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 7. expanded (with root selected) ---------------------------
  (story/reg-variant :story.causa.causality-graph/expanded
    {:doc        "Two-level deep tree with the root selected. The
                 selected-node stroke (magenta accent) highlights the
                 root; non-selected nodes render at 0.85 opacity."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/expanded-buffer)]
                  [:rf.causa/select-dispatch-id 100]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 8. layout stable -------------------------------------------
  (story/reg-variant :story.causa.causality-graph/layout-stable
    {:doc        "Sibling cascades added out-of-order — the layout's
                 deterministic BFS encounter order assigns stable
                 columns regardless of insertion order."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/layout-stable-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 9. cross-origin (panel-specific axis A) --------------------
  ;;
  ;; Panel-specific axis: node-fill colour ladders by `:origin`.
  ;; Five cascades each with a distinct origin surface every fill
  ;; colour. No other Causa panel renders origin as a primary axis.
  (story/reg-variant :story.causa.causality-graph/cross-origin
    {:doc        "Five cascades each with a distinct `:origin`
                 (`:app` / `:pair` / `:story` / `:test` / `:causa`).
                 Surfaces every node-fill colour the panel renders.
                 Panel-specific axis."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/cross-origin-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 10. error+warning border tinting (panel-specific axis B) --
  ;;
  ;; Panel-specific axis: node border tints by per-cascade status —
  ;; red for an `:op-type :error` row, amber for `:warning`. Three
  ;; cascades pin all three treatments side-by-side.
  (story/reg-variant :story.causa.causality-graph/error-and-warning
    {:doc        "Three cascades — one with an `:error`, one with a
                 `:warning`, one clean. Surfaces all three border
                 treatments side-by-side. Panel-specific axis."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/error-and-warning-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 11. orphan-children (panel-specific axis C) ----------------
  ;;
  ;; Panel-specific axis: orphan children render as roots per spec
  ;; §What this doesn't do — 'no retroactive correlation'. This
  ;; variant exercises that branch with three orphans + one true root.
  ;;
  ;; NOTE: spec calls for 10 variants — including the orphan-children
  ;; brings us to 11. We retain 10 to honour the bead's count; the
  ;; orphan-children axis is deferred to a follow-on if the panel-
  ;; specific 'layout stable' or 'collapsed/expanded' aren't enough.
  ;; Kept here in source as an inline note for future expansion.

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.causa.causality-graph/all
    {:doc      "All ten causality-graph variants in one auto-grid.
                Scroll to see the panel's response across empty /
                1 / 5 / 50 nodes, cyclic, collapsed, expanded with
                selection, layout-stable, cross-origin, and error+
                warning border tinting."
     :layout   :variants-grid
     :story    :story.causa.causality-graph
     :columns  2
     :tags     #{:dev}}))

(register-all!)
