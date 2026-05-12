(ns day8.re-frame2-causa.registry
  "Causa's framework registrations — events, subs, fxs under the
  `:rf.causa/*` namespace prefix.

  ## Why the namespace prefix matters (rf2-tijr Option C)

  Per rf2-tijr the registrar is process-global; Causa's registrations
  share the registry with the host app. The `:rf.causa/*` prefix is the
  collision-avoidance contract: Causa never registers under a
  non-`:rf.causa/*` keyword, so a host registering `:user/login` and
  Causa registering `:rf.causa/buffer-cleared` cannot stamp on each
  other.

  ## Why the registrations target the `:rf/causa` frame

  Per rf2-tijr Option C the panel's state lives in a frame named
  `:rf/causa` — a sibling of the host's `:rf/default`. Subscribers /
  dispatchers wrapped inside `[rf/frame-provider {:frame :rf/causa}
  ...]` resolve to that frame; a Causa view subscribing to
  `:rf.causa/trace-buffer` reads `:rf/causa`'s app-db, not the host's.

  Even though the registrar is process-global, each registered handler
  operates *against the active frame's db* — so the registry namespace
  prefix and the frame isolation work together: prefix prevents id
  collision, frame-provider prevents db reads/writes from leaking into
  the host.

  ## Phase 2 scope (rf2-op3bz)

  Phase 1 (rf2-n6x4q) shipped only `:rf.causa/trace-buffer`. Phase 2
  adds the event-detail panel's wiring:

    - `:rf.causa/selected-panel`           sub — current panel-id
    - `:rf.causa/select-panel`             event-db — set current panel
    - `:rf.causa/selected-dispatch-id`     sub — focused cascade
    - `:rf.causa/event-detail`             sub — projected cascade
    - `:rf.causa/select-dispatch-id`       event-db — set focused
    - `:rf.causa/clear-selected-dispatch-id` event-db — clear focus

  ## Phase 3 scope (rf2-t53ze) — Time Travel panel

  Adds the scrubber's wiring against the framework's epoch-history
  surface. The panel's :selected-epoch-id holds the *view* selection
  (per spec §The passive-scrubbing rule — scrubbing rebases panels;
  rewind is opt-in). :pinned-snapshots is the per-frame pin store
  (per spec §Pinned snapshots, Lock 4 session-scoped).

    - `:rf.causa/epoch-history`            sub — :rf.causa/target-frame's history
    - `:rf.causa/selected-epoch-id`        sub — view's selected epoch
    - `:rf.causa/pinned-snapshots`         sub — vector of chip states
    - `:rf.causa/time-travel`              sub — composite for the panel
    - `:rf.causa/select-epoch`             event-db — set view selection (passive)
    - `:rf.causa/clear-selected-epoch`     event-db — drop view selection
    - `:rf.causa/pin-current`              event — eager-copy a pin
    - `:rf.causa/unpin`                    event-db — drop a pin
    - `:rf.causa/rename-pin`               event-db — rewrite a pin's :label
    - `:rf.causa/reset-to-epoch`           event-fx — restore-epoch via fx
    - `:rf.causa/reset-to-pinned`          event-fx — reset-frame-db! via fx

  Two effects route the framework writes — `:rf.causa.fx/restore-epoch`
  and `:rf.causa.fx/reset-frame-db!`. They are reg-fx'd thin
  delegations to `rf/restore-epoch` / `rf/reset-frame-db!`. The
  indirection lets test fixtures stub the writes and assert the
  correct framework call site is reached (Reset to pinned uses
  reset-frame-db!, not restore-epoch, per spec §Why reset-frame-db!
  not restore-epoch).

  Subsequent panel beads add their own per-panel events / subs / fxs."
  (:require [re-frame.core :as rf]
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.time-travel-helpers :as tt-helpers]))

;; ---- defaults ------------------------------------------------------------

(def default-panel-id
  "The hero panel — `:event-detail` — is Causa's default landing per
  spec/007-UX-IA.md §The default landing view + §10 Lock 7. Exposed
  as a Var so the shell and tests share the source of truth."
  :event-detail)

