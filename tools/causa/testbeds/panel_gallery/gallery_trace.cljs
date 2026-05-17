(ns panel-gallery.gallery-trace
  "Story coverage for the **Trace tab** of the new 6-tab Causa chrome
  (rf2-sszlr — gallery rebuild for spec/018-Event-Spine).

  The Trace tab body is the `trace/Panel` view: the raw-event ribbon
  over the 9-axis filter vocabulary. Each variant seeds its frame's
  `:trace-buffer` (and optionally `:trace-filters`) via REAL Causa
  init events fired into the variant frame."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures-trace :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the Trace tab Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-trace
    {:axis :feature
     :doc  "Causa Trace tab — raw-event ribbon over the 9-axis
            filter vocabulary (per spec/018-Event-Spine §5.4)."})

  (story/reg-story :story.causa.trace
    {:doc        "Visual gallery of the Causa Trace tab under varying
                 buffer depth + filter state. Each variant seeds its
                 frame's :trace-buffer via :rf.causa/sync-trace-buffer;
                 the rendered panel reads from the variant frame in
                 isolation."
     :component  :panel-gallery.trace/Panel
     :tags       #{:dev :feature/causa-trace}
     :substrates #{:reagent}})

  ;; ----- 1. short trace (empty) --------------------------------------
  (story/reg-variant :story.causa.trace/empty-trace
    {:doc        "No events in the buffer. Panel renders the
                 :no-events empty-state copy."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/empty-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. short trace (ten events) ---------------------------------
  (story/reg-variant :story.causa.trace/short-trace
    {:doc        "Ten events spanning every canonical op-type. Chip
                 rows surface op-type / source / origin / frame with
                 ≥2 values each; the feed renders one row per event."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/ten-events-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. medium trace (100 events) --------------------------------
  (story/reg-variant :story.causa.trace/medium-trace
    {:doc        "One hundred events spanning all four op-types,
                 three frames, three origins, four sources. The cap
                 (200) is not hit; cap-eviction indicator stays
                 quiet."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/hundred-events-buffer)]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 4. long trace (1000 events; cap-eviction) -------------------
  (story/reg-variant :story.causa.trace/long-trace
    {:doc        "One thousand events — exercises the 200-row cap and
                 surfaces the overflow indicator at the head of the
                 feed. Per `overflow_indicator.cljc` §capped-list the
                 panel renders 200 rows + one '... N rows hidden'
                 indicator row."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/thousand-events-buffer)]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 5. trace with errors ----------------------------------------
  (story/reg-variant :story.causa.trace/trace-with-errors
    {:doc        "Every row is an issue: two errors, two warnings,
                 one info. Severity chip row surfaces all three
                 tiers populated; per-row dot colours match."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/error-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 6. trace with flows -----------------------------------------
  (story/reg-variant :story.causa.trace/trace-with-flows
    {:doc        "Cascade rooted on `:cart/add` that triggers three
                 `:rf.flow/computed` recompute events followed by a
                 downstream view render. Pins the panel's rendering
                 of the flow op-type alongside the dominoes."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/flows-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 7. filter active --------------------------------------------
  (story/reg-variant :story.causa.trace/filter-active
    {:doc        "Ten events with an active :op-type :event filter.
                 Header surfaces 'Clear filters'; the chip row
                 surfaces the active chip highlit. Feed renders only
                 the matching rows."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/filtered-active-buffer)]
                  [:rf.causa/set-trace-filter :op-type :event]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 8. redacted slot --------------------------------------------
  (story/reg-variant :story.causa.trace/redacted
    {:doc        "Dispatched event payload carries `:rf/redacted`
                 markers on `:password` + `:totp` slots. The panel's
                 description column renders the marker verbatim per
                 Spec 009 §Privacy."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/redacted-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 9. cross-frame ----------------------------------------------
  (story/reg-variant :story.causa.trace/cross-frame
    {:doc        "Twelve events spanning three frames evenly. The
                 :frame chip row populates the full ladder; the per-
                 row chip surfaces the frame on every event.
                 Panel-specific axis."
     :events     [[:rf.causa/sync-trace-buffer (fixtures/cross-frame-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 10. source-coord --------------------------------------------
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
    {:doc      "All ten Trace tab variants in one auto-grid. Scroll
                to see the panel's response across empty / short /
                medium / long / errors / flows / filter-active /
                redacted / cross-frame / source-coord."
     :layout   :variants-grid
     :story    :story.causa.trace
     :columns  2
     :tags     #{:dev}}))

(register-all!)
