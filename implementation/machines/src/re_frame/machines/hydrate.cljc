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
  machine spec the same way dispatch does, and enumerate the `:after`
  declarations the snapshot's ACTIVE configuration keeps live. That
  enumeration is then RECONCILED against the frame's timer table, not
  merely added to it: every entry outside the live set is cancelled, and
  the live set is armed at the epoch already recorded.

  The cancel half exists because the two sides of a hydration are not
  symmetrical. `:rf/hydrate` replaces runtime-db wholesale, but the timer
  table is host state and survives that replacement untouched — so a frame
  that already held timers (from before the hydration, or from an earlier
  one) keeps a handle for every declaration the replacement DROPS: an
  actor that is gone from the new snapshots, an `:after`-bearing state
  replaced by a no-`:after` one, a shrunken delay set. Superseding cannot
  reach those; it only ever touches the one timer-table key it is arming.
  The epoch and active-path gates would suppress the eventual stale
  TRANSITION, but the host work would still be held — a literal handle
  until it fired, and a subscription-delay entry indefinitely, holding its
  reaction, its change-watcher and its subscription ref-count with it.

  Enumeration is ACTIVE-configuration-shaped, not whole-spec-shaped:

    - flat / compound — every node from the root down to the active leaf
      (`rf.machines.transition/nodes-along-path`), because a still-active ANCESTOR's
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
  `rf.machines.timer/cancel-frame-timers-on-restore!`, one
  `:rf.machine.timer/cancelled :reason :on-restore` per entry) and never
  re-arms, because Managed-Effects §SSR, preload, hydration, and restore
  rules that restore MUST NOT revive host work. The asymmetry is real and
  intended: restore rewinds a timeline that already ran its host work,
  while hydration establishes host work that was never armed at all. This
  namespace is reached only from the `:rf/hydrate` seam and shares no
  callback with restore.

  Pure / host-agnostic CLJC apart from the one arming call — the walk is a
  pure function of `[spec snapshot]` and is tested as such."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.machines.lifecycle-fx.resolver :as rf.machines.lifecycle-fx.resolver]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.timeout :as rf.machines.timeout]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.machines.transition :as rf.machines.transition]))

#?(:clj (set! *warn-on-reflection* true))

(defn- walkable-state?
  "True iff `state` is a shape `rf.machines.transition/state-path` can normalise — a
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
  `rf.machines.parallel/prefix-region-invoke-id` stamps on the region's own
  `:rf.machine/after-schedule` fxs, so a hydrated timer lands in the slot
  a subsequent state exit will cancel.

  `:state` is `(last decl-path)` — exactly what `build-after-fx` puts on
  the `/scheduled` row for an entered node, so the trace pairing is
  identical whether the timer was entered into or hydrated into."
  [machine snapshot path prefix-fn]
  (into []
        (mapcat (fn [[decl-path node]]
                  (when-let [after-map (:after node)]
                    (let [epoch (rf.machines.transition/node-epoch machine snapshot decl-path)]
                      (map (fn [[delay-key _target]]
                             {:invoke-id (prefix-fn decl-path)
                              :state     (last decl-path)
                              :delay-key delay-key
                              :epoch     epoch})
                           after-map)))))
        (rf.machines.transition/nodes-along-path machine path)))