(def default-target-frame
  "The host frame Causa's time-travel scrubber inspects by default.
  Per spec/002-Time-Travel.md §Cross-frame scrubbing the scrubber is
  per-frame — the frame picker (rf2-xxx, not Phase 3 scope) lets
  the user pick a different host frame. Until that picker lands the
  scrubber is hard-bound to :rf/default — the canonical host frame
  per Tool-Pair §Frame naming.

  Note this is the *host*'s frame, not :rf/causa — Causa's own
  state (selection, pin store) lives in :rf/causa via the shell's
  frame-provider; the *target* of restore-epoch / reset-frame-db! is
  the host's :rf/default."
  :rf/default)

;; ---- subscriptions -------------------------------------------------------

;; Causa's trace-buffer sub returns the Causa-side ring buffer
;; contents (NOT the framework's `(rf/trace-buffer)`). Reading directly
;; from `trace-bus/buffer` is the right shape here because the buffer
;; is process-global, not per-frame — every Causa shell mounted across
;; any frame should see the same trace stream. The sub thunks the
;; pure-data accessor so reactive contexts get a fresh read on every
;; recompute.
(defonce ^:private registered?
  ;; Idempotency sentinel. Re-loading the namespace (shadow-cljs
  ;; `:after-load`) must not re-register the sub (would harmlessly
  ;; replace the handler, but emits a `:rf.warning/handler-replaced`
  ;; trace that pollutes the dev console on every reload).
  (atom false))

(defn register-causa-handlers!
  "Idempotent registration of Causa's :rf.causa/* events, subs, fxs.
  Called from `day8.re-frame2-causa.preload` at load time. Safe to
  call multiple times — second + subsequent calls are no-ops."
  []
  (when (compare-and-set! registered? false true)
    ;; ---- subs ----------------------------------------------------
    (rf/reg-sub :rf.causa/trace-buffer
      (fn [_db _query]
        (trace-bus/buffer)))

    ;; Currently-active panel — drives the canvas's switch logic in
    ;; shell.cljs. Default is the hero panel per §10 Lock 7.
    (rf/reg-sub :rf.causa/selected-panel
      (fn [db _query]
        (get db :selected-panel default-panel-id)))

    ;; The dispatch-id the user has drilled into. nil = empty state
    ;; (cascade list, per spec/007-UX-IA.md §The default landing view).
    (rf/reg-sub :rf.causa/selected-dispatch-id
      (fn [db _query]
        (get db :selected-dispatch-id)))

    ;; Event-detail composite — produces everything the panel needs in
    ;; one read so the view stays a thin renderer. Shape:
    ;;
    ;;     {:cascades             [...]   ; all cascades, oldest first
    ;;      :selected-dispatch-id <id>    ; nil when no selection
    ;;      :selected-cascade     {...}}  ; nil when no selection
    ;;                                    ; OR when the id is no
    ;;                                    ; longer in the buffer
    ;;
    ;; The projection runs against the live buffer on every recompute.
    ;; Per spec/007-UX-IA.md §Performance budget the panel renders at
    ;; most ~200 cascades; the projection is O(n) over the buffer.
    (rf/reg-sub :rf.causa/event-detail
      ;; Signal layer: depend on the trace-buffer sub +
      ;; selected-dispatch-id sub so this composite recomputes when
      ;; either changes. The `:<-` chain is the only sub-registration
      ;; form in v2 (per Spec 002 §Subscriptions composing —
      ;; reg-sub-raw is dropped; see `re-frame.subs/parse-reg-sub-args`).
      :<- [:rf.causa/trace-buffer]
      :<- [:rf.causa/selected-dispatch-id]
      (fn [[buffer selected-id] _query]
        (let [cascades (projection/group-cascades buffer)
              by-id    (when selected-id
                         (some #(when (= selected-id (:dispatch-id %)) %)
                               cascades))]
          {:cascades             cascades
           :selected-dispatch-id selected-id
           :selected-cascade     by-id})))

    ;; ---- Phase 3 (rf2-t53ze) — Time Travel scrubber subs ---------

    ;; Target frame the scrubber inspects. Hard-bound to :rf/default
    ;; until the frame picker (rf2-xxx) lands; the sub abstracts so
    ;; the picker can drop in without rewiring every consumer.
    (rf/reg-sub :rf.causa/target-frame
      (fn [db _query]
        (get db :target-frame default-target-frame)))

    ;; Cached snapshot of the target frame's epoch history, pumped
    ;; by `:rf.causa/epoch-recorded` (dispatched from the epoch-cb in
    ;; preload). The cache is necessary because rf/epoch-history is a
    ;; side-effecting read of the epoch artefact's atom — a sub fn
    ;; can call it but the sub graph won't re-fire when the atom
    ;; mutates. Routing history through Causa's app-db makes the sub
    ;; reactive on its own write path.
    (rf/reg-sub :rf.causa/epoch-history
      (fn [db _query]
        (get db :epoch-history [])))

    ;; The view's currently-selected epoch — nil = newest (no scrub
    ;; in flight). Per spec §The passive-scrubbing rule, scrubbing
    ;; rebases panels but does NOT rewind app-db.
    (rf/reg-sub :rf.causa/selected-epoch-id
      (fn [db _query]
        (get db :selected-epoch-id)))

    ;; Per-frame pin store, keyed by target-frame. Persisted into
    ;; Causa's app-db only — never localStorage / disk (Lock 4 per
    ;; spec §Session-scoped — pins do not survive reload).
    (rf/reg-sub :rf.causa/pin-store
      (fn [db _query]
        (get db :pin-store {})))

    ;; The pin vector for the current target-frame — a flat sequence
    ;; the view iterates. Decoupled from :rf.causa/pin-store so the
    ;; view doesn't re-render when an unrelated frame's pins mutate.
    (rf/reg-sub :rf.causa/pinned-snapshots
      :<- [:rf.causa/pin-store]
      :<- [:rf.causa/target-frame]
      (fn [[pin-store target-frame] _query]
        (tt-helpers/pins-for-frame pin-store target-frame)))

    ;; Composite for the panel — one read produces every slot the
    ;; view needs. Mirrors the Phase-2 `:rf.causa/event-detail`
    ;; composite shape. The :chip-states projection runs chip-state
    ;; over each pin against the current history so detached pins
    ;; carry the visible signal per spec §Pins on the scrubber.
    (rf/reg-sub :rf.causa/time-travel
      :<- [:rf.causa/target-frame]
      :<- [:rf.causa/epoch-history]
      :<- [:rf.causa/selected-epoch-id]
      :<- [:rf.causa/pinned-snapshots]
      (fn [[target-frame history selected-id pins] _query]
        (let [selected-record (when selected-id
                                (tt-helpers/find-epoch-in-history
                                  history selected-id))]
          {:target-frame    target-frame
           :history         history
           :selected-epoch-id selected-id
           :selected-record selected-record
           :selected-index  (tt-helpers/epoch-index-in-history
                              history selected-id)
           :pins            pins
           :chip-states     (tt-helpers/chip-states history pins)
           :cap-reached?    (>= (count pins) tt-helpers/default-pin-cap)})))

    ;; ---- events --------------------------------------------------
    (rf/reg-event-db :rf.causa/select-panel
      (fn [db [_ panel-id]]
        (assoc db :selected-panel panel-id)))

    (rf/reg-event-db :rf.causa/select-dispatch-id
      (fn [db [_ dispatch-id]]
        (assoc db :selected-dispatch-id dispatch-id)))

    (rf/reg-event-db :rf.causa/clear-selected-dispatch-id
      (fn [db _event]
        (dissoc db :selected-dispatch-id)))

    ;; ---- Phase 3 (rf2-t53ze) — Time Travel scrubber events -------

    ;; Pump the latest epoch-history snapshot for the target frame
    ;; into Causa's app-db. Dispatched from the epoch-cb registered
    ;; in preload.cljs on every settled epoch. We don't pass the
    ;; vector across the dispatch boundary — we re-read from the
    ;; framework's `rf/epoch-history` so the snapshot is always
    ;; consistent with the framework's view (the cb fires AFTER the
    ;; record is appended; a stale arg would be off-by-one only on
    ;; the boundary, but threading the live read keeps the contract
    ;; simple).
    (rf/reg-event-db :rf.causa/epoch-recorded
      (fn [db [_ frame-id]]
        (let [target (get db :target-frame default-target-frame)]
          (if (= frame-id target)
            (assoc db :epoch-history (vec (rf/epoch-history target)))
            db))))

    ;; Set the view's selected-epoch (passive scrub). Per spec §The
    ;; passive-scrubbing rule — this DOES NOT call restore-epoch.
    (rf/reg-event-db :rf.causa/select-epoch
      (fn [db [_ epoch-id]]
        (assoc db :selected-epoch-id epoch-id)))

    (rf/reg-event-db :rf.causa/clear-selected-epoch
      (fn [db _event]
        (dissoc db :selected-epoch-id)))

    ;; Pin the epoch at `epoch-id` under the current target-frame
    ;; with `label`. The handler eagerly copies :db-after off the
    ;; live history record (per spec §What a pin captures — eager
    ;; capture). Enforces the 32-pin cap; surfaces `:overflow?` via
    ;; the toast slot the view reads on next render.
    (rf/reg-event-db :rf.causa/pin-current
      (fn [db [_ epoch-id label]]
        (let [target  (get db :target-frame default-target-frame)
              history (vec (or (get db :epoch-history)
                               (rf/epoch-history target)))
              record  (tt-helpers/find-epoch-in-history history epoch-id)
              pin     (tt-helpers/pin-from-epoch record label)]
          (if (some? pin)
            (let [{:keys [store overflow? dropped-pin]}
                  (tt-helpers/pin-snapshot (get db :pin-store {})
                                           target pin)]
              (cond-> (assoc db :pin-store store)
                overflow? (assoc :pin-overflow-toast
                                 {:dropped-label (:label dropped-pin)
                                  :ts            (.getTime (js/Date.))})))
            db))))

    ;; Drop a pin from the current target-frame's pin store.
    (rf/reg-event-db :rf.causa/unpin
      (fn [db [_ epoch-id]]
        (let [target (get db :target-frame default-target-frame)]
          (update db :pin-store
                  tt-helpers/unpin-snapshot target epoch-id))))

    ;; Inline-rename a pin's label. The 4-tuple's other slots are
    ;; immutable (spec §Pin actions §Rename pin).
    (rf/reg-event-db :rf.causa/rename-pin
      (fn [db [_ epoch-id new-label]]
        (let [target (get db :target-frame default-target-frame)]
          (update db :pin-store
                  tt-helpers/rename-pin target epoch-id new-label))))

    ;; Dismiss the cap-reached toast surface.
    (rf/reg-event-db :rf.causa/dismiss-pin-overflow-toast
      (fn [db _] (dissoc db :pin-overflow-toast)))

    ;; ---- write effects (the two confirmed-rewind paths) ----------

    ;; Reset to current epoch — uses restore-epoch (the ring-buffer
    ;; path). Per spec §The passive-scrubbing rule §rewind = explicit:
    ;; this is the confirmed-rewind branch. Per re-frame v2's reg-fx
    ;; contract (Spec API.md §reg-fx) the handler signature is
    ;; (fn [ctx args] ...).
    (rf/reg-fx :rf.causa.fx/restore-epoch
      (fn [_ctx {:keys [frame-id epoch-id]}]
        (rf/restore-epoch frame-id epoch-id)))

    ;; Reset to pinned — uses reset-frame-db! (the value-direct path).
    ;; Per spec §Why reset-frame-db! not restore-epoch — pins hold the
    ;; value directly, so the rewind works even after the underlying
    ;; epoch ages out of the ring buffer.
    (rf/reg-fx :rf.causa.fx/reset-frame-db!
      (fn [_ctx {:keys [frame-id frame-db]}]
        (rf/reset-frame-db! frame-id frame-db)))

    (rf/reg-event-fx :rf.causa/reset-to-epoch
      (fn [{:keys [db]} [_ epoch-id]]
        (let [target (get db :target-frame default-target-frame)]
          ;; Per Spec MIGRATION §Effect map shape — re-frame2's canonical
          ;; fx return is `{:db ... :fx [[fx-id args] ...]}`. Top-level
          ;; effect keys other than :db / :fx are not part of the
          ;; contract; the registered fx is invoked via the :fx vector.
          {:fx [[:rf.causa.fx/restore-epoch
                 {:frame-id target :epoch-id epoch-id}]]})))

    (rf/reg-event-fx :rf.causa/reset-to-pinned
      (fn [{:keys [db]} [_ epoch-id]]
        (let [target (get db :target-frame default-target-frame)
              pin    (tt-helpers/find-pin (get db :pin-store {})
                                          target epoch-id)]
          (when pin
            {:fx [[:rf.causa.fx/reset-frame-db!
                   {:frame-id target :frame-db (:frame-db pin)}]]})))))
  nil)

(defn reset-for-test!
  "Reset the registry's idempotency sentinel so test fixtures can drive
  multiple registration cycles. Test-only — never call from production
  code."
  []
  (reset! registered? false)
  nil)
