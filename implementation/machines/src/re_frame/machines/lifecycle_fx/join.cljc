(ns re-frame.machines.lifecycle-fx.join
  "`:spawn-all` join-event interception.

  Per Spec 005 §Spawn-and-join via `:spawn-all` §Child completion protocol,
  the parent's handler boundary intercepts events whose inner-event-id
  matches the active state's `:on-child-done` / `:on-child-error`. The
  interception:

   1. Resolves the active `:spawn-all`-bearing state by walking the
      snapshot's `:state` path leaf→root looking for a state node whose
      `:spawn-all` declares the matching event keyword.
   2. Reads the join state at `[:rf.runtime/machines :spawned <parent> <invoke-id>]`.
   3. Verifies `<child-id>` (event[1]) is one of the parent's spawned
      children. Forged / unknown ids are rejected with the
      `:rf.error/machine-spawn-all-bad-child-id` error trace and a
      no-op fx (the join state is NOT mutated).
   4. Adds `<child-id>` to `:done` or `:failed`.
   5. If `:resolved?` is already true, this is a post-resolution
      late-completion (it fires NO further parent event — the
      `:resolved?` latch already flipped). Per Spec 005 §Cancel-on-decision
      (rf2-obczvv, XState v5 alignment): when `:cancel-on-decision? false`
      the surviving siblings run to completion, so the late child's result
      STILL folds into the `:done` / `:failed` record (tools observing the
      join-state see the full late-completion record) — only re-resolution
      is suppressed. Under the default `:cancel-on-decision? true` the
      siblings were destroyed at resolution, so a straggler with no live
      join is dropped (record frozen). The `:rf.machine.spawn-all/late-
      completion` trace fires on both paths, carrying `:folded?`.
   6. Else evaluates the join condition. On resolution:
        - latches `:resolved?` true,
        - if `:cancel-on-decision?` (default true), emits per-sibling
          `:rf.machine/destroy` fx and `:rf.machine.spawn/cancelled-on-
          join-resolution` traces,
        - dispatches the parent join event via `:fx [[:dispatch ...]]`.
   7. Writes the new join state back into app-db.

  The interceptor's public entry point is `intercept-spawn-all-event`;
  the handler-factory in `re-frame.machines.lifecycle-fx.registration`
  routes every inbound event through it before the machine's normal `:on`
  lookup."
  (:require [re-frame.machines.parallel :as parallel]
            [re-frame.machines.path-walk :as path-walk]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.reply :as m-reply]
            [re-frame.machines.transition :as transition]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

(defn- find-active-spawn-all-in-tree
  "Helper for `find-active-spawn-alls`. Given a machine-like map with
  `:states` (for a non-parallel machine, the machine itself; for a
  region of a parallel machine, the region body) and a path inside
  that tree, walk leaf→root for a `:spawn-all`-bearing state whose
  `:on-child-done` or `:on-child-error` matches inner-event-id (the
  deepest-wins rule named in `path-walk/walk-path-leaf-to-root`)."
  [tree path inner-event-id]
  (path-walk/walk-path-leaf-to-root
    tree path
    (fn [prefix n]
      (when-let [ia (:spawn-all n)]
        (cond
          (= inner-event-id (:on-child-done ia))
          {:invoke-id prefix :spec ia :kind :done}
          (= inner-event-id (:on-child-error ia))
          {:invoke-id prefix :spec ia :kind :failed})))))

(defn- find-active-spawn-alls
  "Walk the snapshot's `:state` path leaf→root looking for EVERY active
  `:spawn-all`-bearing state whose `:on-child-done` or `:on-child-error`
  matches the given inner-event-id. Returns a vector of
  `{:invoke-id <prefix-path> :spec <invoke-all-spec> :kind :done|:failed}`
  matches (empty when none).

  Per Spec 005 §Parallel regions: for parallel-region machines, iterates
  each region's active state-tree (prefixing the region name onto the
  resolved `:invoke-id`, matching the per-region scoping
  `prefix-region-invoke-id` applies on the entry-side). A flat machine has at
  most one active match.

  Returns ALL matches (not just the first) so the interceptor can
  disambiguate by join-state child-id OWNERSHIP. Two active parallel regions
  may legitimately reuse the SAME generic `:on-child-done` event id (e.g.
  `:done`, `:asset/loaded`); returning every match lets the interceptor
  route a child completion to the region whose join actually owns the
  child-id, rather than first-match-wins mis-routing it to another region's
  join."
  [machine snapshot inner-event-id]
  (cond
    (parallel/parallel? machine)
    (into []
          (keep (fn [[region-name region-state]]
                  (let [region-body (parallel/region-machine machine region-name)
                        region-path (transition/state-path region-state)
                        match       (find-active-spawn-all-in-tree
                                      region-body region-path inner-event-id)]
                    (when match
                      (update match :invoke-id #(vec (cons region-name %)))))))
          (:state snapshot))

    :else
    (if-let [m (find-active-spawn-all-in-tree
                 machine (transition/state-path (:state snapshot)) inner-event-id)]
      [m]
      [])))

(defn- join-condition-met?
  "Evaluate the join condition against the current join state.
  Returns truthy iff the join has resolved on the success-side
  (`:on-all-complete` / `:on-some-complete` should fire)."
  [spec join-state]
  (let [join     (:join spec :all)
        children (:children spec)
        n-total  (count children)
        n-done   (count (:done   join-state))
        n-failed (count (:failed join-state))]
    (cond
      (= :all join)
      (= n-done n-total)

      (= :any join)
      (>= n-done 1)

      (and (map? join) (pos-int? (:n join)))
      (>= n-done (:n join))

      (and (map? join) (fn? (:fn join)))
      ((:fn join) {:done   (:done   join-state)
                   :failed (:failed join-state)
                   :total  n-total})

      :else false)))

(defn- join-unsatisfiable?
  "Decide whether `spec`'s join condition can NEVER be met by the remaining
  undecided children, given `join-state`'s current
  `:done` / `:failed` folds. The footgun this guards: a `{:n N}` / `{:fn}`
  (or `:any` / `:all`) join with NO `:on-any-failed` silently hangs FOREVER
  once enough children have FAILED that the success condition is
  unreachable — no resolution event ever dispatches, the parent rests on
  the `:spawn-all` state, and nothing surfaces the dead join.

  `max-possible-done` is the largest `:done` count still achievable — the
  current `:done` plus every child not yet decided (every pending child
  optimistically succeeding). The join is unsatisfiable when even that
  ceiling cannot satisfy the condition:

    - `:all`        — any failure makes all-done unreachable.
    - `{:n N}`      — `max-possible-done < N`.
    - `:any`        — `max-possible-done < 1` (every child failed).
    - `{:fn pred}`  — opaque to look-ahead; only PROVABLY terminal once every
                      child has reported (`done`+`failed` = total) and the
                      predicate still rejects. (A custom pred MAY reject a
                      future it would later accept, so we cannot predict mid-
                      flight — but an all-reported-and-still-false join is
                      definitively stuck.)

  Returns false for a still-satisfiable (or already-resolved) join."
  [spec join-state]
  (let [join     (:join spec :all)
        children (:children spec)
        n-total  (count children)
        n-done   (count (:done   join-state))
        n-failed (count (:failed join-state))
        n-decided (+ n-done n-failed)
        n-pending (- n-total n-decided)
        max-possible-done (+ n-done n-pending)]
    (cond
      (= :all join)               (pos? n-failed)
      (= :any join)               (< max-possible-done 1)
      (and (map? join) (pos-int? (:n join)))
                                  (< max-possible-done (:n join))
      (and (map? join) (fn? (:fn join)))
                                  (and (zero? n-pending)
                                       (not (join-condition-met? spec join-state)))
      :else                       false)))

(defn- compute-resolution
  "Pure. Given the post-bump `join-state'`, the join spec, and the
  arriving child's `kind` (:done | :failed), decide whether the join
  resolves and which kind of resolution. Returns a map:

      {:resolved?        boolean
       :fail-fired?      boolean
       :success-fired?   boolean
       :resolution-event <event-vec or nil>
       :join-event-kw    <:on-all-complete | :on-some-complete | :on-any-failed | nil>}

  - `:fail-fired?` iff the arriving child errored AND the spec declares
    `:on-any-failed`.
  - `:success-fired?` iff failure didn't fire AND the join condition is
    met by `join-state'`.
  - `:resolution-event` is the spec's event vector to dispatch into the
    parent, or nil when neither path fires.
  - `:join-event-kw` is the resolution kind (used by the
    cancelled-on-join-resolution trace)."
  [spec join-state' kind]
  (let [fail-fired?    (and (= kind :failed)
                            (vector? (:on-any-failed spec)))
        success-fired? (and (not fail-fired?)
                            (join-condition-met? spec join-state'))
        all-join?      (= :all (:join spec :all))
        resolution-event
        (cond
          fail-fired?    (:on-any-failed spec)
          success-fired? (if all-join?
                           (:on-all-complete spec)
                           (:on-some-complete spec)))
        join-event-kw
        (cond
          fail-fired?    :on-any-failed
          success-fired? (if all-join? :on-all-complete :on-some-complete))]
    {:resolved?        (boolean (or fail-fired? success-fired?))
     :fail-fired?      fail-fired?
     :success-fired?   success-fired?
     :resolution-event resolution-event
     :join-event-kw    join-event-kw}))

(defn- decisive-child-reply-facts
  "Build the reply-envelope facts for the DECISIVE child completion that
  drove a `:spawn-all` join resolution, ready to ride
  ADDITIVELY on the resolution trace (Managed-Effects §Tracing /
  §Status taxonomy). The decisive child's completion IS the managed-async
  completion that resolved the join, so it lowers through the shared
  `join-child-reply` (`:status :ok` for the `:done`-side resolutions,
  `:status :error` for the `:on-any-failed` resolution) — the same uniform
  vocabulary the single-`:spawn` `:rf.machine/done` reply carries. Returns
  a tag-map fragment `{:work/id … :rf.reply/status … …}` to merge into the
  resolution trace, or `{}` when no decisive child is resolvable.

  `kind` is the arriving child's fold kind (`:done` / `:failed`);
  `child-extra` is its forwarded payload (the `:value` for a `:done`,
  the error for a `:failed`)."
  [frame-id parent-id invoke-id join-state'' child-id kind child-extra completed-at]
  (let [spawned-id (get-in join-state'' [:children child-id])
        reply      (m-reply/join-child-reply
                     {:parent-id    parent-id
                      :invoke-id    invoke-id
                      :child-id     child-id
                      :spawned-id   spawned-id
                      :frame        frame-id
                      :completed-at completed-at}
                     kind child-extra)
        summary    (m-reply/trace-reply reply {:frame frame-id})]
    (cond-> {:work/id              (:work/id summary)
             :work/kind            (:work/kind summary)
             :rf.reply/status      (:status summary)
             :rf.reply/work-id     (:work/id summary)
             :rf.reply/work-status (:work/status summary)
             :rf.reply/correlation (:correlation summary)}
      (some? (:completed-at summary))
      (assoc :completed-at          (:completed-at summary)
             :rf.reply/completed-at (:completed-at summary)))))

(defn- emit-resolution-traces!
  "Fire the post-resolution observability traces in order: any-failed,
  all-completed, or some-completed.

  The `:frame` tag is REQUIRED for epoch-capture admission
  (`re-frame.epoch.capture/capture-event!` silently drops events whose
  tags lack `:frame`). The caller threads `frame-id` (resolved from
  `(:rf/frame machine)` at the interceptor's entry) so the join
  resolution traces reach the cascade's `:trace-events` slot.

  The DECISIVE child completion that drove the resolution lowers through
  the shared `join-child-reply`; its reply-envelope facts
  (`:work/id`, `:rf.reply/status`, `:rf.reply/work-status`, the causal
  `:completed-at`) ride ADDITIVELY on the resolution trace, so the
  join-resolving child completion classifies the same way the
  single-`:spawn` path does. The public resolution-trace shape
  (`:actor-id` / `:invoke-id` / `:done` / `:failed` / `:reason`) is
  preserved."
  [frame-id parent-id invoke-id spec join-state'' child-id child-extra completed-at
   {:keys [fail-fired? success-fired?]}]
  (when fail-fired?
    (trace/emit! :rf.machine :rf.machine.spawn-all/any-failed
                 (merge {:actor-id parent-id
                         :invoke-id invoke-id
                         :failed-id  child-id
                         :reason     child-extra
                         :failed     (:failed join-state'')
                         :done       (:done   join-state'')
                         :frame      frame-id}
                        (decisive-child-reply-facts
                          frame-id parent-id invoke-id join-state''
                          child-id :failed child-extra completed-at))))
  (when success-fired?
    (let [reply-facts (decisive-child-reply-facts
                        frame-id parent-id invoke-id join-state''
                        child-id :done child-extra completed-at)]
      (if (= :all (:join spec :all))
        (trace/emit! :rf.machine :rf.machine.spawn-all/all-completed
                     (merge {:actor-id parent-id
                             :invoke-id invoke-id
                             :done       (:done join-state'')
                             :frame      frame-id}
                            reply-facts))
        (trace/emit! :rf.machine :rf.machine.spawn-all/some-completed
                     (merge {:actor-id parent-id
                             :invoke-id invoke-id
                             :done       (:done join-state'')
                             :join       (:join spec)
                             :frame      frame-id}
                            reply-facts))))))

(defn- build-resolution-fx
  "Build the fx vector to fire on resolution: per-survivor
  `:rf.machine/destroy` (with one
  `:rf.machine.spawn/cancelled-on-join-resolution` trace each) when
  `:cancel-on-decision?` is true, followed by the join-event dispatch
  carrying the decisive child's forwarded payload. Per Spec 005
  §Spawn-and-join, the dispatched event shape is:

      [<parent-id> [<resolution-event> <decisive-child-id> & <child-extra>]]

  The `:frame` tag is REQUIRED for epoch-capture admission
  (`re-frame.epoch.capture/capture-event!` silently drops events whose
  tags lack `:frame`). The caller threads `frame-id` (resolved from
  `(:rf/frame machine)` at the interceptor's entry) so the per-survivor
  cancellation traces reach the cascade's `:trace-events` slot."
  [frame-id parent-id invoke-id spec join-state'' child-id child-extra
   {:keys [resolved? resolution-event join-event-kw]}]
  (let [cancel? (let [c (:cancel-on-decision? spec)]
                  (if (nil? c) true (boolean c)))
        cancel-fx
        (when (and resolved? cancel?)
          (let [completed-ids (into #{} (concat (:done   join-state'')
                                                (:failed join-state'')))
                survivors     (->> (:children join-state'')
                                   (remove (fn [[cid _]]
                                             (contains? completed-ids cid))))]
            (doseq [[cid spawned-id] survivors]
              ;; A join-survivor cancellation closes the survivor's actor
              ;; work attempt the reply-envelope way: a `:status :cancelled`
              ;; reply (cancellation as DATA, Managed-Effects §Cancellation).
              ;; The reply-envelope facts (`:work/id`
              ;; keyed on the survivor's spawned instance, `:rf.reply/status
              ;; :cancelled`, `:cancel/reason :on-join-resolution`) ride
              ;; ADDITIVELY so the survivor cancellation joins the same
              ;; uniform work/reply row the spawn started — the spawn-all
              ;; analogue of the single-actor destroy cancellation. The
              ;; survivor's own `:rf.machine/destroy` fx ALSO closes it
              ;; through the `:rf.machine/destroyed` cancelled reply; this
              ;; trace carries the join-resolution attribution.
              (let [survivor-summary
                    (m-reply/trace-reply
                      (m-reply/cancelled-actor-reply
                        {:actor-id          spawned-id
                         :parent-id         parent-id
                         :work-bearing-path invoke-id
                         :frame             frame-id
                         :reason            :on-join-resolution})
                      {:frame frame-id})]
                (trace/emit! :rf.machine :rf.machine.spawn/cancelled-on-join-resolution
                             {:actor-id parent-id
                              :invoke-id invoke-id
                              :child-id   cid
                              :spawned-id spawned-id
                              :join-event join-event-kw
                              :frame      frame-id
                              ;; reply-envelope vocabulary (Managed-Effects §9)
                              :work/id              (:work/id survivor-summary)
                              :work/kind            (:work/kind survivor-summary)
                              :rf.reply/status      (:status survivor-summary)
                              :rf.reply/work-id     (:work/id survivor-summary)
                              :rf.reply/work-status (:work/status survivor-summary)
                              :rf.reply/cancelled?  (:cancelled? survivor-summary)
                              :rf.reply/cancel-reason (:cancel/reason survivor-summary)
                              :rf.reply/correlation (:correlation survivor-summary)})))
            (mapv (fn [[_ spawned-id]]
                    [:rf.machine/destroy spawned-id])
                  survivors)))
        dispatch-fx
        (when resolution-event
          (let [inner (vec (concat resolution-event [child-id] child-extra))]
            [[:dispatch [parent-id inner]]]))]
    (vec (concat (or cancel-fx []) (or dispatch-fx [])))))

(defn intercept-spawn-all-event
  "Per Spec 005 §Child completion protocol. When the parent's
  handler receives an event whose inner event-id matches the active
  `:spawn-all`-bearing state's `:on-child-done` / `:on-child-error`,
  the runtime updates the join state and (on resolution) cancels surviving
  siblings + dispatches the join event. The event is NOT fed into the
  machine's normal `:on` lookup.

  Returns nil (NOT a child-event for any active `:spawn-all`) or a
  re-frame effect map with `:rf.db/runtime` (updated runtime-db — the join
  state is durable machine runtime-db state) and `:fx`
  (per-sibling destroys + the join-event dispatch). `runtime-db` is the
  frame's runtime-db partition value (the `:rf.db/runtime` coeffect)."
  [machine runtime-db _path snapshot parent-id inner-event]
  (let [inner-id (first inner-event)
        child-id (second inner-event)
        matches  (find-active-spawn-alls machine snapshot inner-id)
        ;; When more than one active spawn-all matches the event id (two
        ;; parallel regions reusing the SAME `:on-child-done`), route to the
        ;; join whose LIVE join-state `:children` OWNS the arriving child-id —
        ;; ownership, not declaration order, decides. The owning match is
        ;; preferred; if none owns the child (genuinely forged), fall back to
        ;; the first match so the bad-child-id error trace still fires against
        ;; a real join.
        owns?    (fn [{invoke-id :invoke-id}]
                   (let [js (get-in runtime-db (paths/spawned-path parent-id invoke-id))]
                     (and (map? js) (contains? (:children js) child-id))))
        match    (or (some #(when (owns? %) %) matches)
                     (first matches))
        ;; Resolve the live frame from the runtime-stamped machine
        ;; (registration.cljc/prepare-machine-ctx assoc'd `:rf/frame` before
        ;; handing the machine to the interceptor). Threaded into
        ;; `emit-resolution-traces!` / `build-resolution-fx` AND used inline
        ;; for the late-completion + bad-child-id error traces — all of these
        ;; are dropped by epoch-capture without `:frame`.
        frame-id (:rf/frame machine)
        ;; The CAUSAL completion timestamp of the child's finishing dispatch
        ;; (the router-stamped `:rf/time-ms` off the machine def's
        ;; `:rf.cofx`, threaded by prepare-machine-ctx). Rides
        ;; the reply-envelope join-child / late-completion facts the same way
        ;; the single-`:spawn` `:rf.machine/done` reply carries it
        ;; (Managed-Effects §Causal completion metadata). nil for a pure-fn /
        ;; no-cofx caller — then omitted, not nil-filled.
        completed-at (get-in machine [:rf/cofx :rf/time-ms])]
    (when match
      (let [{:keys [spec kind] invoke-id :invoke-id} match
            ;; Per Spec 005 §Spawn-and-join: child dispatches
            ;;   [<parent-id> [<event-kw> <child-id> & extra]]
            ;; where `& extra` is the child's forwarded payload (terminal
            ;; :data slice, error reason, etc). Capture it so the
            ;; decisive child's payload can be appended onto the
            ;; resolution event AND surfaced through the
            ;; :rf.machine.spawn-all/any-failed trace's :reason key
            ;; (Spec 005 §Trace events).
            child-extra (vec (drop 2 inner-event))
            ;; Read the live join state from runtime-db (the seed was written
            ;; by :rf.machine/spawn-all-init on entry).
            join-state (get-in runtime-db (paths/spawned-path parent-id invoke-id))]
        (cond
          ;; Pure-call snapshot: no runtime-db join state seeded yet — fall
          ;; through to no-op (the runtime tracks join state via the fx
          ;; handlers, not via the pure machine-transition).
          (or (not (map? join-state))
              (not (contains? join-state :children)))
          {:rf.db/runtime runtime-db :fx []}

          ;; Already resolved: post-resolution LATE completion. Trace once
          ;; for observability + classify it stale (no further PARENT event
          ;; ever fires — the `:resolved?` latch already flipped). The
          ;; reply-envelope facts mark the completion `:status :stale` /
          ;; `:work/status :suppressed` (Managed-Effects §Stale suppression):
          ;; it is SUPPRESSED from RE-RESOLVING the join — exactly the
          ;; §Stale-suppression "fires no further parent event" rule.
          ;;
          ;; BUT the late child's RESULT still folds into the join-state
          ;; RECORD when `:cancel-on-decision? false` (rf2-obczvv, XState v5
          ;; alignment). Per Spec 005 §Cancel-on-decision: with cancellation
          ;; OFF, surviving siblings RUN TO COMPLETION and "their results
          ;; land in the join-state" so "tools observing the join-state see
          ;; the full late-completion record." Folding the late child into
          ;; `:done` / `:failed` and writing it back is the record update;
          ;; the `:resolved?` latch stays true and NO resolution event /
          ;; cancellation fires (the stale reply records the suppression).
          ;; This reconciles the two halves of the §Cancel-on-decision
          ;; contract: the record IS updated (the full late record is
          ;; observable) while the parent macrostep is NOT re-driven.
          ;;
          ;; When `:cancel-on-decision?` is TRUE (the default) the surviving
          ;; siblings were DESTROYED at resolution, so a late completion is a
          ;; genuinely stale straggler with no live join to fold into — the
          ;; record is left frozen at resolution (the historical drop). The
          ;; public trace shape (`:actor-id` / `:invoke-id` / `:child-id` /
          ;; `:kind`) is preserved on both paths.
          (:resolved? join-state)
          (let [cancel?     (let [c (:cancel-on-decision? spec)]
                              (if (nil? c) true (boolean c)))
                spawned-id  (get-in join-state [:children child-id])
                ;; The late child is only foldable when it is a real,
                ;; not-yet-decided child (a forged / already-folded id must
                ;; not mutate the record). Fold only when cancel is OFF AND
                ;; this child-id is a known child not already in :done/:failed.
                already?    (let [completed (into #{} (concat (:done   join-state)
                                                              (:failed join-state)))]
                              (contains? completed child-id))
                fold?       (and (not cancel?)
                                 (contains? (:children join-state) child-id)
                                 (not already?))
                join-state' (if fold?
                              (case kind
                                :done   (update join-state :done   (fnil conj #{}) child-id)
                                :failed (update join-state :failed (fnil conj #{}) child-id))
                              join-state)
                stale-reply (m-reply/stale-join-child-reply
                              {:parent-id    parent-id
                               :invoke-id    invoke-id
                               :child-id     child-id
                               :spawned-id   spawned-id
                               :frame        frame-id
                               :completed-at completed-at}
                              kind)
                summary    (m-reply/trace-reply stale-reply {:frame frame-id})]
            (trace/emit! :rf.machine :rf.machine.spawn-all/late-completion
                         (cond-> {:actor-id parent-id
                                  :invoke-id invoke-id
                                  :child-id   child-id
                                  :kind       kind
                                  :frame      frame-id
                                  ;; rf2-obczvv — whether the late result was
                                  ;; folded into the record (cancel-off) or
                                  ;; dropped (cancel-on, no live join).
                                  :folded?    fold?
                                  ;; reply-envelope vocabulary (Managed-Effects §9)
                                  :work/id               (:work/id summary)
                                  :work/kind             (:work/kind summary)
                                  :rf.reply/status       (:status summary)
                                  :rf.reply/work-id      (:work/id summary)
                                  :rf.reply/work-status  (:work/status summary)
                                  :rf.reply/stale-reason (:stale/reason summary)
                                  :rf.reply/correlation  (:correlation summary)}
                           (some? (:completed-at summary))
                           (assoc :completed-at          (:completed-at summary)
                                  :rf.reply/completed-at (:completed-at summary))))
            ;; Write back the folded record (cancel-off) so tools observing
            ;; the join-state see the full late-completion record; for the
            ;; cancel-on / forged / already-folded paths `join-state'` is
            ;; identical and `runtime-db` is unchanged. NO resolution fx —
            ;; the join stays latched `:resolved?`, fires no further event.
            {:rf.db/runtime (if fold?
                              (assoc-in runtime-db
                                        (paths/spawned-path parent-id invoke-id)
                                        join-state')
                              runtime-db)
             :fx []})

          ;; Forged / unknown child-id: the inbound `child-id` is NOT in
          ;; the seeded `:children` map. The accident class is a
          ;; hand-crafted dispatch (copy-paste from a sibling :spawn-all,
          ;; typo, cascaded event from a sibling parent) that the runtime
          ;; would otherwise silently fold into `:done` / `:failed`,
          ;; collapsing the join early. Gate it: emit a structured error
          ;; trace and short-circuit with a no-op fx (do NOT mutate the
          ;; join state). Per Spec 005 §Spawn-and-join and the machines
          ;; security-audit finding F1.
          (not (contains? (:children join-state) child-id))
          (do (trace/emit-error! :rf.error/machine-spawn-all-bad-child-id
                                 {:actor-id parent-id
                                  :invoke-id invoke-id
                                  :child-id   child-id
                                  :children   (set (keys (:children join-state)))
                                  :kind       kind
                                  :frame      frame-id
                                  :recovery   :event-dropped})
              {:rf.db/runtime runtime-db :fx []})

          :else
          ;; Read 'compute resolution; emit traces; build fx; write back':
          ;; the body is now three named acts plus an assoc-in.
          (let [join-state' (case kind
                              :done   (update join-state :done   (fnil conj #{}) child-id)
                              :failed (update join-state :failed (fnil conj #{}) child-id))
                resolution   (compute-resolution spec join-state' kind)
                join-state'' (assoc join-state' :resolved? (:resolved? resolution))]
            ;; Surface a join that just became UNSATISFIABLE.
            ;; When a child FAILS and the spec has no `:on-any-failed`, the
            ;; failure folds into `:failed` without resolving; once enough
            ;; children have failed that the success condition is unreachable
            ;; the join hangs forever, silently. Emit a one-shot advisory on
            ;; the fold that FIRST makes the join unsatisfiable (it was
            ;; satisfiable before this fold, and this fold did not resolve) so
            ;; the operator sees the dead join + the likely fix (declare
            ;; `:on-any-failed`). Advisory severity: the request is not
            ;; recovered, but the actor is not crashed — this is a config
            ;; footgun nudge, the dev-advisory family (`:on-spawn-return-
            ;; ignored`, the cofx lints), not an operation-recovery emit.
            (when (and (not (:resolved? resolution))
                       (join-unsatisfiable? spec join-state')
                       (not (join-unsatisfiable? spec join-state)))
              (trace/emit! :warning :rf.warning/spawn-all-join-unsatisfiable
                           {:actor-id  parent-id
                            :invoke-id invoke-id
                            :join      (:join spec :all)
                            :done      (:done   join-state')
                            :failed    (:failed join-state')
                            :total     (count (:children spec))
                            :frame     frame-id
                            :recovery  :join-hangs
                            :reason    (str "A :spawn-all join can no longer be "
                                            "satisfied — too many children have failed "
                                            "and no :on-any-failed transition is declared, "
                                            "so the join will hang forever. Declare "
                                            ":on-any-failed to handle child failures.")}))
            (emit-resolution-traces! frame-id parent-id invoke-id spec join-state''
                                     child-id child-extra completed-at resolution)
            (let [fx (build-resolution-fx frame-id parent-id invoke-id spec join-state''
                                          child-id child-extra resolution)]
              {:rf.db/runtime (assoc-in runtime-db (paths/spawned-path parent-id invoke-id) join-state'')
               :fx fx})))))))
