(ns day8.re-frame2-causa.spine
  "The single-axis spine substrate (rf2-adve5).

  Per `tools/causa/spec/018-Event-Spine.md` §6 Spine binding the spine
  sub `:rf.causa/focus` is the one axis every dependent surface reads
  from. When a user clicks a row, EVERY dependent surface (count
  badges, gutter glyph, detail panel content, mode pill) rebinds via
  the spine — no panel maintains its own selection state.

  ## Shape

      {:dispatch-id <id-or-nil>     ; cascade root the user focused on
       :epoch-id    <id-or-nil>     ; epoch the cascade settled in
       :frame       <frame-id>      ; frame the cascade ran in
       :mode        :live | :retro  ; :live tracks head; :retro pins
       :head?       <bool>          ; true when :dispatch-id is latest
       :previewing? <bool>          ; true while user hovers without click
       :paused?     <bool>}         ; true when LIVE buffer flow paused

  ## Storage

  The spine slot lives in Causa's app-db at `:focus`. The
  `:rf.causa/focus` sub composes the slot with the live cascade list
  (via `:rf.causa/cascades`) so `:head?` and the effective
  `:dispatch-id` are always derived from the current cascade vector —
  never stale.

  ## Shim to legacy selection slots

  Pre-rf2-adve5, two panels owned their own selection: event-detail
  owned `:selected-dispatch-id` / `:selected-dispatch` and time-travel
  owned `:selected-epoch-id`. Spec 018 §6 collapses those into the
  spine, but legacy event handlers + their sibling subs continue to
  exist (each panel reads its own slot directly today). The spine's
  `:rf.causa/focus-cascade` event writes BOTH the new `:focus` slot
  AND the legacy `:selected-dispatch-id` slot so existing panels keep
  rendering with no per-panel change.

  The legacy `:rf.causa/select-dispatch-id` and `:rf.causa/select-
  epoch` events also write through the spine (in their existing
  install! fns under event_detail.cljs / time_travel_events.cljs) so
  the other direction shims too — a click in the existing Causality
  Graph or Time Travel panel updates the spine, and a click in a
  future spine-aware list updates the legacy slots."
  (:require [re-frame.core :as rf]
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-causa.focus-helpers :as fh]
            [day8.re-frame2-causa.panels.common-helpers :as common]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- pure helpers --------------------------------------------------------

(defn focusable-cascades
  "Per rf2-fzbrw — the spine's invariant is 'one focused event at a
  time'. The `:ungrouped` bucket produced by `projection/group-cascades`
  for registry-time emits / frame lifecycle outside a drain / REPL
  evals carries no event vector and is therefore NOT a valid focus
  target: pinning to it leaves every event-detail / app-db / views
  panel with no cascade to render, which degrades into the unwanted
  'all subs / all handlers' aggregate look.

  Strip the bucket before any spine walk so:
    - the head/tail boundary predicates align with what the user sees
      in the L2 event list (which already filters via
      `shell/cascade-has-event?`);
    - stepping past a real event cannot land focus on `:ungrouped`;
    - `compose-focus` cannot resolve an effective dispatch-id to
      `:ungrouped` for a stored slot that was already pointing there.

  Pure data; JVM-runnable; idempotent (re-filtering a cleaned vector
  is a no-op)."
  [cascades]
  (filterv #(not= :ungrouped (:dispatch-id %)) cascades))

(defn head-cascade
  "The latest cascade in the projected list — the cascade whose
  `:dispatch-id` is the spine's 'head'. Returns nil when the list is
  empty (cold start; buffer-cleared). Cascades are sorted oldest-first
  (per `re-frame.trace.projection/group-cascades`), so the last entry
  is the head."
  [cascades]
  (last cascades))

(defn head-dispatch-id
  "The `:dispatch-id` of the head cascade, or nil."
  [cascades]
  (:dispatch-id (head-cascade cascades)))

(defn focusable-head-id
  "The `:dispatch-id` of the head focusable cascade — i.e. the latest
  row the user actually sees in the L2 event list. Returns nil when
  no focusable cascades exist.

  Used for click-on-head detection (rf2-xzzih): clicking the visible
  head event should stay LIVE; the raw `head-dispatch-id` would treat
  an `:ungrouped` bucket as the head if it sorted last, which the
  user never sees as a focusable row."
  [cascades]
  (head-dispatch-id (focusable-cascades cascades)))

(defn cascade-by-id
  "Find a cascade in `cascades` by its `:dispatch-id`. Returns nil when
  no match (id refers to an evicted cascade, or a fresh session).

  The 3-arity prefers a cascade matching `:frame` when supplied —
  multiple cascades can share a dispatch-id across frames (Spec 002
  §Frame isolation + rf2-g6ih4: dispatch-id uniqueness is per-frame,
  not process-global). When no frame-matching cascade exists the
  lookup falls back to a dispatch-id-only match so a stale stored
  frame (e.g. a user-frame the cascade was re-emitted from) doesn't
  hide an otherwise-valid cascade — the cascade's own `:frame` is
  treated as authoritative."
  ([cascades dispatch-id]
   (cascade-by-id cascades dispatch-id nil))
  ([cascades dispatch-id frame-id]
   (when dispatch-id
     (or (when frame-id
           (some #(when (and (= dispatch-id (:dispatch-id %))
                             (= frame-id (:frame %)))
                    %)
                 cascades))
         (some #(when (= dispatch-id (:dispatch-id %)) %)
               cascades)))))

(defn step-dispatch-id
  "Compute the new `:dispatch-id` when stepping by `delta` (-1 for
  prev, +1 for next) from `current-id` through `cascades`.

  - nil `current-id` (no focus yet) starts from the head.
  - When `current-id` is not in `cascades` (was evicted), step from
    head.
  - Bounds-clamped — stepping past either edge stays at the edge.
  - Returns nil only when `cascades` is empty."
  [cascades current-id delta]
  (let [n (count cascades)]
    (when (pos? n)
      (let [current-idx (some (fn [[idx c]]
                                (when (= current-id (:dispatch-id c)) idx))
                              (map-indexed vector cascades))
            base-idx    (or current-idx (dec n))
            new-idx     (-> (+ base-idx delta)
                            (max 0)
                            (min (dec n)))]
        (:dispatch-id (nth cascades new-idx))))))

