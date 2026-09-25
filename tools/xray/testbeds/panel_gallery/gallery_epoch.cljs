(ns panel-gallery.gallery-epoch
  "Story coverage for the **Epoch panel** of the Xray chrome — the
  numbered cascade panel.

  The Epoch panel body is `panels.epoch.view/Panel`: the focused
  epoch's full computational timeline rendered as a numbered vertical
  cascade. Each step is conditional — present only when its driving
  trace events surfaced — so the gallery's variants are organised as a
  feature-coverage TABLE, one variant per section the projection
  lights up.

  ## Section-coverage table

  | Variant                  | Sections exercised                                 |
  |--------------------------|----------------------------------------------------|
  | `vanilla-db`             | DISPATCH · COEFFECT · HANDLER (db FULL+DIFF) · SIDE EFFECTS (flat ledger: `:db` ✓ → app-db) · SUBSCRIPTIONS · VIEWS |
  | `side-effects`           | DISPATCH · HANDLER (fx) · SIDE EFFECTS — FLAT per-effect ledger: one row per effect in execution order — `:db` ✓ → app-db, then the `:fx` rows (✓ ran · ↺ overridden · – skipped-on-platform). Single badge ✓/✗ (AND-of-rows; skipped neutral) |
  | `machine-driven`         | DISPATCH · HANDLER (machine cascade: a SINGLE row stream in canonical phase order — guard · action(phase) · transition · timer) · SIDE EFFECTS |
  | `db-schema-fail`         | DISPATCH · HANDLER (db) · SIDE EFFECTS (flat ledger: `:db` ✗ schema-fail rollback ONLY — the `:where :app-db` violation reason box rides the `:db` row, badge + row paint ✗, no fx rows ran, `:outcome :error`, SUBSCRIPTIONS / VIEWS mute downstream) |
  | `exception`              | DISPATCH · HANDLER (✗ — inline 'Exception Thrown' block UNDER the HANDLER step: message + collapsible stack/ex-data, `— no :db (handler threw)` placeholder, NO 'Rolled back' chip, `:outcome :error`) |
  | `fx-exception`           | DISPATCH · HANDLER (db) · SIDE EFFECTS (flat ledger: `:db` ✓ then the `:fx` rows; the throwing `:email/send` row ✗ with its inline 'Exception Thrown' card; badge ✗; committed `:db` NOT rolled back) |
  | `interceptor-exception`  | DISPATCH · HANDLER (db — ran; `:after` throw doesn't skip it) · INTERCEPTOR (✗ — conditional step after HANDLER, `:audit/trail` threw `:after`; inline 'Exception Thrown' card UNDER the INTERCEPTOR step) · `:outcome :error` |
  | `coeffect-throw-skipped` | DISPATCH · COEFFECT (✗ — `:session` injector threw on the way IN; inline 'Exception Thrown' card UNDER the COEFFECT step) · HANDLER (⊘ SKIPPED — handler never ran, `mark-skipped-handler`) · `:outcome :error` |
  | `caused-by-subs`         | DISPATCH · HANDLER (db) · SUBSCRIPTIONS — `caused by <event-id>` cell + static `:input-signals` inputs column (layer-1 → `app-db`, derived → upstream sub-ids) |
  | `handler-flow-db`        | DISPATCH · HANDLER (`:db` diff = handler-only, t1) · FLOW (`:db` diff = flow's t1→t2 reshape) — the two `:db` contributions as SEPARATE steps |
  | `child-dispatches`       | DISPATCH · HANDLER (fx) · SIDE EFFECTS (the dispatch-family fx rows) |
  | `long-step`              | DISPATCH · HANDLER (fx, 42ms · long-step chrome) · SIDE EFFECTS (28ms long-step) · SUBSCRIPTIONS · VIEWS |
  | `flow-firing`            | DISPATCH · HANDLER (db) · FLOW · SIDE EFFECTS · SUBSCRIPTIONS · VIEWS |
  | `empty`                  | empty-state — no epochs (`:no-focus`)              |
  | `no-events`              | cold pipeline — one epoch with empty `:trace-events` |
  | `unmounted-views`        | DISPATCH · HANDLER (db) · SUBSCRIPTIONS · VIEWS (re-renders + UNMOUNTED sub-section) |
  | `disposed-subs`          | DISPATCH · HANDLER (db) · SUBSCRIPTIONS (recompute + DISPOSED sub-section) |
  | `after-timer-source`     | DISPATCH source-kind enrichment — `from :after timer · 250ms on [:active :authenticating]` |
  | `machine-spawn-source`   | DISPATCH source-kind enrichment — `from machine spawn · :checkout/worker` |
  | `fx-dispatch-source`     | DISPATCH source-kind enrichment — `from fx :dispatch · parent epoch #20` (parent in buffer) |
  | `fx-dispatch-later-source` | DISPATCH source-kind enrichment — `from fx :dispatch-later · 500ms · parent epoch #22` |
  | `fx-dispatch-orphan`     | DISPATCH source-kind enrichment — orphan path: parent dispatch-id 99999 not in buffer (muted unresolved chip) |

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
  then resolves to `(peek epoch-history)` — i.e. the
  oldest-first vector's LAST element. Single-record fixtures place
  the under-test record alone; the multi-record
  `child-dispatches-history` puts the child epoch FIRST and the
  parent (focused) LAST."
  (:require [re-frame.story :as rf.story]
            [panel-gallery.fixtures-epoch :as fixtures]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

(defn register-all!
  "Register the Epoch panel Story surface. Idempotent under
  `install-canonical-vocabulary!` resets so the namespace is
  reloadable."
  []
  (rf.story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (rf.story/reg-tag :feature/xray-epoch
    {:axis :feature
     :doc  "Xray Epoch panel — the focused-epoch numbered cascade
            (per tools/xray/spec/021-Dynamic-Panel-Designs.md §9.1)."})

  (rf.story/reg-story :story.xray.epoch
    {:doc        "Visual gallery of the Xray Epoch panel — the
                 focused-epoch numbered cascade. Each variant seeds
                 its frame's :epoch-history via
                 :rf.xray/sync-epoch-history; the panel reads the
                 head record's :trace-events and renders the
                 sections whose driving traces surfaced."
     :component  :panel-gallery.epoch/Panel
     :tags       #{:dev :feature/xray-epoch}
     :substrates #{:reagent}})

  ;; ----- 1. vanilla db-only cascade ----------------------------------
  (rf.story/reg-variant :story.xray.epoch/vanilla-db
    {:doc        "Vanilla `:db-only` cascade — counter-inc shape.
                 Exercises DISPATCH + COEFFECT + HANDLER (db FULL+DIFF
                 sub-section) + SIDE EFFECTS (flat ledger with a single
                 `:db` ✓ → app-db row — a bare db-only handler that returns
                 only `:db` lights the step)
                 + SUBSCRIPTIONS + VIEWS."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/vanilla-db-history)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 2. SIDE EFFECTS flat ledger ---------------------------------
  (rf.story/reg-variant :story.xray.epoch/side-effects
    {:doc        "SIDE EFFECTS flat per-effect ledger — handler
                 returns `:db` + a three-entry `:fx` vector. Exercises
                 the flat ledger:
                 ONE row per effect in execution order, NO group headers —
                 `:db` ✓ → app-db (the clickable destination marker, not
                 the diff), then `:http/post` ✓ ran · `:analytics/track`
                 ↺ overridden · `:clipboard/write` – skipped-on-platform.
                 After the badge: ONE overall ✓/✗ glyph
                 (AND-of-rows; the skipped row is NEUTRAL, so
                 this all-actioned ledger reads ✓). No post-commit labels."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/effectful-history)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 3. machine-driven cascade -----------------------------------
  (rf.story/reg-variant :story.xray.epoch/machine-driven
    {:doc        "Machine-handler cascade for a :ws/connection machine
                 (`:ws/open` transitions :connecting → :open).
                 Exercises the rich machine-handler section as a SINGLE
                 CASCADE — one row per substrate emit, re-sorted into
                 canonical phase order: guard (pass/fail) · action (across
                 :exit / :transition / :entry / :always / :after-action
                 phases, with per-action data + fx attribution) ·
                 transition · timer-cancel — rather than a per-category
                 roll-up."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/machine-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4. SIDE EFFECTS `:db` schema-fail rollback ------------------
  (rf.story/reg-variant :story.xray.epoch/db-schema-fail
    {:doc        "Cascade where the app-db boundary schema rejected the
                 handler's `:db` write and rolled the cascade back.
                 Exercises the flat ledger's `:db` ✗ schema-fail state
                 under ATOMICITY: the pre-commit
                 rollback fires BEFORE any `:fx`, so the ledger carries
                 just the `:db` CROSS row — the `:where :app-db` violation
                 attaches to it with its reason box; the single badge +
                 the `:db` row both paint ✗; the epoch `:outcome` flips
                 `:error`; SUBSCRIPTIONS / VIEWS mute downstream.
                 Hot-reload drift surfaces on the issues ribbon (Issues
                 is not a tab) — no cascade step surfaces it."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/schema-violations-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4a. handler-threw EXCEPTION ---------------------------------
  (rf.story/reg-variant :story.xray.epoch/exception
    {:doc        "Cascade where the event handler threw before returning
                 (`:rf.error/handler-exception`). Exercises the inline
                 'Exception Thrown' block: the
                 HANDLER step paints ✗ + carries the red error card
                 (message + a collapsible `<details>` disclosing the
                 raw exception's stack + ex-data); the HANDLER `:db`
                 reads `— no :db (handler threw)` (NO phantom app-db);
                 the `Rolled back` chip stays OFF (the handler threw
                 before any commit — NO spurious rollback); the epoch
                 `:outcome` flips `:error`."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/exception-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4b. fx-handler-threw EXCEPTION ------------------------------
  (rf.story/reg-variant :story.xray.epoch/fx-exception
    {:doc        "`:effectful` cascade whose `:db` committed cleanly
                 but a post-commit `:fx` handler (`:email/send`) threw
                 (`:rf.error/fx-handler-exception`). Exercises the flat
                 ledger's per-row exception: `:db` ✓ leads, then the `:fx`
                 rows, with the throwing `:email/send` row ✗ carrying its
                 inline 'Exception Thrown' card (`attach-to-fx-error-row`
                 matches the row by `:fx-id`); the single badge reads ✗;
                 the committed `:db` is NOT rolled back on a post-commit
                 fx throw."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/fx-exception-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4d. INTERCEPTOR step — :after throw ------------------------
  (rf.story/reg-variant :story.xray.epoch/interceptor-exception
    {:doc        "Cascade where a user interceptor (`:audit/trail`) threw
                 in its `:after` phase (`:rf.error/interceptor-exception`)
                 AFTER the handler ran cleanly. Exercises the
                 conditional INTERCEPTOR step: the step renders
                 after HANDLER (an `:after` phase unwinds on the way out),
                 paints ✗, and carries the
                 interceptor id + `:after` phase + the inline 'Exception
                 Thrown' card placed UNDER the INTERCEPTOR step (per-step
                 placement, rather than collapsing onto HANDLER).
                 The HANDLER step is NOT skipped — an `:after` throw runs
                 the handler first — so its `:db` write still renders; the
                 epoch `:outcome` flips `:error`."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/interceptor-after-throw-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4e. upstream :before throw — HANDLER SKIPPED ----------------
  (rf.story/reg-variant :story.xray.epoch/coeffect-throw-skipped
    {:doc        "Cascade where a coeffect injector (`:session`) threw on
                 the way IN (`:rf.error/coeffect-exception`), so the event
                 handler never ran. Exercises the skip path: the
                 throwing cofx's (synthesised placeholder) COEFFECT step
                 paints ✗ with the inline 'Exception Thrown' card UNDER it,
                 and the downstream HANDLER step renders as SKIPPED (⊘
                 muted) — NOT 'ran, returned no :db' (the handler body
                 never executed). The epoch `:outcome` flips `:error`."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/coeffect-throw-skipped-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4f. subscriptions caused-by + input-signals ----------------
  (rf.story/reg-variant :story.xray.epoch/caused-by-subs
    {:doc        "Cascade where `:cart/add` invalidates the layer-1
                 `:cart/items` sub which cascades to two derived subs.
                 Exercises the SUBSCRIPTIONS table's `caused by
                 <event-id>` cell (`caused by :cart/add`
                 below each sub-id) + the static `:input-signals`
                 inputs column (the layer-1 root reads
                 `app-db`, the derived subs name their upstream input
                 sub-id)."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/caused-by-subs-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4d. handler-`:db` vs flow-`:db`-diff -----------------------
  (rf.story/reg-variant :story.xray.epoch/handler-flow-db
    {:doc        "Cascade where the handler writes `[:cart :items]` and a
                 downstream `:cart/total` flow then writes
                 `[:cart :total]`. Exercises the
                 separation: the HANDLER step's `:db` diff shows ONLY
                 the handler's own change (post-handler / pre-flow ==
                 t1 — `[:cart :items]`), while the FLOW step shows the
                 flow's OWN `:db` diff (the t1→t2 reshape —
                 `[:cart :total] 120 → 195`) as a SEPARATE numbered
                 step. The two `:db` contributions stay separate."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/handler-flow-db-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 5. child-dispatching cascade --------------------------------
  (rf.story/reg-variant :story.xray.epoch/child-dispatches
    {:doc        "Parent cascade that returns `:dispatch` +
                 `:dispatch-n` + `:dispatch-later` fx. The SIDE
                 EFFECTS step surfaces the dispatch-family fx rows;
                 there is no separate CHILD DISPATCHES step."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/child-dispatches-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 6. long-step cascade ----------------------------------------
  (rf.story/reg-variant :story.xray.epoch/long-step
    {:doc        "Cascade with a 42ms handler + 28ms fx + 18ms view —
                 every duration over the 16ms `long-step-threshold-ms`.
                 Exercises the long-step warning chrome (`▲` glyph +
                 warning tone) on per-step duration chips."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/long-step-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 7. flow-firing cascade --------------------------------------
  (rf.story/reg-variant :story.xray.epoch/flow-firing
    {:doc        "Cascade triggering three downstream flows
                 (`:cart/total`, `:cart/item-count`, `:cart/badge`).
                 Exercises the FLOW step's per-row before/after
                 rendering."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/flows-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 8. empty (no epochs) ----------------------------------------
  (rf.story/reg-variant :story.xray.epoch/empty
    {:doc        "No epochs in history — drives the panel's
                 `:no-focus` empty-state line ('No epoch focused.')."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/empty-history)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 9. cold pipeline (one epoch, no trace events) ---------------
  (rf.story/reg-variant :story.xray.epoch/no-events
    {:doc        "One epoch whose `:trace-events` slice is empty —
                 the projection returns an empty step vector; the
                 panel renders its cold-pipeline empty-state without
                 crashing."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/no-events-history)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  ;; ----- 10. unmounted-views cascade ---------------------------------
  (rf.story/reg-variant :story.xray.epoch/unmounted-views
    {:doc        "Route-change cascade where two view instances unmount
                 (modal + sidebar item) while a new view re-renders.
                 Exercises the VIEWS step's UNMOUNTED sub-section
                 — header reads `N re-rendered; M unmounted`,
                 each unmounted-row paints the red ✗ teardown glyph."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/unmounted-views-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 11. disposed-subs cascade -----------------------------------
  (rf.story/reg-variant :story.xray.epoch/disposed-subs
    {:doc        "Route-change cascade where three sub-cache entries
                 evict (two `:no-more-derefers`, one `:hot-reload`)
                 while one sub recomputes. Exercises the SUBSCRIPTIONS
                 step's DISPOSED sub-section — header reads
                 `N recomputed (...); L disposed`, each row paints the
                 red ✗ eviction glyph + a muted reason chip."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/disposed-subs-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 12. :after-timer source enrichment ----------------------------
  (rf.story/reg-variant :story.xray.epoch/after-timer-source
    {:doc        "Cascade dispatched by a machine `:after` timer firing.
                 The DISPATCH step renders
                 `from :after timer · 250ms on [:active :authenticating]` —
                 the kind label, the delay-ms chip, and the
                 source-state-path as a click-to-source affordance."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/after-timer-source-history)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 13. :machine-spawn source enrichment --------------------------
  (rf.story/reg-variant :story.xray.epoch/machine-spawn-source
    {:doc        "Cascade dispatched by a spawn fx. The DISPATCH step
                 renders
                 `from machine spawn · :checkout/worker` — the kind label
                 + the spawned actor-id."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/machine-spawn-source-history)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 14. :fx-dispatch source enrichment ----------------------------
  (rf.story/reg-variant :story.xray.epoch/fx-dispatch-source
    {:doc        "Multi-record history: a parent cascade emits a
                 `:dispatch` fx; the child cascade is the head record.
                 The DISPATCH step renders
                 `from fx :dispatch · parent epoch #20` — the kind
                 label + a click-to-navigate parent-epoch chip resolved
                 against the in-buffer parent."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/fx-dispatch-source-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 15. :fx-dispatch-later source enrichment ----------------------
  (rf.story/reg-variant :story.xray.epoch/fx-dispatch-later-source
    {:doc        "Multi-record history: a parent cascade emits a
                 `:dispatch-later` fx (500ms); the timer-fired child
                 cascade is the head record.
                 The DISPATCH step renders
                 `from fx :dispatch-later · 500ms · parent epoch #22` —
                 the kind label, the original scheduled delay, and the
                 parent-epoch navigation link."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/fx-dispatch-later-source-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 16. :fx-dispatch orphan (parent aged out) ---------------------
  (rf.story/reg-variant :story.xray.epoch/fx-dispatch-orphan
    {:doc        "Defensive `:fx-dispatch` variant: the child cascade's
                 parent-dispatch-id has no matching epoch in the buffer
                 (the parent aged out of the ring). The DISPATCH step
                 renders the kind label + the unresolved parent chip
                 (`parent dispatch #99999 (not in buffer)`) — muted
                 plain span, no dead click affordance."
     :setup     [[:rf.xray/sync-epoch-history (fixtures/fx-dispatch-orphaned-source-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (rf.story/reg-workspace :Workspace.xray.epoch/all
    {:doc      "All twenty-two Epoch panel variants in one auto-grid.
                Scroll to see the cascade across vanilla-db /
                side-effects / machine-driven / db-schema-fail /
                exception / fx-exception / interceptor-exception /
                coeffect-throw-skipped / caused-by-subs / handler-flow-db
                / child-dispatches / long-step / flow-firing / empty /
                no-events / unmounted-views / disposed-subs and the
                per-source-kind enrichment variants
                (after-timer / machine-spawn / fx-dispatch /
                fx-dispatch-later / fx-dispatch-orphan). Exceptions
                surface UNDER the step where they
                occurred (the conditional INTERCEPTOR step + the
                per-step ✓/✗/⊘ status), an upstream `:before`-chain
                throw marking the HANDLER SKIPPED (⊘). The SIDE
                EFFECTS step is a FLAT
                per-effect ledger (one row per effect in execution order
                + a single ✓/✗ badge + the `:db` → app-db marker + the
                `:db` schema-fail rollback). Plus the subscriptions
                `caused by <event-id>` cell + static `:input-signals`
                inputs column, and the handler-`:db` vs flow-`:db`-diff
                split."
     :layout   :variants-grid
     :for      :story.xray.epoch
     :columns  2
     :tags     #{:dev}}))

(register-all!)
