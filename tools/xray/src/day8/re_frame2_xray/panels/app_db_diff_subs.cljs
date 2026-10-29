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
    - `:rf.xray/app-db-current+diff`   — the atomic, egress-PROJECTED
                                         `{:status :value :before
                                         :runtime-value :runtime-before
                                         :epoch-id :redacted-modified}`
                                         the panel body derives from
    - `:rf.xray/app-db-state`          — the section model the body renders

  No sub here answers a raw epoch record: every frame-state value leaves
  `:rf.xray/app-db-current+diff` already projected for on-box render
  (`local-render/local-render-record-state`), so no reader can reach a
  past epoch's state around the egress seam. The Epoch panel projects
  its own record (`epoch-panel/redact-record-db`).

  ### No frame-keyed cache

  The diff surface (`:rf.xray/app-db-current+diff` →
  `:rf.xray/app-db-state`) is PURELY reactive — it re-derives from the
  live subs each render with NO memoization atom — so there is no
  frame-keyed cache to grow across frames observed once in a long
  parallel-frames / SSR-hydration churn session, and nothing to age
  out. No eviction machinery is needed."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as h]
            [day8.re-frame2-xray.panels.local-render :as local-render]
            [day8.re-frame2-xray.panels.shared.focus-resolver :as focus]))

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
  ;; ## Why one sub, not a chain
  ;;
  ;; The sub joins the live db, the epoch history and the spine
  ;; `:rf.xray/focus` map DIRECTLY and resolves every slot from ONE
  ;; `record` in one computation. A chain of focus → epoch-id → record →
  ;; section-model subs can paint one frame (~17–22 ms under real mouse
  ;; timing) while the chain is still propagating, so the rendered
  ;; `:before` would lag the focus — the previous epoch's diff flashing as
  ;; "stuck", most visible when zoomed into a subtree. `(peek history)` is
  ;; NOT a fallback here: the App-DB diff follows the FOCUSED epoch.
  ;;
  ;; ATOMICITY INVARIANT (asserted by the deterministic unit test):
  ;;   for any returned map with a focused epoch, `:value` and `:before`
  ;;   are the projected `:db-after` / `:db-before` of the record named by
  ;;   `:epoch-id`. When no epoch is focused (cold boot, no event-bundles)
  ;;   `:value` is the projected LIVE db and `:before` is nil — plain
  ;;   current-state with no diff overlay.
  ;;
  ;; ## `:status` — a focus this panel cannot show
  ;;
  ;; `:status` is the shared resolver's 3-arity answer
  ;; (`focus/resolve-focus-status`). A focus pinned to an evicted epoch
  ;; (`:epoch-evicted`), to a bundle that settled no epoch (`:no-epoch`) or to
  ;; one whose epoch the ingest gate withheld (`:withheld`)
  ;; carries no value at all, so the body renders that state's placeholder
  ;; (tools/xray/spec/021 §10.7) instead of passing the LIVE db off as the
  ;; selected event's.
  ;;
  ;; ## The ON-BOX egress seam
  ;;
  ;; Every frame-state value leaves this sub projected for on-box render
  ;; under the `:rf.egress/local-redacted` profile (Spec 015 §Projection
  ;; profiles): the local operator sees large values but not slots the
  ;; frame declared `:sensitive`, which become `:rf/redacted` and render as
  ;; the edn-inspector's redacted chip. There is no process-global
  ;; show-sensitive toggle (Spec 015 §Cross-tool visibility grain).
  ;;
  ;; A focused record projects through `local-render-record-state`, so each
  ;; past epoch is redacted under the classification it was RECORDED with
  ;; (Spec 015 §Retained values keep their recorded classification): a
  ;; route left or a machine destroyed since still covers what it recorded.
  ;; The value and its pre-image take the same projection, so a sensitive
  ;; slot reads `:rf/redacted` on both sides and the inline `← was X`
  ;; annotation never reconstructs it. The cold-boot LIVE value projects
  ;; through `local-render-value` under the live classification. Both fail
  ;; closed: an unreachable observed frame redacts the whole value.
  (rf/reg-sub :rf.xray/app-db-current+diff
    {:inputs [[:rf.xray/target-frame-db]
              [:rf.xray/target-frame-runtime-db]
              [:rf.xray/epoch-history]
              [:rf.xray/focus]
              [:rf.xray/observed-frame]
              [:rf.xray/epoch-withheld]]}
    (fn [[db runtime-db history focus observed-frame withheld] _query]
      (let [epoch-id (:epoch-id focus)
            status   (focus/resolve-focus-status epoch-id (:dispatch-id focus) history withheld)
            record   (some-> (when epoch-id (find-epoch-in-history history epoch-id))
                             (local-render/local-render-record-state observed-frame))]
        (cond
          record
          ;; The reserved AREAS read the runtime-db partition (machines and
          ;; routing live there), so the focused epoch's runtime-db value and
          ;; pre-image come off the record's whole frame-state pair, moving
          ;; per epoch in lockstep with the app-db `:value` / `:before`.
          {:status            status
           :value             (:db-after record)
           :before            (:db-before record)
           :runtime-value     (get (:frame-state-after record) :rf.db/runtime)
           :runtime-before    (get (:frame-state-before record) :rf.db/runtime)
           :epoch-id          epoch-id
           ;; The SUPPRESSED-SIGNAL count. A redacted slot reads
           ;; `:rf/redacted` on both sides, so the structural diff emits
           ;; nothing for a secret that changed; this count is what tells the
           ;; operator (tools/xray/spec/004 §Count semantics). It is the
           ;; framework's exact figure, computed from the raw db pair at
           ;; assembly (Spec-Schemas §`:rf/epoch-record`); absent reads as 0,
           ;; and the chip renders only on a positive integer.
           :redacted-modified (:rf.epoch/redacted-modified-paths-count record)}

          (#{:epoch-evicted :no-epoch :withheld} status)
          {:status status}

          :else
          {:status        status
           :value         (local-render/local-render-value db observed-frame)
           :runtime-value (local-render/local-render-value runtime-db observed-frame)}))))

  ;; ---- current-state section model ------------------------------------
  ;;
  ;; Decomposes the atomic, already-projected map above into the section
  ;; model `current-state-sections` produces: the TOP user-domain section
  ;; (app-db minus reserved keys) plus one section per reserved runtime
  ;; subsystem (machines/spawned fan out per instance; route and the other
  ;; slices are singletons). The app-db `:value` / `:before` drive TOP; the
  ;; runtime-db `:runtime-value` / `:runtime-before` drive the reserved
  ;; areas. The pre-images are threaded as the diff PRE-IMAGE (spec/021
  ;; §4.3) so changed nodes carry the inline `← was X` annotation in place.
  ;;
  ;; `:status` and `:redacted-modified` ride beside the sections rather than
  ;; through `current-state-sections`, which stays the pure value → sections
  ;; projection: neither belongs to a section. `app_db_diff_state/state-body`
  ;; reads both off the model.
  (rf/reg-sub :rf.xray/app-db-state
    {:inputs [[:rf.xray/app-db-current+diff]]}
    (fn [[{:keys [status value before runtime-value runtime-before redacted-modified]}] _query]
      (if (#{:epoch-evicted :no-epoch :withheld} status)
        {:status status}
        ;; Diff-mode is entered iff a real app-db pre-image is present: an
        ;; absent / nil `:db-before` (cold boot, or a record with no
        ;; pre-image slot) renders plain current-state.
        (cond-> (if (some? before)
                  (h/current-state-sections value runtime-value
                                            {:app before :runtime runtime-before})
                  (h/current-state-sections value runtime-value))
          (and (int? redacted-modified) (pos? redacted-modified))
          (assoc :redacted-modified redacted-modified))))))