(defn epoch-id-for-cascade
  "Return the `:epoch-id` of the epoch record in `epoch-history` whose
  settling cascade is `dispatch-id`, or nil when no match is found
  (the cascade is mid-build, was evicted from the ring, or the focus
  pins `:ungrouped`).

  Per spec/018 §6 Spine events the spine sub `:rf.causa/focus` carries
  both `:dispatch-id` (cascade root, opaque router counter) and
  `:epoch-id` (per-frame settling counter, primary key into the epoch
  ring buffer). The two are semantically the same identity — a
  cascade settles into exactly one epoch record — but they're
  separate counter spaces, so the linkage is a per-cascade lookup
  against the buffer.

  The lookup leans on the existing `common/dispatch-id-of-epoch`
  helper, which walks an epoch record's `:trace-events` for the first
  `:dispatch-id` tag. Synthetic test epochs that omit `:trace-events`
  but carry a literal `:dispatch-id` slot are matched directly so
  fixture rigs that pre-date the spec/018 spine wiring continue to
  resolve. Pure data; JVM-runnable."
  [epoch-history dispatch-id]
  (when (and dispatch-id (seq epoch-history))
    (some (fn [record]
            (when (or (= dispatch-id (common/dispatch-id-of-epoch record))
                      (= dispatch-id (:dispatch-id record)))
              (:epoch-id record)))
          epoch-history)))

