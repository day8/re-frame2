(ns re-frame.machines.hydrate
  "SSR hydration re-arm for machine `:after` timers — the client-side
  counterpart of `re-frame.machines.ssr`'s wire projection.

  ## The hole this closes

  Durable machine state and host work are two different things, and only
  the first one crosses the wire. The server renders a machine's `:state`
  statically and schedules NO wall-clock timer (Spec 005 §SSR mode; the
  pure side emits `:rf.machine.timer/skipped-on-server` in place of
  `/scheduled`). The hydration payload then carries the snapshot — right
  `:state`, right `:data`, right `:rf/after-epoch` — and the client
  installs it verbatim. Nothing in that path reconstructs the host-side
  timer table, so a machine hydrated while sitting in an `:after`-bearing
  state had the correct durable state and NO live timer: it could sit
  there forever unless some unrelated external event moved it.

  Spec 011 §`:after` is no-op under SSR names the two admissible
  handoffs — re-fire entry actions on hydration, or \"treat hydration as a
  special case that schedules `:after` timers without re-running other
  entry effects\" — and requires that `:after` timers begin running on the
  client *per the snapshot's epoch*. This namespace is the second
  handoff, which is the one that is actually safe: re-running entry would
  re-arm the timers AND re-fire `:entry` actions, raises and spawns that
  already happened on the server.

  ## What it does, and what it deliberately does not

  Given a frame whose runtime-db has just been REPLACED by a hydration
  payload, walk `[:rf.runtime/machines :snapshots]`, resolve each actor's
  machine spec the same way dispatch does, enumerate the `:after`
  declarations the snapshot's ACTIVE configuration keeps live, and arm one
  client timer for each at the epoch already recorded.

  Enumeration is ACTIVE-configuration-shaped, not whole-spec-shaped:

    - flat / compound — every node from the root down to the active leaf
      (`transition/nodes-along-path`), because a still-active ANCESTOR's
      `:after` is as live as the leaf's;
    - parallel — the same walk inside each region's own active path,
      against the synthetic region-spec so the region's per-region epoch
      slot and region-prefixed invoke-id are the ones the runtime already
      uses; plus the ROOT-owned `:after` at decl-path `[]`, which belongs
      to the machine rather than to any region.

  It runs NO cascade. No `:entry`, no `:exit`, no `:action`, no `:raise`,
  no `:spawn`, no `:always` — none of the historical effects the server
  already performed are replayed, and no snapshot is written (the epoch is
  read, never bumped). The only thing this seam creates is host work.

  ## Remaining duration: the FULL declared delay

  A reconstructed timer is armed for its whole declared delay measured
  from hydration, not for a remainder. That is not a preference — it is
  the only reading the durable data supports. The snapshot records the
  `:after` epoch and nothing else about the timer: Spec 005 §Clock
  abstraction states outright that `:after` scheduling reads the host
  clock to schedule but never records the schedule instant, precisely so
  that firing stays replay-sound as a facts-against-facts epoch check
  rather than a wall-clock comparison. There is consequently no
  `scheduled-at` / `due-at` anywhere on the wire to subtract from, and
  Spec 011 §`:after` is no-op under SSR gives the matching rationale:
  `:after` timers are state-entry-relative and \"have no semantic meaning
  until the user is interacting with the page — i.e., until after
  hydration\". The client's arrival IS the entry, for timing purposes.

  ## Not the epoch-restore path

  Epoch restore is the mirror image and stays that way: it CANCELS the
  frame's in-flight `:after` handles (`:machines/on-frame-restored!` →
  `timer/cancel-frame-timers-on-restore!`, one
  `:rf.machine.timer/cancelled :reason :on-restore` per entry) and never
  re-arms, because Managed-Effects §SSR, preload, hydration, and restore
  rules that restore MUST NOT revive host work. The asymmetry is real and
  intended: restore rewinds a timeline that already ran its host work,
  while hydration establishes host work that was never armed at all. This
  namespace is reached only from the `:rf/hydrate` seam and shares no
  callback with restore.

  Pure / host-agnostic CLJC apart from the one arming call — the walk is a
  pure function of `[spec snapshot]` and is tested as such."
  (:require [re-frame.frame :as frame]
            [re-frame.machines.lifecycle-fx.resolver :as resolver]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.timeout :as timeout]
            [re-frame.machines.timer :as timer]
            [re-frame.machines.transition :as transition]))

#?(:clj (set! *warn-on-reflection* true))

(defn- walkable-state?
  "True iff `state` is a shape `transition/state-path` can normalise — a
  leaf keyword or a vector path.

  A snapshot arriving from the wire is deserialised, untrusted data. Per
  Spec 011 §The `:rf/hydrate` event hydration is best-effort
  (degraded-but-running), so a snapshot whose `:state` is neither shape is
  SKIPPED here rather than throwing out of the fx and taking the other
  actors' timers down with it. Such a snapshot is corrupt for every other
  machine read too, and the first ordinary dispatch against it raises the
  named `:rf.error/machine-bad-state-form` with the diagnostic that owns
  that condition — this seam does not duplicate it."
  [state]
  (or (keyword? state) (vector? state)))

