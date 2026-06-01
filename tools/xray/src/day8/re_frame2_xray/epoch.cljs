(ns day8.re-frame2-xray.epoch
  "Cross-cutting epoch primitives — target frame, epoch history, the
  selected-epoch slot. Previously these registrations lived inside the
  Time Travel panel namespaces (`panels.time-travel-events` +
  `panels.time-travel-subs`). The Time Travel panel itself was deleted
  with rf2-qy0nu (unreachable in the 4-layer shell), but its epoch /
  target-frame plumbing is consumed by live panels:

  - `panels.app-db-diff-subs` reads `:rf.xray/epoch-history`,
    `:rf.xray/selected-epoch-id`, `:rf.xray/target-frame`.
  - `panels.machine-inspector` reads `:rf.xray/epoch-history`,
    `:rf.xray/target-frame`, `:rf.xray/target-frame-db`.
  - `panels.reactive-panel-subs` reads `:rf.xray/epoch-history` to
    project the focused cascade's `:trace-events` into the Reactive
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

  (rf/reg-sub :rf.xray/selected-epoch-id
    (fn [db _query]
      (get db :selected-epoch-id)))

  ;; ---- events ------------------------------------------------------------

  ;; `:rf.xray/set-target-frame` — host-frame focus picker. Dispatched
  ;; by `core/set-target-frame!`. Writing nil resets to the default
  ;; (`:rf/default`); a known frame-id seeds `:epoch-history` from the
  ;; framework's per-frame epoch ring so the immediate subscribe-after-
  ;; dispatch read sees a hydrated slot.
  ;;
  ;; rf2-ulpp8 — the reducer ALSO aligns `[:focus :frame]` to the same
  ;; target. The two axes encode the same gesture (the user is observing
  ;; this host frame); the picker-write path (`spine/set-frame-reducer`)
  ;; already aligns both axes per rf2-ug1r6 + rf2-thodq. Pre-fix, mount-
  ;; time and `core/set-target-frame!` callers wrote only `:target-frame`,
  ;; leaving `[:focus :frame]` nil — which made:
  ;;   - `filter-cascades-by-frame` a no-op (reads `:focus-slot :frame`),
  ;;     so the L2 list showed every frame's cascades even though the
  ;;     picker view collapsed the dropdown label to a specific frame;
  ;;   - `compose-focus`'s `slot-frame` filter inactive, so the head
  ;;     walk picked the global most-recent cascade — Issues / Views /
  ;;     App-DB Diff scoped to whichever frame's event was most recent,
  ;;     not the observed frame.
  ;; A nil `frame-id` (the reset case) symmetrically clears the focus
  ;; slot's `:frame` — leaving it set to a stale value would re-introduce
  ;; the misalignment in the inverse direction.
  (rf/reg-event-db :rf.xray/set-target-frame
    (fn [db [_ frame-id]]
      (let [target (or frame-id defaults/default-target-frame)]
        (cond-> (assoc db :epoch-history (vec (rf/epoch-history target)))
          (nil? frame-id)  (dissoc :target-frame)
          (nil? frame-id)  (update :focus (fnil dissoc {}) :frame)
          (some? frame-id) (assoc :target-frame frame-id)
          (some? frame-id) (assoc-in [:focus :frame] frame-id)))))

  ;; `:rf.xray/epoch-recorded` — dispatched from `preload/install-
  ;; epoch-listener!` whenever the framework records a new epoch on any
  ;; frame. Re-reads the per-frame ring into `:epoch-history` so the
  ;; companion sub re-fires on the standard app-db-write reactive path.
  ;; `:rf.trace/no-emit? true` — the dispatch must not itself emit a
  ;; trace event (the listener is part of Xray's instrumentation loop;
  ;; a self-emit would re-enter the listener).
  (rf/reg-event-db :rf.xray/epoch-recorded
    {:rf.trace/no-emit? true}
    (fn [db [_ frame-id]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        (if (= frame-id target)
          (assoc db :epoch-history (vec (rf/epoch-history target)))
          db))))

  ;; `:rf.xray/sync-epoch-history` — wholesale overwrite of the
  ;; `:epoch-history` slot. Dispatched from `mount.cljs/open!` on first
  ;; Ctrl+Shift+C to seed Xray's app-db with the framework's existing
  ;; per-frame ring contents. `:rf.trace/no-emit? true` matches the
  ;; `epoch-recorded` rationale above.
  ;;
  ;; rf2-mdpfz — the sync ALSO focuses the LATEST seeded epoch. The
  ;; sync seeds `:epoch-history` DIRECTLY, bypassing the normal trace-
  ;; driven path (`:rf.xray/epoch-recorded` → a fresh cascade →
  ;; `compose-focus` auto-following head), so nothing would otherwise
  ;; select an epoch: every focus-keyed Dynamic panel renders its
  ;; "nothing focused" state (App-db → "no user-domain keys yet";
  ;; the Epoch / Reactive / Machines panels → their no-focus lines).
  ;; The App-DB before-image specifically follows `[:focus :epoch-id]`
  ;; with NO head-fallback (`app_db_diff_subs/app-db-current+diff` is
  ;; deliberately fallback-free per rf2-yng0y), so seeding history is
  ;; not enough — focus MUST carry the epoch-id.
  ;;
  ;; We stamp BOTH the spine `[:focus :epoch-id]` (what `compose-focus`
  ;; surfaces as `:rf.xray/focus` `:epoch-id` when no cascade head is
  ;; present — i.e. history-only seeds) AND the `:selected-epoch-id`
  ;; shim slot, mirroring `:rf.xray/select-epoch`'s dual-write. The
  ;; LATEST epoch is the HEAD of the oldest-first ring — `(peek hist)`
  ;; — matching the natural "show the most recent unless the operator
  ;; clicks an earlier row" debugging UX (the same head-bias the
  ;; focus-resolver's rf2-h0120 head-fallback encodes). An empty
  ;; history clears focus so a no-epoch seed renders its empty-state.
  ;;
  ;; When a live trace buffer IS also seeded (the chrome story seeds
  ;; both via `:rf.xray/sync-trace-buffer`), `compose-focus`'s LIVE
  ;; auto-follow re-derives `:epoch-id` from the head cascade — that
  ;; path is unchanged; this stamp is the authoritative selection only
  ;; for history-only seeds (the standalone panel-gallery stories).
  (rf/reg-event-db :rf.xray/sync-epoch-history
    {:rf.trace/no-emit? true}
    (fn [db [_ history]]
      (let [history    (vec history)
            latest-id  (:epoch-id (peek history))]
        (cond-> (assoc db :epoch-history history)
          (some? latest-id) (-> (assoc-in [:focus :epoch-id] latest-id)
                                (assoc :selected-epoch-id latest-id))
          (nil? latest-id)  (-> (update :focus (fnil dissoc {}) :epoch-id)
                                (dissoc :selected-epoch-id))))))

  ;; `:rf.xray/select-epoch` — spine shim (rf2-adve5). Owns the
  ;; `:selected-epoch-id` slot that App-DB Diff's `selected-epoch-*`
  ;; subs read, AND writes through the spine's `[:focus :epoch-id]`
  ;; slot so the `:rf.xray/focus` sub the spec/018 surfaces consume
  ;; rebinds when the user picks an epoch. Symmetric with
  ;; `:rf.xray/select-dispatch-id` (in registry.cljs post rf2-5gl5r).
  (rf/reg-event-db :rf.xray/select-epoch
    (fn [db [_ epoch-id]]
      (-> db
          (assoc :selected-epoch-id epoch-id)
          (assoc-in [:focus :epoch-id] epoch-id))))

  nil)
