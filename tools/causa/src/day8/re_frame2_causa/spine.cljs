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
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- pure helpers --------------------------------------------------------

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
  `toggle-live-pause`) snaps back to head."
  [focus cascades]
  (let [head-id    (head-dispatch-id cascades)
        slot-id    (:dispatch-id focus)
        slot-frame (:frame focus)
        paused?    (boolean (:paused? focus))
        mode       (or (:mode focus)
                       (if (or (nil? slot-id) (= slot-id head-id))
                         :live
                         :retro))
        eff-id  (cond
                  ;; Live + unpaused — track head, regardless of slot-id.
                  ;; This is the rf2-s0s5x Phase A change: previously the
                  ;; stored slot-id won here, so once `focus-step-reducer`
                  ;; landed on head the slot pinned to that id and never
                  ;; auto-advanced as newer cascades arrived. Pre-alpha
                  ;; the LIVE pill's contract is unambiguous — head IS
                  ;; the focus.
                  (and (= :live mode) (not paused?))
                  head-id
                  ;; Live but paused — slot-id wins (frozen inspection);
                  ;; if the stored id has been evicted from the buffer,
                  ;; snap to head so the inspector doesn't dangle on a
                  ;; nonexistent cascade.
                  (and (= :live mode) paused?)
                  (if (cascade-by-id cascades slot-id slot-frame) slot-id head-id)
                  ;; Retro — slot-id wins UNCONDITIONALLY (even when
                  ;; evicted). The event-detail panel's orphaned-state
                  ;; branch surfaces "this id is no longer in the
                  ;; buffer" copy off the evicted slot-id; auto-snapping
                  ;; to head in retro would hide that signal. Empty
                  ;; slot-id (initial state) does snap to head per §4
                  ;; Defaults.
                  (nil? slot-id)
                  head-id
                  :else
                  slot-id)
        cascade (cascade-by-id cascades eff-id slot-frame)]
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
  `:dispatch-id` into the `:focus` slot, flips to `:retro` mode, and
  shims the legacy `:selected-dispatch-id` / `:selected-dispatch`
  slots so the existing event-detail / causality / machine-inspector
  panels keep rendering without per-panel change."
  [db dispatch-id frame-id]
  (cond-> db
    true       (update :focus (fnil assoc {})
                       :dispatch-id dispatch-id
                       :mode :retro
                       :previewing? false)
    frame-id   (assoc-in [:focus :frame] frame-id)
    ;; Legacy shim — panels reading these slots stay live.
    true       (assoc :selected-dispatch-id dispatch-id
                      :selected-dispatch    (cond-> {:dispatch-id dispatch-id}
                                              frame-id (assoc :frame frame-id)))))

(defn focus-step-reducer
  "Pure reducer for `:rf.causa/focus-cascade-prev` / `-next`. Steps
  the `:dispatch-id` through the cascade vector by `delta` (-1 or
  +1), updates the legacy shim slot, and flips mode based on the
  step's outcome — stepping back from head → :retro; stepping
  forward back to head → :live (the user has scrubbed home).

  Per rf2-s0s5x Phase A — `compose-focus` now treats `:live` mode as
  always tracking head, so the stored slot-id can lag the actual
  focused cascade. Compute `current-id` through the same composer so
  pressing `j` (prev) from LIVE steps back one from the CURRENT head
  rather than from a stale stored id."
  [db cascades delta]
  (let [current-id (:dispatch-id (compose-focus (get db :focus) cascades))
        new-id     (step-dispatch-id cascades current-id delta)
        head-id    (head-dispatch-id cascades)
        new-mode   (if (= new-id head-id) :live :retro)
        cascade    (cascade-by-id cascades new-id)
        frame-id   (:frame cascade)]
    (cond-> db
      true     (update :focus (fnil assoc {})
                       :dispatch-id new-id
                       :mode new-mode
                       :previewing? false
                       :paused? false)
      frame-id (assoc-in [:focus :frame] frame-id)
      true     (assoc :selected-dispatch-id new-id
                      :selected-dispatch    (cond-> {:dispatch-id new-id}
                                              frame-id (assoc :frame frame-id))))))

(defn follow-head-reducer
  "Pure reducer for `:rf.causa/follow-head`. Snaps the spine to LIVE,
  clears the pinned id (`:dispatch-id nil` means 'track head'), and
  clears `:paused?` so the LIVE buffer flow resumes. Also clears the
  legacy `:selected-dispatch-id` so the event-detail panel returns to
  the cascade-list landing view."
  [db]
  (-> db
      (update :focus (fnil assoc {})
              :dispatch-id nil
              :mode :live
              :paused? false
              :previewing? false)
      (dissoc :selected-dispatch :selected-dispatch-id)))

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

;; ---- registration --------------------------------------------------------

(defn- db->cascades
  "Read the cascade vector by re-running the projection against the
  Causa app-db's `:trace-buffer` slot (falling back to the trace-bus
  atom for pre-mount callers). Used by the step events that need to
  walk the cascade list inside an event-db handler — the equivalent
  reactive path inside the `:rf.causa/focus` sub uses the
  `:rf.causa/cascades` chain."
  [db]
  (let [buffer (or (get db :trace-buffer) (trace-bus/buffer))]
    (projection/group-cascades buffer)))

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
      (focus-cascade-reducer db dispatch-id frame-id)))

  (rf/reg-event-db :rf.causa/focus-cascade-prev
    (fn [db _event]
      (focus-step-reducer db (db->cascades db) -1)))

  (rf/reg-event-db :rf.causa/focus-cascade-next
    (fn [db _event]
      (focus-step-reducer db (db->cascades db) +1)))

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

  nil)
