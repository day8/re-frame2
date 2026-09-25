(ns day8.re-frame2-xray.panels.app-db-diff-subs
  "Subscriptions and read-models for the app-db tab.

  The app-db tab is a CURRENT-STATE inspector: it renders
  the FOCUSED epoch's `:db-after` (its own post-state, per the
  per-epoch-delta contract) sectioned by reserved `:rf/*`
  area, with the focused epoch's `:db-before` threaded as the diff
  pre-image. The subs here surface that focused-epoch read-model:

    - `:rf.xray/observed-frame`        — the picker/focus-selected frame
    - `:rf.xray/target-frame-db`       — the observed frame's LIVE db
    - `:rf.xray/focus-epoch-id`        — the focused epoch-id off the spine
    - `:rf.xray/selected-epoch-record` — the focused `:rf/epoch-record`
                                         (the Epoch panel's `:db` diff
                                         surface reads this)
    - `:rf.xray/app-db-current+diff`   — the atomic `{:value :before
                                         :runtime-value :runtime-before
                                         :epoch-id :redacted-modified}`
                                         the panel body derives from
    - `:rf.xray/app-db-state`          — the section model the body renders

  ## No composite diff sub

  The app-db panel body reads only
  `:rf.xray/app-db-state` (+ `:rf.xray/app-db-current+diff` for the
  render key); the Epoch panel's `:db` diff reads
  `:rf.xray/selected-epoch-record` and runs its own `db-diff-paths`.
  There is no composite app-db diff sub between them: the canonical
  per-path diff lens lives in the Editscript-backed engine at
  `day8.re-frame2-xray.diff.engine` (consumed by the Epoch HANDLER
  `:db` view + the Machine Inspector `:diff` lens).

  ### No frame-keyed cache

  The diff surface (`:rf.xray/app-db-current+diff` →
  `:rf.xray/app-db-state`) is PURELY reactive — it re-derives from the
  live subs each render with NO memoization atom — so there is no
  frame-keyed cache to grow across frames observed once in a long
  parallel-frames / SSR-hydration churn session, and nothing to age
  out. No eviction machinery is needed."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as h]
            [day8.re-frame2-xray.panels.local-render :as local-render]))

(defn- find-epoch-in-history
  "Return the `:rf/epoch-record` in `history` whose `:epoch-id` matches
  `epoch-id`, or nil if absent. Pure data → record-or-nil."
  [history epoch-id]
  (when (some? epoch-id)
    (some (fn [r] (when (= epoch-id (:epoch-id r)) r))
          history)))

