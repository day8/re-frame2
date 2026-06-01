(ns day8.re-frame2-xray.panels.app-db-diff-subs
  "Subscriptions and read-models for the App-DB Diff panel.

  The diff projection itself is computed by the Editscript-backed
  engine at `day8.re-frame2-xray.diff.engine` — invoked inside
  `views/edn_inspector.cljs` from `:db-before` threaded through the
  composite. The subs here surface the flat-row lens (used by the
  panel's `:diff` mode renderer + the MCP exporter + `show me when
  this changed` walker) plus the per-epoch derived projections the
  panel composite depends on (focused slice + redacted-modified count
  + flow-writes origin attribution).

  ## rf2-xuyac — flat-row engine residency

  The `:rf.xray/selected-epoch-diff` sub routes through
  `day8.re-frame2-xray.diff.engine/project`'s `:flat-rows` channel and
  emits the universal 4-tuple shape `[path before after op]` — the
  same shape the Machine Inspector `:diff` lens (rf2-yqjrd /
  `snapshot-flat-diff-rows`) and the Epoch HANDLER `:db` `:diff` view
  (`db-diff-paths`) consume. One engine, one consumer-facing shape
  across every Xray `:diff` lens (spec/021 §9.1.5.2). The home-grown
  `app-db-diff-helpers/diff-paths` walker that this sub previously
  called remains in the helpers ns for the trace panel
  (`db-changed-diff-triples`, out of scope for rf2-xuyac)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.diff.engine :as diff-engine]
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as h]))

(defn- flat-rows->triples
  "Project `engine/project`'s `:flat-rows` (maps `{:path :op :before
  :after}`) into the universal 4-tuple shape `[path before after op]`
  the App-DB / HANDLER `:db` / Machine Inspector `:diff` renderers all
  consume. Pure data → data."
  [flat-rows]
  (mapv (fn [{:keys [path op before after]}]
          [path before after op])
        flat-rows))

(defn- find-epoch-in-history
  "Return the `:rf/epoch-record` in `history` whose `:epoch-id` matches
  `epoch-id`, or nil if absent. Pure data → record-or-nil. Inlined
  here when the Time Travel panel was deleted (rf2-qy0nu) — App-DB
  Diff was the only remaining consumer."
  [history epoch-id]
  (when (some? epoch-id)
    (some (fn [r] (when (= epoch-id (:epoch-id r)) r))
          history)))

(defonce diff-cache
  ;; Per-`:epoch-id` cache for the diff triples computed by the
  ;; `:rf.xray/selected-epoch-diff` sub. Tests reset this atom
  ;; between cases; production callers should only write via the sub.
  (atom {}))

(defonce redacted-modified-cache
  ;; Per-`:epoch-id` cache for the redacted-paths-modified count
  ;; computed by `:rf.xray/selected-epoch-redacted-modified-count`
  ;; (rf2-bz1cl). Same caching contract as `diff-cache` above — the
  ;; walk is bounded by `count-redacted-modified-paths`' structural-
  ;; sharing short-circuit but cascades replay the same db-before /
  ;; db-after pair across the inspector's life, so caching the
  ;; final integer is still cheap insurance. Tests reset this atom
  ;; between cases.
  (atom {}))

(defonce flow-writes-cache
  ;; Per-`:epoch-id` cache for the flow-writes projection computed by
  ;; `:rf.xray/selected-epoch-flow-writes` (rf2-s8r6c). Mirrors
  ;; `diff-cache`'s contract — the walk over `:trace-events` is O(N)
  ;; in trace count, and cascades replay the same record across the
  ;; inspector's life. Tests reset this atom between cases.
  (atom {}))

(defn install!
  "Install the App-DB Diff subscriptions."
  []
  ;; rf2-fvplw — panel-observed frame follows the spine `:rf.xray/focus`.
  ;; The frame-picker writes `[:focus :frame]` via `:rf.xray/set-frame`,
  ;; and `compose-focus` also derives `:frame` from the focused cascade.
  ;; Without this seam the App-db panel previously read only the legacy
  ;; `:target-frame` slot (which `:rf.xray/set-frame` does NOT touch),
  ;; so it stayed hardcoded to `:rf/default` no matter what the user
  ;; picked. The legacy slot survives as the fallback when no focus has
  ;; resolved a frame yet (cold start, no focusable cascades) — keeps
  ;; the boot-time empty-state useful.
  (rf/reg-sub :rf.xray/observed-frame
    :<- [:rf.xray/focus]
    :<- [:rf.xray/target-frame]
    (fn [[focus target] _query]
      (or (:frame focus) target)))

  (rf/reg-sub :rf.xray/target-frame-db
    :<- [:rf.xray/observed-frame]
    :<- [:rf.xray/epoch-history]
    (fn [[target _epoch-history] _query]
      (rf/frame-db target)))

  ;; rf2-70tkv — derive the panel's epoch-id from the spine sub
  ;; `:rf.xray/focus` rather than the legacy `:rf.xray/selected-
  ;; epoch-id` slot. The spine sub auto-tracks head in LIVE mode
  ;; (deriving `:epoch-id` from the head cascade via
  ;; `epoch-id-for-cascade` against `:epoch-history`); the legacy
  ;; slot is only written by user clicks (L2 row select, epoch
  ;; chip, prev/next step) and so stays pinned to the last user
  ;; action.
  ;;
  ;; Mike repro: user clicks an L2 row (legacy slot → pinned
  ;; epoch); user clicks Follow-head (focus :mode flips to :live);
  ;; new arrivals advance the focus's :dispatch-id correctly, but
  ;; pre-fix the legacy slot was untouched so every App-DB diff
  ;; sub kept resolving to the pinned epoch — the panel froze.
  ;; Pivoting these subs on focus's :epoch-id closes the gap
  ;; without changing the legacy slot's role (still authoritative
  ;; under RETRO + LIVE-paused via the spine's compose-focus
  ;; passthrough).
  (rf/reg-sub :rf.xray/focus-epoch-id
    :<- [:rf.xray/focus]
    (fn [focus _query]
      (:epoch-id focus)))

  (rf/reg-sub :rf.xray/selected-epoch-record
    :<- [:rf.xray/epoch-history]
    :<- [:rf.xray/focus-epoch-id]
    (fn [[history selected-id] _query]
      (when selected-id
        (find-epoch-in-history history selected-id))))

  ;; Per rf2-drf32 — the diff sub falls back to `(peek history)` when
  ;; the selected epoch is absent from history. The selection slot is
  ;; shared with the Time Travel panel (so a scrub from there is
  ;; visible here too), but a stale selection (e.g. an epoch that has
  ;; aged out of the ring buffer, or persisted from a prior session)
  ;; previously stranded this panel showing "no slice changes" for
  ;; every subsequent dispatch. Always picking the latest record when
  ;; the selection can't be located restores the user's expectation
  ;; that "the diff panel shows whatever just happened".
  ;; rf2-xuyac — route through the canonical Editscript engine
  ;; (`diff/engine.cljc`) and emit the universal 4-tuple shape
  ;; `[path before after op]` mirrored across every Xray `:diff` lens
  ;; (Machine Inspector `snapshot-flat-diff-rows`, Epoch HANDLER
  ;; `:db` `db-diff-paths`). Same engine + same consumer-facing
  ;; shape → engines no longer disagree on R6 vector-shift / R7
  ;; type-change / R8 redaction across surfaces (spec/021 §9.1.5.2,
  ;; spec/004 §Changed-paths derivation).
  (rf/reg-sub :rf.xray/selected-epoch-diff
    :<- [:rf.xray/epoch-history]
    :<- [:rf.xray/focus-epoch-id]
    (fn [[history selected-id] _query]
      (let [record (or (when selected-id
                         (find-epoch-in-history history selected-id))
                       (peek history))]
        (when record
          (let [epoch-id (:epoch-id record)
                cached   (get @diff-cache epoch-id ::miss)]
            (if (not= ::miss cached)
              cached
              (let [proj (diff-engine/project (:db-before record)
                                              (:db-after  record))
                    ;; rf2-5j7ch / rf2-9d4j8 — empty↔populated map
                    ;; replacements are pre-expanded inside the engine
                    ;; (per-key `:added` / `:removed` rows) so cold-boot
                    ;; epochs read with per-key granularity at every
                    ;; lens (`:diff`, `:full-with-diff`, container
                    ;; ops). No post-processor needed here.
                    diff (flat-rows->triples (:flat-rows proj))
                    live (into #{} (map :epoch-id) history)]
                (swap! diff-cache
                       (fn [m]
                         (-> m
                             (select-keys live)
                             (assoc epoch-id diff))))
                diff)))))))

  ;; ---- rf2-bz1cl / rf2-dl3gx — redacted-paths-modified count ----------
  ;;
  ;; The structural diff (above) correctly emits NO rows when both
  ;; sides of a path carry `:rf/redacted` — `:rf/redacted` = `:rf/redacted`
  ;; structurally. When an app-supplied `:redact-fn` substitutes the
  ;; sentinel into `:db-before` / `:db-after` AND the underlying values
  ;; differ, the diff body is empty and the developer's "something
  ;; changed" expectation is not met.
  ;;
  ;; This sub computes a separate-from-diff signal: the count of
  ;; redacted leaves whose enclosing subtree mutated across the
  ;; cascade. The renderer surfaces the count as a muted chip above
  ;; the diff body when count > 0.
  ;;
  ;; ## Sources (preferred → fallback)
  ;;
  ;; 1. **Egress slot (rf2-dl3gx, preferred).** The epoch record may
  ;;    carry `:rf.epoch/redacted-modified-paths-count` — an integer
  ;;    computed BY THE FRAMEWORK from raw db-before / db-after values
  ;;    BEFORE the `:redact-fn` runs (per
  ;;    `re-frame.epoch.assembly/redacted-modified-paths-count` +
  ;;    `spec/Spec-Schemas.md §:rf/epoch-record`). When the slot is
  ;;    present the sub returns it verbatim — exact count, no walk.
  ;;
  ;; 2. **Heuristic fallback (rf2-bz1cl).** Records that lack the slot
  ;;    (legacy snapshots, mock records with no schema layer, tests
  ;;    seeding raw record maps) fall back to the Xray-side heuristic
  ;;    `app-db-diff-helpers/count-redacted-modified-paths` — paths where
  ;;    both sides carry the sentinel AND the parent subtree's pointer
  ;;    differs. Tight upper bound; can over-state when a sibling
  ;;    changed and the redacted slot was incidentally untouched.
  ;;
  ;; The cache is shared between both paths — `:epoch-id` keys the
  ;; integer regardless of which source produced it.
  ;;
  ;; ## Selection fallback
  ;;
  ;; Mirrors `:rf.xray/selected-epoch-diff` — the same selection-stale
  ;; path that strands the diff panel strands the chip; same
  ;; `(peek history)` fallback applies.
  (rf/reg-sub :rf.xray/selected-epoch-redacted-modified-count
    :<- [:rf.xray/epoch-history]
    :<- [:rf.xray/focus-epoch-id]
    (fn [[history selected-id] _query]
      (let [record (or (when selected-id
                         (find-epoch-in-history history selected-id))
                       (peek history))]
        (if record
          (let [epoch-id (:epoch-id record)
                cached   (get @redacted-modified-cache epoch-id ::miss)]
            (if (not= ::miss cached)
              cached
              ;; Egress slot wins when present — exact count from the
              ;; framework (rf2-dl3gx). Falls back to the Xray-side
              ;; heuristic only when the slot is absent.
              (let [egress (:rf.epoch/redacted-modified-paths-count record)
                    n      (if (some? egress)
                             egress
                             (h/count-redacted-modified-paths
                               (:db-before record)
                               (:db-after  record)))
                    live   (into #{} (map :epoch-id) history)]
                (swap! redacted-modified-cache
                       (fn [m]
                         (-> m
                             (select-keys live)
                             (assoc epoch-id n))))
                n)))
          0))))

  ;; ---- rf2-s8r6c — flow-writes projection -----------------------------
  ;;
  ;; Project the selected epoch's `:rf.flow/computed` trace events into
  ;; `[{:flow-id … :write-path …} …]`. Same shape rf2-lo37i's
  ;; `event-detail/flows-fired` produces but sourced from the epoch
  ;; record's `:trace-events` slot (the App-DB Diff panel is epoch-
  ;; rooted, not cascade-rooted, so it reads the per-epoch raw trace
  ;; list rather than going through the cascade projection).
  ;;
  ;; Same selection-stale fallback as the diff sub — `(peek history)`
  ;; when the selected epoch is absent from history.
  (rf/reg-sub :rf.xray/selected-epoch-flow-writes
    :<- [:rf.xray/epoch-history]
    :<- [:rf.xray/focus-epoch-id]
    (fn [[history selected-id] _query]
      (let [record (or (when selected-id
                         (find-epoch-in-history history selected-id))
                       (peek history))]
        (when record
          (let [epoch-id (:epoch-id record)
                cached   (get @flow-writes-cache epoch-id ::miss)]
            (if (not= ::miss cached)
              cached
              (let [writes (h/flow-writes-from-trace-events
                             (:trace-events record))
                    live   (into #{} (map :epoch-id) history)]
                (swap! flow-writes-cache
                       (fn [m]
                         (-> m
                             (select-keys live)
                             (assoc epoch-id writes))))
                writes)))))))

  (rf/reg-sub :rf.xray/focused-slice-path
    (fn [db _query]
      (get db :focused-slice-path)))

  (rf/reg-sub :rf.xray/show-me-when-this-changed-result
    :<- [:rf.xray/focused-slice-path]
    :<- [:rf.xray/epoch-history]
    (fn [[focused-path history] _query]
      (if focused-path
        (h/epochs-touching-path history focused-path)
        [])))

  ;; ---- rf2-yng0y — ATOMIC current-state + focused-epoch before-image --
  ;;
  ;; The app-db tab is a CURRENT-STATE inspector (re-frame-10x style)
  ;; that ALSO carries the focused epoch's diff inline (spec/021 §4.1 —
  ;; "what does state LOOK LIKE — and what just changed?"):
  ;;
  ;;   :value  = the observed frame's LIVE app-db (constant as you scrub)
  ;;   :before = the focused epoch's `:db-before` (the inline-diff
  ;;             pre-image — the ONLY thing that changes per epoch)
  ;;
  ;; ## Why one sub, not a 5-deep chain (rf2-yng0y root-cause fix)
  ;;
  ;; Previously the section model resolved through a deep composed
  ;; chain — `:rf.xray/focus → :rf.xray/focus-epoch-id →
  ;; :rf.xray/selected-epoch-record → :rf.xray/app-db-state`. Under
  ;; real mouse timing (dispatches landing mid-frame relative to
  ;; Reagent's rAF-batched flush) the panel could paint ONE frame
  ;; (~17–22 ms) reading `app-db-state` while the focus→record chain was
  ;; still propagating, so the rendered `:before`/diff lagged the focus
  ;; by a frame — the previous epoch's diff flashing as "stuck", most
  ;; visible when zoomed into a subtree (the stale frame IS the entire
  ;; visible content).
  ;;
  ;; This sub collapses the chain: it joins the live db, the epoch
  ;; history, and the spine `:rf.xray/focus` map DIRECTLY, then resolves
  ;; the focused record's `:db-before` and `:epoch-id` together in ONE
  ;; computation. `:before` and `:epoch-id` are pulled from the SAME
  ;; `record`, so they can NEVER disagree — there is no intermediate
  ;; reaction layer carrying a stale `:before` for a frame, and so no
  ;; stale projection can paint. (Note: `(peek history)` is NOT a
  ;; fallback here — the App-DB before-image follows the FOCUSED epoch;
  ;; the `(peek history)` fallback that `:rf.xray/selected-epoch-diff`
  ;; uses is a separate Epoch-panel/MCP concern.)
  ;;
  ;; ATOMICITY INVARIANT (asserted by the deterministic unit test):
  ;;   for any returned map, `(= :before (:db-before <record of
  ;;   :epoch-id>))` — `:before` is, by construction, the `:db-before`
  ;;   of the epoch named by `:epoch-id`. When no epoch is focused
  ;;   (cold boot, no cascades) both are nil and the panel renders plain
  ;;   current-state.
  (rf/reg-sub :rf.xray/app-db-current+diff
    :<- [:rf.xray/target-frame-db]
    :<- [:rf.xray/epoch-history]
    :<- [:rf.xray/focus]
    (fn [[db history focus] _query]
      (let [epoch-id (:epoch-id focus)
            record   (when epoch-id (find-epoch-in-history history epoch-id))
            before   (when record (:db-before record))]
        {:value    db
         ;; `:before` and `:epoch-id` come from the SAME record — they
         ;; move together, never one-frame apart.
         :before   before
         :epoch-id (when record epoch-id)})))

  ;; ---- rf2-okvit / rf2-ad7zx.11 — current-state section model ---------
  ;;
  ;; Decomposes the atomic `{:value :before :epoch-id}` (above) into the
  ;; section model `current-state-sections` produces: the TOP
  ;; user-domain section (app-db minus reserved keys) + one section per
  ;; reserved `:rf/*` area (machines/spawned fan out per instance; route
  ;; + the other slices are singletons).
  ;;
  ;; The focused epoch's `:db-before` is threaded as the diff PRE-IMAGE
  ;; (spec/021 §4.3) so each section's changed nodes carry the inline
  ;; `← was X` annotation in place. Because this derives from the atomic
  ;; sub, the section model's `:before-top` / per-area `:before` slices
  ;; ALWAYS belong to the focused `:epoch-id` — no stale-`before`
  ;; intermediate frame (rf2-yng0y).
  ;;
  ;; nil-safe — an absent / empty db yields an empty TOP + zero
  ;; reserved-area entries (rf2-jcdvo — empty areas are filtered at
  ;; projection time so the renderer never draws placeholder cards).
  (rf/reg-sub :rf.xray/app-db-state
    :<- [:rf.xray/app-db-current+diff]
    (fn [{:keys [value before]} _query]
      ;; Diff-mode is entered iff a real pre-image is present, mirroring
      ;; the pre-rf2-yng0y `(if-let [before (:db-before record)] …)`
      ;; contract: an absent / nil `:db-before` (cold boot, or a record
      ;; with no pre-image slot) renders plain current-state. The atomic
      ;; sub carries `before` = the focused record's `:db-before` (or nil
      ;; when no epoch is focused), so this preserves the exact prior
      ;; diff/no-diff behaviour while inheriting the atomic sub's
      ;; stale-`before`-free contract.
      (if (some? before)
        (h/current-state-sections value before)
        (h/current-state-sections value))))

  ;; rf2-fvplw — `:target-frame` in the composite output is the
  ;; *observed* frame (picker-selected / focused-cascade frame), not
  ;; the legacy `:target-frame` slot. The empty-state body uses this
  ;; value, so the user always sees the frame their focus is on rather
  ;; than the hardcoded `:rf/default`.
  (rf/reg-sub :rf.xray/app-db-diff
    :<- [:rf.xray/observed-frame]
    :<- [:rf.xray/target-frame-db]
    :<- [:rf.xray/selected-epoch-diff]
    :<- [:rf.xray/focused-slice-path]
    :<- [:rf.xray/show-me-when-this-changed-result]
    :<- [:rf.xray/epoch-history]
    :<- [:rf.xray/selected-epoch-redacted-modified-count]
    :<- [:rf.xray/selected-epoch-flow-writes]
    :<- [:rf.xray/focus-epoch-id]
    (fn [[target db diff-triples focused-path focused-hits
          history redacted-modified-count flow-writes selected-epoch-id]
         _query]
      (let [{:keys [non-reserved]} (h/partition-reserved
                                     (or diff-triples []))]
        {:target-frame              target
         :history-empty?            (empty? history)
         :changed-non-reserved      non-reserved
         :changed-reserved          (h/reserved-summary db)
         :focused-path              focused-path
         :focused-hits              focused-hits
         ;; rf2-bz1cl — redacted-paths-modified hint chip surface.
         ;; Count > 0 when an app `:redact-fn` substituted the
         ;; `:rf/redacted` sentinel into both `:db-before` /
         ;; `:db-after` at the same path inside a mutated subtree.
         :redacted-modified-count   redacted-modified-count
         ;; rf2-s8r6c — per-path origin attribution input. The renderer
         ;; combines the full triples + this vec to tag each section
         ;; header with `[fx :db]` / `[flow :flow-id]` / mixed.
         :flow-writes               (or flow-writes [])
         :diff-triples              (or diff-triples [])
         ;; rf2-5kfxe.2 — surfaced so the renderer can key each
         ;; section by epoch-id, forcing a React re-mount per cascade
         ;; and replaying the diff-flash CSS animation.
         :selected-epoch-id         selected-epoch-id}))))
