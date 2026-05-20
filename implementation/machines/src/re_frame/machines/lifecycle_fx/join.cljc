(ns re-frame.machines.lifecycle-fx.join
  "`:spawn-all` join-event interception (rf2-6vmw).

  Per Spec 005 §Spawn-and-join via `:spawn-all` §Child completion protocol,
  the parent's handler boundary intercepts events whose inner-event-id
  matches the active state's `:on-child-done` / `:on-child-error`. The
  interception:

   1. Resolves the active `:spawn-all`-bearing state by walking the
      snapshot's `:state` path leaf→root looking for a state node whose
      `:spawn-all` declares the matching event keyword.
   2. Reads the join state at `[:rf/spawned <parent> <invoke-id>]`.
   3. Verifies `<child-id>` (event[1]) is one of the parent's spawned
      children. Forged / unknown ids are rejected with the
      `:rf.error/machine-spawn-all-bad-child-id` error trace and a
      no-op fx (the join state is NOT mutated). See rf2-ns8ut.
   4. Adds `<child-id>` to `:done` or `:failed`.
   5. If `:resolved?` is already true, the event is silently dropped
      (post-resolution late-completion).
   6. Else evaluates the join condition. On resolution:
        - latches `:resolved?` true,
        - if `:cancel-on-decision?` (default true), emits per-sibling
          `:rf.machine/destroy` fx and `:rf.machine.spawn/cancelled-on-
          join-resolution` traces,
        - dispatches the parent join event via `:fx [[:dispatch ...]]`.
   7. Writes the new join state back into app-db.

  The interceptor's public entry point is `intercept-invoke-all-event`;
  the handler-factory in `re-frame.machines.lifecycle-fx.registration`
  routes every inbound event through it before the machine's normal `:on`
  lookup."
  (:require [re-frame.machines.parallel :as parallel]
            [re-frame.machines.path-walk :as path-walk]
            [re-frame.machines.transition :as transition]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

(defn- find-active-invoke-all-in-tree
  "Helper for `find-active-invoke-all`. Given a machine-like map with
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
          {:spawn-id prefix :spec ia :kind :done}
          (= inner-event-id (:on-child-error ia))
          {:spawn-id prefix :spec ia :kind :failed})))))

(defn- find-active-invoke-all
  "Walk the snapshot's `:state` path leaf→root looking for an
  `:spawn-all`-bearing state whose `:on-child-done` or `:on-child-error`
  matches the given inner-event-id. Returns
  `{:spawn-id <prefix-path> :spec <invoke-all-spec> :kind :done|:failed}`
  or nil.

  Per Spec 005 §Parallel regions (rf2-l67o): for parallel-region
  machines, iterates each region's active state-tree (prefixing the
  region name onto the resolved `:spawn-id`, matching the per-region
  scoping `prefix-region-invoke-id` applies on the entry-side)."
  [machine snapshot inner-event-id]
  (cond
    (parallel/parallel? machine)
    (some (fn [[region-name region-state]]
            (let [region-body (parallel/region-machine machine region-name)
                  region-path (transition/state-path region-state)
                  match       (find-active-invoke-all-in-tree
                                region-body region-path inner-event-id)]
              (when match
                (update match :spawn-id #(vec (cons region-name %))))))
          (:state snapshot))

    :else
    (find-active-invoke-all-in-tree machine (transition/state-path (:state snapshot)) inner-event-id)))

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

(defn- emit-resolution-traces!
  "Fire the post-resolution observability traces in order: any-failed,
  all-completed, or some-completed.

  Per rf2-ko8jb the `:frame` tag is REQUIRED for epoch-capture admission
  (`re-frame.epoch.capture/capture-event!` silently drops events whose
  tags lack `:frame`). The caller threads `frame-id` (resolved from
  `(:rf/frame machine)` at the interceptor's entry) so the join
  resolution traces reach the cascade's `:trace-events` slot."
  [frame-id parent-id invoke-id spec join-state'' child-id child-extra
   {:keys [fail-fired? success-fired?]}]
  (when fail-fired?
    (trace/emit! :machine :rf.machine.spawn-all/any-failed
                 {:machine-id parent-id
                  :spawn-id  invoke-id
                  :failed-id  child-id
                  :reason     child-extra
                  :failed     (:failed join-state'')
                  :done       (:done   join-state'')
                  :frame      frame-id}))
  (when success-fired?
    (if (= :all (:join spec :all))
      (trace/emit! :machine :rf.machine.spawn-all/all-completed
                   {:machine-id parent-id
                    :spawn-id  invoke-id
                    :done       (:done join-state'')
                    :frame      frame-id})
      (trace/emit! :machine :rf.machine.spawn-all/some-completed
                   {:machine-id parent-id
                    :spawn-id  invoke-id
                    :done       (:done join-state'')
                    :join       (:join spec)
                    :frame      frame-id}))))

(defn- build-resolution-fx
  "Build the fx vector to fire on resolution: per-survivor
  `:rf.machine/destroy` (with one
  `:rf.machine.spawn/cancelled-on-join-resolution` trace each) when
  `:cancel-on-decision?` is true, followed by the join-event dispatch
  carrying the decisive child's forwarded payload. Per Spec 005
  §Spawn-and-join, the dispatched event shape is:

      [<parent-id> [<resolution-event> <decisive-child-id> & <child-extra>]]

  Per rf2-ko8jb the `:frame` tag is REQUIRED for epoch-capture admission
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
              (trace/emit! :machine :rf.machine.spawn/cancelled-on-join-resolution
                           {:machine-id parent-id
                            :spawn-id  invoke-id
                            :child-id   cid
                            :spawned-id spawned-id
                            :join-event join-event-kw
                            :frame      frame-id}))
            (mapv (fn [[_ spawned-id]]
                    [:rf.machine/destroy spawned-id])
                  survivors)))
        dispatch-fx
        (when resolution-event
          (let [inner (vec (concat resolution-event [child-id] child-extra))]
            [[:dispatch [parent-id inner]]]))]
    (vec (concat (or cancel-fx []) (or dispatch-fx [])))))

