(ns panel-gallery.gallery-trace
  "Story coverage for the **Trace tab** of the new 6-tab Xray chrome
  (rf2-sszlr — gallery rebuild for spec/018-Event-Spine; epoch-scoped
  rewire rf2-ofoqu).

  The Trace tab body is the `trace/Panel` view: the raw-event ribbon
  scoped to the spine's FOCUSED EPOCH (rf2-td380). The panel reads the
  focused epoch's `:trace-events` slice — resolved via the shared
  `focus-resolver` over `:rf.xray/focus` + `:rf.xray/epoch-history` —
  NOT the trace bus.

  ## Why seed via `:rf.xray/sync-epoch-history` (rf2-ofoqu)

  Pre-rewire each variant seeded the trace BUS via
  `:rf.xray/sync-trace-buffer`, which the epoch-scoped panel no longer
  reads — so the variants rendered empty. Each variant now seeds its
  frame's `:epoch-history` via `:rf.xray/sync-epoch-history` (a vector
  of `:rf/epoch-record` maps, each carrying a populated `:trace-events`
  slice). No variant pins focus: with no bus seeded there are no
  cascades, so the focus-resolver's head-fallback renders the HEAD
  epoch record (`(peek epoch-history)`)."
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

  (story/reg-tag :feature/xray-trace
    {:axis :feature
     :doc  "Xray Trace tab — the focused-epoch domino-trail ribbon
            (per spec/018-Event-Spine §5.4 + rf2-td380)."})

  (story/reg-story :story.xray.trace
    {:doc        "Visual gallery of the Xray Trace tab under varying
                 epoch trace-event depth + shape. Each variant seeds
                 its frame's :epoch-history via
                 :rf.xray/sync-epoch-history; the panel reads the
                 focused epoch's :trace-events from the variant frame
                 in isolation."
     :component  :panel-gallery.trace/Panel
     :tags       #{:dev :feature/xray-trace}
     :substrates #{:reagent}})

  ;; ----- 1. empty trace (focused epoch carries no events) ------------
  (story/reg-variant :story.xray.trace/empty-trace
    {:doc        "Focused epoch carries an empty :trace-events slice.
                 Panel renders the :no-events empty-state ('No
                 events.')."
     :events     [[:rf.xray/sync-epoch-history (fixtures/empty-trace-history)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 2. short trace (normal ten-row cascade) ---------------------
  (story/reg-variant :story.xray.trace/short-trace
    {:doc        "A normal cascade — the focused epoch's domino trail
                 is ten events spanning every canonical op-type. One
                 row per event, newest first."
     :events     [[:rf.xray/sync-epoch-history (fixtures/short-trace-history)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. medium trace (100 rows) ----------------------------------
  (story/reg-variant :story.xray.trace/medium-trace
    {:doc        "Focused epoch with a 100-row domino trail spanning
                 all four op-types. The 200-row cap is not hit; the
                 overflow indicator stays quiet."
     :events     [[:rf.xray/sync-epoch-history (fixtures/medium-trace-history)]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 4. long trace (1000 rows; cap-eviction) ---------------------
  (story/reg-variant :story.xray.trace/long-trace
    {:doc        "Focused epoch with a 1000-row trail — exercises the
                 200-row cap and surfaces the overflow indicator at the
                 head of the feed."
     :events     [[:rf.xray/sync-epoch-history (fixtures/long-trace-history)]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 5. trace with errors ----------------------------------------
  (story/reg-variant :story.xray.trace/trace-with-errors
    {:doc        "Focused epoch whose every row is an issue: two
                 errors, two warnings, one info. Per-row dot colours
                 match the severity tiers."
     :events     [[:rf.xray/sync-epoch-history (fixtures/errors-trace-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 6. multi-op epoch with flows --------------------------------
  ;; A multi-op history: a leading counter epoch precedes the focused
  ;; flow-cascade epoch (head), exercising a realistic ring while
  ;; head-fallback keeps the flow epoch in view.
  (story/reg-variant :story.xray.trace/trace-with-flows
    {:doc        "Multi-op history. The focused (head) epoch is a
                 `:cart/add` cascade that triggers three
                 `:rf.flow/computed` recompute rows then a downstream
                 view render — the panel renders the flow op-type
                 alongside the dominoes. A prior counter epoch sits
                 behind it in the ring."
     :events     [[:rf.xray/sync-epoch-history (fixtures/flows-trace-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 7. mixed op-types -------------------------------------------
  ;; rf2-gkczt: chip-filtering was removed from the Trace panel — the
  ;; focused epoch IS the scope. This variant exercises a mixed-op-type
  ;; trail with no filter event.
  (story/reg-variant :story.xray.trace/mixed-op-types
    {:doc        "Focused epoch whose trail mixes event + fx op-types.
                 The feed renders every row (no chip-filtering
                 post-rf2-gkczt)."
     :events     [[:rf.xray/sync-epoch-history (fixtures/mixed-op-types-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 8. redacted slot --------------------------------------------
  (story/reg-variant :story.xray.trace/redacted
    {:doc        "Focused epoch whose dispatched event payload carries
                 `:rf/redacted` markers on `:password` + `:totp`. The
                 panel's description column renders the marker verbatim
                 per Spec 009 §Privacy."
     :events     [[:rf.xray/sync-epoch-history (fixtures/redacted-trace-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 9. cross-frame ----------------------------------------------
  (story/reg-variant :story.xray.trace/cross-frame
    {:doc        "Focused epoch whose trail spans three frames evenly.
                 The per-row frame projection surfaces the frame on
                 every event. Panel-specific axis."
     :events     [[:rf.xray/sync-epoch-history (fixtures/cross-frame-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 10. source-coord --------------------------------------------
  (story/reg-variant :story.xray.trace/source-coord
    {:doc        "Focused epoch whose every row carries a
                 `:source-coord` slot (file + line). The per-row
                 source-coord chip renders with the cyan accent and is
                 clickable. Panel-specific axis: jump-to-editor."
     :events     [[:rf.xray/sync-epoch-history (fixtures/source-coord-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.xray.trace/all
    {:doc      "All ten Trace tab variants in one auto-grid. Scroll
                to see the panel's response across empty / short /
                medium / long / errors / flows / mixed-op-types /
                redacted / cross-frame / source-coord."
     :layout   :variants-grid
     :story    :story.xray.trace
     :columns  2
     :tags     #{:dev}}))

(register-all!)