(defn- decls-along-path
  "Every live `:after` declaration on the active `path` of one SCOPE —
  `machine` is a flat/compound machine spec or a synthetic region-spec.

  Returns a vector of
  `{:invoke-id <vec> :state <kw> :delay-key <k> :epoch <int>}`, one entry
  per delay-key per `:after`-bearing node along the path.

  `prefix-fn` maps the in-scope decl-path to the invoke-id the timer table
  is keyed by: identity-ish (`vec`) for a flat/compound machine, and the
  region-name prepend for a region — the same shape
  `parallel/prefix-region-invoke-id` stamps on the region's own
  `:rf.machine/after-schedule` fxs, so a hydrated timer lands in the slot
  a subsequent state exit will cancel.

  `:state` is `(last decl-path)` — exactly what `build-after-fx` puts on
  the `/scheduled` row for an entered node, so the trace pairing is
  identical whether the timer was entered into or hydrated into."
  [machine snapshot path prefix-fn]
  (into []
        (mapcat (fn [[decl-path node]]
                  (when-let [after-map (:after node)]
                    (let [epoch (transition/node-epoch machine snapshot decl-path)]
                      (map (fn [[delay-key _target]]
                             {:invoke-id (prefix-fn decl-path)
                              :state     (last decl-path)
                              :delay-key delay-key
                              :epoch     epoch})
                           after-map)))))
        (transition/nodes-along-path machine path)))

(defn- root-parallel-decls
  "The ROOT-owned `:after` declarations of a parallel machine (Spec 005
  §Root parallel `:after`). The root is not a region, so its epoch lives
  at the flat slot under decl-path `[]`, its invoke-id is `[]`, and its
  `:state` is the `:rf/parallel-root` sentinel — matching what
  `schedule-root-after-fx` / `build-after-fx` emit at machine birth, and
  what `root-after-match` expects when the timer fires."
  [spec snapshot]
  (if-let [after-map (:after spec)]
    (let [epoch (transition/node-epoch spec snapshot [])]
      (mapv (fn [[delay-key _target]]
              {:invoke-id []
               :state     :rf/parallel-root
               :delay-key delay-key
               :epoch     epoch})
            after-map))
    []))

(defn active-after-decls
  "PURE. Enumerate every `:after` declaration one hydrated `snapshot` keeps
  live under `spec`, as
  `[{:invoke-id … :state … :delay-key … :epoch …} …]`.

  `spec` is desugared first (`timeout/desugar-timeouts` — idempotent, and
  a no-rebuild fast path for the timeout-free common case) so an authored
  `:timeout` / `:on-timeout` is enumerated through the `:after` it lowers
  onto, exactly as the transition engine sees it.

  Returns `[]` for a snapshot with no active `:after` — a machine sitting
  in an ordinary state hydrates with no timers, which is the whole
  correctness point of enumerating the ACTIVE configuration rather than
  the spec."
  [spec snapshot]
  (let [spec (timeout/desugar-timeouts spec)
        st   (:state snapshot)]
    (cond
      (parallel/parallel? spec)
      ;; A parallel snapshot's `:state` is a region-name → region-state
      ;; map. Walk each region's own active path against the synthetic
      ;; region-spec (which carries `:rf/region`, so `node-epoch` reads the
      ;; per-region epoch slot), then add the root-owned declarations.
      (into (root-parallel-decls spec snapshot)
            (mapcat (fn [[region-name region-state]]
                      (when (and (contains? (:regions spec) region-name)
                                 (walkable-state? region-state))
                        (decls-along-path (parallel/region-machine spec region-name)
                                          snapshot
                                          (transition/state-path region-state)
                                          #(vec (cons region-name %))))))
            (when (map? st) st))

      (walkable-state? st)
      (decls-along-path spec snapshot (transition/state-path st) vec)

      :else [])))

(defn rearm-after-timers!
  "Reconstruct the host-side `:after` timer table for `frame-id` from the
  machine snapshots its runtime-db currently holds. The body behind the
  `:machines/rearm-after-hydration!` late-bind hook and the
  `:rf.machine/hydrate-rearm` fx; called ONLY after a valid `:rf/hydrate`
  has committed the payload's runtime-db.

  Refuses a `:server` frame — the frame's own `:platform` config resolved
  the way `registration/prepare-machine-ctx` resolves it, so this seam and
  `build-after-fx`'s server-skip can never disagree about which platform a
  frame is. A frame with no `:platform` config is a client (the machines
  default), so an ordinary JVM test frame re-arms.

  Idempotent by the ordinary timer-table key: a second install over the
  same snapshots supersedes the first arm (one `:on-supersede` cancel,
  one fresh arm) rather than leaving two handles on one declaration.

  Returns nil; the only observable is the timer table plus one
  `:rf.machine.timer/scheduled` trace per armed declaration."
  [frame-id]
  (when (and frame-id
             (not= :server (or (:platform (frame/frame-meta frame-id)) :client)))
    (let [snapshots (get-in (frame/frame-runtime-db-value frame-id)
                            (paths/snapshot-path))]
      (doseq [[actor-id snapshot] snapshots
              :when (map? snapshot)
              :let  [spec (resolver/spec-from-id-or-snapshot actor-id snapshot)]
              :when (map? spec)
              decl  (active-after-decls spec snapshot)]
        (timer/rearm-hydrated-after-timer!
          frame-id actor-id (:invoke-id decl) (:state decl)
          (:delay-key decl) (:epoch decl) snapshot))))
  nil)
