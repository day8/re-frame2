(ns day8.re-frame2-xray.panels.app-db-diff-subs
  "Subscriptions and read-models for the app-db tab.

  The app-db tab is a CURRENT-STATE inspector (rf2-okvit): it renders
  the FOCUSED epoch's `:db-after` (its own post-state, per the
  rf2-02j4r per-epoch-delta contract) sectioned by reserved `:rf/*`
  area, with the focused epoch's `:db-before` threaded as the diff
  pre-image. The subs here surface that focused-epoch read-model:

    - `:rf.xray/observed-frame`        — the picker/focus-selected frame
    - `:rf.xray/target-frame-db`       — the observed frame's LIVE db
    - `:rf.xray/focus-epoch-id`        — the focused epoch-id off the spine
    - `:rf.xray/selected-epoch-record` — the focused `:rf/epoch-record`
                                         (the Epoch panel's `:db` diff
                                         surface reads this)
    - `:rf.xray/app-db-current+diff`   — the atomic `{:value :before
                                         :epoch-id}` the panel body
                                         derives from
    - `:rf.xray/app-db-state`          — the section model the body renders
    - `:rf.xray/focused-slice-path` /
      `:rf.xray/show-me-when-this-changed-result` — the cross-epoch
                                         'show me when this changed' walker

  ## rf2-p53m2 — dead diff-sub family pruned

  The `:rf.xray/selected-epoch-diff` → `:rf.xray/app-db-diff` composite
  family (plus its `:rf.xray/selected-epoch-redacted-modified-count` /
  `:rf.xray/selected-epoch-flow-writes` inputs and the three
  `[frame-id epoch-id]` caches rf2-nfgps frame-scoped) was removed: it
  had NO production view consumer. The app-db panel body reads only
  `:rf.xray/app-db-state` (+ `:rf.xray/app-db-current+diff` for the
  render key); the Epoch panel's `:db` diff reads
  `:rf.xray/selected-epoch-record` and runs its own `db-diff-paths`;
  the MCP `get-app-db-diff` tool projects directly through
  `diff.engine/project` (`runtime.cljs`), never the sub chain. The
  composite + its inner subs + caches were a hardened-but-unrendered
  surface carrying a docstring that claimed false consumers (the Epoch
  panel + an MCP exporter — neither consumed it). Per the pre-alpha
  masterpiece posture (CLARITY) the dead surface is gone; the canonical
  per-path diff lens lives in the Editscript-backed engine at
  `day8.re-frame2-xray.diff.engine` (consumed by the Epoch HANDLER
  `:db` view + the Machine Inspector `:diff` lens) and in the MCP
  `get-app-db-diff` tool.

  ### rf2-zgrhw — the prune-cache growth RISK is resolved by THIS prune

  rf2-zgrhw filed a latent RISK against the rf2-nfgps prune-cache: its
  prune only ran on a cache MISS for the CURRENTLY observed frame, so a
  frame observed once and never again kept its keyspace for the process
  lifetime (bounded by distinct-frames × ring-size — slow growth across
  dead frames in a long parallel-frames / SSR-hydration churn session).
  That RISK no longer applies: the three `[frame-id epoch-id]` caches it
  describes were the diff-sub family's memo atoms, removed wholesale
  above. The surviving diff surface (`:rf.xray/app-db-current+diff` →
  `:rf.xray/app-db-state`) is PURELY reactive — it re-derives from the
  live subs each render with NO memoization atom — so there is no
  frame-keyed cache to grow and nothing to age out. No eviction
  machinery is needed (adding any would be machinery for a surface that
  no longer exists)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as h]
            [day8.re-frame2-xray.panels.local-render :as local-render]))

(defn- find-epoch-in-history
  "Return the `:rf/epoch-record` in `history` whose `:epoch-id` matches
  `epoch-id`, or nil if absent. Pure data → record-or-nil. Inlined
  here when the Time Travel panel was deleted (rf2-qy0nu) — App-DB
  Diff was the only remaining consumer."
  [history epoch-id]
  (when (some? epoch-id)
    (some (fn [r] (when (= epoch-id (:epoch-id r)) r))
          history)))