(defn compose-focus
  "Derive the public `:rf.causa/focus` map from a stored `:focus` slot
  and the live cascade vector. Always re-derives `:head?` and the
  effective `:dispatch-id` from the cascade list — never trusts a
  stale stored value.

  In `:live` mode an unset (or evicted) dispatch-id snaps to head so
  a fresh session opens already pointing at the latest cascade per
  §4 Defaults.

  ## Live auto-follow (rf2-s0s5x Phase A)

  In `:live` mode the effective `:dispatch-id` is ALWAYS the current
  head — even when a stored `slot-id` exists and is still in the
  buffer. Previously the composer honoured the stored slot-id once it
  was set (so `focus-step-reducer` landing on the then-head left
  `:dispatch-id` pinned to that id even after newer cascades arrived);
  the user observed focus 'stuck' even though the LIVE pill said it
  was tracking. Spec/018 §3 says the LIVE pill auto-tracks head; this
  composer is the canonical site that derives that behaviour.

  `:paused?` suspends the auto-track — when paused, the focus stays
  on the stored slot-id so the user can inspect a cascade without
  losing it as new traffic arrives. Resuming (`follow-head` /
  `toggle-live-pause`) snaps back to head.

  ## No-aggregate-state invariant (rf2-fzbrw)

  The spine walks `focusable-cascades` (the `:ungrouped` bucket is
  stripped) — so the head/tail boundary and any retro-pin operation
  resolves against the user-visible cascade list. The composer
  additionally snaps to head whenever the stored slot is nil OR
  points at `:ungrouped` OR points at an evicted cascade. Combined
  with the LIVE auto-follow above this makes 'buffer non-empty +
  effective :dispatch-id nil' structurally unreachable — the L4
  panels never have to handle the 'no cascade' degraded render.

  ## Frame-picker scoping (rf2-oziyr)

  When `slot-frame` is non-nil (the frame-picker has restricted the
  inspectable surface to one frame), the head walk runs over
  cascades restricted to that frame so `:live` mode auto-tracks the
  picked frame's head and `[◀ ▶ ⏭]` boundaries respect the picker.
  Without this scoping the composer would pick the global head
  (whatever frame fired the latest event), which under multi-frame
  apps drifts focus off the picker's frame on every cross-frame
  dispatch."
  [focus cascades]
  (let [focusable* (focusable-cascades cascades)
        slot-frame (:frame focus)
        focusable  (if slot-frame
                     (filterv #(= slot-frame (:frame %)) focusable*)
                     focusable*)
        head-id    (head-dispatch-id focusable)
        slot-id    (:dispatch-id focus)
        paused?    (boolean (:paused? focus))
        ;; rf2-fzbrw — a slot pointing at nil OR the :ungrouped bucket
        ;; is NOT a valid focus pin. (Evicted ids are still a valid
        ;; pin: the event-detail panel surfaces its orphaned-state
        ;; copy off them, so we don't conflate "evicted" with "never
        ;; valid".)
        slot-pinnable? (and slot-id (not= :ungrouped slot-id))
        mode       (or (:mode focus)
                       (if (or (nil? slot-id) (= slot-id head-id))
                         :live
                         :retro))
        eff-id  (cond
                  ;; rf2-fzbrw — slot is nil / :ungrouped while a
                  ;; focusable buffer exists. Snap to head regardless
                  ;; of mode so the unreachable "focus nil + buffer
                  ;; non-empty" state stays unreachable. (Evicted retro
                  ;; ids flow through to the :else branch so the
                  ;; orphaned-state UX survives.)
                  (and (some? head-id) (not slot-pinnable?))
                  head-id
                  ;; Live + unpaused — track head, regardless of
                  ;; slot-id (rf2-s0s5x Phase A). Previously the
                  ;; stored slot-id won here, so once
                  ;; focus-step-reducer landed on the then-head the
                  ;; slot pinned to that id and never auto-advanced
                  ;; as newer cascades arrived. The LIVE pill's
                  ;; contract is unambiguous — head IS the focus.
                  (and (= :live mode) (not paused?))
                  head-id
                  ;; Live + paused — slot-id wins (frozen inspection);
                  ;; if the stored id has been evicted from the
                  ;; buffer, snap to head so the inspector doesn't
                  ;; dangle on a nonexistent cascade.
                  (and (= :live mode) paused?)
                  (if (cascade-by-id focusable slot-id slot-frame)
                    slot-id
                    head-id)
                  ;; Retro — slot-id wins. Evicted ids preserve the
                  ;; orphaned-state surface in the event-detail panel.
                  :else
                  slot-id)
        cascade (cascade-by-id focusable eff-id slot-frame)]
    {:dispatch-id eff-id
     :epoch-id    (:epoch-id focus)
     :frame       (or (:frame cascade) (:frame focus))
     :mode        mode
     :head?       (or (nil? eff-id)
                      (= eff-id head-id))
     :previewing? (boolean (:previewing? focus))
     :paused?     paused?}))

;; ---- pure reducers exposed for direct unit testing ----------------------

(defn focus-cascade-reducer
  "Pure reducer for the `:rf.causa/focus-cascade <id>` event. Writes
  `:dispatch-id` into the `:focus` slot, picks the spine `:mode`,
  stamps `:epoch-id` (the cascade's settling epoch primary key per
  spec/018 §6 Spine events), and shims the legacy
  `:selected-dispatch-id` / `:selected-dispatch` / `:selected-epoch-
  id` slots so the existing event-detail / causality / machine-
  inspector / app-db / time-travel panels keep rendering without per-
  panel change.

  ## Mode selection (rf2-xzzih)

  When the caller supplies `head-id` (the 5-arg arity) the reducer
  picks the new mode head-aware: clicking the head event keeps the
  spine LIVE so new arrivals continue to auto-advance; clicking any
  non-head event pins to RETRO. Without `head-id` (legacy 3-arg /
  4-arg arities) the mode defaults to RETRO — preserves the rf2-
  s0s5x Phase A contract for callers that don't yet pass the head.

  Per spec/018 §6 the spine sub `:rf.causa/focus` carries `:epoch-id`
  as a first-class slot; consumers (Views' focused-cascade-pair sub,
  App-db's selected-epoch-* sub chain) pivot on it. The 4-arg arity
  takes a resolved `epoch-id`; event handlers resolve it from the
  Causa `:epoch-history` slot via `epoch-id-for-cascade` before
  calling. The 3-arg arity (back-compat for callers that don't have
  the buffer handy) leaves `:epoch-id` nil — the focus sub still
  rebinds on `:dispatch-id`, but the epoch-keyed surfaces will not
  pivot until a 4-arg call lands."
  ([db dispatch-id frame-id]
   (focus-cascade-reducer db dispatch-id frame-id nil nil))
  ([db dispatch-id frame-id epoch-id]
   (focus-cascade-reducer db dispatch-id frame-id epoch-id nil))
  ([db dispatch-id frame-id epoch-id head-id]
   (let [mode (if (and head-id (= dispatch-id head-id)) :live :retro)]
     (cond-> db
       true       (update :focus (fnil assoc {})
                          :dispatch-id dispatch-id
                          :epoch-id    epoch-id
                          :mode        mode
                          :previewing? false)
       frame-id   (assoc-in [:focus :frame] frame-id)
       ;; Legacy shim — panels reading these slots stay live.
       true       (assoc :selected-dispatch-id dispatch-id
                         :selected-epoch-id    epoch-id
                         :selected-dispatch    (cond-> {:dispatch-id dispatch-id}
                                                 frame-id (assoc :frame frame-id)))))))

(defn focus-step-reducer
  "Pure reducer for `:rf.causa/focus-cascade-prev` / `-next`. Steps
  the `:dispatch-id` through the cascade vector by `delta` (-1 or
  +1), resolves the cascade's settling `:epoch-id` from
  `epoch-history` (per spec/018 §6 Spine events), updates the legacy
  shim slots, and flips mode based on the step's outcome — stepping
  back from head → :retro; stepping forward back to head → :live (the
  user has scrubbed home).

  Per rf2-s0s5x Phase A — `compose-focus` now treats `:live` mode as
  always tracking head, so the stored slot-id can lag the actual
  focused cascade. Compute `current-id` through the same composer so
  pressing `j` (prev) from LIVE steps back one from the CURRENT head
  rather than from a stale stored id.

  Per rf2-fzbrw the walk runs over `focusable-cascades` so the
  `:ungrouped` bucket (registry-time emits / lifecycle / REPL evals)
  is never a step target — pinning to it would degrade the L4 panels
  into the unwanted 'all subs / all handlers' aggregate look. When
  the buffer carries no focusable cascades, OR the step would land on
  the current focus (already at a boundary), the reducer returns `db`
  unchanged — a true no-op so `[<]` on the first event (or `[>]` on
  the latest event) cannot clear or shuffle focus.

  The 3-arg arity (back-compat for callers that don't have the epoch
  buffer handy) treats `epoch-history` as empty so `:epoch-id`
  resolves to nil. Production callers in `install!` go through the
  4-arg arity."
  ([db cascades delta]
   (focus-step-reducer db cascades [] delta))
  ([db cascades epoch-history delta]
   (let [slot-frame (get-in db [:focus :frame])
         focusable* (focusable-cascades cascades)
         ;; rf2-oziyr — when the frame-picker has restricted the
         ;; inspectable surface, the step walk MUST honour that
         ;; restriction so [◀ ▶ ⏭] / j / k step through the picker's
         ;; cascades only, matching what the user sees in L2.
         focusable  (if slot-frame
                      (filterv #(= slot-frame (:frame %)) focusable*)
                      focusable*)
         ;; rf2-s0s5x Phase A: resolve current-id through the same
         ;; composer the spine sub uses, so in LIVE mode `j` steps
         ;; back from the CURRENT head rather than from a stale
         ;; stored id.
         current-id (:dispatch-id (compose-focus (get db :focus) cascades))
         ;; rf2-a1z3b — when a focus-set is active, step skips past
         ;; out-of-focus cascades to the next/prev in-focus row. When
         ;; no focus-set is active, fall through to the plain
         ;; step-dispatch-id walk so the existing nav semantics are
         ;; preserved exactly.
         focus-set  (get db :focus-set)
         new-id     (if focus-set
                      (fh/step-in-focus-id focusable focus-set current-id delta)
                      (step-dispatch-id focusable current-id delta))
         head-id    (head-dispatch-id focusable)]
     (if (or (nil? new-id)
             ;; rf2-fzbrw — boundary no-op. Stepping past either edge
             ;; resolved to the same id we already hold; return db
             ;; unchanged so the ribbon's `:disabled` contract is
             ;; honoured at the reducer layer too. A keyboard j/k at
             ;; the edge has no observable effect.
             (and (some? current-id) (= new-id current-id)))
       db
       (let [new-mode (if (= new-id head-id) :live :retro)
             cascade  (cascade-by-id focusable new-id)
             frame-id (:frame cascade)
             epoch-id (epoch-id-for-cascade epoch-history new-id)]
         (cond-> db
           true     (update :focus (fnil assoc {})
                            :dispatch-id new-id
                            :epoch-id    epoch-id
                            :mode new-mode
                            :previewing? false
                            :paused? false)
           frame-id (assoc-in [:focus :frame] frame-id)
           true     (assoc :selected-dispatch-id new-id
                           :selected-epoch-id    epoch-id
                           :selected-dispatch    (cond-> {:dispatch-id new-id}
                                                   frame-id (assoc :frame frame-id)))))))))

(defn follow-head-reducer
  "Pure reducer for `:rf.causa/follow-head`. Snaps the spine to LIVE,
  clears the pinned id (`:dispatch-id nil` means 'track head'), and
  clears `:paused?` so the LIVE buffer flow resumes. Also clears the
  legacy `:selected-dispatch-id` / `:selected-epoch-id` slots in
  lockstep so the event-detail / app-db / time-travel panels return
  to their LIVE-tracking landing views."
  [db]
  (-> db
      (update :focus (fnil assoc {})
              :dispatch-id nil
              :epoch-id    nil
              :mode :live
              :paused? false
              :previewing? false)
      (dissoc :selected-dispatch :selected-dispatch-id :selected-epoch-id)))

(defn toggle-live-pause-reducer
  "Pure reducer for `:rf.causa/toggle-live-pause`. Toggles `:paused?`
  inside the `:focus` slot. Mode stays `:live` — the LIVE buffer
  continues collecting; only auto-scrolling stops. When already in
  `:retro`, toggling is a no-op (the Space key has no meaning when
  the user has already pinned an older row — they would press `L` to
  resume LIVE in that case)."
  [db]
  (if (= :retro (get-in db [:focus :mode]))
    db
    (update-in db [:focus :paused?] not)))

(defn set-frame-reducer
  "Pure reducer for `:rf.causa/set-frame <frame-id>`. Writes `:frame`
  into the `:focus` slot and clears `:dispatch-id` so the spine snaps
  to the new frame's head."
  [db frame-id]
  (-> db
      (update :focus (fnil assoc {})
              :frame frame-id
              :dispatch-id nil
              :mode :live)))

(defn preview-cascade-reducer
  "Pure reducer for `:rf.causa/preview-cascade <id>`. Sets `:previewing?
  true` and writes `:dispatch-id` transiently. nil `id` clears the
  preview without changing the committed selection."
  [db dispatch-id]
  (if (nil? dispatch-id)
    (assoc-in db [:focus :previewing?] false)
    (-> db
        (assoc-in [:focus :previewing?] true)
        (assoc-in [:focus :dispatch-id] dispatch-id))))

;; ---- focus-set reducers (rf2-a1z3b) -------------------------------------

(defn set-focus-reducer
  "Pure reducer for `:rf.causa/set-focus`. Writes a focus-set descriptor
  `{:dimension <kw> :value <v> :pivot-id <dispatch-id>}` into the
  `:focus-set` slot. The pivot id is the dispatch-id of the cascade
  whose gutter was clicked — the L2 row uses it to render the filled
  `⦿` pivot marker (vs the open `⦿` for other in-focus rows).

  When the same pivot+dimension combo is set twice (gutter click on
  the existing pivot), returns db with the slot CLEARED — the toggle-
  off contract per the gesture model. Comparing dimension+value
  (not pivot-id alone) means clicking a DIFFERENT in-focus row's
  gutter rebuilds focus around that new pivot rather than toggling
  off; only clicking the SAME pivot toggles.

  Pure data; JVM-runnable."
  [db dimension value pivot-id]
  (let [current (get db :focus-set)]
    (if (and current
             (= dimension (:dimension current))
             (= value (:value current))
             (= pivot-id (:pivot-id current)))
      (dissoc db :focus-set)
      (assoc db :focus-set {:dimension dimension
                            :value     value
                            :pivot-id  pivot-id}))))

(defn clear-focus-reducer
  "Pure reducer for `:rf.causa/clear-focus`. Drops the `:focus-set` slot
  unconditionally. Wired from the Esc keybind, the ribbon chip's `✕`,
  and a body-click on an out-of-focus row."
  [db]
  (dissoc db :focus-set))

;; ---- registration --------------------------------------------------------

(defn db->cascades
  "Read the cascade vector by re-running the projection against the
  Causa app-db's `:trace-buffer` slot (falling back to the trace-bus
  atom for pre-mount callers). Used by the step events that need to
  walk the cascade list inside an event-db handler — the equivalent
  reactive path inside the `:rf.causa/focus` sub uses the
  `:rf.causa/cascades` chain.

  Public so legacy spine-shim events (e.g. `:rf.causa/select-
  dispatch-id` in event_detail.cljs) can reuse the same projection
  when they need head-id (rf2-xzzih)."
  [db]
  (let [buffer (or (get db :trace-buffer) (trace-bus/buffer))]
    (projection/group-cascades buffer)))

(defn- db->epoch-history
  "Read the Causa app-db's `:epoch-history` slot — the per-frame
  epoch ring buffer (per `tools/causa/spec/018-Event-Spine.md` §5.2)
  used to resolve `:dispatch-id → :epoch-id` for spine focus events.
  Empty vector when the slot is absent (cold start, pre-mount)."
  [db]
  (get db :epoch-history []))

(defn install!
  "Idempotent install — register the `:rf.causa/focus` sub + its
  driving events. Called from `registry.cljs`'s
  `register-causa-handlers!` fan-out."
  []
  ;; ---- :rf.causa/focus — the spine sub ---------------------------------
  ;;
  ;; Composes the stored `:focus` slot with the live cascade list so
  ;; `:head?` is derived (never stored). The dependency on
  ;; `:rf.causa/cascades` means the spine recomputes whenever a new
  ;; cascade lands, which is what gives LIVE mode its auto-tracking
  ;; behaviour: a fresh head cascade re-derives `:head?` to true and
  ;; (in LIVE) snaps the effective `:dispatch-id` forward.
  (rf/reg-sub :rf.causa/focus-slot
    (fn [db _query]
      (get db :focus)))

  (rf/reg-sub :rf.causa/focus
    :<- [:rf.causa/focus-slot]
    :<- [:rf.causa/cascades]
    (fn [[focus cascades] _query]
      (compose-focus focus cascades)))

  ;; ---- events ----------------------------------------------------------

  (rf/reg-event-db :rf.causa/focus-cascade
    (fn [db [_ dispatch-id frame-id]]
      (let [cascades (db->cascades db)
            head-id  (focusable-head-id cascades)
            epoch-id (epoch-id-for-cascade (db->epoch-history db) dispatch-id)]
        (focus-cascade-reducer db dispatch-id frame-id epoch-id head-id))))

  (rf/reg-event-db :rf.causa/focus-cascade-prev
    (fn [db _event]
      (focus-step-reducer db (db->cascades db) (db->epoch-history db) -1)))

  (rf/reg-event-db :rf.causa/focus-cascade-next
    (fn [db _event]
      (focus-step-reducer db (db->cascades db) (db->epoch-history db) +1)))

  (rf/reg-event-db :rf.causa/follow-head
    (fn [db _event]
      (follow-head-reducer db)))

  (rf/reg-event-db :rf.causa/toggle-live-pause
    (fn [db _event]
      (toggle-live-pause-reducer db)))

  (rf/reg-event-db :rf.causa/set-frame
    (fn [db [_ frame-id]]
      (set-frame-reducer db frame-id)))

  (rf/reg-event-db :rf.causa/preview-cascade
    (fn [db [_ dispatch-id]]
      (preview-cascade-reducer db dispatch-id)))

  ;; ---- focus-set (rf2-a1z3b) -----------------------------------------
  ;;
  ;; The focus-set is a lens NOT a filter — the L2 list keeps rendering
  ;; every cascade, but rows that don't match the focus-set descriptor
  ;; dim to ~40% opacity and the `[◀]` / `[▶]` nav buttons skip past
  ;; them to the next/prev in-focus row. The focus-set rides centrally
  ;; in the spine slot so cross-panel triggers (Machines panel click →
  ;; focus-set on a machine; managed-fx row click → focus-set on the
  ;; HTTP correlation id) all flow through the same write surface.

  (rf/reg-sub :rf.causa/focus-set
    (fn [db _query]
      (get db :focus-set)))

  (rf/reg-event-db :rf.causa/set-focus
    (fn [db [_ dimension value pivot-id]]
      (set-focus-reducer db dimension value pivot-id)))

  (rf/reg-event-db :rf.causa/clear-focus
    (fn [db _event]
      (clear-focus-reducer db)))

  nil)
