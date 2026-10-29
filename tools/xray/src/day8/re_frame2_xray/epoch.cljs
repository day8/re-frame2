(ns day8.re-frame2-xray.epoch
  "Cross-cutting epoch primitives — target frame, epoch history, the
  selected-epoch slot. This epoch / target-frame plumbing is consumed
  across the shell:

  - `panels.app-db-diff-subs` reads `:rf.xray/epoch-history`,
    `:rf.xray/target-frame`, and pivots on the spine focus epoch via
    `:rf.xray/focus-epoch-id`.
  - `panels.machine-inspector` reads `:rf.xray/epoch-history`,
    `:rf.xray/target-frame`, `:rf.xray/target-frame-db`.
  - `panels.reactive-panel-subs` reads `:rf.xray/epoch-history` to
    project the focused event-bundle's `:trace-events` into the Reactive
    panel's sub-cascade + view-re-render rendering.
  - `core/target-frame` + `core/set-target-frame!` read / dispatch the
    target-frame slot and `:rf.xray/set-target-frame` event.
  - `install/register-epoch-collector!` (re-exported as
    `preload/register-epoch-collector!`) dispatches `:rf.xray/epoch-
    recorded` when the framework records a new epoch (any frame),
    task-coalesced to one dispatch per frame per tick.
  - `mount.cljs` seeds `:rf.xray/sync-epoch-history` at first open.
  - `shell/tab-bar` dispatches `:rf.xray/reset-to-epoch` —
    the UI rewind affordance — and reads `:rf.xray/reset-flash` for the
    inline failure flash.

  Keeping the plumbing in its own namespace makes the cross-cutting
  intent visible: the slot is `:rf/xray`-frame state shared across every
  panel that cares about time, not any one panel's private surface."
  (:require [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.epoch.assembly :as rf.epoch.assembly]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.defaults :as defaults]))

