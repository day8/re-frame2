(ns panel-gallery.trace-stories
  "Story coverage for the Causa trace panel under gallery framing
  (rf2-8r20i, Phase 2).

  Nine variants, each one render of `trace-view` against a variant
  frame whose `:trace-buffer` (and optionally `:trace-filters`) has
  been seeded by REAL Causa init events fired into the variant frame.

  ## Why real init events

  Variant `:events` are dispatched via `(rf/dispatch-sync ev {:frame
  variant-id})` per `tools/story/spec/002-Runtime.md`. Causa's
  registered handlers (`:rf.causa/sync-trace-buffer`,
  `:rf.causa/set-trace-filter`) write via `(assoc db ...)` — Story's
  `:rf.story/*` runtime slots survive untouched. Direct app-db assoc
  would wipe the lifecycle / loaders-complete / assertions slots and
  corrupt the variant.

  ## Why frame-provider {:frame variant-id} not :rf/causa

  The Story canvas wraps each variant in `[frame-provider {:frame
  variant-id}]`. Subscriptions inside the rendered tree resolve to
  the variant frame. `:rf.causa/trace-feed` reads from the current
  frame's app-db (the seeded buffer + filters). Each variant therefore
  observes its own bespoke trace stream in isolation; no two variants
  share state."
  (:require [re-frame.story :as story]
            [panel-gallery.trace-fixtures :as fixtures]
            [panel-gallery.gallery-views :as gallery-views]))

(defn register-gallery-view! []
  (gallery-views/register!))

(defn register-all!
  "Register the trace Story surface. Idempotent under
  `register-canonical-vocabulary!` resets so the namespace is reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-trace
    {:axis :feature
     :doc  "Causa trace panel — raw-event ribbon over the 9-axis
            filter vocabulary."})

  (story/reg-story :story.causa.trace
    {:doc        "Visual gallery of the Causa trace panel under varying
                 buffer depth + filter state. Each variant seeds its
                 frame's :trace-buffer via :rf.causa/sync-trace-buffer;
                 the rendered panel reads from the variant frame in
                 isolation."
     :component  :panel-gallery.trace/Panel
     :tags       #{:dev :feature/causa-trace}
     :substrates #{:reagent}})

  ;; ----- 1. empty buffer --------------------------------------------
  (story/reg-variant :story.causa.trace/empty-buffer
    {:doc        "No events in the buffer. Panel renders the
                 :no-events empty-state copy."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/empty-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. ten events (small) --------------------------------------
  (story/reg-variant :story.causa.trace/ten-events
    {:doc        "Ten events spanning every canonical op-type. Chip
                 rows surface op-type / source / origin / frame with
                 ≥2 values each; the feed renders one row per event."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/ten-events-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. one hundred events (medium) -----------------------------
  (story/reg-variant :story.causa.trace/hundred-events
    {:doc        "One hundred events spanning all four op-types,
                 three frames, three origins, four sources. The cap
                 (200) is not hit; cap-eviction indicator stays
                 quiet."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/hundred-events-buffer)]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 4. one thousand events (cap-eviction) ----------------------
  (story/reg-variant :story.causa.trace/thousand-events-cap-eviction
    {:doc        "One thousand events — exercises the 200-row cap and
                 surfaces the overflow indicator at the head of the
                 feed. Per `overflow_indicator.cljc` §capped-list the
                 panel renders 200 rows + one '... N rows hidden'
                 indicator row."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/thousand-events-buffer)]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 5. filter active -------------------------------------------
  (story/reg-variant :story.causa.trace/filter-active
    {:doc        "Ten events with an active :op-type :event filter.
                 Header surfaces 'Clear filters'; the chip row
                 surfaces the active chip highlit. Feed renders only
                 the matching rows."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/filtered-active-buffer)]
                  [:rf.causa/set-trace-filter :op-type :event]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 6. redacted slot present -----------------------------------
  (story/reg-variant :story.causa.trace/redacted
    {:doc        "Dispatched event payload carries `:rf/redacted`
                 markers on `:password` + `:totp` slots. The panel's
                 description column renders the marker verbatim per
                 Spec 009 §Privacy."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/redacted-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 7. errors / warnings / advisories --------------------------
  (story/reg-variant :story.causa.trace/error
    {:doc        "Every row is an issue: two errors, two warnings,
                 one info. Severity chip row surfaces all three
                 tiers populated; per-row dot colours match."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/error-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 8. cross-frame mix (panel-specific axis A) -----------------
  ;;
  ;; Panel-specific axis: the :frame chip row surfaces 3+ values
  ;; (:rf/default, :rf/causa, :tenant/alpha) side-by-side. Per Spec 009
  ;; §Canonical per-frame routing key this is the trace panel's
  ;; signature multi-tenancy axis.
  (story/reg-variant :story.causa.trace/cross-frame
    {:doc        "Twelve events spanning three frames evenly. The
                 :frame chip row populates the full ladder; the per-
                 row chip surfaces the frame on every event.
                 Panel-specific axis."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/cross-frame-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 9. source-coord populated (panel-specific axis B) ----------
  ;;
  ;; Panel-specific axis: every emit inside a dispatch can carry
  ;; :rf.trace/trigger-handler :source-coord (per Spec 009 §Source-
  ;; coord). The trace panel's per-row source-coord chip is the
  ;; affordance for jump-to-editor — this variant pins the rendering
  ;; surface.
  (story/reg-variant :story.causa.trace/source-coord
    {:doc        "Six events each carrying a `:source-coord` slot
                 (file + line). The per-row source-coord chip
                 renders with the cyan accent and is clickable.
                 Panel-specific axis: source-coord jump-to-editor."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/source-coord-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.causa.trace/all
    {:doc      "All nine trace variants in one auto-grid. Scroll to
                see the panel's response across empty / 10 / 100 /
                1000 events, filter active, redacted, error mix,
                cross-frame, and source-coord populated."
     :layout   :variants-grid
     :story    :story.causa.trace
     :columns  2
     :tags     #{:dev}}))

(register-all!)
