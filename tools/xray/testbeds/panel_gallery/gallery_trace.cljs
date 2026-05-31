(ns panel-gallery.gallery-trace
  "Story coverage for the **Trace tab** of the 6-tab Xray chrome
  (rf2-sszlr — gallery rebuild for spec/018-Event-Spine; epoch-scoped
  rewire rf2-ofoqu; FLAT-list refresh rf2-aqusw).

  The Trace tab body is the `trace/Panel` view: the focused epoch's
  whole trace as a SINGLE FLAT LIST of op rows (rf2-aqusw — the prior
  4-band hierarchy is gone), scoped to the spine's FOCUSED EPOCH
  (rf2-td380). The panel reads the focused epoch's `:trace-events`
  slice — resolved via the shared `focus-resolver` over
  `:rf.xray/focus` + `:rf.xray/epoch-history` — NOT the trace bus.

  ## The flat row shape (rf2-aqusw)

  The list reads top-down, OLDEST-FIRST. Each op is a row of six
  columns — `Δt · stage · area badge · what-happened · target/detail ·
  duration`. The STAGE column names the Epoch-panel pipeline step the
  op belongs to (DISPATCH · COEFFECT · HANDLER · FLOW · SIDE EFFECTS ·
  SUBSCRIPTIONS · VIEWS) and the row's left EDGE is colour-coded with
  that step's colour — both resolved through the Epoch panel's own
  `panels.epoch.badge` taxonomy (one step model, DRY). Errors /
  warnings render inline at their chronological point; the left edge
  rides the severity colour over the stage colour so a failure stands
  out. Clicking any row opens the edn-inspector on its raw trace MAP
  inline (spec/023 §3). No chip-filtering (the focused epoch IS the
  scope, rf2-gkczt); no row cap (every op renders).

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
    {:doc        "Visual gallery of the Xray Trace tab (the FLAT
                 oldest-first op-row list, rf2-aqusw) under varying
                 epoch trace-event depth + shape. Each variant seeds
                 its frame's :epoch-history via
                 :rf.xray/sync-epoch-history; the panel reads the
                 focused epoch's :trace-events from the variant frame
                 in isolation and projects each op into a row with a
                 STAGE column + colour-coded left edge."
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
                 row per event, oldest-first, each carrying its STAGE
                 column + colour-coded left edge."
     :events     [[:rf.xray/sync-epoch-history (fixtures/short-trace-history)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. medium trace (100 rows) ----------------------------------
  (story/reg-variant :story.xray.trace/medium-trace
    {:doc        "Focused epoch with a 100-row domino trail spanning
                 all four op-types. The flat list renders every row
                 (no row cap, rf2-aqusw); exercises the panel under a
                 mid-density epoch with the full stage taxonomy."
     :events     [[:rf.xray/sync-epoch-history (fixtures/medium-trace-history)]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- 4. long trace (1000 rows) -----------------------------------
  (story/reg-variant :story.xray.trace/long-trace
    {:doc        "Focused epoch with a 1000-row trail — the flat list
                 renders the whole epoch's trace in fire order
                 (oldest-first); exercises the panel + the shared
                 resizable-table under a deep epoch."
     :events     [[:rf.xray/sync-epoch-history (fixtures/long-trace-history)]]
     :tags       #{:dev :state/large}
     :substrates #{:reagent}})

  ;; ----- 5. trace with errors ----------------------------------------
  (story/reg-variant :story.xray.trace/trace-with-errors
    {:doc        "Focused epoch whose every row is an issue: two
                 errors, two warnings, one info. Errors / warnings
                 render inline at their chronological point; the row's
                 left EDGE rides the severity colour over the stage
                 colour and the Δt leads with `!` so a failure stands
                 out (spec/023 §7)."
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
                 row's target/detail column renders the dispatched
                 event vector with the marker verbatim per Spec 009
                 §Privacy; clicking the row opens the edn-inspector on
                 the raw trace MAP (the marker survives there too)."
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
                 `:source-coord` slot (file + line). The per-row `↗`
                 open-in-editor glyph rides the end of the
                 target/detail column with the accent colour and is
                 clickable. Panel-specific axis: jump-to-editor."
     :events     [[:rf.xray/sync-epoch-history (fixtures/source-coord-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.xray.trace/all
    {:doc      "All ten Trace tab variants (the FLAT oldest-first
                op-row list, rf2-aqusw) in one auto-grid. Scroll to see
                the panel's response across empty / short / medium /
                long / errors / flows / mixed-op-types / redacted /
                cross-frame / source-coord — each row carrying its
                STAGE column + colour-coded left edge."
     :layout   :variants-grid
     :story    :story.xray.trace
     :columns  2
     :tags     #{:dev}}))

(register-all!)