;; ---- the `:epoch-history` ingest gate -------------------------------------
;;
;; The framework's per-frame epoch ring retains RAW records — redaction
;; happens at off-box egress (`epoch/assembly.cljc`: "the ring and
;; listeners retain raw replay material"). Every record carries
;; `:trace-events` verbatim, so a `:sensitive? true` cascade that
;; `collect-trace!` and `snapshot-from-rings` both drop from `:trace-buffer`
;; would still reach the Epoch panel, the Issues ribbon, the Reactive panel,
;; the Machine Inspector and the Trace feed through THIS slot — they all read
;; `:trace-events` off the focused record.
;;
;; So the same Spec 009 §Privacy gate the trace side applies guards the one
;; seam every `:epoch-history` write passes through, on the TRACE-EVENT
;; signal: a record whose cascade carries a sensitive-stamped event is
;; withheld whole. App-db values at classified-sensitive paths are NOT this
;; gate's concern — holding a token in app-db does not make a later counter
;; tick sensitive — so they stay on the record and the render-side
;; projections redact them under the classification the record was recorded
;; with (`panels.local-render/local-render-record-state`). The rule is
;; tools/xray/spec/013 §One policy, three ingress paths.
;;
;; NO COUNTER BUMP. `config/note-suppressed!` counts what the LISTENER
;; dropped; the same events were already counted there on their way past
;; `collect-trace!`, so bumping here would double-count the `[● REDACTED
;; N]` indicator against one cascade.

(defn withheld?
  "True when the ingest gate withholds `record` from Xray's `:epoch-history`.

  Never under a sensitive-revealing profile (`:rf.egress/local-raw`, the
  trusted-local opt-in). Otherwise the record is withheld when its own
  `:trace-events` carry a sensitive-stamped event — the trace-event signal of
  the framework rollup (`re-frame.epoch.assembly/sensitive-trace?`). A record
  that has lost `:trace-events` to the ring's keep-window cannot be read for
  that signal, so it falls back to the combined `:rf.epoch/sensitive?` rollup
  and fails closed.

  ## The grain is the RECORD, not the event

  Removing only the offending events from `:trace-events` would leave the
  cascade standing in every sibling slot, because `build-record` derives
  those slots from the very events being removed — `:sub-runs`, `:renders`
  and `:effects` are projections of `:trace-events`, and `:trigger-event` is
  lifted out of the run-start's event vector. An event-grain scrub moves the
  leak; it does not close it. `trace-collector/bundles-for-frame` answers the
  same question for the BUNDLE read the same way."
  [record]
  (and (not (config/include-sensitive?))
       (if (contains? record :trace-events)
         (rf.epoch.assembly/sensitive-trace? (:trace-events record))
         (true? (:rf.epoch/sensitive? record)))))

(defn redact-history
  "Gate `records` — a framework per-frame epoch ring, oldest-first — keeping
  every record `withheld?` does not withhold. Pure. Returns a vector."
  [records]
  (into [] (remove withheld?) records))

(defn assoc-history
  "Write `records` into `db` through the ingest gate: the kept records into
  `:epoch-history`, and the `:dispatch-id`s of the withheld ones into
  `:epoch-withheld`. Every `:epoch-history` writer goes through here.

  The withheld ids are the stub a withheld record leaves: a panel focused on
  a bundle whose epoch is in the set says the egress policy withheld it,
  rather than that the bundle settled no epoch
  (`panels.shared.focus-resolver/resolve-focus-status`). An id is all the
  stub carries — the envelope itself is what the gate keeps off screen."
  [db records]
  (let [held? (group-by (comp boolean withheld?) records)]
    (assoc db
           :epoch-history  (vec (get held? false))
           :epoch-withheld (into #{} (keep :dispatch-id) (get held? true)))))

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

  ;; The `:dispatch-id`s of the records the ingest gate withheld from
  ;; `:epoch-history` (`assoc-history`).
  (rf/reg-sub :rf.xray/epoch-withheld
    (fn [db _query]
      (get db :epoch-withheld #{})))

  ;; Transient `reset-to-event` failure flash. Holds a short
  ;; message string when a `rf.epoch/restore-epoch!` rewind fails (epoch aged
  ;; out of the buffer, or a restore-during-drain rejection). nil =
  ;; nothing to show. The flash is INLINE on the tab ribbon — never a
  ;; modal — and clears on the next reset attempt (`:rf.xray/reset-to-
  ;; epoch` dissocs the slot before re-running the restore).
  ;; A failure must never be a silent lie; it must also never block.
  (rf/reg-sub :rf.xray/reset-flash
    (fn [db _query]
      (get db :reset-flash)))

  ;; ---- effects -----------------------------------------------------------

  ;; `restore-epoch` is a side-effecting framework call (it
  ;; rewinds an OBSERVED frame's `app-db` to a past epoch's `:db-after`),
  ;; so it lives in an fx, not a plain `reg-event` `:db` reducer. `rf.epoch/restore-epoch!`
  ;; returns `{:ok? false :reason <op> …}` on any of the seven documented
  ;; failure modes (per Tool-Pair §Time-travel — Restore, including
  ;; `:rf.epoch/restore-non-ok-record`) leaving the frame unchanged, and
  ;; `false` when the epoch surface is elided; on failure we dispatch the
  ;; inline flash with the reason so the operator is told why (never a
  ;; silent lie). The framework also emits a structured `:rf.epoch/*` trace
  ;; row on the bus, which Xray's own Trace panel shows.
  ;; re-frame2 fx handlers are `(fn [ctx args] …)` — `args` is the value
  ;; from the `:fx` vector entry (here the `{:frame … :epoch-id …}` map).
  (rf/reg-fx :rf.xray.fx/restore-epoch
    (fn [_ctx {:keys [frame epoch-id]}]
      (when (and frame epoch-id)
        (let [r (rf.epoch/restore-epoch! frame epoch-id)]
          (when-not (:ok? r)
            (rf/dispatch [:rf.xray/reset-flash-failed (:reason r)]))))))

  ;; ---- events ------------------------------------------------------------

  ;; `:rf.xray/set-target-frame` — host-frame focus picker. Dispatched
  ;; by `core/set-target-frame!`. EP-0002 — writing nil resets
  ;; to UNSELECTED (dissocs `:target-frame`), NOT to a synthesised
  ;; `:rf/default`: the inspected target is never absence-repaired to the
  ;; conventional `:rf/default` app-frame id (Spec 002 §Frame target resolution). A known
  ;; frame-id seeds `:epoch-history` from the framework's per-frame epoch
  ;; ring so the immediate subscribe-after-dispatch read sees a hydrated
  ;; slot; a nil resets the history to `[]` (`rf.epoch/epoch-history nil` → `[]`).
  ;;
  ;; The reducer ALSO aligns `[:focus :frame]` to the same target. The
  ;; two axes encode the same gesture (the user is observing this host
  ;; frame), and the picker-write path (`spine/set-frame-reducer`) aligns
  ;; both axes too. Writing only `:target-frame` from mount-time and
  ;; `core/set-target-frame!` callers would leave `[:focus :frame]` nil,
  ;; which would make:
  ;;   - `filter-event-bundles-by-frame` a no-op (reads `:focus-slot :frame`),
  ;;     so the L2 list would show every frame's event-bundles even though
  ;;     the picker view collapses the dropdown label to a specific frame;
  ;;   - `compose-focus`'s `slot-frame` filter inactive, so the head
  ;;     walk would pick the global most-recent event-bundle — Issues /
  ;;     Views / App-DB Diff scoped to whichever frame's event was most
  ;;     recent, not the observed frame.
  ;; A nil `frame-id` (the reset case) symmetrically clears the focus
  ;; slot's `:frame` — leaving it set to a stale value would misalign the
  ;; two axes in the inverse direction.
  (rf/reg-event :rf.xray/set-target-frame
    (fn [{:keys [db]} [_ frame-id]]
      {:db (let [target (or frame-id defaults/default-target-frame)]
        (cond-> (assoc-history db (rf.epoch/epoch-history target))
          (nil? frame-id)  (dissoc :target-frame)
          (nil? frame-id)  (update :focus (fnil dissoc {}) :frame)
          (some? frame-id) (assoc :target-frame frame-id)
          (some? frame-id) (assoc-in [:focus :frame] frame-id)))}))

  ;; `:rf.xray/epoch-recorded` — dispatched from `install/register-
  ;; epoch-collector!` when the framework records a new epoch on any
  ;; frame. Re-reads the per-frame ring into `:epoch-history` so the
  ;; companion sub re-fires on the standard app-db-write reactive path.
  ;;
  ;; ONE DISPATCH PER FRAME PER TASK, not one per epoch. The
  ;; collector coalesces a same-tick burst into a single dispatch per
  ;; distinct frame. The arg means "this frame recorded"; for a
  ;; non-target frame this reducer is a no-op re-read that costs a
  ;; queue slot per task rather than per epoch.
  ;; `:rf.trace/no-emit? true` — the dispatch must not itself emit a
  ;; trace event (the listener is part of Xray's instrumentation loop;
  ;; a self-emit would re-enter the listener).
  ;;
  ;; COLD-START ADOPTION. While the target is UNSELECTED the
  ;; `(= frame-id target)` comparison below can never match: `target` is
  ;; nil and every real recording frame differs from it, so without
  ;; adoption `:epoch-history` would stay empty for as long as nobody
  ;; clicked. That is the state Xray
  ;; mounts in whenever it comes up BEFORE the host's first cascade
  ;; (the preload's `boot-on-runtime-ready!`, or any app whose first
  ;; dispatch is user-driven): `focusable-head-frame-id` has no
  ;; pre-mount cascade to resolve, so the mount seed leaves the slot
  ;; nil, `compose-focus` yields `:epoch-id nil`, and the L4 Epoch panel
  ;; would render "No event focused" while the L2 list filled and
  ;; auto-follow highlighted its head row.
  ;;
  ;; So an ingest onto an unselected target ADOPTS the recording frame,
  ;; aligning both axes exactly as `:rf.xray/set-target-frame` does.
  ;; This is NOT the `:rf/default` synthesis EP-0002
  ;; forbids — the adopted frame is the one that actually RECORDED, so
  ;; it is unique resolution from observed evidence, the same tier as
  ;; the mount-time discovery policy and the same "first gesture out of
  ;; the unselected state selects a target" move
  ;; `spine/reseed-epoch-history-for-frame` already makes on the click
  ;; path. It fires only while the slot is unselected, so an explicit
  ;; host-config or picker choice always outranks it.
  ;;
  ;; Xray's OWN frame is excluded. The epoch listener is registered
  ;; process-wide and `:rf/xray` records epochs like any other frame —
  ;; and at cold start, with the host quiet, Xray's own chrome events
  ;; are the likeliest first settle of all. Adopting `:rf/xray` would
  ;; point the inspector at itself, which is strictly worse than the
  ;; unselected state this repairs. (`frame_switcher/internal-frames`
  ;; carries the wider picker-exclusion set; reaching it from here
  ;; would be a view→plumbing edge, so only the own-frame id — already
  ;; in scope via `defaults` — is filtered.)
  (rf/reg-event :rf.xray/epoch-recorded
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ frame-id]]
      {:db (let [target (get db :target-frame defaults/default-target-frame)
                 adopt? (and (nil? target)
                             (some? frame-id)
                             (not= frame-id defaults/default-frame-id))]
        (cond
          adopt?
          (-> db
              (assoc :target-frame frame-id)
              (assoc-in [:focus :frame] frame-id)
              (assoc-history (rf.epoch/epoch-history frame-id)))

          (= frame-id target)
          (assoc-history db (rf.epoch/epoch-history target))

          :else db))}))

  ;; `:rf.xray/sync-epoch-history` — wholesale overwrite of the
  ;; `:epoch-history` slot. Dispatched from `mount.cljs/open!` on first
  ;; Ctrl+Shift+C to seed Xray's app-db with the framework's existing
  ;; per-frame ring contents. `:rf.trace/no-emit? true` matches the
  ;; `epoch-recorded` rationale above.
  ;;
  ;; The sync ALSO focuses the LATEST seeded epoch. The
  ;; sync seeds `:epoch-history` DIRECTLY, bypassing the normal trace-
  ;; driven path (`:rf.xray/epoch-recorded` → a fresh event-bundle →
  ;; `compose-focus` auto-following head), so nothing would otherwise
  ;; select an epoch: every focus-keyed Dynamic panel renders its
  ;; "nothing focused" state (App-db → "no user-domain keys yet";
  ;; the Epoch / Reactive / Machines panels → their no-focus lines).
  ;; The App-DB before-image specifically follows `[:focus :epoch-id]`
  ;; with NO head-fallback (`app_db_diff_subs/app-db-current+diff` is
  ;; deliberately fallback-free), so seeding history is
  ;; not enough — focus MUST carry the epoch-id.
  ;;
  ;; We stamp the spine `[:focus :epoch-id]` (what `compose-focus`
  ;; surfaces as `:rf.xray/focus` `:epoch-id` when no event-bundle head is
  ;; present — i.e. history-only seeds), the single source of truth the
  ;; focus-keyed panels follow (there is no `:selected-epoch-id`
  ;; mirror). The LATEST epoch is the HEAD of the oldest-first ring
  ;; — `(peek hist)` — matching the natural "show the most recent unless
  ;; the operator clicks an earlier row" debugging UX (the same head-bias
  ;; the focus-resolver's head-fallback encodes). An empty
  ;; history clears focus so a no-epoch seed renders its empty-state.
  ;;
  ;; `peek` runs over the GATED history, so when the
  ;; newest record is a redacted one the focus lands on the newest
  ;; SURVIVING record rather than on a record no panel can render. A
  ;; seed of nothing but redacted records clears focus, which is the
  ;; same empty-state an empty seed gets.
  ;;
  ;; When a live trace buffer IS also seeded (the chrome story seeds
  ;; both via `:rf.xray/sync-trace-buffer`), `compose-focus`'s LIVE
  ;; auto-follow re-derives `:epoch-id` from the head event-bundle; this
  ;; stamp is the authoritative selection only for history-only seeds
  ;; (the standalone panel-gallery stories).
  (rf/reg-event :rf.xray/sync-epoch-history
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ history]]
      {:db (let [db        (assoc-history db history)
            latest-id (:epoch-id (peek (:epoch-history db)))]
        (cond-> db
          (some? latest-id) (assoc-in [:focus :epoch-id] latest-id)
          (nil? latest-id)  (update :focus (fnil dissoc {}) :epoch-id)))}))

  ;; `:rf.xray/select-epoch` — spine shim. Writes the
  ;; spine's `[:focus :epoch-id]` slot — the single source of truth that
  ;; the spec/018 `:rf.xray/focus` sub surfaces (and that App-DB Diff's
  ;; `selected-epoch-*` subs rebind on via `:rf.xray/focus-epoch-id`)
  ;; when the user picks an epoch (there is no `:selected-epoch-id`
  ;; mirror). Symmetric with `:rf.xray/select-dispatch-id` (in
  ;; registry.cljs).
  (rf/reg-event :rf.xray/select-epoch
    (fn [{:keys [db]} [_ epoch-id]]
      {:db (assoc-in db [:focus :epoch-id] epoch-id)}))

  ;; `:rf.xray/reset-to-epoch` — the UI rewind affordance.
  ;; The tab ribbon's `Reset` button dispatches this with the OBSERVED
  ;; frame (the frame Xray is inspecting — the frame-switcher selection,
  ;; NOT `:rf/xray` Xray's own chrome frame) and the currently-focused
  ;; epoch-id. The view supplies both from `:rf.xray/observed-frame` +
  ;; `:rf.xray/focus-epoch-id` so this event stays a thin trampoline into
  ;; the `:rf.xray.fx/restore-epoch` effect (which calls the framework's
  ;; `rf.epoch/restore-epoch!`, reinstalling the epoch's WHOLE `:frame-state-after`
  ;; — app-db AND runtime-db in one atomic write, per epoch ·
  ;; `restore-epoch!`; the retained `:db-after` is only an app-db projection
  ;; for diffs, never the restore source — "if the event still exists, app
  ;; state must be as if the event happened").
  ;; No dialog, no confirmation — the button just does it (programmers
  ;; are power users). A nil frame / epoch-id is a guarded no-op (the
  ;; button is disabled when no epoch is focused, but the event stays
  ;; defensive).
  ;;
  ;; Clear any STALE failure flash on every fresh attempt.
  ;; The flash is set by `:rf.xray/reset-flash-failed` on a failed
  ;; restore and has no auto-dismiss, so without this clear a later
  ;; SUCCESSFUL reset would leave the "Reset failed" message standing —
  ;; a silent lie (the sub would return the stale string, the ribbon
  ;; would keep rendering it). Dissoc-ing `:reset-flash` here in the `:db`
  ;; is the flash's steady-state clear (the comment on
  ;; `:rf.xray/clear-reset-flash` below names it): a fresh attempt wipes the
  ;; prior failure, and the fx re-sets the flash only when THIS attempt
  ;; also fails. The dissoc runs before the fx so a failure that fires
  ;; `:rf.xray/reset-flash-failed` synchronously still wins.
  (rf/reg-event :rf.xray/reset-to-epoch
    (fn [{:keys [db]} [_ frame epoch-id]]
      {:db (dissoc db :reset-flash)
       :fx [[:rf.xray.fx/restore-epoch {:frame frame :epoch-id epoch-id}]]}))

  ;; `:rf.xray/reset-flash-failed` — set the inline failure
  ;; flash, naming the refusal's `:reason`. Dispatched from
  ;; `:rf.xray.fx/restore-epoch` when `rf.epoch/restore-epoch!` refuses; a nil
  ;; reason means the epoch surface itself is unavailable (elided).
  ;; `:rf.trace/no-emit? true` keeps Xray's own chrome event off the trace
  ;; bus it is inspecting.
  (rf/reg-event :rf.xray/reset-flash-failed
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ reason]]
      {:db (assoc db :reset-flash (if reason
                                    (str "Reset failed — " reason)
                                    "Reset failed — epoch surface unavailable"))}))

  ;; `:rf.xray/clear-reset-flash` — clear the inline flash.
  ;; The steady-state clear path is `:rf.xray/reset-to-epoch` dissoc-ing
  ;; the slot on every fresh attempt; this event is the
  ;; explicit imperative clear (tests + any future dismiss affordance).
  ;; `:rf.trace/no-emit? true` per the sibling rationale.
  (rf/reg-event :rf.xray/clear-reset-flash
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} _event]
      {:db (dissoc db :reset-flash)}))

  nil)
