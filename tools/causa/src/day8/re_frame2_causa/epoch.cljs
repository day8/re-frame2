(ns day8.re-frame2-causa.epoch
  "Cross-cutting epoch primitives — target frame, epoch history, the
  selected-epoch slot. Previously these registrations lived inside the
  Time Travel panel namespaces (`panels.time-travel-events` +
  `panels.time-travel-subs`). The Time Travel panel itself was deleted
  with rf2-qy0nu (unreachable in the 4-layer shell), but its epoch /
  target-frame plumbing is consumed by live panels:

  - `panels.app-db-diff-subs` reads `:rf.causa/epoch-history`,
    `:rf.causa/selected-epoch-id`, `:rf.causa/target-frame`.
  - `panels.machine-inspector` reads `:rf.causa/epoch-history`,
    `:rf.causa/target-frame`, `:rf.causa/target-frame-db`.
  - `panels.views-subs` + `panels.views-sub-diff-subs` read
    `:rf.causa/epoch-history`.
  - `core/active-frame` + `core/set-target-frame!` read / dispatch the
    target-frame slot and `:rf.causa/set-target-frame` event.
  - `preload/install-epoch-listener!` dispatches `:rf.causa/epoch-
    recorded` whenever the framework records a new epoch (any frame).
  - `mount.cljs` seeds `:rf.causa/sync-epoch-history` at first open.
  - `panels.app-db-diff-sections` dispatches `:rf.causa/select-epoch`
    when a section's epoch chip is clicked.

  Splitting the plumbing out makes the cross-cutting intent visible:
  the slot is `:rf/causa`-frame state shared across every panel that
  cares about time, not Time Travel's private surface."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.defaults :as defaults]))

(defn install!
  "Install the cross-cutting epoch subs + events."
  []

  ;; ---- subs --------------------------------------------------------------

  (rf/reg-sub :rf.causa/target-frame
    (fn [db _query]
      (get db :target-frame defaults/default-target-frame)))

  (rf/reg-sub :rf.causa/epoch-history
    (fn [db _query]
      (get db :epoch-history [])))

  (rf/reg-sub :rf.causa/selected-epoch-id
    (fn [db _query]
      (get db :selected-epoch-id)))

  ;; ---- events ------------------------------------------------------------

  ;; `:rf.causa/set-target-frame` — host-frame focus picker. Dispatched
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
  (rf/reg-event-db :rf.causa/set-target-frame
    (fn [db [_ frame-id]]
      (let [target (or frame-id defaults/default-target-frame)]
        (cond-> (assoc db :epoch-history (vec (rf/epoch-history target)))
          (nil? frame-id)  (dissoc :target-frame)
          (nil? frame-id)  (update :focus (fnil dissoc {}) :frame)
          (some? frame-id) (assoc :target-frame frame-id)
          (some? frame-id) (assoc-in [:focus :frame] frame-id)))))

  ;; `:rf.causa/epoch-recorded` — dispatched from `preload/install-
  ;; epoch-listener!` whenever the framework records a new epoch on any
  ;; frame. Re-reads the per-frame ring into `:epoch-history` so the
  ;; companion sub re-fires on the standard app-db-write reactive path.
  ;; `:rf.trace/no-emit? true` — the dispatch must not itself emit a
  ;; trace event (the listener is part of Causa's instrumentation loop;
  ;; a self-emit would re-enter the listener).
  (rf/reg-event-db :rf.causa/epoch-recorded
    {:rf.trace/no-emit? true}
    (fn [db [_ frame-id]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        (if (= frame-id target)
          (assoc db :epoch-history (vec (rf/epoch-history target)))
          db))))

  ;; `:rf.causa/sync-epoch-history` — wholesale overwrite of the
  ;; `:epoch-history` slot. Dispatched from `mount.cljs/open!` on first
  ;; Ctrl+Shift+C to seed Causa's app-db with the framework's existing
  ;; per-frame ring contents. `:rf.trace/no-emit? true` matches the
  ;; `epoch-recorded` rationale above.
  (rf/reg-event-db :rf.causa/sync-epoch-history
    {:rf.trace/no-emit? true}
    (fn [db [_ history]]
      (assoc db :epoch-history (vec history))))

  ;; `:rf.causa/select-epoch` — spine shim (rf2-adve5). Owns the
  ;; `:selected-epoch-id` slot that App-DB Diff's `selected-epoch-*`
  ;; subs read, AND writes through the spine's `[:focus :epoch-id]`
  ;; slot so the `:rf.causa/focus` sub the spec/018 surfaces consume
  ;; rebinds when the user picks an epoch. Symmetric with
  ;; `:rf.causa/select-dispatch-id` in event_detail.cljs.
  (rf/reg-event-db :rf.causa/select-epoch
    (fn [db [_ epoch-id]]
      (-> db
          (assoc :selected-epoch-id epoch-id)
          (assoc-in [:focus :epoch-id] epoch-id))))

  nil)
