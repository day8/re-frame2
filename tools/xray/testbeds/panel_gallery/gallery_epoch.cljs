(ns panel-gallery.gallery-epoch
  "Story coverage for the **Epoch panel** of the new Xray chrome
  (rf2-mzcwt — gallery surface for the numbered cascade panel landed
  in rf2-sc3r1 + extended through PRs #2191 / #2193).

  The Epoch panel body is `panels.epoch.view/Panel`: the focused
  epoch's full computational timeline rendered as a numbered vertical
  cascade. Each step is conditional — present only when its driving
  trace events surfaced — so the gallery's variants are organised as a
  feature-coverage TABLE, one variant per section the projection
  lights up.

  ## Section-coverage table

  | Variant                | Sections exercised                                 |
  |------------------------|----------------------------------------------------|
  | `vanilla-db`           | DISPATCH · COEFFECT · HANDLER (db) · APP-DB DIFF · SUBSCRIPTIONS · VIEWS |
  | `reg-event-fx`         | DISPATCH · HANDLER (fx) · APP-DB DIFF · FX (✓ + ✗ + header counters) |
  | `machine-driven`       | DISPATCH · HANDLER (machine: TRANSITION · GUARDS · LIFECYCLE · AFTER-TIMERS · DATA-REDUCTION · SNAPSHOT-DIFF) · APP-DB DIFF · FX |
  | `schema-violations`    | DISPATCH · HANDLER (db, with rolled-back app-db violation sub-block + downstream mute) · SCHEMA HOT-RELOAD tail (rf2-xgeag) |
  | `child-dispatches`     | DISPATCH · HANDLER (fx) · APP-DB DIFF · FX · CHILD DISPATCHES (resolved + not-in-buffer) |
  | `long-step`            | DISPATCH · HANDLER (fx, 42ms · long-step chrome) · APP-DB DIFF · FX (28ms long-step) · SUBSCRIPTIONS · VIEWS |
  | `flow-firing`          | DISPATCH · HANDLER (db) · APP-DB DIFF · FLOW · FX · SUBSCRIPTIONS · VIEWS |
  | `empty`                | empty-state — no epochs (`:no-focus`)              |
  | `no-events`            | cold pipeline — one epoch with empty `:trace-events` |
  | `unmounted-views`      | DISPATCH · HANDLER (db) · SUBSCRIPTIONS · VIEWS (re-renders + UNMOUNTED sub-section — rf2-gmw1i) |
  | `disposed-subs`        | DISPATCH · HANDLER (db) · SUBSCRIPTIONS (recompute + DISPOSED sub-section — rf2-wpfjo) |

  ## Why seed via `:rf.xray/sync-epoch-history`

  `:rf.xray/sync-epoch-history` is the canonical seed event used by
  the framework's epoch integration. The handler `assoc`s the history
  vector into Xray's frame app-db under `:epoch-history` — Story's
  `:rf.story/*` runtime slots survive untouched per
  `tools/story/spec/002-Runtime.md` §Coexistence with hosting
  application state.

  ## Focus resolution (head-fallback)

  No variant pins `:rf.xray/focus`. With no spine bus seeded the focus
  stays nil; the shared `panels.shared.focus-resolver`'s head-fallback
  (rf2-h0120) then resolves to `(peek epoch-history)` — i.e. the
  oldest-first vector's LAST element. Single-record fixtures place
  the under-test record alone; the multi-record
  `child-dispatches-history` puts the child epoch FIRST and the
  parent (focused) LAST."
  (:require [re-frame.story :as story]
            [panel-gallery.fixtures-epoch :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the Epoch panel Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/xray-epoch
    {:axis :feature
     :doc  "Xray Epoch panel — the focused-epoch numbered cascade
            (per tools/xray/spec/021-Dynamic-Panel-Designs.md §9.1;
            rf2-sc3r1 + #2191 + #2193)."})

  (story/reg-story :story.xray.epoch
    {:doc        "Visual gallery of the Xray Epoch panel — the
                 focused-epoch numbered cascade. Each variant seeds
                 its frame's :epoch-history via
                 :rf.xray/sync-epoch-history; the panel reads the
                 head record's :trace-events and renders the
                 sections whose driving traces surfaced."
     :component  :panel-gallery.epoch/Panel
     :tags       #{:dev :feature/xray-epoch}
     :substrates #{:reagent}})

  ;; ----- 1. vanilla reg-event-db cascade -----------------------------
  (story/reg-variant :story.xray.epoch/vanilla-db
    {:doc        "Vanilla `reg-event-db` cascade — counter-inc shape.
                 Exercises DISPATCH + COEFFECT + HANDLER (db-flavour
                 source) + APP-DB DIFF + SUBSCRIPTIONS + VIEWS."
     :events     [[:rf.xray/sync-epoch-history (fixtures/vanilla-db-history)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 2. reg-event-fx cascade -------------------------------------
  (story/reg-variant :story.xray.epoch/reg-event-fx
    {:doc        "`reg-event-fx` cascade — returns :db + :http/post +
                 a failing :metrics fx. Exercises the FX section's
                 per-fx outcome chrome (✓ / ✗) plus the rf2-uffov
                 header counters."
     :events     [[:rf.xray/sync-epoch-history (fixtures/reg-event-fx-history)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. machine-driven cascade -----------------------------------
  (story/reg-variant :story.xray.epoch/machine-driven
    {:doc        "Machine-handler cascade for a :ws/connection machine
                 (`:ws/open` transitions :connecting → :open).
                 Exercises the rich machine-handler section — TRANSITION,
                 GUARDS, LIFECYCLE (across :exit / :transition / :entry
                 / :always phases), AFTER-TIMERS, DATA-REDUCTION,
                 SNAPSHOT-DIFF (the rf2-9c27r full design)."
     :events     [[:rf.xray/sync-epoch-history (fixtures/machine-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4. schema-violation cascade ---------------------------------
  (story/reg-variant :story.xray.epoch/schema-violations
    {:doc        "Cascade where the app-db boundary check fired
                 (validation failure with rollback) AND a hot-reload
                 drift surfaced. Exercises the rf2-xgeag inline
                 violation sub-block: the :app-db violation attaches
                 to HANDLER with a pink sub-block + rolled-back banner,
                 and downstream steps mute. The hot-reload drift rides
                 on the standalone SCHEMA HOT-RELOAD tail step."
     :events     [[:rf.xray/sync-epoch-history (fixtures/schema-violations-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 5. child-dispatching cascade --------------------------------
  (story/reg-variant :story.xray.epoch/child-dispatches
    {:doc        "Parent cascade that returns `:dispatch` +
                 `:dispatch-n` + `:dispatch-later` fx. The
                 CHILD DISPATCHES section renders one resolved row
                 (`:cart/add` — child epoch present in buffer with
                 matching parent-dispatch-id) and three not-in-buffer
                 rows (muted marker) — rf2-yx1ae."
     :events     [[:rf.xray/sync-epoch-history (fixtures/child-dispatches-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 6. long-step cascade ----------------------------------------
  (story/reg-variant :story.xray.epoch/long-step
    {:doc        "Cascade with a 42ms handler + 28ms fx + 18ms view —
                 every duration over the 16ms `long-step-threshold-ms`.
                 Exercises the long-step warning chrome (`▲` glyph +
                 warning tone) on per-step duration chips (rf2-nqt3d
                 per-row portion; the top-of-pipeline summary chip
                 was retired by rf2-dwuq3)."
     :events     [[:rf.xray/sync-epoch-history (fixtures/long-step-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 7. flow-firing cascade --------------------------------------
  (story/reg-variant :story.xray.epoch/flow-firing
    {:doc        "Cascade triggering three downstream flows
                 (`:cart/total`, `:cart/item-count`, `:cart/badge`).
                 Exercises the FLOW step's per-row before/after
                 rendering."
     :events     [[:rf.xray/sync-epoch-history (fixtures/flows-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 8. empty (no epochs) ----------------------------------------
  (story/reg-variant :story.xray.epoch/empty
    {:doc        "No epochs in history — drives the panel's
                 `:no-focus` empty-state line ('No epoch focused.')."
     :events     [[:rf.xray/sync-epoch-history (fixtures/empty-history)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 9. cold pipeline (one epoch, no trace events) ---------------
  (story/reg-variant :story.xray.epoch/no-events
    {:doc        "One epoch whose `:trace-events` slice is empty —
                 the projection returns an empty step vector; the
                 panel renders its cold-pipeline empty-state without
                 crashing."
     :events     [[:rf.xray/sync-epoch-history (fixtures/no-events-history)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 10. unmounted-views cascade (rf2-gmw1i) ---------------------
  (story/reg-variant :story.xray.epoch/unmounted-views
    {:doc        "Route-change cascade where two view instances unmount
                 (modal + sidebar item) while a new view re-renders.
                 Exercises the VIEWS step's UNMOUNTED sub-section
                 (rf2-gmw1i) — header reads `N re-rendered; M unmounted`,
                 each unmounted-row paints the red ✗ teardown glyph."
     :events     [[:rf.xray/sync-epoch-history (fixtures/unmounted-views-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 11. disposed-subs cascade (rf2-wpfjo) -----------------------
  (story/reg-variant :story.xray.epoch/disposed-subs
    {:doc        "Route-change cascade where three sub-cache entries
                 evict (two `:no-more-derefers`, one `:hot-reload`)
                 while one sub recomputes. Exercises the SUBSCRIPTIONS
                 step's DISPOSED sub-section (rf2-wpfjo) — header reads
                 `N recomputed (...); L disposed`, each row paints the
                 red ✗ eviction glyph + a muted reason chip."
     :events     [[:rf.xray/sync-epoch-history (fixtures/disposed-subs-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.xray.epoch/all
    {:doc      "All eleven Epoch panel variants in one auto-grid.
                Scroll to see the cascade across vanilla-db /
                reg-event-fx / machine-driven / schema-violations /
                child-dispatches / long-step / flow-firing / empty /
                no-events / unmounted-views / disposed-subs."
     :layout   :variants-grid
     :story    :story.xray.epoch
     :columns  2
     :tags     #{:dev}}))

(register-all!)
