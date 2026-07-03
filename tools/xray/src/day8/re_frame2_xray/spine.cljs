(ns day8.re-frame2-xray.spine
  "The single-axis spine substrate (rf2-adve5).

  ## rf2-r9lyy — `:ungrouped` opt-in surface

  By default the spine strips the `:ungrouped` pseudo-event-bundle bucket
  from every walk (`focusable-event-bundles`) so the bucket is never a
  focus target — pinning to it degrades the L4 panels into an
  aggregate look (rf2-fzbrw). Mike's 2026-05-19 closure of rf2-q60yf
  flipped this from a hard contract to an opt-in: the new
  `:settings/show-ungrouped?` knob (defaults `false`) reveals the
  bucket in L2 and lets the user click it to focus. When the flag
  is `true`, the spine's helpers/reducers include `:ungrouped` in
  their walks; when `false`, the existing strict behaviour is
  preserved. Every public arity that previously walked
  `focusable-event-bundles` gains an additive arity that takes the flag.

  Per `tools/xray/spec/018-Event-Spine.md` §6 Spine binding the spine
  sub `:rf.xray/focus` is the one axis every dependent surface reads
  from. When a user clicks a row, EVERY dependent surface (count
  badges, gutter glyph, detail panel content, mode pill) rebinds via
  the spine — no panel maintains its own selection state.

  ## Shape

      {:dispatch-id <id-or-nil>     ; event-bundle root the user focused on
       :epoch-id    <id-or-nil>     ; epoch the event-bundle settled in
       :frame       <frame-id>      ; frame the event-bundle ran in
       :mode        :live | :retro  ; :live tracks head; :retro pins
       :head?       <bool>          ; true when :dispatch-id is latest
       :previewing? <bool>          ; true while user hovers without click
       :paused?     <bool>}         ; true when LIVE buffer flow paused

  ## Storage

  The spine slot lives in Xray's app-db at `:focus`. The
  `:rf.xray/focus` sub composes the slot with the live event-bundle list
  (via `:rf.xray/event-bundles`) so `:head?` and the effective
  `:dispatch-id` are always derived from the current event-bundle vector —
  never stale.

  ## No mirror slots — focus is the single source of truth

  Spec 018 §6 collapses per-panel selection into the spine. There are
  no mirror slots: the App-DB-diff / Views panels follow the focused
  epoch through `[:focus :epoch-id]` (surfaced by `:rf.xray/focus` →
  `:rf.xray/focus-epoch-id` → `app_db_diff_subs`), and the cross-panel
  `:rf.xray/focused-event-bundle-detail` composite (relocated to `registry.cljs`
  post rf2-5gl5r — the retired Event/Handler panel originally owned it;
  renamed off the retired-panel name per rf2-7ed9ms)
  reads focus directly off the spine. The former `:selected-dispatch-id`
  / `:selected-dispatch` / `:selected-epoch-id` mirror slots are all
  gone (the last, `:selected-epoch-id`, was retired by rf2-uy7nz once
  every production consumer had migrated to the spine focus epoch via
  rf2-70tkv) — there is no longer any dual-write."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.panels.common-helpers :as common]
            [day8.re-frame2-xray.self-noise :as self-noise]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- pure helpers --------------------------------------------------------

(defn focusable-event-bundles
  "Per rf2-fzbrw — the spine's invariant is 'one focused event at a
  time'. The `:ungrouped` bucket produced by `projection/group-by-event`
  for registry-time emits / frame lifecycle outside a drain / REPL
  evals carries no event vector and is therefore NOT a valid focus
  target BY DEFAULT: pinning to it leaves every epoch / app-db /
  views panel with no event-bundle to render, which degrades into the
  unwanted 'all subs / all handlers' aggregate look.

  Strip the bucket before any spine walk so:
    - the head/tail boundary predicates align with what the user sees
      in the L2 event list (which already filters via
      `shell/event-bundle-has-event?`);
    - stepping past a real event cannot land focus on `:ungrouped`;
    - `compose-focus` cannot resolve an effective dispatch-id to
      `:ungrouped` for a stored slot that was already pointing there.

  ## rf2-r9lyy — opt-in inclusion

  Mike 2026-05-19 closure of rf2-q60yf: pass `show-ungrouped? true`
  (the 2-arity) to KEEP the bucket. Users debugging SSR / REPL /
  registry-time flows opt in via Settings → General → Power user
  → 'Show :ungrouped pseudo-event-bundle events in L2'. When the flag
  is on, the bucket appears as a focusable target so clicking it
  populates the downstream panels with its trace events.

  Pure data; JVM-runnable; idempotent (re-filtering a cleaned vector
  is a no-op)."
  ([event-bundles]
   (focusable-event-bundles event-bundles false))
  ([event-bundles show-ungrouped?]
   (if show-ungrouped?
     (vec event-bundles)
     (filterv #(not= :ungrouped (:dispatch-id %)) event-bundles))))

(defn head-event-bundle
  "The latest event-bundle in the projected list — the event-bundle whose
  `:dispatch-id` is the spine's 'head'. Returns nil when the list is
  empty (cold start; buffer-cleared). Event bundles are sorted oldest-first
  (per `re-frame.trace.projection/group-by-event`), so the last entry
  is the head."
  [event-bundles]
  (last event-bundles))

(defn head-dispatch-id
  "The `:dispatch-id` of the head event-bundle, or nil."
  [event-bundles]
  (:dispatch-id (head-event-bundle event-bundles)))

(defn focusable-head-id
  "The `:dispatch-id` of the head focusable event-bundle — i.e. the latest
  row the user actually sees in the L2 event list. Returns nil when
  no focusable event-bundles exist.

  Used for click-on-head detection (rf2-xzzih): clicking the visible
  head event should stay LIVE; the raw `head-dispatch-id` would treat
  an `:ungrouped` bucket as the head if it sorted last, which the
  user never sees as a focusable row.

  rf2-r9lyy — the 2-arity threads `show-ungrouped?` through
  `focusable-event-bundles` so the head-detection also lights up the
  bucket when the user has opted in."
  ([event-bundles]
   (focusable-head-id event-bundles false))
  ([event-bundles show-ungrouped?]
   (head-dispatch-id (focusable-event-bundles event-bundles show-ungrouped?))))

(defn focusable-head-frame-id
  "The `:frame` of the head focusable event-bundle — i.e. the frame whose
  most recent event is the latest L2-visible row. Returns nil when no
  focusable event-bundle exists (cold start; buffer-cleared; only the
  `:ungrouped` bucket present).

  Used at first-mount seeding time (rf2-boyc2): `compose-focus` derives
  the panel-observed frame from this same head event-bundle, so seeding the
  Xray app-db's `:target-frame` + `:epoch-history` from the same axis
  closes the initial-mount race where the App-DB panel renders the
  boot empty-state for `:cart-frame` (the observed frame) because
  `:epoch-history` was seeded from `:rf/default` (the legacy default).
  Picker-driven `set-frame-reducer` already aligns the same two axes
  on a frame change; this helper extends that alignment to mount.

  Mount-seed callers stay on the 1-arity (which always strips
  `:ungrouped`) because the bucket carries `:frame nil` — seeding
  `:target-frame nil` is never useful regardless of the
  `:show-ungrouped?` knob."
  [event-bundles]
  (:frame (head-event-bundle (focusable-event-bundles event-bundles))))

(defn event-bundle-by-id
  "Find an event-bundle in `event-bundles` by its `:dispatch-id`. Returns nil when
  no match (id refers to an evicted event-bundle, or a fresh session).

  The 3-arity prefers an event-bundle matching `:frame` when supplied —
  multiple event-bundles can share a dispatch-id across frames (Spec 002
  §Frame isolation + rf2-g6ih4: dispatch-id uniqueness is per-frame,
  not process-global). When no frame-matching event-bundle exists the
  lookup falls back to a dispatch-id-only match so a stale stored
  frame (e.g. a user-frame the event-bundle was re-emitted from) doesn't
  hide an otherwise-valid event-bundle — the event-bundle's own `:frame` is
  treated as authoritative."
  ([event-bundles dispatch-id]
   (event-bundle-by-id event-bundles dispatch-id nil))
  ([event-bundles dispatch-id frame-id]
   (when dispatch-id
     (or (when frame-id
           (some #(when (and (= dispatch-id (:dispatch-id %))
                             (= frame-id (:frame %)))
                    %)
                 event-bundles))
         (some #(when (= dispatch-id (:dispatch-id %)) %)
               event-bundles)))))

(defn event-bundle-by-focus
  "Strict event-bundle lookup keyed by the composed spine `focus` map.

  Unlike `event-bundle-by-id`'s fallback (which treats the event-bundle's own
  `:frame` as authoritative and returns a dispatch-id-only match when
  no frame-specific event-bundle exists), this resolves an event-bundle for a
  panel read where `focus` may carry a `:frame`. When it does, the
  match must agree on BOTH `:frame` and `:dispatch-id`; if no event-bundle
  in that frame carries the focused dispatch-id, the result is nil
  rather than a same-id event-bundle from a foreign frame.

  Dispatch ids are unique only WITHIN a frame (Spec 002 §Frame
  isolation + rf2-g6ih4); the framework's trace projection groups
  event-bundles by `[frame dispatch-id]` and intentionally emits two
  event-bundle records when the same id occurs in two frames (see
  `re-frame.trace.projection/grouped-event-bundles`). Panel reads that key
  by dispatch-id alone can surface records/overlays/status from the
  first matching frame while focus is actually on another — this
  helper is the frame-strict path those reads use (rf2-bz7flo).

  When `focus` has no `:frame` (single-frame / pre-frame-set focus),
  the lookup degrades to a plain dispatch-id match."
  [event-bundles focus]
  (let [dispatch-id (:dispatch-id focus)
        frame       (:frame focus)]
    (when dispatch-id
      (if frame
        (some #(when (and (= dispatch-id (:dispatch-id %))
                          (= frame (:frame %)))
                 %)
              event-bundles)
        (some #(when (= dispatch-id (:dispatch-id %)) %) event-bundles)))))

(defn step-event-bundle
  "Step by `delta` (-1 for prev, +1 for next) from the current
  position through `event-bundles` and return the whole stepped event-bundle
  RECORD (carrying `:frame`).

  - nil `current-id` (no focus yet) starts from the head.
  - When the current position is not in `event-bundles` (was evicted), step
    from head.
  - Bounds-clamped — stepping past either edge stays at the edge.
  - Returns nil only when `event-bundles` is empty.

  rf2-bz7flo — the spine step path needs the stepped row's FRAME, not
  just its dispatch-id, because dispatch ids collide across frames.
  Resolving the stepped event-bundle back out of `event-bundles` by id alone
  (the previous `(event-bundle-by-id focusable (step-dispatch-id …))`
  shape) could land on a foreign frame's same-id event-bundle at a
  different index. Walking by index keeps frame + id in lockstep with
  the row the user actually stepped to.

  rf2-xj3kbn — the CURRENT-position lookup must be frame-strict too.
  Dispatch ids are unique only WITHIN a frame (Spec 002 §Frame
  isolation + rf2-g6ih4); the framework projection emits a separate
  event-bundle record per `[frame dispatch-id]`, so the same id can occur
  in two frames at two indices. When the focus carries a `:frame`
  (the live/composed head's frame, or a retro pin's frame), the
  current index MUST match BOTH `[frame dispatch-id]` — otherwise a
  same-id event-bundle in an EARLIER frame is found first and the walk
  steps from the wrong row (a false boundary no-op, or a step into a
  foreign neighbour). Frameless focus (single-frame / pre-frame-set)
  degrades to the id-only match.

  Two arities:
  - `[event-bundles current-id delta]` — id-only current lookup (frameless
    callers / `step-dispatch-id`'s thin projection).
  - `[event-bundles current-frame current-id delta]` — frame-strict: when
    `current-frame` is non-nil the current index matches
    `[current-frame current-id]`, falling back to id-only when no
    frame-matching row exists (a stale stored frame must not hide an
    otherwise-valid current row)."
  ([event-bundles current-id delta]
   (step-event-bundle event-bundles nil current-id delta))
  ([event-bundles current-frame current-id delta]
   (let [n (count event-bundles)]
     (when (pos? n)
       (let [current-idx (or (when current-frame
                               (some (fn [[idx c]]
                                       (when (and (= current-id (:dispatch-id c))
                                                  (= current-frame (:frame c)))
                                         idx))
                                     (map-indexed vector event-bundles)))
                             (some (fn [[idx c]]
                                     (when (= current-id (:dispatch-id c)) idx))
                                   (map-indexed vector event-bundles)))
             base-idx    (or current-idx (dec n))
             new-idx     (-> (+ base-idx delta)
                             (max 0)
                             (min (dec n)))]
         (nth event-bundles new-idx))))))

(defn step-dispatch-id
  "Compute the new `:dispatch-id` when stepping by `delta` (-1 for
  prev, +1 for next) from `current-id` through `event-bundles`. Thin
  `:dispatch-id` projection of `step-event-bundle` (which see for the
  step semantics). Returns nil only when `event-bundles` is empty."
  [event-bundles current-id delta]
  (some-> (step-event-bundle event-bundles current-id delta) :dispatch-id))

(defn epoch-id-for-event-bundle
  "Return the `:epoch-id` of the epoch record in `epoch-history` whose
  settling event-bundle is `dispatch-id`, or nil when no match is found
  (the event-bundle is mid-build, was evicted from the ring, or the focus
  pins `:ungrouped`).

  Per spec/018 §6 Spine events the spine sub `:rf.xray/focus` carries
  both `:dispatch-id` (event-bundle root, opaque router counter) and
  `:epoch-id` (per-frame settling counter, primary key into the epoch
  ring buffer). The two are semantically the same identity — a
  event-bundle settles into exactly one epoch record — but they're
  separate counter spaces, so the linkage is a per-event-bundle lookup
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

(defn dispatch-id-for-epoch
  "Return the settling `:dispatch-id` of the epoch record in
  `epoch-history` whose `:epoch-id` is `epoch-id`, or nil when no
  match is found.

  Reverse of `epoch-id-for-event-bundle`. Used by `:rf.xray/focus-epoch`
  (rf2-5qp4g) to pivot focus by epoch-id (the primary key into the
  epoch ring) when the caller — the Epoch panel's DISPATCH step's
  parent-epoch navigation — only has the epoch-id and needs to drive
  the spine's `:focus-event-bundle-reducer` which keys on dispatch-id.

  Pure data; JVM-runnable."
  [epoch-history epoch-id]
  (when (and epoch-id (seq epoch-history))
    (some (fn [record]
            (when (= epoch-id (:epoch-id record))
              (common/dispatch-id-of-epoch record)))
          epoch-history)))

(defn compose-focus
  "Derive the public `:rf.xray/focus` map from a stored `:focus` slot
  and the live event-bundle vector. Always re-derives `:head?` and the
  effective `:dispatch-id` from the event-bundle list — never trusts a
  stale stored value.

  In `:live` mode an unset (or evicted) dispatch-id snaps to head so
  a fresh session opens already pointing at the latest event-bundle per
  §4 Defaults.

  ## Live auto-follow (rf2-s0s5x Phase A)

  In `:live` mode the effective `:dispatch-id` is ALWAYS the current
  head — even when a stored `slot-id` exists and is still in the
  buffer. Previously the composer honoured the stored slot-id once it
  was set (so `focus-step-reducer` landing on the then-head left
  `:dispatch-id` pinned to that id even after newer event-bundles arrived);
  the user observed focus 'stuck' even though the LIVE pill said it
  was tracking. Spec/018 §3 says the LIVE pill auto-tracks head; this
  composer is the canonical site that derives that behaviour.

  `:paused?` suspends the auto-track — when paused, the focus stays
  on the stored slot-id so the user can inspect an event-bundle without
  losing it as new traffic arrives. Resuming (`follow-head` /
  `toggle-live-pause`) snaps back to head.

  ## No-aggregate-state invariant (rf2-fzbrw)

  The spine walks `focusable-event-bundles` (the `:ungrouped` bucket is
  stripped) — so the head/tail boundary and any retro-pin operation
  resolves against the user-visible event-bundle list. The composer
  additionally snaps to head whenever the stored slot is nil OR
  points at `:ungrouped` OR points at an evicted event-bundle. Combined
  with the LIVE auto-follow above this makes 'buffer non-empty +
  effective :dispatch-id nil' structurally unreachable — the L4
  panels never have to handle the 'no event-bundle' degraded render.

  ## Frame-picker scoping (rf2-oziyr)

  When `slot-frame` is non-nil (the frame-picker has restricted the
  inspectable surface to one frame), the head walk runs over
  event-bundles restricted to that frame so `:live` mode auto-tracks the
  picked frame's head and `[◀ ▶ ⏭]` boundaries respect the picker.
  Without this scoping the composer would pick the global head
  (whatever frame fired the latest event), which under multi-frame
  apps drifts focus off the picker's frame on every cross-frame
  dispatch.

  ## Frame-strict head-frame resolution (rf2-xj3kbn)

  The effective event-bundle is resolved as a whole RECORD per branch — the
  head-tracking / snap-to-head branches use `head-event-bundle` directly
  (the last focusable row) rather than re-resolving `eff-id` through
  the id-only `event-bundle-by-id`. With no `slot-frame` stored, an id-only
  lookup would return the FIRST same-id event-bundle's `:frame`, so when the
  head's `:dispatch-id` also occurs in an earlier frame the composed
  focus would report `[head-id, earlier-frame]` — a mismatched pair
  that mis-steers `focus-step-reducer`'s current-position lookup (the
  L2 `[◀ ▶]` controls then false-no-op or step from a foreign row).
  Taking the frame from the head record keeps `:dispatch-id` + `:frame`
  in lockstep. RETRO / LIVE-paused branches still resolve by id (the
  user pinned a specific id, possibly in a stored `slot-frame`).

  ## rf2-r9lyy — `:show-ungrouped?` opt-in

  Pass `show-ungrouped? true` via the 3-arity to include the
  `:ungrouped` bucket as a valid focus target. When on:
    - the head walk considers `:ungrouped` as a possible head;
    - a stored `:dispatch-id :ungrouped` is pinnable (does not snap
      to head).
  When off (default 2-arity), the existing strict behaviour applies.

  ## rf2-70tkv — `:epoch-id` auto-follow in LIVE mode

  The 4-arity takes `epoch-history` (the per-frame epoch ring) and
  re-derives `:epoch-id` to the head event-bundle's settling epoch when in
  LIVE+unpaused mode, mirroring how `:dispatch-id` auto-tracks head.
  Pre-fix the composer always returned `(:epoch-id focus)` from the
  stored slot, so a previously-pinned epoch (set by clicking an L2
  row's epoch chip, by `:rf.xray/select-epoch`, by Time Travel
  scrubbing) stayed wired into the focus map even after the user
  resumed LIVE — every panel that pivots on focus `:epoch-id`
  (Views' focused-event-bundle-pair, Machine Inspector's focused-event
  lens, App-DB diff's selected-epoch chain via `[:focus :epoch-id]`)
  stayed frozen on the old epoch while `:dispatch-id` correctly tracked
  head. The 3-arity (legacy / pre-
  rf2-70tkv callers, test rigs that don't have the history handy)
  preserves the original behaviour: `:epoch-id` stays as stored. The
  reactive `:rf.xray/focus` sub in `install!` uses the 4-arity so
  every panel auto-tracks `:epoch-id` for free.

  RETRO + LIVE-paused continue to honour the stored `:epoch-id`:
  retro pins the event-bundle and its settling epoch in lockstep, and
  pausing LIVE is the explicit 'freeze inspection' gesture."
  ([focus event-bundles]
   (compose-focus focus event-bundles false nil))
  ([focus event-bundles show-ungrouped?]
   (compose-focus focus event-bundles show-ungrouped? nil))
  ([focus event-bundles show-ungrouped? epoch-history]
  (let [focusable* (focusable-event-bundles event-bundles show-ungrouped?)
        slot-frame (:frame focus)
        focusable  (if slot-frame
                     (filterv #(= slot-frame (:frame %)) focusable*)
                     focusable*)
        head-id    (head-dispatch-id focusable)
        slot-id    (:dispatch-id focus)
        paused?    (boolean (:paused? focus))
        ;; rf2-fzbrw — a slot pointing at nil OR the :ungrouped bucket
        ;; is NOT a valid focus pin BY DEFAULT. (Evicted ids are still
        ;; a valid pin: downstream panels surface an "epoch evicted"
        ;; placeholder off them, so we don't conflate "evicted" with
        ;; "never valid".) rf2-r9lyy — when `show-ungrouped?` is on,
        ;; `:ungrouped` IS pinnable so the user's click on the bucket
        ;; row sticks.
        slot-pinnable? (and slot-id
                            (or show-ungrouped?
                                (not= :ungrouped slot-id)))
        mode       (or (:mode focus)
                       (if (or (nil? slot-id) (= slot-id head-id))
                         :live
                         :retro))
        ;; rf2-xj3kbn — resolve the effective EVENT-BUNDLE RECORD per branch,
        ;; not a bare id that's then re-resolved by `event-bundle-by-id`
        ;; (which is id-only when `slot-frame` is nil and so can pick a
        ;; foreign frame's same-id row for the `:frame`). The head-track
        ;; / snap-to-head branches use `head-event-bundle` directly so the
        ;; composed focus's `:frame` is the ACTUAL head row's frame
        ;; (the last focusable event-bundle), keeping `:dispatch-id` + `:frame`
        ;; in lockstep even when the head id also occurs in an earlier
        ;; frame. The paused / retro branches still resolve by id (the
        ;; user pinned a specific id, possibly in a stored frame).
        head-event-bundle* (head-event-bundle focusable)
        ;; A RETRO pin (or a LIVE-paused pin) is the one case where the
        ;; effective id is the STORED slot-id even if its event-bundle has
        ;; been evicted (nil record) — those branches preserve the id
        ;; so the downstream "epoch evicted" placeholder renders. The
        ;; head-tracking branches never fall back to slot-id (an empty
        ;; buffer yields a nil head, which downstream treats as cold
        ;; start, NOT as the stale slot).
        retro-pin? (or (and (= :live mode) paused?)
                       (and (not (and (some? head-id) (not slot-pinnable?)))
                            (not (and (= :live mode) (not paused?)))))
        eff-event-bundle (cond
                  ;; rf2-fzbrw — slot is nil / :ungrouped while a
                  ;; focusable buffer exists. Snap to head regardless
                  ;; of mode so the unreachable "focus nil + buffer
                  ;; non-empty" state stays unreachable. (Evicted retro
                  ;; ids flow through to the :else branch so the
                  ;; orphaned-state UX survives.)
                  (and (some? head-id) (not slot-pinnable?))
                  head-event-bundle*
                  ;; Live + unpaused — track head, regardless of
                  ;; slot-id (rf2-s0s5x Phase A). Previously the
                  ;; stored slot-id won here, so once
                  ;; focus-step-reducer landed on the then-head the
                  ;; slot pinned to that id and never auto-advanced
                  ;; as newer event-bundles arrived. The LIVE pill's
                  ;; contract is unambiguous — head IS the focus.
                  (and (= :live mode) (not paused?))
                  head-event-bundle*
                  ;; Live + paused — slot-id wins (frozen inspection);
                  ;; if the stored id has been evicted from the
                  ;; buffer, snap to head so the inspector doesn't
                  ;; dangle on a nonexistent event-bundle.
                  (and (= :live mode) paused?)
                  (or (event-bundle-by-id focusable slot-id slot-frame)
                      head-event-bundle*)
                  ;; Retro — slot-id wins. Evicted ids resolve to a nil
                  ;; event-bundle record here; `eff-id` falls back to the
                  ;; stored slot-id below so the orphaned-state UX
                  ;; survives.
                  :else
                  (event-bundle-by-id focusable slot-id slot-frame))
        ;; rf2-xj3kbn — `eff-id` + the `:frame` both come from the
        ;; resolved event-bundle RECORD so they stay in lockstep with the
        ;; actual row (head / pinned), never an id-only re-resolution
        ;; that could borrow a foreign frame's same-id row. Only a
        ;; RETRO/paused pin whose event-bundle was evicted falls back to the
        ;; stored slot-id (nil event-bundle) — the head branches return the
        ;; head's own id (or nil for a cold-start empty buffer).
        eff-id  (or (:dispatch-id eff-event-bundle)
                    (when retro-pin? slot-id))
        event-bundle eff-event-bundle
        ;; rf2-70tkv — `:epoch-id` auto-follows head in LIVE+unpaused
        ;; mode when `epoch-history` is supplied (the reactive
        ;; `:rf.xray/focus` sub path). Mirrors the `eff-id` auto-
        ;; track above so every panel that pivots on focus
        ;; `:epoch-id` (Views, Machine Inspector, App-DB diff via
        ;; `[:focus :epoch-id]`) lights up the head event-bundle on every
        ;; new arrival instead of staying pinned to a stale stored
        ;; value. Retro / LIVE-paused / unsupplied-history
        ;; preserve the stored slot — those modes have explicit
        ;; pinning semantics.
        ;;
        ;; When the head event-bundle's settling epoch is NOT in
        ;; `epoch-history` (eff-id is on a frame other than the one
        ;; the history slot is keyed to — multi-frame apps where
        ;; the picker's frame and the head event-bundle's frame disagree;
        ;; epoch evicted from the ring) we return nil rather than
        ;; the stored slot. Panels uniformly treat nil as 'no pin,
        ;; use the head fallback' (App-DB Diff's `(peek history)`,
        ;; Views' `(dec (count history))`, Machine Inspector's
        ;; `(peek history)`) — keeping a stale stored id here would
        ;; resurrect the very freeze the auto-track was meant to
        ;; eliminate.
        eff-epoch-id (cond
                       (and (= :live mode) (not paused?)
                            (some? eff-id)
                            (some? epoch-history))
                       (epoch-id-for-event-bundle epoch-history eff-id)
                       :else
                       (:epoch-id focus))]
    {:dispatch-id eff-id
     :epoch-id    eff-epoch-id
     :frame       (or (:frame event-bundle) (:frame focus))
     :mode        mode
     :head?       (or (nil? eff-id)
                      (= eff-id head-id))
     :previewing? (boolean (:previewing? focus))
     :paused?     paused?})))

;; ---- pure reducers exposed for direct unit testing ----------------------

(defn focus-event-bundle-reducer
  "Pure reducer for the `:rf.xray/focus-event <id>` event. Writes
  `:dispatch-id` into the `:focus` slot, picks the spine `:mode`, and
  stamps `:epoch-id` (the event-bundle's settling epoch primary key per
  spec/018 §6 Spine events) — which the App-DB-diff / Views panels
  follow through `:rf.xray/focus` → `:rf.xray/focus-epoch-id`.

  ## Mode selection (rf2-xzzih)

  When the caller supplies `head-id` (the 5-arg arity) the reducer
  picks the new mode head-aware: clicking the head event keeps the
  spine LIVE so new arrivals continue to auto-advance; clicking any
  non-head event pins to RETRO. Without `head-id` (legacy 3-arg /
  4-arg arities) the mode defaults to RETRO — preserves the rf2-
  s0s5x Phase A contract for callers that don't yet pass the head.

  Per spec/018 §6 the spine sub `:rf.xray/focus` carries `:epoch-id`
  as a first-class slot; consumers (Views' focused-event-bundle-pair sub,
  App-db's selected-epoch-* sub chain) pivot on it. The 4-arg arity
  takes a resolved `epoch-id`; event handlers resolve it from the
  Xray `:epoch-history` slot via `epoch-id-for-event-bundle` before
  calling. The 3-arg arity (back-compat for callers that don't have
  the buffer handy) leaves `:epoch-id` nil — the focus sub still
  rebinds on `:dispatch-id`, but the epoch-keyed surfaces will not
  pivot until a 4-arg call lands."
  ([db dispatch-id frame-id]
   (focus-event-bundle-reducer db dispatch-id frame-id nil nil))
  ([db dispatch-id frame-id epoch-id]
   (focus-event-bundle-reducer db dispatch-id frame-id epoch-id nil))
  ([db dispatch-id frame-id epoch-id head-id]
   (let [mode (if (and head-id (= dispatch-id head-id)) :live :retro)]
     (cond-> db
       true       (update :focus (fnil assoc {})
                          :dispatch-id dispatch-id
                          :epoch-id    epoch-id
                          :mode        mode
                          :previewing? false)
       frame-id   (assoc-in [:focus :frame] frame-id)))))

(defn focus-step-reducer
  "Pure reducer for `:rf.xray/focus-event-prev` / `-next`. Steps
  the `:dispatch-id` through the event-bundle vector by `delta` (-1 or
  +1), resolves the event-bundle's settling `:epoch-id` from
  `epoch-history` (per spec/018 §6 Spine events) into the `:focus`
  slot, and flips mode based on the step's outcome — stepping
  back from head → :retro; stepping forward back to head → :live (the
  user has scrubbed home).

  Per rf2-s0s5x Phase A — `compose-focus` now treats `:live` mode as
  always tracking head, so the stored slot-id can lag the actual
  focused event-bundle. Compute `current-id` through the same composer so
  pressing `j` (prev) from LIVE steps back one from the CURRENT head
  rather than from a stale stored id.

  Per rf2-fzbrw the walk runs over `focusable-event-bundles` so the
  `:ungrouped` bucket (registry-time emits / lifecycle / REPL evals)
  is never a step target — pinning to it would degrade the L4 panels
  into the unwanted 'all subs / all handlers' aggregate look. When
  the buffer carries no focusable event-bundles, OR the step would land on
  the current focus (already at a boundary), the reducer returns `db`
  unchanged — a true no-op so `[<]` on the first event (or `[>]` on
  the latest event) cannot clear or shuffle focus.

  The 3-arg arity (back-compat for callers that don't have the epoch
  buffer handy) treats `epoch-history` as empty so `:epoch-id`
  resolves to nil. Production callers in `install!` go through the
  4-arg arity. rf2-r9lyy — the 5-arg arity threads
  `show-ungrouped?` so the step walk includes the `:ungrouped`
  bucket when the user has opted in."
  ([db event-bundles delta]
   (focus-step-reducer db event-bundles [] delta false))
  ([db event-bundles epoch-history delta]
   (focus-step-reducer db event-bundles epoch-history delta false))
  ([db event-bundles epoch-history delta show-ungrouped?]
   (let [slot-frame (get-in db [:focus :frame])
         focusable* (focusable-event-bundles event-bundles show-ungrouped?)
         ;; rf2-oziyr — when the frame-picker has restricted the
         ;; inspectable surface, the step walk MUST honour that
         ;; restriction so [◀ ▶ ⏭] / j / k step through the picker's
         ;; event-bundles only, matching what the user sees in L2.
         focusable  (if slot-frame
                      (filterv #(= slot-frame (:frame %)) focusable*)
                      focusable*)
         ;; rf2-s0s5x Phase A: resolve current focus through the same
         ;; composer the spine sub uses, so in LIVE mode `j` steps
         ;; back from the CURRENT head rather than from a stale
         ;; stored id. rf2-r9lyy — pass show-ungrouped? so the
         ;; composer's pinnability check honours the opt-in.
         current    (compose-focus (get db :focus) event-bundles show-ungrouped?)
         current-id (:dispatch-id current)
         ;; rf2-xj3kbn — the composed focus carries the resolved row's
         ;; `:frame`; thread it into the current-position lookup so the
         ;; walk starts from the RIGHT row when the focused dispatch-id
         ;; also occurs in another frame. Without the frame the id-only
         ;; lookup finds the first same-id event-bundle (an earlier frame's),
         ;; making prev/next a false boundary no-op or stepping from a
         ;; foreign row. When `slot-frame` already scoped `focusable` to
         ;; one frame the frame is redundant but harmless; the open
         ;; (slot-frame nil, multi-frame) walk is where it matters.
         current-frame (:frame current)
         ;; rf2-bz7flo — resolve the stepped row as an EVENT-BUNDLE record so its
         ;; `:frame` comes from the exact row stepped to. Re-resolving by
         ;; id alone (`event-bundle-by-id focusable new-id`) could pick a
         ;; foreign frame's same-id event-bundle when the step walk spans
         ;; frames (slot-frame unset, initial LIVE/head). frame + id stay
         ;; in lockstep with the row the user actually landed on.
         stepped    (step-event-bundle focusable current-frame current-id delta)
         new-id     (:dispatch-id stepped)
         new-frame  (:frame stepped)
         head-id    (head-dispatch-id focusable)]
     (if (or (nil? new-id)
             ;; rf2-fzbrw — boundary no-op. Stepping past either edge
             ;; resolved to the same row we already hold; return db
             ;; unchanged so the ribbon's `:disabled` contract is
             ;; honoured at the reducer layer too. A keyboard j/k at
             ;; the edge has no observable effect.
             ;;
             ;; rf2-xj3kbn — the no-op test is frame-strict too: a
             ;; genuine edge means the SAME `[frame dispatch-id]`
             ;; coordinate, not just the same id. When a same-id
             ;; event-bundle exists in another frame, stepping onto it is a
             ;; REAL move even though the id matches — comparing id
             ;; alone would swallow that step. Frameless focus (no
             ;; `current-frame`) degrades to the id-only edge test.
             (and (some? current-id)
                  (= new-id current-id)
                  (or (nil? current-frame)
                      (= new-frame current-frame))))
       db
       (let [new-mode (if (= new-id head-id) :live :retro)
             frame-id (:frame stepped)
             epoch-id (epoch-id-for-event-bundle epoch-history new-id)]
         (cond-> db
           true     (update :focus (fnil assoc {})
                            :dispatch-id new-id
                            :epoch-id    epoch-id
                            :mode new-mode
                            :previewing? false
                            :paused? false)
           frame-id (assoc-in [:focus :frame] frame-id)))))))

(defn follow-head-reducer
  "Pure reducer for `:rf.xray/follow-head`. Snaps the spine to LIVE,
  clears the pinned id (`:dispatch-id nil` means 'track head'), clears
  `:epoch-id` so the App-DB / Views panels return to their LIVE-tracking
  landing views, and clears `:paused?` so the LIVE buffer flow resumes."
  [db]
  (update db :focus (fnil assoc {})
          :dispatch-id nil
          :epoch-id    nil
          :mode :live
          :paused? false
          :previewing? false))

(defn toggle-live-pause-reducer
  "Pure reducer for `:rf.xray/toggle-live-pause`. Toggles `:paused?`
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
  "Pure reducer for `:rf.xray/set-frame <frame-id>`. Writes `:frame`
  into the `:focus` slot and clears `:dispatch-id` so the spine snaps
  to the new frame's head.

  ## rf2-ug1r6 + rf2-thodq — picker also drives `:target-frame` + reseeds
  ## `:epoch-history`

  The Xray app-db carries a single `:epoch-history` slot keyed by the
  legacy `:target-frame`. Every per-frame composite (App-DB tab's
  `:rf.xray/app-db-current+diff`; Reactive panel's focused-event-bundle
  trace projection (rf2-wyvf2); the machine-inspector scrubber) reads
  off that slot. Pre-fix the slot stayed on whatever
  `:target-frame` was at boot (`:rf/default`) even when the user picked
  a different frame in the ribbon picker — so:

    - App-DB tab's `:rf.xray/app-db-current+diff` resolves the focused
      epoch against the WRONG frame's history. If the boot target had
      no epochs the focused record is nil and the panel rendered the
      'app-db for :cart-frame is at the boot value. No diffs yet.'
      empty-state EVEN WITH a focused event-bundle (rf2-ug1r6). (rf2-p53m2 —
      this bullet previously named the pruned `:rf.xray/selected-epoch-
      diff`; the live read-model is the atomic current+diff sub.)
    - The Reactive panel's `:rf.xray/reactive-data` composite reads
      the same wrong-frame history; `focused-epoch-record` returns nil
      for the focused `:epoch-id`, `:has-event-bundle?` resolves to false,
      and the body renders 'No event focused.' EVEN WITH a focused
      event-bundle (rf2-thodq · pre-fix).

  Both bugs share the same root: the picker writes only `[:focus :frame]`
  but every per-frame composite reads off a slot keyed on a DIFFERENT
  axis. The fix aligns the two axes — the picker IS the user's 'observe
  this frame' gesture, identical in intent to `core/set-target-frame!`.

  This reducer now also writes `:target-frame` and re-seeds the
  `:epoch-history` slot from `epoch-history-for-frame` so every per-
  frame sub re-fires off the standard app-db-write reactive path with
  the correct frame's epochs. The pure 2-arg arity takes the resolved
  history as a parameter (JVM-runnable); the event handler in
  `install!` resolves it via `rf/epoch-history` at dispatch time."
  ([db frame-id]
   (set-frame-reducer db frame-id []))
  ([db frame-id epoch-history-for-frame]
   (-> db
       (update :focus (fnil assoc {})
               :frame frame-id
               :dispatch-id nil
               :mode :live)
       (assoc :target-frame  frame-id
              :epoch-history (vec epoch-history-for-frame)))))

(defn reseed-epoch-history-for-frame
  "Pure reducer: re-key the Xray app-db's per-frame `:epoch-history`
  slot (and the legacy `:target-frame` axis it is keyed on) onto
  `frame-id`, supplying that frame's resolved ring contents as
  `epoch-history-for-frame`.

  ## rf2-q8hvw — cross-frame L2-row clicks must re-seed the slot

  The `:epoch-history` slot is a SINGLE per-frame ring keyed on
  `:target-frame`. It is seeded at mount from the boot head frame and
  re-keyed only by the frame PICKER (`set-frame-reducer`) and the
  `:rf.xray/epoch-recorded` listener (which appends only when the
  recording frame IS the current `:target-frame`). With the picker
  UNTOUCHED the L2 list shows event-bundles from EVERY frame (the event-bundles
  sub is whole-buffer, frame-scoped only by the picker), so clicking a
  row for an event-bundle that settled in a NON-head frame leaves the slot on
  the boot frame's ring — `epoch-id-for-event-bundle` finds no match and the
  clicked event-bundle's settling epoch resolves to nil. Every epoch-keyed
  panel (App-DB diff, Views' focused-event-bundle-pair, Machine Inspector)
  then renders empty/stale for an event-bundle that genuinely settled an
  epoch. Sibling of the rf2-ug1r6 / rf2-thodq / rf2-lo28u
  attribution-gap class.

  The COMMITTED focus handlers (`:rf.xray/focus-event`,
  `:rf.xray/focus-epoch`) call this BEFORE resolving the clicked
  event-bundle's epoch-id so the resolution runs against the RIGHT frame's
  ring AND the per-frame composites re-bind to the now-selected frame.
  It only fires the write when `frame-id` differs from the current
  `:target-frame` — a same-frame click is a no-op (the slot already
  holds that frame's ring, kept fresh by `epoch-recorded`).

  `:rf.xray/preview-cascade` deliberately DOES NOT call this (rf2-uo0rc.5):
  a transient hover must not persist a cross-frame re-key. The preview
  handler instead resolves the previewed event-bundle's epoch against that
  frame's ring DIRECTLY (a read), leaving the committed `:target-frame`
  / `:epoch-history` untouched so the committed focus keeps resolving
  against the right ring after the hover ends.
  Unlike `set-frame-reducer` this DOES NOT touch the `:focus` slot:
  the focus mutation (dispatch-id / mode / epoch-id) stays the
  responsibility of `focus-event-bundle-reducer` et al.; this helper only
  aligns the per-frame history axis the epoch resolver reads from.

  The current target is the `:target-frame` slot, which is
  `defaults/default-target-frame` (EP-0002 rf2-bd4div: **nil =
  UNSELECTED**, NOT `:rf/default`) when absent — the same resolution
  `:rf.xray/epoch-recorded` / `:rf.xray/set-target-frame` apply. When the
  target is unselected, any real `frame-id` differs from nil, so a click
  re-keys the slot onto the clicked event-bundle's frame (the first cross-frame
  click out of the unselected state selects a target — exactly the
  picker's 'observe this frame' gesture).

  nil `frame-id` (the event-bundle's frame is unknown — e.g. the 3-arg
  focus path) is a no-op: there is no frame to re-key onto, and
  clobbering `:target-frame` to nil would orphan every per-frame sub.
  The event handlers resolve `epoch-history-for-frame` via
  `rf/epoch-history` at dispatch time, exactly as the picker path does.

  EP-0002 (rf2-bd4div) — when the target is UNSELECTED (`:target-frame`
  absent, now nil rather than the retired `:rf/default` default) and a
  history was seeded DIRECTLY (the `:rf.xray/sync-epoch-history`
  panel-gallery seed path, which writes `:epoch-history` without a
  `:target-frame`), a focus-event-bundle onto `frame-id` ADOPTS that frame as
  the target but PRESERVES the directly-seeded history — it does NOT
  clobber it with the (empty) framework ring. This keeps the same
  no-clobber contract the pre-EP `:rf/default`-default branch provided for
  the direct-seed case (the seed IS that frame's history; the gallery
  seeded it for the frame now being focused)."
  [db frame-id epoch-history-for-frame]
  (let [current-target (get db :target-frame defaults/default-target-frame)]
    (cond
      ;; No frame to re-key onto.
      (nil? frame-id) db
      ;; Same target already selected → already keyed on this frame.
      (= frame-id current-target) db
      ;; Unselected target with a directly-seeded history → adopt the
      ;; frame as target, preserve the seed (no framework-ring clobber).
      (and (nil? current-target)
           (seq (:epoch-history db)))
      (assoc db :target-frame frame-id)
      ;; Cross-frame re-key → switch target + load that frame's ring.
      :else
      (assoc db
             :target-frame  frame-id
             :epoch-history (vec epoch-history-for-frame)))))

(defn preview-cascade-reducer
  "Pure reducer for `:rf.xray/preview-cascade <id>`. A preview is a
  TRANSIENT hover overlay: it sets `:previewing? true` and writes the
  previewed `:dispatch-id` / `:epoch-id`, but it must NOT leave the
  committed selection perturbed once the hover ends. nil `id` clears the
  preview and RESTORES the committed selection.

  ## rf2-yng0y — write `:epoch-id` alongside `:dispatch-id`

  Pre-fix this reducer wrote ONLY `:dispatch-id`, leaving `:epoch-id`
  pointing at the prior (committed) epoch. Harmless in RETRO (the
  App-DB before-image now derives directly from `:epoch-id`, so a
  stale-`:epoch-id` preview just shows the committed epoch's diff), but
  in LIVE mode `compose-focus` re-derives `:epoch-id` from the
  perturbed `:dispatch-id` via `epoch-id-for-event-bundle` — so previewing
  one event-bundle while focused on another desynced the two axes mid-
  interaction. Writing the resolved `:epoch-id` in lockstep with
  `:dispatch-id` keeps the two axes consistent through the whole
  preview gesture, matching `focus-event-bundle-reducer`'s write contract.

  ## rf2-uo0rc.5 — preview-clear restores the committed selection

  Pre-fix the nil-arm cleared ONLY `:previewing?`, leaving
  `:dispatch-id` / `:epoch-id` pinned at the PREVIEWED value. In RETRO,
  `compose-focus` honours the stored slot-id (LIVE+unpaused tracks head
  so it masked the bug), so a hover-then-leave left focus on the
  previewed event-bundle rather than the originally-committed one. The fix:
  on the FIRST preview of a gesture we snapshot the committed
  `:dispatch-id` / `:epoch-id` into `[:focus :pre-preview]`; preview-
  clear restores them and drops the backup. A preview is now a true
  non-destructive overlay over the committed slot.

  The 1-arity (caller has no epoch buffer handy) leaves `:epoch-id`
  untouched — back-compat for the pure-shape callers; the 2-arity
  (resolved `epoch-id`) is the production path the event handler
  drives."
  ([db dispatch-id] (preview-cascade-reducer db dispatch-id nil))
  ([db dispatch-id epoch-id]
   (if (nil? dispatch-id)
     ;; preview-clear: restore the committed selection captured at the
     ;; start of the hover gesture (if any), then drop the backup +
     ;; the previewing flag. No backup → nothing to restore (the
     ;; committed slot was never perturbed).
     (let [backup (get-in db [:focus :pre-preview])]
       (cond-> db
         backup (assoc-in [:focus :dispatch-id] (:dispatch-id backup))
         backup (assoc-in [:focus :epoch-id]    (:epoch-id backup))
         true   (update :focus dissoc :pre-preview)
         true   (assoc-in [:focus :previewing?] false)))
     ;; preview-start / preview-move: on the FIRST hover of a gesture
     ;; (not already previewing) snapshot the committed selection so
     ;; preview-clear can restore it. Subsequent moves within the same
     ;; gesture keep the original backup (the committed selection, not a
     ;; previously-previewed value).
     (cond-> db
       (not (get-in db [:focus :previewing?]))
       (assoc-in [:focus :pre-preview]
                 {:dispatch-id (get-in db [:focus :dispatch-id])
                  :epoch-id    (get-in db [:focus :epoch-id])})
       true     (assoc-in [:focus :previewing?] true)
       true     (assoc-in [:focus :dispatch-id] dispatch-id)
       epoch-id (assoc-in [:focus :epoch-id] epoch-id)))))

;; ---- registration --------------------------------------------------------

(defn db->event-bundles
  "Read the event-bundle vector by re-running the projection against the
  Xray app-db's `:trace-buffer` slot (falling back to a fresh
  framework-ring snapshot via `trace-collector/snapshot-from-rings`
  for pre-mount callers — the slot is absent until the first refresh
  microtask drains). Used by the step events that need to walk the
  event-bundle list inside an event-db handler — the equivalent reactive
  path inside the `:rf.xray/focus` sub uses the `:rf.xray/event-bundles`
  chain.

  Public so cross-panel spine-shim events (e.g. `:rf.xray/select-
  dispatch-id`, relocated from event_detail.cljs to registry.cljs in
  rf2-5gl5r) can reuse the same projection when they need head-id
  (rf2-xzzih).

  Applies the `self-noise/xray-internal-event-bundle?` filter (rf2-qlvq8) so
  this event-side walk matches the reactive `:rf.xray/event-bundles` sub
  (registry.cljs) and the first-mount seed (mount.cljs), both of which
  already strip Xray-internal event-bundles. Without it, the head-id / step /
  focus / machine-inspector walks could resolve a target to an
  `:rf.xray/*` event-bundle dispatched WITHOUT a `{:frame :rf/xray}` option
  (palette quick-actions, headless helpers) that landed on `:rf/default`
  and slipped past the ingest gate — an event-bundle the user never sees in
  the L2 list. This is the single projection helper every spine event
  handler shares, so filtering here keeps the event side and the view
  side reading the same set."
  [db]
  (let [buffer (or (get db :trace-buffer) (trace-collector/snapshot-from-rings))]
    (self-noise/filtered-event-bundles buffer)))

(defn- db->epoch-history
  "Read the Xray app-db's `:epoch-history` slot — the per-frame
  epoch ring buffer (per `tools/xray/spec/018-Event-Spine.md` §5.2)
  used to resolve `:dispatch-id → :epoch-id` for spine focus events.
  Empty vector when the slot is absent (cold start, pre-mount)."
  [db]
  (get db :epoch-history []))

(defn db->show-ungrouped?
  "Read the live `:show-ungrouped?` opt-in flag (rf2-r9lyy) off the
  Xray app-db's seeded settings slot, falling back to the config
  atom for callers that fire before the settings popup has been
  opened (the popup's `:settings-open` handler seeds the slot from
  the atom on first open). Mirrors the read shape used by the
  `:rf.xray/show-ungrouped?` sub in `settings/subs.cljs`.

  Public (rf2-nugvv) so cross-panel spine-shim events that need to
  resolve the composed focus inside a `reg-event` handler — e.g.
  the Machine Inspector's per-machine prev/next nav — can pass the
  same opt-in into `compose-focus` / `focus-event-bundle-reducer` rather
  than re-deriving it."
  [db]
  (boolean
    (or (get-in db [:settings :general :show-ungrouped?])
        (config/get-setting :general :show-ungrouped?))))

(defn install!
  "Idempotent install — register the `:rf.xray/focus` sub + its
  driving events. Called from `registry.cljs`'s
  `register-xray-handlers!` fan-out."
  []
  ;; ---- :rf.xray/focus — the spine sub ---------------------------------
  ;;
  ;; Composes the stored `:focus` slot with the live event-bundle list so
  ;; `:head?` is derived (never stored). The dependency on
  ;; `:rf.xray/event-bundles` means the spine recomputes whenever a new
  ;; event-bundle lands, which is what gives LIVE mode its auto-tracking
  ;; behaviour: a fresh head event-bundle re-derives `:head?` to true and
  ;; (in LIVE) snaps the effective `:dispatch-id` forward.
  (rf/reg-sub :rf.xray/focus-slot
    (fn [db _query]
      (get db :focus)))

  ;; rf2-70tkv — `:rf.xray/epoch-history` joined so `compose-focus`
  ;; can auto-derive `:epoch-id` to the head event-bundle's settling
  ;; epoch in LIVE+unpaused mode. Without this seam the focus map's
  ;; `:epoch-id` stayed wired to the stored slot (last :rf.xray/
  ;; select-epoch / focus-event-bundle / focus-step write), and every
  ;; panel pivoting on focus `:epoch-id` (Views, Machine Inspector,
  ;; App-DB diff via `[:focus :epoch-id]`) stayed frozen on the old
  ;; epoch while `:dispatch-id` correctly tracked head.
  (rf/reg-sub :rf.xray/focus
    :<- [:rf.xray/focus-slot]
    :<- [:rf.xray/event-bundles]
    :<- [:rf.xray/show-ungrouped?]
    :<- [:rf.xray/epoch-history]
    (fn [[focus event-bundles show-ungrouped? epoch-history] _query]
      (compose-focus focus event-bundles show-ungrouped? epoch-history)))

  ;; ---- events ----------------------------------------------------------
  ;;
  ;; rf2-r9lyy — the focus-event-bundle / focus-event-bundle-prev / next handlers
  ;; read the live `:show-ungrouped?` setting off the db so the step /
  ;; head detection walks include the `:ungrouped` bucket when the user
  ;; has opted in. The settings popup writes through the same slot;
  ;; pre-popup-seed reads fall back to the config atom via the helper
  ;; below (mirrors the `:rf.xray/show-ungrouped?` sub).

  (rf/reg-event :rf.xray/focus-event
    (fn [{:keys [db]} [_ dispatch-id frame-id]]
      ;; rf2-q8hvw — re-key `:epoch-history` onto the clicked event-bundle's
      ;; frame BEFORE resolving its settling epoch. With the picker
      ;; untouched the L2 list spans every frame, so a non-head-frame
      ;; row's epoch lives outside the boot-frame slot; resolving against
      ;; the stale slot yields nil. No-op when the frame is unchanged.
      {:db (let [db             (reseed-epoch-history-for-frame
                             db frame-id (rf/epoch-history frame-id))
            event-bundles       (db->event-bundles db)
            show-ungrouped? (db->show-ungrouped? db)
            head-id        (focusable-head-id event-bundles show-ungrouped?)
            epoch-id       (epoch-id-for-event-bundle (db->epoch-history db) dispatch-id)]
        (focus-event-bundle-reducer db dispatch-id frame-id epoch-id head-id))}))

  (rf/reg-event :rf.xray/focus-event-prev
    (fn [{:keys [db]} _event]
      {:db (focus-step-reducer db (db->event-bundles db) (db->epoch-history db) -1
                          (db->show-ungrouped? db))}))

  (rf/reg-event :rf.xray/focus-event-next
    (fn [{:keys [db]} _event]
      {:db (focus-step-reducer db (db->event-bundles db) (db->epoch-history db) +1
                          (db->show-ungrouped? db))}))

  (rf/reg-event :rf.xray/focus-epoch
    ;; Per rf2-5qp4g — focus the spine by epoch-id (the primary key
    ;; into the epoch ring buffer). Resolves the matching record's
    ;; settling dispatch-id via `dispatch-id-for-epoch` then defers
    ;; to `focus-event-bundle-reducer` so the same focus-mutation path is
    ;; reused. The Epoch panel's DISPATCH step renders a parent-epoch
    ;; navigation chip for `:fx-dispatch` / `:fx-dispatch-later`
    ;; dispatches (the parent epoch was settled in the buffer); the
    ;; chip's click dispatches this event with the resolved
    ;; parent-epoch-id.
    (fn [{:keys [db]} [_ epoch-id]]
      ;; rf2-q8hvw — if the targeted epoch's record lives in a frame
      ;; other than the slot's current `:target-frame`, re-key
      ;; `:epoch-history` onto it BEFORE resolving the settling
      ;; dispatch-id, so the resolution runs against the right frame's
      ;; ring (the dispatch-id lookup walks `:epoch-history`). The frame
      ;; is read off the record in the current slot; when the slot
      ;; already holds that frame the re-seed is a no-op.
      {:db (let [record-frame    (:frame (some #(when (= epoch-id (:epoch-id %)) %)
                                          (db->epoch-history db)))
            db              (reseed-epoch-history-for-frame
                             db record-frame (rf/epoch-history record-frame))
            epoch-history   (db->epoch-history db)
            dispatch-id     (dispatch-id-for-epoch epoch-history epoch-id)
            event-bundles        (db->event-bundles db)
            show-ungrouped? (db->show-ungrouped? db)
            head-id         (focusable-head-id event-bundles show-ungrouped?)
            record          (some #(when (= epoch-id (:epoch-id %)) %)
                                  epoch-history)
            frame-id        (:frame record)]
        (if dispatch-id
          (focus-event-bundle-reducer db dispatch-id frame-id epoch-id head-id)
          ;; No matching dispatch-id (the epoch's trace was elided or
          ;; the record lacks the slot) — still pin epoch-id so panels
          ;; pivoting on `:rf.xray/focus :epoch-id` (App-db diff, Views,
          ;; machine-inspector) follow the navigation. The event-bundle list
          ;; will refresh and `compose-focus` will recover the dispatch-id
          ;; on the next live tick.
          (cond-> (update db :focus (fnil assoc {})
                          :epoch-id   epoch-id
                          :mode       :retro
                          :previewing? false)
            frame-id (assoc-in [:focus :frame] frame-id))))}))

  (rf/reg-event :rf.xray/follow-head
    (fn [{:keys [db]} _event]
      {:db (follow-head-reducer db)}))

  (rf/reg-event :rf.xray/toggle-live-pause
    (fn [{:keys [db]} _event]
      {:db (toggle-live-pause-reducer db)}))

  (rf/reg-event :rf.xray/set-frame
    (fn [{:keys [db]} [_ frame-id]]
      ;; rf2-ug1r6 + rf2-thodq — re-seed `:epoch-history` from the
      ;; framework's per-frame ring at picker-change time so every
      ;; per-frame composite (App-DB Diff, Views, machine-inspector)
      ;; reads the picked frame's epochs immediately. See the reducer
      ;; docstring for the shared root cause.
      {:db (set-frame-reducer db frame-id (rf/epoch-history frame-id))}))

  (rf/reg-event :rf.xray/preview-cascade
    (fn [{:keys [db]} [_ dispatch-id frame-hint]]
      ;; rf2-yng0y — resolve the previewed event-bundle's settling epoch-id
      ;; from the per-frame ring and write it in lockstep with
      ;; `:dispatch-id` so the spine's two axes never desync mid-
      ;; preview (the App-DB before-image follows `:epoch-id`). nil
      ;; dispatch-id (preview-clear) resolves to nil and the reducer
      ;; restores the committed selection.
      ;;
      ;; rf2-uo0rc.5 — a preview is a TRANSIENT hover, so it must NOT
      ;; persist a cross-frame re-key. Pre-fix this handler called
      ;; `reseed-epoch-history-for-frame`, which writes `:target-frame` +
      ;; `:epoch-history` onto the previewed event-bundle's frame; on preview-
      ;; clear (frame-id nil) that reseed was a no-op, so the re-key from
      ;; the hover was NEVER reverted — after hovering a cross-frame L2
      ;; row and leaving, `:target-frame` stuck on the previewed frame
      ;; and the committed focus then resolved its epoch against the
      ;; WRONG ring. The fix resolves the previewed event-bundle's epoch
      ;; against THAT frame's ring DIRECTLY (a read, not a write) and
      ;; leaves the committed `:target-frame` / `:epoch-history`
      ;; untouched — the cross-frame hazard rf2-q8hvw fixes for COMMITTED
      ;; clicks (`:rf.xray/focus-event`) does not apply to a transient
      ;; preview, which never commits a frame change. nil dispatch-id
      ;; (preview-clear) resolves frame to nil → epoch-id nil; the
      ;; reducer's restore-arm ignores it.
      ;;
      ;; rf2-bz7flo — dispatch ids collide across frames, so a preview
      ;; command for an id present in two frames cannot be disambiguated
      ;; by id alone. The optional `frame-hint` (the L2 row's frame —
      ;; the row knows which frame it renders) is threaded into the
      ;; 3-arity `event-bundle-by-id` so the previewed event-bundle — and thus the
      ;; ring its epoch resolves against — is the FOCUSED frame's. When
      ;; the caller omits the hint (legacy / single-frame dispatch) the
      ;; lookup degrades to the id-only match as before.
      {:db (let [frame-id      (:frame (event-bundle-by-id (db->event-bundles db) dispatch-id frame-hint))
            frame-history (when frame-id (rf/epoch-history frame-id))
            epoch-id      (epoch-id-for-event-bundle frame-history dispatch-id)]
        (preview-cascade-reducer db dispatch-id epoch-id))}))

  nil)