(defn- root-parallel-decls
  "The ROOT-owned `:after` declarations of a parallel machine (Spec 005
  §Root parallel `:after`). The root is not a region, so its epoch lives
  at the flat slot under decl-path `[]`, its invoke-id is `[]`, and its
  `:state` is the `:rf/parallel-root` sentinel — matching what
  `schedule-root-after-fx` / `build-after-fx` emit at machine birth, and
  what `root-after-match` expects when the timer fires."
  [spec snapshot]
  (if-let [after-map (:after spec)]
    (let [epoch (rf.machines.transition/node-epoch spec snapshot [])]
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

  `spec` is desugared first (`rf.machines.timeout/desugar-timeouts` — idempotent, and
  a no-rebuild fast path for the timeout-free common case) so an authored
  `:timeout` / `:on-timeout` is enumerated through the `:after` it lowers
  onto, exactly as the transition engine sees it.

  Returns `[]` for a snapshot with no active `:after` — a machine sitting
  in an ordinary state hydrates with no timers, which is the whole
  correctness point of enumerating the ACTIVE configuration rather than
  the spec."
  [spec snapshot]
  (let [spec (rf.machines.timeout/desugar-timeouts spec)
        st   (:state snapshot)]
    (cond
      (rf.machines.parallel/parallel? spec)
      ;; A parallel snapshot's `:state` is a region-name → region-state
      ;; map. Walk each region's own active path against the synthetic
      ;; region-spec (which carries `:rf/region`, so `node-epoch` reads the
      ;; per-region epoch slot), then add the root-owned declarations.
      (into (root-parallel-decls spec snapshot)
            (mapcat (fn [[region-name region-state]]
                      (when (and (contains? (:regions spec) region-name)
                                 (walkable-state? region-state))
                        (decls-along-path (rf.machines.parallel/region-machine spec region-name)
                                          snapshot
                                          (rf.machines.transition/state-path region-state)
                                          #(vec (cons region-name %))))))
            (when (map? st) st))

      (walkable-state? st)
      (decls-along-path spec snapshot (rf.machines.transition/state-path st) vec)

      :else [])))

(defn live-declarations
  "PURE. Every `:after` declaration the whole `snapshots` map keeps live, as
  a vector of `active-after-decls` entries each carrying its owning
  `:actor-id` and the `:snapshot` the arm must resolve its delay against.

  This is the enumeration BOTH halves of the reconcile read: the cancel
  half takes the set of `[actor-id invoke-id delay-key]` identities from it,
  the arm half walks it. Computing it once is what makes the two halves
  agree by construction — a table entry is cancelled if and only if this
  walk did not produce it.

  A snapshot that is not a map, or whose actor resolves to no machine spec,
  contributes nothing and takes nothing else down with it (Spec 011
  §The `:rf/hydrate` event — hydration is best-effort)."
  [snapshots]
  (vec (for [[actor-id snapshot] snapshots
             :when (map? snapshot)
             :let  [spec (rf.machines.lifecycle-fx.resolver/spec-from-id-or-snapshot actor-id snapshot)]
             :when (map? spec)
             decl  (active-after-decls spec snapshot)]
         (assoc decl :actor-id actor-id :snapshot snapshot))))

(defn rearm-after-timers!
  "RECONCILE the host-side `:after` timer table for `frame-id` to the
  machine snapshots its runtime-db currently holds. The body behind the
  `:machines/rearm-after-hydration!` late-bind hook and the
  `:rf.machine/hydrate-rearm` fx; called ONLY after a valid `:rf/hydrate`
  has committed the payload's runtime-db.

  A reconcile, not a union. `:rf/hydrate` replaces runtime-db WHOLESALE
  while the timer table — host state — survives the replacement untouched,
  so arming the replacement's live declarations is only half of it. The
  frame may hold timers from before the hydration, or from an earlier
  `:rf/hydrate`; where the replacement drops an actor, moves an
  `:after`-bearing state to a no-`:after` one, or shrinks a delay set,
  those handles belong to declarations no installed snapshot makes.
  Superseding cannot reach them (`schedule-after-timer!` only ever touches
  the one key it is arming), so the two phases are explicit:

    1. CANCEL — `rf.machines.timer/cancel-timers-absent-from!` takes the set
       difference and releases every entry outside the live set, each with
       the ordinary closed-set `:reason` naming why it went
       (`:on-destroy` for a dropped actor, `:on-exit` for a declaring node
       no longer on the active configuration). A subscription-delay entry
       releases its reaction, watcher and subscription ref-count with it.
    2. ARM — one `rearm-hydrated-after-timer!` per live declaration, at
       the epoch the snapshot already carries. A declaration that was
       already armed is superseded in place by the ordinary timer-table
       key, so repeating an identical hydration leaves one handle per
       declaration rather than two.

  Refuses a `:server` frame — the frame's own `:platform` config resolved
  the way `registration/prepare-machine-ctx` resolves it, so this seam and
  `build-after-fx`'s server-skip can never disagree about which platform a
  frame is. A frame with no `:platform` config is a client (the machines
  default), so an ordinary JVM test frame re-arms. The refusal covers the
  cancel phase too: a server-side hydrate reconciles nothing, because it
  arms nothing to reconcile against.

  Runs NO cascade in either phase — no `:entry`, no `:exit`, no `:action`,
  no `:raise`, no `:spawn`, and no snapshot write. A cancelled timer's
  state is simply not entered on the client; the durable exit already
  happened wherever the replacement snapshot came from.

  Sibling frames are untouched (the table is partitioned per frame), and
  epoch restore keeps its opposite contract — `:machines/on-frame-restored!`
  cancels and never re-arms, and shares no callback with this seam.

  ## One incarnation for the whole reconcile

  Both phases emit callback-bearing traces —
  `:rf.machine.timer/cancelled` in phase 1, and in phase 2 both each
  arm's leading `:on-supersede` and the arm's own
  `:rf.machine.timer/scheduled` (hydration arms with
  `:emit-scheduled-trace?` true, so it is emitted here rather than by a
  transition; on a frame that held no prior timer it is the FIRST
  callback of the whole reconcile) — and a listener on any of them can
  `destroy-frame!` this frame and publish a same-id successor B. The
  declarations being reconciled are A's: they were enumerated from the
  runtime-db A held. Installing them into B would be host work derived
  from a frame that no longer exists, which is the same class of leak the
  cancel half exists to close, arriving from the other direction.

  So the reconcile captures the owning incarnation ONCE, before either
  phase, and every callback-bearing step is fenced by that ONE predicate
  (rf2-jqvgp): the cancel batch short-circuits on it, the arm loop
  rechecks it before each declaration, and each arm carries it down into
  `schedule-after-timer!` in place of a fresh capture — where it fences
  BOTH of that fn's callback boundaries, the leading `:on-supersede`
  cancel and the `/scheduled` emit, each with its own recheck before any
  durable step. Per-step capture
  is what fails here, and it fails silently: each step alone is correct
  about the owner it captured, and B — a live frame with the right id —
  accepts the work without complaint. The loop is explicit rather than a
  `doseq` for exactly that recheck.

  Returns nil; the only observables are the timer table, one
  `:rf.machine.timer/scheduled` trace per armed declaration, and one
  `:rf.machine.timer/cancelled` trace per released one."
  [frame-id]
  (when (and frame-id
             (not= :server (or (:platform (rf.frame/frame-meta frame-id)) :client)))
    ;; Captured BEFORE anything callback-bearing runs, so it names the
    ;; incarnation whose runtime-db the declarations below are read from.
    (let [owner-gone? (rf.machines.timer/successor-published?-fn frame-id)
          snapshots   (get-in (rf.frame/frame-runtime-db-value frame-id)
                              (rf.machines.paths/snapshot-path))
          live        (live-declarations snapshots)]
      ;; PHASE 1 — release the host work the replacement snapshots dropped.
      ;; Before the arm, so a declaration that survives is superseded by its
      ;; own re-arm rather than cancelled and re-created.
      (rf.machines.timer/cancel-timers-absent-from!
        frame-id
        (into #{} (map (juxt :actor-id :invoke-id :delay-key)) live)
        (set (keys snapshots))
        owner-gone?)
      ;; PHASE 2 — arm / supersede the live set, while the frame is still the
      ;; one these declarations were enumerated from.
      (loop [decls live]
        (when (and (seq decls) (not (owner-gone?)))
          (let [decl (first decls)]
            (rf.machines.timer/rearm-hydrated-after-timer!
              frame-id (:actor-id decl) (:invoke-id decl) (:state decl)
              (:delay-key decl) (:epoch decl) (:snapshot decl) owner-gone?))
          (recur (rest decls))))))
  nil)
