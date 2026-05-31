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

  | Variant                  | Sections exercised                                 |
  |--------------------------|----------------------------------------------------|
  | `vanilla-db`             | DISPATCH · COEFFECT · HANDLER (db FULL+DIFF) · SIDE EFFECTS (`:db` ✓) · SUBSCRIPTIONS · VIEWS |
  | `side-effects`           | DISPATCH · HANDLER (fx) · SIDE EFFECTS — `:db` ✓ + `:fx` (✓ ran · ↺ overridden · · skipped) + other (· dropped) sub-steps, each with its per-effect tick + sub-step ✓/✗ rollup (rf2-kt6js) |
  | `machine-driven`         | DISPATCH · HANDLER (machine cascade: GUARDS · LIFECYCLE · TRANSITION · AFTER-TIMERS) · SIDE EFFECTS |
  | `db-schema-fail`         | DISPATCH · HANDLER (db) · SIDE EFFECTS (`:db` ✗ schema-fail rollback — the `:where :app-db` violation reason box rides the `:db` row, step + sub-step + tick paint ✗, `:outcome :error`, SUBSCRIPTIONS / VIEWS mute downstream — rf2-kt6js / rf2-8resu) |
  | `exception`              | DISPATCH · HANDLER (✗ — inline 'Exception Thrown' block: message + collapsible stack/ex-data, `— no :db (handler threw)` placeholder, NO 'Rolled back' chip, `:outcome :error` — rf2-ahhgn / rf2-wnvid) |
  | `fx-exception`           | DISPATCH · HANDLER (db) · SIDE EFFECTS (`:fx` row ✗ — inline 'Exception Thrown' card on the throwing `:email/send` row, `1 threw` header chip, committed `:db` NOT rolled back — rf2-ahhgn) |
  | `caused-by-subs`         | DISPATCH · HANDLER (db) · SUBSCRIPTIONS — `caused by <event-id>` cell (rf2-1cc03) + static `:input-signals` inputs column (layer-1 → `app-db`, derived → upstream sub-ids — rf2-87c8a) |
  | `handler-flow-db`        | DISPATCH · HANDLER (`:db` diff = handler-only, t1) · FLOW (`:db` diff = flow's t1→t2 reshape) — the two `:db` contributions as SEPARATE steps (rf2-4wywy / rf2-48oc4) |
  | `child-dispatches`       | DISPATCH · HANDLER (fx) · SIDE EFFECTS · CHILD DISPATCHES (resolved + not-in-buffer) |
  | `long-step`              | DISPATCH · HANDLER (fx, 42ms · long-step chrome) · SIDE EFFECTS (28ms long-step) · SUBSCRIPTIONS · VIEWS |
  | `flow-firing`            | DISPATCH · HANDLER (db) · FLOW · SIDE EFFECTS · SUBSCRIPTIONS · VIEWS |
  | `empty`                  | empty-state — no epochs (`:no-focus`)              |
  | `no-events`              | cold pipeline — one epoch with empty `:trace-events` |
  | `unmounted-views`        | DISPATCH · HANDLER (db) · SUBSCRIPTIONS · VIEWS (re-renders + UNMOUNTED sub-section — rf2-gmw1i) |
  | `disposed-subs`          | DISPATCH · HANDLER (db) · SUBSCRIPTIONS (recompute + DISPOSED sub-section — rf2-wpfjo) |
  | `after-timer-source`     | DISPATCH source-kind enrichment (rf2-5qp4g) — `from :after timer · 250ms on [:active :authenticating]` |
  | `machine-spawn-source`   | DISPATCH source-kind enrichment (rf2-5qp4g) — `from machine spawn · :checkout/worker` |
  | `fx-dispatch-source`     | DISPATCH source-kind enrichment (rf2-5qp4g) — `from fx :dispatch · parent epoch #20` (parent in buffer) |
  | `fx-dispatch-later-source` | DISPATCH source-kind enrichment (rf2-5qp4g) — `from fx :dispatch-later · 500ms · parent epoch #22` |
  | `fx-dispatch-orphan`     | DISPATCH source-kind enrichment (rf2-5qp4g) — orphan path: parent dispatch-id 99999 not in buffer (muted unresolved chip) |

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
                 Exercises DISPATCH + COEFFECT + HANDLER (db FULL+DIFF
                 sub-section) + SIDE EFFECTS (`:db` ✓ — a bare
                 reg-event-db that returns only `:db` now lights the
                 step's `:db` sub-step, rf2-kt6js) + SUBSCRIPTIONS +
                 VIEWS."
     :events     [[:rf.xray/sync-epoch-history (fixtures/vanilla-db-history)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 2. SIDE EFFECTS step (rf2-kt6js) ----------------------------
  (story/reg-variant :story.xray.epoch/side-effects
    {:doc        "SIDE EFFECTS step showcase (rf2-kt6js) — handler
                 returns `:db` + a three-entry `:fx` vector + a stray
                 top-level `:analytics` effect. Exercises all three
                 sub-steps in fixed order `:db → :fx → other`, each
                 with its per-effect tick: `:db` ✓ committed; `:fx`
                 ✓ ran (`:http/post`) · ↺ overridden (`:analytics/track`)
                 · · skipped-on-platform (`:clipboard/write`); other
                 `:analytics` · dropped (the runtime executes only
                 `{:db :fx}`). Each sub-step carries a ✓/✗ rollup in its
                 header off the shared rf2-ahhgn `:status` primitive."
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

  ;; ----- 4. SIDE EFFECTS `:db` schema-fail rollback (rf2-kt6js) ------
  (story/reg-variant :story.xray.epoch/db-schema-fail
    {:doc        "Cascade where the app-db boundary schema rejected the
                 handler's `:db` write and rolled the cascade back.
                 Exercises the SIDE EFFECTS step's `:db` ✗ schema-fail
                 state (rf2-kt6js / rf2-8resu): the `:where :app-db`
                 violation attaches to the `:db` row with its reason
                 box; the step header + the `:db` sub-step + the
                 per-effect tick all paint ✗; the epoch `:outcome`
                 flips `:error`; SUBSCRIPTIONS / VIEWS mute downstream.
                 Hot-reload drift is an Issues-panel concern (rf2-7gf7v)
                 — no cascade step surfaces it."
     :events     [[:rf.xray/sync-epoch-history (fixtures/schema-violations-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4a. handler-threw EXCEPTION (rf2-ahhgn · rf2-wnvid) ---------
  (story/reg-variant :story.xray.epoch/exception
    {:doc        "Cascade where the event handler threw before returning
                 (`:rf.error/handler-exception`). Exercises the inline
                 'Exception Thrown' block (rf2-ahhgn / rf2-wnvid): the
                 HANDLER step paints ✗ + carries the red error card
                 (message + a collapsible `<details>` disclosing the
                 raw exception's stack + ex-data); the HANDLER `:db`
                 reads `— no :db (handler threw)` (NO phantom app-db);
                 the `Rolled back` chip stays OFF (the handler threw
                 before any commit — NO spurious rollback); the epoch
                 `:outcome` flips `:error`."
     :events     [[:rf.xray/sync-epoch-history (fixtures/exception-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4b. fx-handler-threw EXCEPTION (rf2-ahhgn) -----------------
  (story/reg-variant :story.xray.epoch/fx-exception
    {:doc        "`reg-event-fx` cascade whose `:db` committed cleanly
                 but a post-commit `:fx` handler (`:email/send`) threw
                 (`:rf.error/fx-handler-exception`). Exercises the SIDE
                 EFFECTS step's per-`:fx`-row 'Exception Thrown' card
                 (`attach-to-fx-error-row` matches the throwing row by
                 `:fx-id`), the row's ✗ tick, the `1 threw` header chip,
                 and the contract that the committed `:db` is NOT rolled
                 back on a post-commit fx throw (rf2-wnvid)."
     :events     [[:rf.xray/sync-epoch-history (fixtures/fx-exception-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4c. subscriptions caused-by + input-signals ----------------
  ;;          (rf2-1cc03 · rf2-87c8a)
  (story/reg-variant :story.xray.epoch/caused-by-subs
    {:doc        "Cascade where `:cart/add` invalidates the layer-1
                 `:cart/items` sub which cascades to two derived subs.
                 Exercises the SUBSCRIPTIONS table's `caused by
                 <event-id>` cell (rf2-1cc03 — `caused by :cart/add`
                 below each sub-id) + the static `:input-signals`
                 inputs column (rf2-87c8a — the layer-1 root reads
                 `app-db`, the derived subs name their upstream input
                 sub-id)."
     :events     [[:rf.xray/sync-epoch-history (fixtures/caused-by-subs-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 4d. handler-`:db` vs flow-`:db`-diff (rf2-4wywy · rf2-48oc4)
  (story/reg-variant :story.xray.epoch/handler-flow-db
    {:doc        "Cascade where the handler writes `[:cart :items]` and a
                 downstream `:cart/total` flow then writes
                 `[:cart :total]`. Exercises the rf2-4wywy / rf2-48oc4
                 separation: the HANDLER step's `:db` diff shows ONLY
                 the handler's own change (post-handler / pre-flow ==
                 t1 — `[:cart :items]`), while the FLOW step shows the
                 flow's OWN `:db` diff (the t1→t2 reshape —
                 `[:cart :total] 120 → 195`) as a SEPARATE numbered
                 step. The two `:db` contributions are no longer
                 conflated."
     :events     [[:rf.xray/sync-epoch-history (fixtures/handler-flow-db-history)]]
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

  ;; ----- 12. :after-timer source enrichment (rf2-5qp4g) ----------------
  (story/reg-variant :story.xray.epoch/after-timer-source
    {:doc        "Cascade dispatched by a machine `:after` timer firing
                 (rf2-ejtpd + rf2-5qp4g). The DISPATCH step renders
                 `from :after timer · 250ms on [:active :authenticating]` —
                 the kind label, the delay-ms chip, and the
                 source-state-path as a click-to-source affordance."
     :events     [[:rf.xray/sync-epoch-history (fixtures/after-timer-source-history)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 13. :machine-spawn source enrichment (rf2-5qp4g) --------------
  (story/reg-variant :story.xray.epoch/machine-spawn-source
    {:doc        "Cascade dispatched by a spawn fx (rf2-ejtpd +
                 rf2-5qp4g). The DISPATCH step renders
                 `from machine spawn · :checkout/worker` — the kind label
                 + the spawned actor-id."
     :events     [[:rf.xray/sync-epoch-history (fixtures/machine-spawn-source-history)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  ;; ----- 14. :fx-dispatch source enrichment (rf2-5qp4g) ----------------
  (story/reg-variant :story.xray.epoch/fx-dispatch-source
    {:doc        "Multi-record history: a parent cascade emits a
                 `:dispatch` fx; the child cascade is the head record
                 (rf2-ejtpd + rf2-5qp4g). The DISPATCH step renders
                 `from fx :dispatch · parent epoch #20` — the kind
                 label + a click-to-navigate parent-epoch chip resolved
                 against the in-buffer parent."
     :events     [[:rf.xray/sync-epoch-history (fixtures/fx-dispatch-source-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 15. :fx-dispatch-later source enrichment (rf2-5qp4g) ----------
  (story/reg-variant :story.xray.epoch/fx-dispatch-later-source
    {:doc        "Multi-record history: a parent cascade emits a
                 `:dispatch-later` fx (500ms); the timer-fired child
                 cascade is the head record (rf2-ejtpd + rf2-5qp4g).
                 The DISPATCH step renders
                 `from fx :dispatch-later · 500ms · parent epoch #22` —
                 the kind label, the original scheduled delay, and the
                 parent-epoch navigation link."
     :events     [[:rf.xray/sync-epoch-history (fixtures/fx-dispatch-later-source-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- 16. :fx-dispatch orphan (parent aged out) (rf2-5qp4g) ---------
  (story/reg-variant :story.xray.epoch/fx-dispatch-orphan
    {:doc        "Defensive `:fx-dispatch` variant: the child cascade's
                 parent-dispatch-id has no matching epoch in the buffer
                 (the parent aged out of the ring). The DISPATCH step
                 renders the kind label + the unresolved parent chip
                 (`parent dispatch #99999 (not in buffer)`) — muted
                 plain span, no dead click affordance."
     :events     [[:rf.xray/sync-epoch-history (fixtures/fx-dispatch-orphaned-source-history)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- workspace ---------------------------------------------------
  (story/reg-workspace :Workspace.xray.epoch/all
    {:doc      "All twenty Epoch panel variants in one auto-grid. Scroll
                to see the cascade across vanilla-db / side-effects /
                machine-driven / db-schema-fail / exception /
                fx-exception / caused-by-subs / handler-flow-db /
                child-dispatches / long-step / flow-firing / empty /
                no-events / unmounted-views / disposed-subs and the
                rf2-5qp4g per-source-kind enrichment variants
                (after-timer / machine-spawn / fx-dispatch /
                fx-dispatch-later / fx-dispatch-orphan). The
                rf2-rmg2k refresh surfaces the SIDE EFFECTS step
                (`:db`/`:fx`/other sub-steps + per-effect ticks +
                `:db` schema-fail), the inline 'Exception Thrown'
                block (handler + fx throws), the subscriptions
                `caused by <event-id>` cell + static `:input-signals`
                inputs column, and the handler-`:db` vs flow-`:db`-diff
                split."
     :layout   :variants-grid
     :story    :story.xray.epoch
     :columns  2
     :tags     #{:dev}}))

(register-all!)