(defn install!
  "Install the app-db tab's subscriptions."
  []
  ;; The panel-observed frame follows the spine `:rf.xray/focus`.
  ;; The frame-picker writes `[:focus :frame]` via `:rf.xray/set-frame`,
  ;; and `compose-focus` also derives `:frame` from the focused event-bundle.
  ;; `set-frame-reducer` also writes `:target-frame` and re-seeds
  ;; `:epoch-history`, so the two axes agree at the writer; the panel
  ;; reads focus because focus, not the slot, is the axis it follows.
  ;;
  ;; The `:target-frame` slot is the `or` fallback below when no focus has
  ;; resolved a frame yet. An unselected target is `nil` rather than
  ;; `:rf/default`, so a cold start with no focusable event-bundles yields
  ;; nil and the panel renders its unselected-target state instead of a
  ;; boot frame.
  (rf/reg-sub :rf.xray/observed-frame
    {:inputs [[:rf.xray/focus] [:rf.xray/target-frame]]}
    (fn [[focus target] _query]
      (or (:frame focus) target)))

  (rf/reg-sub :rf.xray/target-frame-db
    {:inputs [[:rf.xray/observed-frame] [:rf.xray/epoch-history]]}
    (fn [[target _epoch-history] _query]
      (rf/app-db-value target)))

  ;; The observed frame's LIVE runtime-db partition
  ;; value. Framework subsystem durable state (machine snapshots, the route
  ;; slice, the spawn registry) lives in the reserved `:rf.db/runtime`
  ;; partition, not app-db; panels that inspect that state
  ;; (Machines inspector, Routing tab) source it from here rather than from
  ;; `:rf.xray/target-frame-db` (which carries app-db only). Sibling of the
  ;; app-db target sub above; same `:epoch-history` dependency so it
  ;; recomputes on every committed transition the panel is following.
  (rf/reg-sub :rf.xray/target-frame-runtime-db
    {:inputs [[:rf.xray/observed-frame] [:rf.xray/epoch-history]]}
    (fn [[target _epoch-history] _query]
      (:rf.db/runtime (rf/frame-state-value target))))

  ;; Derive the panel's epoch-id from the spine sub
  ;; `:rf.xray/focus` rather than the `:rf.xray/selected-
  ;; epoch-id` slot. The spine sub auto-tracks head in LIVE mode
  ;; (deriving `:epoch-id` from the head event-bundle via
  ;; `epoch-id-for-event-bundle` against `:epoch-history`); the
  ;; slot is only written by user clicks (L2 row select, epoch
  ;; chip, prev/next step) and so stays pinned to the last user
  ;; action.
  ;;
  ;; Reading the slot would freeze the panel: a user clicks an L2
  ;; row (slot → pinned epoch), then Follow-head (focus :mode flips
  ;; to :live), and new arrivals advance the focus's :dispatch-id
  ;; while the slot keeps naming the pinned epoch. Reading focus's
  ;; :epoch-id follows head without changing the slot's role
  ;; (authoritative under RETRO + LIVE-paused via the spine's
  ;; compose-focus passthrough).
  (rf/reg-sub :rf.xray/focus-epoch-id
    {:inputs [[:rf.xray/focus]]}
    (fn [[focus] _query]
      (:epoch-id focus)))

  (rf/reg-sub :rf.xray/selected-epoch-record
    {:inputs [[:rf.xray/epoch-history] [:rf.xray/focus-epoch-id]]}
    (fn [[history selected-id] _query]
      (when selected-id
        (find-epoch-in-history history selected-id))))

  ;; ---- PER-EPOCH-DELTA current-state + before-image -------------------
  ;;
  ;; The app-db tab shows the SELECTED epoch's OWN delta — what THIS
  ;; event changed, and nothing later (spec/021 §4.1, spec/004
  ;; §Diff-semantics):
  ;;
  ;;   :value  = the focused epoch's `:db-after`  (the post-state OF that
  ;;             event — moves per epoch as you scrub)
  ;;   :before = the focused epoch's `:db-before` (the pre-state OF that
  ;;             event — moves per epoch as you scrub)
  ;;
  ;; Both come from the SAME focused record, so the inline diff is
  ;; exactly `db-before(N) → db-after(N)` — epoch N's per-epoch delta,
  ;; independent of any later event. Selecting :media/deep highlights
  ;; ONLY what :media/deep changed; :media/shallow stays unhighlighted
  ;; until you select ITS epoch.
  ;;
  ;; ## Why per-epoch-delta, not live-vs-before
  ;;
  ;; Setting `:value` to the LIVE target-frame-db ("constant as you
  ;; scrub", a re-frame-10x current-state framing) and diffing it
  ;; against the focused `:db-before` equals the per-epoch
  ;; delta ONLY when the focused epoch is HEAD (live == that epoch's
  ;; `:db-after`). Scrubbed to ANY non-head epoch it becomes a
  ;; CUMULATIVE diff — everything changed from the focused epoch forward
  ;; to NOW — so later events' changes bleed onto earlier selections,
  ;; which misleads in the core time-travel use case. So `:value`
  ;; follows the focused epoch's `:db-after`, and the diff is the
  ;; epoch's own delta at every position.
  ;;
  ;; ## Why one sub, not a 5-deep chain
  ;;
  ;; Resolved through a deep composed
  ;; chain — `:rf.xray/focus → :rf.xray/focus-epoch-id →
  ;; :rf.xray/selected-epoch-record → :rf.xray/app-db-state` — under
  ;; real mouse timing (dispatches landing mid-frame relative to
  ;; Reagent's rAF-batched flush) the panel could paint ONE frame
  ;; (~17–22 ms) reading `app-db-state` while the focus→record chain was
  ;; still propagating, so the rendered `:before`/diff would lag the focus
  ;; by a frame — the previous epoch's diff flashing as "stuck", most
  ;; visible when zoomed into a subtree (the stale frame IS the entire
  ;; visible content).
  ;;
  ;; This sub collapses the chain: it joins the live db, the epoch
  ;; history, and the spine `:rf.xray/focus` map DIRECTLY, then resolves
  ;; the focused record's `:db-after`, `:db-before` and `:epoch-id`
  ;; together in ONE computation. All three are pulled from the SAME
  ;; `record`, so they can NEVER disagree — pulling `:value` from the
  ;; focused record too STRENGTHENS the atomicity (every slot from one
  ;; record, not value-from-live + before-from-record). (Note: `(peek
  ;; history)` is NOT a fallback here — the App-DB diff follows the
  ;; FOCUSED epoch.)
  ;;
  ;; ATOMICITY INVARIANT (asserted by the deterministic unit test):
  ;;   for any returned map with a focused epoch, `:value` =
  ;;   `(:db-after <record of :epoch-id>)` AND `:before` =
  ;;   `(:db-before <record of :epoch-id>)` — both are, by construction,
  ;;   the slots of the epoch named by `:epoch-id`. When no epoch is
  ;;   focused (cold boot, no event-bundles) `:value` falls back to the LIVE
  ;;   db and `:before` is nil — the panel renders plain current-state
  ;;   with no diff overlay.
  (rf/reg-sub :rf.xray/app-db-current+diff
    {:inputs [[:rf.xray/target-frame-db]
              [:rf.xray/target-frame-runtime-db]
              [:rf.xray/epoch-history]
              [:rf.xray/focus]]}
    (fn [[db runtime-db history focus] _query]
      (let [epoch-id (:epoch-id focus)
            record   (when epoch-id (find-epoch-in-history history epoch-id))
            before   (when record (:db-before record))
            ;; The reserved AREAS read the runtime-db
            ;; PARTITION (machines / routing / elision live there), so the
            ;; section model needs the focused epoch's runtime-db value +
            ;; pre-image too. The epoch record stores the WHOLE frame-state
            ;; (`:frame-state-before` / `-after`), each carrying
            ;; the `:rf.db/runtime` partition; project it out so the runtime
            ;; areas move per-epoch in lockstep with the app-db `:value` /
            ;; `:before`. Cold boot / no focus → the LIVE runtime-db.
            rt-value  (if record
                        (get (:frame-state-after record) :rf.db/runtime)
                        runtime-db)
            rt-before (when record
                        (get (:frame-state-before record) :rf.db/runtime))]
        {;; `:value` is the focused epoch's `:db-after` (its
         ;; OWN post-state), so the inline diff is db-before(N) →
         ;; db-after(N) = epoch N's per-epoch delta, not the cumulative
         ;; live-vs-db-before(N) that would bleed later events onto earlier
         ;; selections. Cold boot / no focus → fall back to the LIVE db
         ;; (plain current-state, no diff).
         :value    (if record (:db-after record) db)
         ;; `:value`, `:before` and `:epoch-id` all come from the SAME
         ;; record — they move together, never one-frame apart.
         :before   before
         ;; The runtime-db partition value + pre-image for the focused
         ;; epoch (the reserved areas' source).
         :runtime-value  rt-value
         :runtime-before rt-before
         :epoch-id (when record epoch-id)
         ;; The SUPPRESSED-SIGNAL count, read straight off
         ;; the focused record (the same record every slot above comes
         ;; from, so it can never describe a different epoch).
         ;;
         ;; The panel redacts both sides of a declared-sensitive slot
         ;; (`:rf.xray/app-db-state` below), so the structural diff sees
         ;; `:rf/redacted` = `:rf/redacted` and emits NOTHING — a changed
         ;; secret is invisible, which is the elision contract working and
         ;; a terrible thing to leave unsaid. `tools/xray/spec/004-App-DB-
         ;; Diff.md` §Count semantics designs the answer as a chip that
         ;; sits BESIDE the diff rather than inside it; this slot is what
         ;; feeds it, and `app_db_diff_state/top-section` draws it.
         ;;
         ;; `:rf.epoch/redacted-modified-paths-count` is the framework's
         ;; EXACT figure, computed in `re-frame.epoch.assembly/build-record`
         ;; from the RAW db pair before any projection runs — no walk and
         ;; no heuristic on this side. Spec-Schemas §`:rf/epoch-record`
         ;; marks the slot OPTIONAL and tells consumers to read absent as
         ;; 0, which is exactly what a nil here means downstream: the chip
         ;; renders only on a positive integer, so a host with no
         ;; classification layer draws no chip rather than a zero one.
         ;;
         ;; NOT a `:rf.xray/selected-epoch-redacted-modified-count` sub:
         ;; there is none, and its absence is
         ;; pinned by `app_db_diff_subs_cljs_test/pruned-diff-sub-family-
         ;; stays-gone`. This is a slot on the atomic sub the panel
         ;; reads, and it has a consumer.
         :redacted-modified (when record
                              (:rf.epoch/redacted-modified-paths-count record))})))

  ;; ---- current-state section model ------------------------------------
  ;;
  ;; Decomposes the atomic `{:value :before :runtime-value :runtime-before
  ;; :epoch-id}` (above) into the section model `current-state-sections`
  ;; produces: the TOP user-domain section (app-db minus reserved keys) +
  ;; one section per reserved runtime subsystem (machines/spawned fan out
  ;; per instance; route + the other slices are singletons).
  ;;
  ;; The TWO partitions feed two halves of the
  ;; model: the app-db `:value` / `:before` drives the user-domain TOP
  ;; section; the runtime-db `:runtime-value` / `:runtime-before` drives
  ;; the reserved areas (machines / routing / elision live in the
  ;; runtime-db partition). Both move per focused-epoch in lockstep.
  ;;
  ;; The focused epoch's pre-images are threaded as the diff PRE-IMAGE
  ;; (spec/021 §4.3) so each section's changed nodes carry the inline
  ;; `← was X` annotation in place. Because this derives from the atomic
  ;; sub, the section model's `:before-top` / per-area `:before` slices
  ;; ALWAYS belong to the focused `:epoch-id` — no stale-`before`
  ;; intermediate frame.
  ;;
  ;; nil-safe — absent / empty partitions yield an empty TOP + zero
  ;; reserved-area entries (empty areas are filtered at
  ;; projection time so the renderer never draws placeholder cards).
  ;; The ON-BOX LOCAL-RENDER egress seam. Every
  ;; value-bearing partition of the section model (the app-db `value` /
  ;; `before` + the runtime-db `runtime-value` / `runtime-before`) is
  ;; projected through `re-frame.core/project-egress` under the on-box
  ;; dev-UI default profile `:rf.egress/local-redacted` (Spec 015
  ;; §Projection profiles + §The graduation gate). Xray consumes that
  ;; profile: the local operator sees
  ;; large values (the `include-large?` overlay) but NOT slots the OBSERVED
  ;; frame declared `:sensitive` — those redact to `:rf/redacted`, which
  ;; the shared edn-inspector already paints as a first-class chip. Per
  ;; EP-0015 §Cross-tool visibility grain there is NO process-global
  ;; show-sensitive toggle: revealing sensitive values is a per-(tool,frame)
  ;; `:rf.egress/local-raw` operator opt-in, not the default.
  ;;
  ;; Projecting BOTH the value AND the matching pre-image under the SAME
  ;; frame policy keeps the diff honest — a sensitive slot reads
  ;; `:rf/redacted` on both sides, so the inline `← was X` annotation never
  ;; reconstructs (or even hints at) the redacted content. Fail-closed: an
  ;; unreachable observed frame redacts the whole value rather than ship it
  ;; raw under no policy (`local-render/local-render-value`).
  ;;
  ;; The section model carries `:redacted-modified` through
  ;; to the renderer. It is NOT part of the section decomposition (it
  ;; belongs to no section — it is a record-level rollup about the whole
  ;; epoch), so it rides as a sibling slot on the model map rather than
  ;; through `current-state-sections`, which stays the pure value→sections
  ;; projection it is. `app_db_diff_state/state-body` reads it off the
  ;; model and hands it to the TOP section's header; nothing else looks at
  ;; it. The paragraph on `:rf.xray/app-db-current+diff` above carries why
  ;; the count exists at all.
  (rf/reg-sub :rf.xray/app-db-state
    {:inputs [[:rf.xray/app-db-current+diff] [:rf.xray/observed-frame]]}
    (fn [[{:keys [value before runtime-value runtime-before redacted-modified]}
          observed-frame] _query]
      (let [redact (fn [v] (local-render/local-render-value v observed-frame))
            value          (redact value)
            runtime-value  (redact runtime-value)
            ;; Diff-mode is entered iff a real app-db pre-image is present:
            ;; an absent / nil `:db-before` (cold
            ;; boot, or a record with no pre-image slot) renders plain
            ;; current-state. When diffing, the runtime areas diff against
            ;; the SAME focused epoch's runtime-db pre-image.
            sections (if (some? before)
                       (h/current-state-sections value runtime-value
                                                 {:app     (redact before)
                                                  :runtime (redact runtime-before)})
                       (h/current-state-sections value runtime-value))]
        (cond-> sections
          (and (int? redacted-modified) (pos? redacted-modified))
          (assoc :redacted-modified redacted-modified))))))