(defn intercept-invoke-all-event
  "Per Spec 005 §Child completion protocol (rf2-6vmw). When the parent's
  handler receives an event whose inner event-id matches the active
  `:spawn-all`-bearing state's `:on-child-done` / `:on-child-error`,
  the runtime updates the join state and (on resolution) cancels surviving
  siblings + dispatches the join event. The event is NOT fed into the
  machine's normal `:on` lookup.

  Returns nil (NOT a child-event for any active `:spawn-all`) or a
  re-frame effect map with `:db` (updated app-db) and `:fx` (per-sibling
  destroys + the join-event dispatch)."
  [machine db _path snapshot parent-id inner-event]
  (let [inner-id (first inner-event)
        match    (find-active-invoke-all machine snapshot inner-id)
        ;; Per rf2-ko8jb: resolve the live frame from the runtime-stamped
        ;; machine (registration.cljc/prepare-machine-ctx assoc'd
        ;; `:rf/frame` before handing the machine to the interceptor).
        ;; Threaded into `emit-resolution-traces!` / `build-resolution-fx`
        ;; AND used inline for the late-completion + bad-child-id error
        ;; traces — all of these are dropped by epoch-capture without
        ;; `:frame`.
        frame-id (:rf/frame machine)]
    (when match
      (let [{:keys [spec kind] invoke-id :spawn-id} match
            child-id   (second inner-event)
            ;; Per Spec 005 §Spawn-and-join: child dispatches
            ;;   [<parent-id> [<event-kw> <child-id> & extra]]
            ;; where `& extra` is the child's forwarded payload (terminal
            ;; :data slice, error reason, etc). Capture it so the
            ;; decisive child's payload can be appended onto the
            ;; resolution event AND surfaced through the
            ;; :rf.machine.spawn-all/any-failed trace's :reason key
            ;; (Spec 005 §Trace events).
            child-extra (vec (drop 2 inner-event))
            ;; Read the live join state from app-db (the seed was written
            ;; by :rf.machine/spawn-all-init on entry).
            join-state (get-in db [:rf/spawned parent-id invoke-id])]
        (cond
          ;; Pure-call snapshot: no app-db join state seeded yet — fall
          ;; through to no-op (the runtime tracks join state via the fx
          ;; handlers, not via the pure machine-transition).
          (or (not (map? join-state))
              (not (contains? join-state :children)))
          {:db db :fx []}

          ;; Already resolved: ignore late-completion. Trace once for
          ;; observability so tools can correlate.
          (:resolved? join-state)
          (do (trace/emit! :machine :rf.machine.spawn-all/late-completion
                           {:machine-id parent-id
                            :spawn-id  invoke-id
                            :child-id   child-id
                            :kind       kind
                            :frame      frame-id})
              {:db db :fx []})

          ;; Forged / unknown child-id: the inbound `child-id` is NOT in
          ;; the seeded `:children` map. The accident class is a
          ;; hand-crafted dispatch (copy-paste from a sibling :spawn-all,
          ;; typo, cascaded event from a sibling parent) that the runtime
          ;; would otherwise silently fold into `:done` / `:failed`,
          ;; collapsing the join early. Gate it: emit a structured error
          ;; trace and short-circuit with a no-op fx (do NOT mutate the
          ;; join state). Per Spec 005 §Spawn-and-join and the machines
          ;; security-audit finding F1 (rf2-ns8ut / rf2-s9tf8).
          (not (contains? (:children join-state) child-id))
          (do (trace/emit-error! :rf.error/machine-spawn-all-bad-child-id
                                 {:machine-id parent-id
                                  :spawn-id  invoke-id
                                  :child-id   child-id
                                  :children   (set (keys (:children join-state)))
                                  :kind       kind
                                  :frame      frame-id
                                  :recovery   :event-dropped})
              {:db db :fx []})

          :else
          ;; Read 'compute resolution; emit traces; build fx; write back':
          ;; the body is now three named acts plus an assoc-in.
          (let [join-state' (case kind
                              :done   (update join-state :done   (fnil conj #{}) child-id)
                              :failed (update join-state :failed (fnil conj #{}) child-id))
                resolution   (compute-resolution spec join-state' kind)
                join-state'' (assoc join-state' :resolved? (:resolved? resolution))]
            (emit-resolution-traces! frame-id parent-id invoke-id spec join-state''
                                     child-id child-extra resolution)
            (let [fx (build-resolution-fx frame-id parent-id invoke-id spec join-state''
                                          child-id child-extra resolution)]
              {:db (assoc-in db [:rf/spawned parent-id invoke-id] join-state'')
               :fx fx})))))))
