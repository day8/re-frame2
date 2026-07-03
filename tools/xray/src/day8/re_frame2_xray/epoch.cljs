(ns day8.re-frame2-xray.epoch
  "Cross-cutting epoch primitives — target frame, epoch history, the
  selected-epoch slot. Previously these registrations lived inside the
  Time Travel panel namespaces (`panels.time-travel-events` +
  `panels.time-travel-subs`). The Time Travel panel itself was deleted
  with rf2-qy0nu (unreachable in the 4-layer shell), but its epoch /
  target-frame plumbing is consumed by live panels:

  - `panels.app-db-diff-subs` reads `:rf.xray/epoch-history`,
    `:rf.xray/target-frame`, and pivots on the spine focus epoch via
    `:rf.xray/focus-epoch-id`.
  - `panels.machine-inspector` reads `:rf.xray/epoch-history`,
    `:rf.xray/target-frame`, `:rf.xray/target-frame-db`.
  - `panels.reactive-panel-subs` reads `:rf.xray/epoch-history` to
    project the focused event-bundle's `:trace-events` into the Reactive
    panel's sub-cascade + view-re-render rendering (rf2-wyvf2).
  - `core/target-frame` + `core/set-target-frame!` read / dispatch the
    target-frame slot and `:rf.xray/set-target-frame` event. (Pre
    rf2-kmhvg the reader was `core/active-frame`; the rename eliminated
    the `active` / `target` split.)
  - `preload/install-epoch-listener!` dispatches `:rf.xray/epoch-
    recorded` whenever the framework records a new epoch (any frame).
  - `mount.cljs` seeds `:rf.xray/sync-epoch-history` at first open.
  - `panels.app-db-diff-sections` dispatches `:rf.xray/select-epoch`
    when a section's epoch chip is clicked.
  - `shell/tab-bar` dispatches `:rf.xray/reset-to-epoch` (rf2-hga49) —
    the UI rewind affordance — and reads `:rf.xray/reset-flash` for the
    inline failure flash.

  Splitting the plumbing out makes the cross-cutting intent visible:
  the slot is `:rf/xray`-frame state shared across every panel that
  cares about time, not Time Travel's private surface."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.defaults :as defaults]))

(defn install!
  "Install the cross-cutting epoch subs + events."
  []

  ;; ---- subs --------------------------------------------------------------

  (rf/reg-sub :rf.xray/target-frame
    (fn [db _query]
      (get db :target-frame defaults/default-target-frame)))

  (rf/reg-sub :rf.xray/epoch-history
    (fn [db _query]
      (get db :epoch-history [])))

  ;; rf2-hga49 — transient `reset-to-event` failure flash. Holds a short
  ;; message string when a `rf/restore-epoch!` rewind fails (epoch aged
  ;; out of the buffer, or a restore-during-drain rejection). nil =
  ;; nothing to show. The flash is INLINE on the tab ribbon — never a
  ;; modal — and clears on the next reset attempt (`:rf.xray/reset-to-
  ;; epoch` dissocs the slot before re-running the restore, rf2-wa7tk).
  ;; A failure must never be a silent lie; it must also never block.
  (rf/reg-sub :rf.xray/reset-flash
    (fn [db _query]
      (get db :reset-flash)))

  ;; ---- effects -----------------------------------------------------------

  ;; rf2-hga49 — `restore-epoch` is a side-effecting framework call (it
  ;; rewinds an OBSERVED frame's `app-db` to a past epoch's `:db-after`),
  ;; so it lives in an fx, not a plain `reg-event` `:db` reducer. `rf/restore-epoch!`
  ;; returns `false` on any of the seven documented failure modes (per
  ;; Tool-Pair §Time-travel — Restore, including
  ;; `:rf.epoch/restore-non-ok-record`) leaving the frame unchanged; on
  ;; failure we dispatch the inline flash so the operator is told (never
  ;; a silent lie). The framework also emits a structured `:rf.epoch/*`
  ;; trace row on the bus — Xray's own Trace panel surfaces the specifics.
  ;; re-frame2 fx handlers are `(fn [ctx args] …)` — `args` is the value
  ;; from the `:fx` vector entry (here the `{:frame … :epoch-id …}` map).
  (rf/reg-fx :rf.xray.fx/restore-epoch
    (fn [_ctx {:keys [frame epoch-id]}]
      (when (and frame epoch-id)
        (let [ok? (rf/restore-epoch! frame epoch-id)]
          (when-not ok?
            (rf/dispatch [:rf.xray/reset-flash-failed]))))))

  ;; ---- events ------------------------------------------------------------

  ;; `:rf.xray/set-target-frame` — host-frame focus picker. Dispatched
  ;; by `core/set-target-frame!`. EP-0002 (rf2-bd4div) — writing nil resets
  ;; to UNSELECTED (dissocs `:target-frame`), NOT to a synthesised
  ;; `:rf/default`: the inspected target is never absence-repaired to the
  ;; ordinary `:rf/default` id (Spec 002 §Frame target resolution). A known
  ;; frame-id seeds `:epoch-history` from the framework's per-frame epoch
  ;; ring so the immediate subscribe-after-dispatch read sees a hydrated
  ;; slot; a nil resets the history to `[]` (`rf/epoch-history nil` → `[]`).
  ;;
  ;; rf2-ulpp8 — the reducer ALSO aligns `[:focus :frame]` to the same
  ;; target. The two axes encode the same gesture (the user is observing
  ;; this host frame); the picker-write path (`spine/set-frame-reducer`)
  ;; already aligns both axes per rf2-ug1r6 + rf2-thodq. Pre-fix, mount-
  ;; time and `core/set-target-frame!` callers wrote only `:target-frame`,
  ;; leaving `[:focus :frame]` nil — which made:
  ;;   - `filter-event-bundles-by-frame` a no-op (reads `:focus-slot :frame`),
  ;;     so the L2 list showed every frame's event-bundles even though the
  ;;     picker view collapsed the dropdown label to a specific frame;
  ;;   - `compose-focus`'s `slot-frame` filter inactive, so the head
  ;;     walk picked the global most-recent event-bundle — Issues / Views /
  ;;     App-DB Diff scoped to whichever frame's event was most recent,
  ;;     not the observed frame.
  ;; A nil `frame-id` (the reset case) symmetrically clears the focus
  ;; slot's `:frame` — leaving it set to a stale value would re-introduce
  ;; the misalignment in the inverse direction.
  (rf/reg-event :rf.xray/set-target-frame
    (fn [{:keys [db]} [_ frame-id]]
      {:db (let [target (or frame-id defaults/default-target-frame)]
        (cond-> (assoc db :epoch-history (vec (rf/epoch-history target)))
          (nil? frame-id)  (dissoc :target-frame)
          (nil? frame-id)  (update :focus (fnil dissoc {}) :frame)
          (some? frame-id) (assoc :target-frame frame-id)
          (some? frame-id) (assoc-in [:focus :frame] frame-id)))}))

  ;; `:rf.xray/epoch-recorded` — dispatched from `preload/install-
  ;; epoch-listener!` whenever the framework records a new epoch on any
  ;; frame. Re-reads the per-frame ring into `:epoch-history` so the
  ;; companion sub re-fires on the standard app-db-write reactive path.
  ;; `:rf.trace/no-emit? true` — the dispatch must not itself emit a
  ;; trace event (the listener is part of Xray's instrumentation loop;
  ;; a self-emit would re-enter the listener).
  (rf/reg-event :rf.xray/epoch-recorded
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ frame-id]]
      {:db (let [target (get db :target-frame defaults/default-target-frame)]
        (if (= frame-id target)
          (assoc db :epoch-history (vec (rf/epoch-history target)))
          db))}))

  ;; `:rf.xray/sync-epoch-history` — wholesale overwrite of the
  ;; `:epoch-history` slot. Dispatched from `mount.cljs/open!` on first
  ;; Ctrl+Shift+C to seed Xray's app-db with the framework's existing
  ;; per-frame ring contents. `:rf.trace/no-emit? true` matches the
  ;; `epoch-recorded` rationale above.
  ;;
  ;; rf2-mdpfz — the sync ALSO focuses the LATEST seeded epoch. The
  ;; sync seeds `:epoch-history` DIRECTLY, bypassing the normal trace-
  ;; driven path (`:rf.xray/epoch-recorded` → a fresh event-bundle →
  ;; `compose-focus` auto-following head), so nothing would otherwise
  ;; select an epoch: every focus-keyed Dynamic panel renders its
  ;; "nothing focused" state (App-db → "no user-domain keys yet";
  ;; the Epoch / Reactive / Machines panels → their no-focus lines).
  ;; The App-DB before-image specifically follows `[:focus :epoch-id]`
  ;; with NO head-fallback (`app_db_diff_subs/app-db-current+diff` is
  ;; deliberately fallback-free per rf2-yng0y), so seeding history is
  ;; not enough — focus MUST carry the epoch-id.
  ;;
  ;; We stamp the spine `[:focus :epoch-id]` (what `compose-focus`
  ;; surfaces as `:rf.xray/focus` `:epoch-id` when no event-bundle head is
  ;; present — i.e. history-only seeds), the single source of truth the
  ;; focus-keyed panels follow (rf2-uy7nz retired the `:selected-epoch-
  ;; id` mirror). The LATEST epoch is the HEAD of the oldest-first ring
  ;; — `(peek hist)` — matching the natural "show the most recent unless
  ;; the operator clicks an earlier row" debugging UX (the same head-bias
  ;; the focus-resolver's rf2-h0120 head-fallback encodes). An empty
  ;; history clears focus so a no-epoch seed renders its empty-state.
  ;;
  ;; When a live trace buffer IS also seeded (the chrome story seeds
  ;; both via `:rf.xray/sync-trace-buffer`), `compose-focus`'s LIVE
  ;; auto-follow re-derives `:epoch-id` from the head event-bundle — that
  ;; path is unchanged; this stamp is the authoritative selection only
  ;; for history-only seeds (the standalone panel-gallery stories).
  (rf/reg-event :rf.xray/sync-epoch-history
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ history]]
      {:db (let [history    (vec history)
            latest-id  (:epoch-id (peek history))]
        (cond-> (assoc db :epoch-history history)
          (some? latest-id) (assoc-in [:focus :epoch-id] latest-id)
          (nil? latest-id)  (update :focus (fnil dissoc {}) :epoch-id)))}))

  ;; `:rf.xray/select-epoch` — spine shim (rf2-adve5). Writes the
  ;; spine's `[:focus :epoch-id]` slot — the single source of truth that
  ;; the spec/018 `:rf.xray/focus` sub surfaces (and that App-DB Diff's
  ;; `selected-epoch-*` subs rebind on via `:rf.xray/focus-epoch-id`)
  ;; when the user picks an epoch (rf2-uy7nz retired the `:selected-
  ;; epoch-id` mirror). Symmetric with `:rf.xray/select-dispatch-id` (in
  ;; registry.cljs post rf2-5gl5r).
  (rf/reg-event :rf.xray/select-epoch
    (fn [{:keys [db]} [_ epoch-id]]
      {:db (assoc-in db [:focus :epoch-id] epoch-id)}))

  ;; `:rf.xray/reset-to-epoch` (rf2-hga49) — the UI rewind affordance.
  ;; The tab ribbon's `Reset` button dispatches this with the OBSERVED
  ;; frame (the frame Xray is inspecting — the frame-switcher selection,
  ;; NOT `:rf/xray` Xray's own chrome frame) and the currently-focused
  ;; epoch-id. The view supplies both from `:rf.xray/observed-frame` +
  ;; `:rf.xray/focus-epoch-id` so this event stays a thin trampoline into
  ;; the `:rf.xray.fx/restore-epoch` effect (which calls the framework's
  ;; `rf/restore-epoch!`, targeting the epoch's `:db-after` — "if the
  ;; event still exists, app state must be as if the event happened").
  ;; No dialog, no confirmation — the button just does it (programmers
  ;; are power users). A nil frame / epoch-id is a guarded no-op (the
  ;; button is disabled when no epoch is focused, but the event stays
  ;; defensive).
  ;;
  ;; rf2-wa7tk — clear any STALE failure flash on every fresh attempt.
  ;; The flash is set by `:rf.xray/reset-flash-failed` on a failed
  ;; restore but had no auto-dismiss and no success-path clear, so a
  ;; later SUCCESSFUL reset left the "Reset failed" message standing —
  ;; a silent lie (the sub still returned the stale string, the ribbon
  ;; kept rendering it). Dissoc-ing `:reset-flash` here in the `:db`
  ;; honours the documented "the next successful reset" clear contract
  ;; (`:rf.xray/clear-reset-flash` docstring): a fresh attempt wipes the
  ;; prior failure, and the fx re-sets the flash only when THIS attempt
  ;; also fails. The dissoc runs before the fx so a failure that fires
  ;; `:rf.xray/reset-flash-failed` synchronously still wins.
  (rf/reg-event :rf.xray/reset-to-epoch
    (fn [{:keys [db]} [_ frame epoch-id]]
      {:db (dissoc db :reset-flash)
       :fx [[:rf.xray.fx/restore-epoch {:frame frame :epoch-id epoch-id}]]}))

  ;; `:rf.xray/reset-flash-failed` (rf2-hga49) — set the inline failure
  ;; flash. Dispatched from `:rf.xray.fx/restore-epoch` when
  ;; `rf/restore-epoch!` returns false. `:rf.trace/no-emit? true` keeps
  ;; Xray's own chrome event off the trace bus it is inspecting.
  (rf/reg-event :rf.xray/reset-flash-failed
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} _event]
      {:db (assoc db :reset-flash "Reset failed — epoch unavailable (see Trace)")}))

  ;; `:rf.xray/clear-reset-flash` (rf2-hga49) — clear the inline flash.
  ;; The steady-state clear path is `:rf.xray/reset-to-epoch` dissoc-ing
  ;; the slot on every fresh attempt (rf2-wa7tk); this event remains the
  ;; explicit imperative clear (tests + any future dismiss affordance).
  ;; `:rf.trace/no-emit? true` per the sibling rationale.
  (rf/reg-event :rf.xray/clear-reset-flash
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} _event]
      {:db (dissoc db :reset-flash)}))

  nil)