(defn install!
  "Install the app-db tab's subscriptions."
  []
  ;; rf2-fvplw — panel-observed frame follows the spine `:rf.xray/focus`.
  ;; The frame-picker writes `[:focus :frame]` via `:rf.xray/set-frame`,
  ;; and `compose-focus` also derives `:frame` from the focused event-bundle.
  ;; Without this seam the App-db panel previously read only the legacy
  ;; `:target-frame` slot (which `:rf.xray/set-frame` does NOT touch),
  ;; so it stayed hardcoded to `:rf/default` no matter what the user
  ;; picked. The legacy slot survives as the fallback when no focus has
  ;; resolved a frame yet (cold start, no focusable event-bundles) — keeps
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
      (rf/app-db-value target)))

  ;; EP-0001 (rf2-vzld77) — the observed frame's LIVE runtime-db partition
  ;; value. Framework subsystem durable state (machine snapshots, the route
  ;; slice, the spawn registry) moved out of app-db `:rf/runtime` into the
  ;; reserved `:rf.db/runtime` partition; panels that inspect that state
  ;; (Machines inspector, Routing tab) source it from here rather than from
  ;; `:rf.xray/target-frame-db` (which carries app-db only). Sibling of the
  ;; app-db target sub above; same `:epoch-history` dependency so it
  ;; recomputes on every committed transition the panel is following.
  (rf/reg-sub :rf.xray/target-frame-runtime-db
    :<- [:rf.xray/observed-frame]
    :<- [:rf.xray/epoch-history]
    (fn [[target _epoch-history] _query]
      (rf/runtime-db-value target)))

  ;; rf2-70tkv — derive the panel's epoch-id from the spine sub
  ;; `:rf.xray/focus` rather than the legacy `:rf.xray/selected-
  ;; epoch-id` slot. The spine sub auto-tracks head in LIVE mode
  ;; (deriving `:epoch-id` from the head event-bundle via
  ;; `epoch-id-for-event-bundle` against `:epoch-history`); the legacy
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

  ;; ---- rf2-02j4r — PER-EPOCH-DELTA current-state + before-image -------
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
  ;; ## Why per-epoch-delta, not live-vs-before (rf2-02j4r reversal)
  ;;
  ;; The rf2-yng0y design set `:value = the LIVE target-frame-db`
  ;; ("constant as you scrub", a re-frame-10x current-state framing).
  ;; Diffing LIVE-value vs focused-`:db-before` equals the per-epoch
  ;; delta ONLY when the focused epoch is HEAD (live == that epoch's
  ;; `:db-after`). Scrub to ANY non-head epoch and it became a
  ;; CUMULATIVE diff — everything changed from the focused epoch forward
  ;; to NOW — so later events' changes bled onto earlier selections.
  ;; That actively misled during the core time-travel use case (Mike,
  ;; 2026-06-04). The fix: `:value` follows the focused epoch's
  ;; `:db-after`, so the diff is the epoch's own delta at every position.
  ;;
  ;; ## Why one sub, not a 5-deep chain (rf2-yng0y root-cause fix KEPT)
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
  ;; the focused record's `:db-after`, `:db-before` and `:epoch-id`
  ;; together in ONE computation. All three are pulled from the SAME
  ;; `record`, so they can NEVER disagree — pulling `:value` from the
  ;; focused record too STRENGTHENS the atomicity (every slot from one
  ;; record, not value-from-live + before-from-record). (Note: `(peek
  ;; history)` is NOT a fallback here — the App-DB diff follows the
  ;; FOCUSED epoch.)
  ;;
  ;; rf2-jmucu — the App-DB segment-inspector popup ALSO reads `:value`
  ;; (via `:rf.xray/segment-inspector-value`) so the popup and the panel
  ;; body show the identical focused-epoch image at every scrub position.
  ;;
  ;; ATOMICITY INVARIANT (asserted by the deterministic unit test):
  ;;   for any returned map with a focused epoch, `:value` =
  ;;   `(:db-after <record of :epoch-id>)` AND `:before` =
  ;;   `(:db-before <record of :epoch-id>)` — both are, by construction,
  ;;   the slots of the epoch named by `:epoch-id`. When no epoch is
  ;;   focused (cold boot, no event-bundles) `:value` falls back to the LIVE
  ;;   db and `:before` is nil — the panel renders plain current-state
  ;;   with no diff overlay (unchanged from rf2-yng0y).
  (rf/reg-sub :rf.xray/app-db-current+diff
    :<- [:rf.xray/target-frame-db]
    :<- [:rf.xray/target-frame-runtime-db]
    :<- [:rf.xray/epoch-history]
    :<- [:rf.xray/focus]
    (fn [[db runtime-db history focus] _query]
      (let [epoch-id (:epoch-id focus)
            record   (when epoch-id (find-epoch-in-history history epoch-id))
            before   (when record (:db-before record))
            ;; EP-0001 (rf2-tj6w9l) — the reserved AREAS read the runtime-db
            ;; PARTITION (machines / routing / elision moved there), so the
            ;; section model needs the focused epoch's runtime-db value +
            ;; pre-image too. The epoch record stores the WHOLE frame-state
            ;; (`:frame-state-before` / `-after`, decision #2), each carrying
            ;; the `:rf.db/runtime` partition; project it out so the runtime
            ;; areas move per-epoch in lockstep with the app-db `:value` /
            ;; `:before`. Cold boot / no focus → the LIVE runtime-db.
            rt-value  (if record
                        (get (:frame-state-after record) :rf.db/runtime)
                        runtime-db)
            rt-before (when record
                        (get (:frame-state-before record) :rf.db/runtime))]
        {;; rf2-02j4r — `:value` is the focused epoch's `:db-after` (its
         ;; OWN post-state), so the inline diff is db-before(N) →
         ;; db-after(N) = epoch N's per-epoch delta, not the cumulative
         ;; live-vs-db-before(N) that bled later events onto earlier
         ;; selections. Cold boot / no focus → fall back to the LIVE db
         ;; (plain current-state, no diff).
         :value    (if record (:db-after record) db)
         ;; `:value`, `:before` and `:epoch-id` all come from the SAME
         ;; record — they move together, never one-frame apart.
         :before   before
         ;; The runtime-db partition value + pre-image for the focused
         ;; epoch (the reserved areas' source post-EP-0001).
         :runtime-value  rt-value
         :runtime-before rt-before
         :epoch-id (when record epoch-id)})))

  ;; ---- rf2-okvit / rf2-ad7zx.11 — current-state section model ---------
  ;;
  ;; Decomposes the atomic `{:value :before :runtime-value :runtime-before
  ;; :epoch-id}` (above) into the section model `current-state-sections`
  ;; produces: the TOP user-domain section (app-db minus reserved keys) +
  ;; one section per reserved runtime subsystem (machines/spawned fan out
  ;; per instance; route + the other slices are singletons).
  ;;
  ;; EP-0001 (rf2-tj6w9l) — the TWO partitions feed two halves of the
  ;; model: the app-db `:value` / `:before` drives the user-domain TOP
  ;; section; the runtime-db `:runtime-value` / `:runtime-before` drives
  ;; the reserved areas (machines / routing / elision moved to the
  ;; runtime-db partition). Both move per focused-epoch in lockstep.
  ;;
  ;; The focused epoch's pre-images are threaded as the diff PRE-IMAGE
  ;; (spec/021 §4.3) so each section's changed nodes carry the inline
  ;; `← was X` annotation in place. Because this derives from the atomic
  ;; sub, the section model's `:before-top` / per-area `:before` slices
  ;; ALWAYS belong to the focused `:epoch-id` — no stale-`before`
  ;; intermediate frame (rf2-yng0y).
  ;;
  ;; nil-safe — absent / empty partitions yield an empty TOP + zero
  ;; reserved-area entries (rf2-jcdvo — empty areas are filtered at
  ;; projection time so the renderer never draws placeholder cards).
  ;; EP-0015 (rf2-t55hxg.12) — the ON-BOX LOCAL-RENDER egress seam. Every
  ;; value-bearing partition of the section model (the app-db `value` /
  ;; `before` + the runtime-db `runtime-value` / `runtime-before`) is
  ;; projected through `re-frame.core/project-egress` under the on-box
  ;; dev-UI default profile `:rf.egress/local-redacted` (Spec 015
  ;; §Projection profiles + §The graduation gate). Xray is the EP-0015
  ;; issue-3 GRADUATING CONSUMER for that profile: the local operator sees
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
  (rf/reg-sub :rf.xray/app-db-state
    :<- [:rf.xray/app-db-current+diff]
    :<- [:rf.xray/observed-frame]
    (fn [[{:keys [value before runtime-value runtime-before]} observed-frame] _query]
      (let [redact (fn [v] (local-render/local-render-value v observed-frame))
            value          (redact value)
            runtime-value  (redact runtime-value)]
        ;; Diff-mode is entered iff a real app-db pre-image is present,
        ;; mirroring the pre-rf2-yng0y `(if-let [before (:db-before record)]
        ;; …)` contract: an absent / nil `:db-before` (cold boot, or a record
        ;; with no pre-image slot) renders plain current-state. When diffing,
        ;; the runtime areas diff against the SAME focused epoch's runtime-db
        ;; pre-image.
        (if (some? before)
          (h/current-state-sections value runtime-value
                                    {:app     (redact before)
                                     :runtime (redact runtime-before)})
          (h/current-state-sections value runtime-value))))))
