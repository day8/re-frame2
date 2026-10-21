(ns re-frame.machines.timer
  "Wall-clock `:after` timer scheduling. Per Spec 005 §Delayed `:after`
  transitions.

  On entry to an `:after`-bearing state node, the pure transition engine
  (`re-frame.machines.transition`) emits one `:rf.machine/after-schedule`
  fx per `:after` entry. The fx handler here resolves the delay (pos-int?
  literal / ISO-8601 duration string / `:delays` name / fn-form) once, against
  the committed snapshot, schedules a real timer via
  `rf.interop/schedule-after!` (the Spec 005 §Clock abstraction primitive —
  `set-timeout!`'s spec-named machines surface) and emits the
  `:rf.machine.timer/scheduled` row with the milliseconds it armed. On expiry
  the timer dispatches the synthetic

      [<parent-id> [:rf.machine.timer/after-elapsed <delay-key> <epoch> <decl-path>]]

  back into the parent machine via the late-bound `:router/dispatch!`
  hook. Per Spec 005 §Hierarchy interaction the scheduling node's
  declaring path (`<decl-path>`) travels with EVERY timer alongside its
  per-path epoch — the event is always this 4-element shape, and it carries
  the armed milliseconds as metadata (`transition/elapsed-event`) for the
  `/fired` and `/stale-after` rows.
  Pick-after-transition (in transition) resolves the delay-key
  against the active state path's `:after` table; epoch-mismatch surfaces
  as `:rf.machine.timer/stale-after`, epoch-match drives the transition
  through the standard cascade.

  A frame-scoped timer table tracks live handles so cancellation (state
  exit, actor or frame destroy) can clear them. The table is
  partitioned per frame (`{<frame-id> {<inner-key> <entry>}}`), so
  concurrent frames in the same process — the common test-fixture and
  SSR-load shape — observe disjoint timer state. The
  epoch mechanism backstops correctness; explicit cancellation via
  `:rf.machine/after-cancel` is an optimisation that promptly releases
  the host-clock handle.

  No-op under `:platform :server` (per Spec 005 §SSR mode); the pure side
  already emitted `:rf.machine.timer/skipped-on-server` in place of
  /scheduled."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines.lifecycle-fx.resolver :as rf.machines.lifecycle-fx.resolver]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.timeout :as rf.machines.timeout]
            [re-frame.machines.transition :as rf.machines.transition]
            [re-frame.machines.work :as rf.machines.work]
            [re-frame.managed-timer :as rf.managed-timer]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

(defonce after-timers
  ;; Per Spec 005 §Delayed `:after` transitions: runtime-owned timer-handle
  ;; table for in-flight :after timers, partitioned per frame.
  ;;
  ;; Outer shape: {<frame-id> {<inner-key> <entry>}}.
  ;; Inner key:   {:parent <parent-id> :spawn <invoke-id-vec>
  ;;               :delay <delay-key>} — multiple delays per :after map
  ;;              have their own slot, and parallel-region machines
  ;;              partition further on the region-prefixed invoke-id.
  ;;              The key is a map rather than a positional tuple — the map
  ;;              form lets readers ignore slot order.
  ;; Entry value:
  ;;   {:handle <opaque host-clock handle; nil while arming>
  ;;    :resolved-ms <int>
  ;;    :epoch <int>
  ;;    :state <state-keyword>
  ;;    :region <region name for a region :after, else nil>
  ;;    :delay-source <:literal | :fn | :named>
  ;;    :token <this attempt's ownership token, `next-attempt-token`>}
  ;;
  ;; Frame-scoping the outer key keeps the timer table out of process-global
  ;; state. Two consequences:
  ;;   (1) Two frames running concurrently see strictly disjoint inner
  ;;       tables — no cross-frame contamination of timer handles, no
  ;;       chance that one frame's `reset-timers!` clobbers another's
  ;;       in-flight handles.
  ;;   (2) `after-cancel-fx` (state-exit cleanup) scans only the active
  ;;       frame's inner map rather than every frame's combined entries —
  ;;       O(timers-this-frame) instead of O(timers-all-frames).
  ;;
  ;; Cancellation (state exit, actor destroy) clears the entry and
  ;; releases the handle. Frame-teardown clears the
  ;; entire inner map via the `:machines/on-frame-destroyed!` late-bind
  ;; hook invoked from `rf.frame/destroy-frame!`.
  (atom {}))

(defn- after-timer-key
  "Inner-table key — frame-id is the OUTER key into `after-timers` and
  is intentionally absent from this map.

  The key is a `{:parent ... :spawn ... :delay ...}` map rather than a
  positional tuple — the map form lets readers ignore slot order, and the
  scan in `after-cancel-fx` reads `(:parent k)` / `(:spawn k)` rather than
  `(nth k 0)` / `(nth k 1)`. The key is opaque to callers (used only as a
  `get-in` index into `after-timers`'s inner map)."
  [parent-id invoke-id delay-key]
  {:parent parent-id
   :spawn (vec invoke-id)
   :delay  delay-key})

(defn- region-relative-path
  "The declaring node's path as the transition engine names it. A region
  `:after`'s `invoke-id` carries the region name as its head
  (`prefix-region-invoke-id`), while the engine resolves a region's timer
  against its region-relative path (`pick-after-transition`), so the head is
  stripped when `region` is the region it names. A flat / compound /
  parallel-root timer has no region, so its path is unchanged."
  [region invoke-id]
  (if (and region (seq invoke-id) (= region (first invoke-id)))
    (vec (rest invoke-id))
    (vec invoke-id)))

;; `:after` delay-source classification (`{:literal :fn :named}`) lives once
;; in `re-frame.machines.transition/classify-delay-source` — the leaf engine
;; that owns the `:after` grammar and already tags the pure-side
;; `:rf.machine.timer/skipped-on-server` trace from it. The fx side reuses the same
;; classifier so the two can never disagree on a delay-key's source.

(defn- named-delay
  "The value the `:delays` name `delay-name` stands for in the timer owner's
  machine definition — the registered singleton, or the registered TYPE a
  spawned actor's snapshot names (`spec-from-id-or-snapshot`, the lookup
  dispatch and hydration use). nil when the definition, or the name in it, is
  gone."
  [actor-id snapshot delay-name]
  (get-in (rf.machines.lifecycle-fx.resolver/spec-from-id-or-snapshot actor-id snapshot)
          [:delays delay-name]))

(defn- resolve-delay-ms
  "Resolve an :after map key to a positive-integer ms delay. For pos-int?
  literal: returns the value. For an ISO-8601 duration string (\"PT5S\"):
  its milliseconds, read by the parser the `:timeout` duration uses. For a
  keyword: the machine's `:delays` entry of that name, resolved as the same
  value written inline would be — so a named fn is called here too, never
  earlier. For fn: invokes (f {:snapshot snapshot}) once; a config-driven
  delay is a fn reading its value from the snapshot's `:data`.

  Returns the resolved value, or nil when the key resolves to nothing; the
  caller treats anything but a positive number as a bad delay.

  A throwing resolver's error row names the owning actor under `:actor-id`,
  like every machine row, so the machine's classification governs its
  exception text."
  [frame-id actor-id delay-key snapshot]
  (cond
    (number? delay-key)
    delay-key

    (string? delay-key)
    (rf.machines.timeout/resolve-duration-ms delay-key)

    (keyword? delay-key)
    ;; Registration admits only a pos-int / ISO string / fn as a `:delays`
    ;; value, so the recursion is one level deep; a keyword value (a
    ;; definition that bypassed validation) resolves to nothing.
    (let [v (named-delay actor-id snapshot delay-key)]
      (when-not (keyword? v)
        (resolve-delay-ms frame-id actor-id v snapshot)))

    (fn? delay-key)
    ;; A throwing fn-form `:after` emits `:rf.error/machine-after-fn-threw`
    ;; on the exception path, so the exception is observable rather than
    ;; silently swallowed. The delay then resolves to nothing, and the timer
    ;; is skipped and reported as `:rf.error/machine-bad-after-delay`.
    ;;
    ;; The `:after` delay-fn receives the unified context-map
    ;; `{:snapshot ...}` and returns a positive-int ms delay.
    (try (delay-key {:snapshot snapshot})
         (catch #?(:clj Throwable :cljs :default) e
           (rf.trace/emit-error! :rf.error/machine-after-fn-threw
                              {:exception e
                               :actor-id  actor-id
                               :frame     frame-id
                               :recovery  :skipped})
           nil))

    :else
    nil))

(declare schedule-after-timer!)

(defn- release-entry-resources!
  "Best-effort release of the host-clock handle belonging to a single
  timer-table entry. Pure side-effect; the caller owns the swap that removes
  the entry from the outer atom. Tolerates an arming sentinel, whose
  `:handle` is still nil. It releases THIS entry's own captured handle (A's
  own host work), so it always runs."
  [entry]
  ;; Shared best-effort host-clock cancel — swallows throws and no-ops a nil
  ;; handle.
  (rf.managed-timer/cancel! (:handle entry)))

(defonce ^:private after-attempt-counter
  ;; Monotonic per-arm attempt-token source (mirrors core's
  ;; `dispatch-later-counter`). Every `schedule-after-timer!`
  ;; arm stamps its slot with a unique token, so a trailing cancellation of an
  ;; OLD attempt can never claim a re-armed SUCCESSOR occupying the same reused
  ;; `{:parent :spawn :delay}` key, and a late-returning arm can
  ;; detect that a cleanup already claimed its slot. The durable per-decl-path
  ;; EPOCH remains the transition stale-gate (a hydration re-arm keeps the
  ;; same epoch on purpose); this token is transient host-work OWNERSHIP only —
  ;; never snapshot / replay / SSR-egress state.
  (atom 0))

(defn- next-attempt-token []
  (swap! after-attempt-counter inc))

(defn- claim-entry!
  "Atomically remove the `[frame-id k]` slot IFF it still holds the attempt
  identified by `token`, pruning an emptied inner frame-map. Returns the exact
  entry removed — so the caller releases precisely what it claimed — or nil when
  the slot no longer holds this attempt: a concurrent supersede / cleanup
  already took it, or a fresh re-arm installed a SUCCESSOR under the same key.
  The token check is what stops an old cancellation from deleting a successor it
  never released. The host side effect (release / host-clock
  cancel) rides the returned value OUTSIDE the retriable swap, never inside it
  (`swap-vals!` yields the pre-swap snapshot from the winning CAS attempt)."
  [frame-id k token]
  (let [[old _new]
        (swap-vals! after-timers
                    (fn [m]
                      (if (= token (:token (get-in m [frame-id k])))
                        (let [inner (dissoc (get m frame-id) k)]
                          (if (empty? inner)
                            (dissoc m frame-id)
                            (assoc m frame-id inner)))
                        m)))
        claimed (get-in old [frame-id k])]
    (when (= token (:token claimed))
      claimed)))

(defn- emit-cancelled!
  "Emit the unified `:rf.machine.timer/cancelled` trace for one
  cancellation site. Every cancellation path — state exit, machine
  destroy, in-place supersede, frame destroy, epoch restore — flows
  through this single emit so consumers can pair
  scheduled→fired→cancelled by actor, declaring node, epoch and
  `:delay-key`, and branch on
  `:reason` from the closed set `:on-exit / :on-destroy / :on-supersede
  / :on-frame-destroy / :on-restore` (the epoch-restore
  host-timer cleanup, `cancel-frame-timers-on-restore!`).

  Payload shape mirrors `:rf.machine.timer/scheduled` for arm-fire-
  cancel pairing — same `:actor-id` / `:state` / `:decl-path` / `:region`
  / `:delay` / `:epoch` / `:frame` slots — plus the `:reason`
  discriminator. `:delay` reads the
  entry's `:resolved-ms` — the wall-clock window the timer actually held —
  and `:delay-key` the `:after` key it was declared under, as on every
  timer row."
  [frame-id k entry reason]
  (let [delay-source (:delay-source entry)
        ;; The `:region` recorded at schedule time names the region whose
        ;; head the timer-table `:spawn` carries. The row names the node by
        ;; its region-relative path with `:region` beside it, and the work
        ;; record's `:rf.reply/work-id` puts the region back at the path's head and
        ;; ends with the `:after` key, as the fired / stale rows do, so all
        ;; the rows of one `:after` entry share one work-id and no other
        ;; timer — another region's, or a sibling entry on the same node —
        ;; shares it.
        region       (:region entry)
        decl-path    (region-relative-path region (:spawn k))
        ;; Close the timer work attempt as DATA (Managed-Effects
        ;; §Cancellation): a cancelled `:after` timer's work record is
        ;; `:status :cancelled`, its work id matching the fired / stale rows'
        ;; so the cancellation joins the work row the timer's scheduling
        ;; started, and its cancel reason is the closed `:reason`
        ;; discriminator. Its tags are extra keys BESIDE the public trace
        ;; shape (`:actor-id` / `:state` / `:delay` / `:epoch` / `:reason`),
        ;; never a replacement for it.
        work         (rf.machines.work/record
                       (rf.machines.work/timer-work-id
                         (:parent k) region decl-path (:delay k) (:epoch entry))
                       :cancelled
                       {:cancel-reason reason
                        :correlation   (assoc (rf.machines.work/timer-correlation
                                                (:parent k) (:state entry) (:resolved-ms entry))
                                              :gate (rf.machines.work/after-suppression-gate
                                                      decl-path (:epoch entry)))})]
    (rf.trace/emit! :rf.machine :rf.machine.timer/cancelled
                 (cond-> {;; the timer's owning actor INSTANCE;
                          ;; `:machine-id` is reserved for the registered TYPE.
                          :actor-id   (:parent k)
                          :state      (:state entry)
                          :decl-path  decl-path
                          :delay      (:resolved-ms entry)
                          :delay-key  (:delay k)
                          :epoch      (:epoch entry)
                          :reason     reason
                          :frame      frame-id
                          ;; work record (Spec 005 §Machine completions emit work records)
                          :rf.reply/work-kind     (:work-kind work)
                          :rf.reply/status        (:status work)
                          :rf.reply/work-id       (:work-id work)
                          :rf.reply/work-status   (:work-status work)
                          :rf.reply/cancelled?    true
                          :rf.reply/cancel-reason (:cancel-reason work)
                          :rf.reply/correlation   (:correlation work)}
                   (some? region)       (assoc :region region)
                   (some? delay-source) (assoc :delay-source delay-source)))))

(defn- cancel-after-timer-entry!
  "Cancel the CURRENT occupant of a single :after timer-table slot `[frame-id k]`,
  emitting one `:rf.machine.timer/cancelled` trace stamped with
  `reason` (closed set: `:on-exit / :on-destroy / :on-supersede /
  :on-frame-destroy / :on-restore`) and releasing the claimed entry's host
  handle. Idempotent — a second call against the same `[frame-id k]` is a
  no-op (the entry is gone so no trace fires).

  Reads the current occupant's `:token` and atomically CLAIMS the slot only while
  that token is still current (`claim-entry!`), so when the host-clock fire
  claimed the entry first this cancellation emits and releases nothing, and the
  release (`release-entry-resources!`) cancels only the claimed entry's own
  handle. A cancellation that claims an ARMING sentinel (`:handle nil`, host
  clock not yet armed) still emits the owed trace; the arming thread's publish
  phase then finds its token gone and cancels the returned host handle.

  Every cancellation goes through here: `schedule-after-timer!`'s leading
  `:on-supersede`, and each BATCH (`after-cancel-fx`, `cancel-actor-timers!`,
  `cancel-all-timers!`, `cancel-frame-timers-on-restore!`,
  `cancel-timers-absent-from!`), which snapshots the frame's keys and cancels
  each key's current occupant. No arm can install a new attempt at a key between
  a batch's snapshot and its claim, because an arm runs only inside one of the
  frame's events, under its `:drain-lock`. The batches run in an event or in
  `perform-restore!`'s serialized section, both holding that lock, or in a frame
  destroy after its claim has stopped the frame's events, with the id's
  reservation making a same-id `make-frame` fail fast."
  [frame-id k reason]
  (when-let [entry (get-in @after-timers [frame-id k])]
    (when-let [claimed (claim-entry! frame-id k (:token entry))]
      (emit-cancelled! frame-id k claimed reason)
      (release-entry-resources! claimed))))

(defn- schedule-after-timer!
  "Internal helper: resolve the delay, emit the `:rf.machine.timer/scheduled`
  row with the milliseconds it resolved to, and install the host-clock timer —
  for a state entry (`after-schedule-fx`) and an SSR hydration re-arm
  (`rearm-hydrated-after-timer!`) alike. Under `:platform :server` it arms
  nothing; the pure side emitted `/skipped-on-server` instead.

  Idempotent against the timer-table key — cancels any prior entry
  before installing the new one. The leading cancel emits a
  `:rf.machine.timer/cancelled` trace with `:reason :on-supersede` when a
  live prior entry holds the key (a re-arm over it, such as a second
  hydration); an initial schedule against an empty slot is a no-op cancel.

  Both callers run inside an event: the ordinary `:after` arm from its fx,
  and the SSR hydration re-arm from the `:rf.machine/hydrate-rearm` fx. A
  destroy of the frame requested there cannot finish before the event ends,
  and the id's reservation makes a same-id `make-frame` fail fast, so the
  frame that owns the arm at its leading supersede still owns it at the
  publish, and the arm rechecks no owner.

  The ORDER of the reservation against the `/scheduled` emit is itself the
  contract. The attempt's timer-table slot is reserved BEFORE the emit, so
  the announced attempt is a CANCELLABLE one from the instant it becomes
  visible, and whichever cleanup claims the sentinel closes the row with the
  mirrored `/cancelled`. No `/scheduled` row is left that can never be
  followed by `/fired` or `/cancelled` (Spec 005 §Trace event catalogue pairs
  the two by `(actor-id, state, epoch)`). The host clock is still armed AFTER
  the emit, so `/scheduled` still precedes even a synchronously-firing host
  callback."
  [frame-id parent-id invoke-id state delay-key epoch server? snapshot]
  (let [delay-source (rf.machines.transition/classify-delay-source delay-key)
        k            (after-timer-key parent-id invoke-id delay-key)
        ;; A region `:after`'s `invoke-id` is region-PREFIXED
        ;; (`prefix-region-invoke-id` prepends the region name). Record that
        ;; region name in the entry so every row names the node by its
        ;; region-relative `:decl-path` with `:region` beside it — matching
        ;; the fired / stale rows (whose `carried-decl-path` is region-stripped
        ;; by `pick-after-transition`, which tags the match with `:region`) —
        ;; and `emit-cancelled!` builds the same region-qualified
        ;; `:rf.reply/work-id` the fired / stale replies do. Parallel machines carry
        ;; a region-name → state MAP as `:state`;
        ;; a flat / compound machine's `:state` is a keyword / vector path (no
        ;; region head), and the parallel-ROOT timer's `invoke-id` is empty
        ;; (`[]`, no head) — both yield nil, so nothing is stripped.
        region       (when (and snapshot
                                 (map? (:state snapshot))
                                 (seq invoke-id))
                       (first invoke-id))
        decl-path    (region-relative-path region invoke-id)]
    (cancel-after-timer-entry! frame-id k :on-supersede)
    (cond
      server?
      ;; Pure-side already emitted :skipped-on-server; no-op here.
      nil

      :else
      (let [resolved-ms (resolve-delay-ms frame-id parent-id delay-key snapshot)]
        (cond
          (or (not (number? resolved-ms))
              (not (pos? resolved-ms)))
          ;; Bad delay resolution — report it and skip. A dynamic delay
          ;; that resolves to anything but a positive number is the fault
          ;; a static key raises at registration, so it carries that id,
          ;; `:rf.error/machine-bad-after-delay`, on the same diagnostic
          ;; channel; no timer is armed and the state waits for an event.
          (rf.trace/emit-error! :rf.error/machine-bad-after-delay
                             ;; the timer's owning actor is a LIVE INSTANCE;
                             ;; address it by `:actor-id`, not `:machine-id`
                             ;; (reserved for the registered TYPE).
                             {:actor-id       parent-id
                              :state          state
                              :slot           :after
                              :delay-key      delay-key
                              :delay-source   delay-source
                              :resolved-delay resolved-ms
                              :frame          frame-id
                              :recovery       :skipped})

          :else
          ;; TWO-PHASE, TOKEN-OWNED arm (mirrors core
          ;; `:dispatch-later`). The host clock is armed BETWEEN
          ;; reserving the slot and publishing the handle, so a concurrent
          ;; cleanup (frame / actor / state-exit / restore / supersede) — or a
          ;; host scheduler that fires the callback synchronously before
          ;; `arm!` returns — can atomically CLAIM this attempt and leave the
          ;; publish phase to find its token gone and cancel the orphan handle.
          (let [token       (next-attempt-token)
                ;; PHASE 1 — RESERVE the slot with an arming sentinel (`:handle
                ;; nil`) carrying this attempt's `:token`, BEFORE arming any host
                ;; clock.
                reservation {:handle          nil
                             :resolved-ms     resolved-ms
                             :epoch           epoch
                             :state           state
                             :region          region
                             :delay-source    delay-source
                             :token           token}]
            ;; The reservation is taken BEFORE the `/scheduled`
            ;; fan-out below, not after it. Outside a drain the row's listener
            ;; runs synchronously, so an attempt that is only reserved afterwards is
            ;; not yet cancellable at the moment it becomes VISIBLE: a listener
            ;; that destroys the owning incarnation leaves a `/scheduled` row
            ;; that can never be followed by `/fired` or `/cancelled`, and Spec
            ;; 005 §Trace event catalogue has the two events mirror each other
            ;; precisely so consumers can pair them by `(actor-id, state,
            ;; epoch)`. Reserving first makes the attempt a claimable one from
            ;; the instant it is announced, so the pair below is closed by the
            ;; entry's OWN `/cancelled`, from whichever cleanup claims the
            ;; sentinel. The host clock is still armed after the emit, so a
            ;; host that fires synchronously cannot beat `/scheduled` to the
            ;; stream.
            (swap! after-timers assoc-in [frame-id k] reservation)
            (rf.trace/emit! :rf.machine :rf.machine.timer/scheduled
                         (cond-> {;; the timer's owning actor INSTANCE;
                                  ;; `:machine-id` is reserved for the
                                  ;; registered TYPE.
                                  :actor-id     parent-id
                                  :state        state
                                  :decl-path    decl-path
                                  :delay        resolved-ms
                                  :delay-key    delay-key
                                  :delay-source delay-source
                                  :epoch        epoch
                                  :frame        frame-id}
                           (some? region) (assoc :region region)))
            (let [handle
                  ;; Shared positive-delay-guarded arm — the host-clock arm
                  ;; step both timer artefacts share. `resolved-ms` is already
                  ;; known-positive here (the non-positive case was handled by
                  ;; the prior `cond` branch), so the guard is a no-op
                  ;; pass-through.
                  (rf.managed-timer/arm!
                    (fn []
                      ;; ATOMICALLY claim THIS fire's slot — dispatch AUTHORITY
                      ;; + reap in a single swap (mirrors core
                      ;; `:dispatch-later`). If a cleanup / supersede claimed the slot
                      ;; first — even a synchronous host callback firing before
                      ;; `arm!` returns, or a JVM cleanup racing the arm — this
                      ;; fire has LOST authority: suppress the dead-on-arrival
                      ;; dispatch and touch nothing (the claimer already released
                      ;; our resources). On the live path the claim wins, and
                      ;; because the claim removes THIS fire's entry, a
                      ;; GUARD-SUPPRESSED fire (no state exit → no `:on-exit`
                      ;; cancel) strands nothing. A one-shot `:after` timer
                      ;; fires exactly once per arming (XState §delayed
                      ;; transitions); its handle has just fired, so nothing
                      ;; else is held, and a fired timer was not cancelled, so
                      ;; no `:cancelled` trace is owed.
                      (when (claim-entry! frame-id k token)
                        (when-let [dispatch! (rf.late-bind/get-fn :router/dispatch!)]
                          ;; Stamp `:source :after-timer` so the Epoch panel's
                          ;; DISPATCH step labels it "from :after timer" and
                          ;; Xray's L2 timeline can prefix the row + per-source
                          ;; filter pills can discriminate timer-fired cascades
                          ;; (closed-enum per Spec-Schemas
                          ;; §`:rf/dispatch-envelope`). Per Spec 005 §Hierarchy
                          ;; interaction: carry the scheduling node's decl-path
                          ;; (`invoke-id`) so the pure side routes the firing to
                          ;; the correct per-path epoch — colliding delay-keys
                          ;; across hierarchy levels resolve unambiguously. The
                          ;; armed ms ride along for the `/fired` /
                          ;; `/stale-after` rows.
                          (dispatch! [parent-id (rf.machines.transition/elapsed-event
                                                  delay-key epoch invoke-id resolved-ms)]
                                     {:frame  frame-id
                                      :source :after-timer}))))
                    resolved-ms)
                  ;; PHASE 2 — PUBLISH the host handle IFF this attempt still
                  ;; owns the slot. `swap-vals!` is a pure replace-if-token-
                  ;; present; the cancel side effect below rides the
                  ;; CAS-derived old snapshot, never inside the swap.
                  [old _new]
                  (swap-vals! after-timers
                              (fn [m]
                                (if (= token (:token (get-in m [frame-id k])))
                                  (assoc-in m [frame-id k :handle] handle)
                                  m)))
                  owned? (= token (:token (get-in old [frame-id k])))]
              (if owned?
                handle
                ;; A cleanup / supersede / synchronous fire claimed the sentinel
                ;; while we armed — do NOT republish; cancel the orphan handle
                ;; (idempotent even if it already fired). The claimer already
                ;; released the entry and emitted the single owed `:cancelled`
                ;; trace (or, for a self-fire, dispatched silently).
                (do (rf.managed-timer/cancel! handle)
                    nil)))))))))

(defn after-schedule-fx
  "fx handler for `:rf.machine/after-schedule`. Per Spec 005 §Delayed
  `:after` transitions, on entry to an :after-bearing state node the
  runtime emits one of these per :after entry. The handler
  resolves the delay (literal pos-int? / ISO-8601 string / `:delays` name /
  fn) once against the committed snapshot, emits the
  `:rf.machine.timer/scheduled` row with the milliseconds it resolved to,
  and schedules a real wall-clock timer via
  `rf.interop/schedule-after!` (Spec 005 §Clock abstraction).

  The synthetic event dispatched on expiry is

      [<parent-id> [:rf.machine.timer/after-elapsed <delay-key> <epoch> <decl-path>]]

  (the scheduling node's declaring path travels with the timer per Spec
  005 §Hierarchy interaction) which routes through pick-after-transition's
  per-node epoch check and (on match) through the standard transition
  cascade.

  No-op under `:platform :server` (per Spec 005 §SSR mode)."
  [{frame-id :frame} args]
  (let [;; The cascade envelope frame is the fx-context `:frame`; a nil
        ;; stamp is an invariant failure (`:rf.error/no-frame-context`),
        ;; never a synthesised `:rf/default`.
        frame-id   (rf.frame/require-frame-stamp!
                     frame-id :rf.machine/after-schedule
                     {:where 'rf.machine/after-schedule
                      :event-id (:rf/parent-id args)})
        parent-id  (:rf/parent-id args)
        invoke-id  (:rf/invoke-id args)
        state      (:state args)
        delay-key  (:delay-key args)
        epoch      (:epoch args)
        server?    (boolean (:server? args))
        ;; Machine snapshots are durable runtime-db state.
        snapshot   (get-in (rf.frame/frame-runtime-db-value frame-id)
                           (rf.machines.paths/snapshot-path parent-id))]
    (schedule-after-timer! frame-id parent-id invoke-id state
                            delay-key epoch server? snapshot)
    nil))

(defn rearm-hydrated-after-timer!
  "Arm ONE `:after` timer for a node the hydrated snapshot says is ALREADY
  active. The SSR hydration re-arm seam
  (`re-frame.machines.hydrate/rearm-after-timers!`) is the only caller.

  This is deliberately NOT a second scheduling mechanism: it is the
  ordinary `schedule-after-timer!` arm with two differences that both
  follow from hydration not being a state ENTRY.

    - The `epoch` is the one the hydrated snapshot ALREADY carries; it is
      never bumped. Entry bumps (`commit-snapshot`'s `bump-after-epochs`)
      because a re-entry must invalidate the prior visit's in-flight
      timer; hydration re-establishes host work for a visit that already
      happened, so bumping would strand the very snapshot being restored.
    - No transition ran, so no `:rf.machine/after-schedule` fx exists to
      arm it: this fn arms it directly (emitting a state-entry cascade to
      get one would replay the server's entry effects). The `/scheduled`
      row is the ORDINARY one `schedule-after-timer!` emits for every arm,
      so the scheduled→fired / →cancelled pairing on `(actor-id, state,
      epoch)` holds for a hydrated timer exactly as for an entered one.

  Never server-armed: hydration re-arm runs on the client by contract
  (Spec 011 §`:after` is no-op under SSR), and the caller has already
  refused a `:server` frame, so `server?` is passed `false`. Idempotent
  through the ordinary timer-table key — a second hydration supersedes
  the first arm rather than duplicating it."
  [frame-id parent-id invoke-id state delay-key epoch snapshot]
  (schedule-after-timer! frame-id parent-id invoke-id state delay-key epoch
                         false snapshot)
  nil)

(defn cancel-timers-absent-from!
  "Release every `:after` host timer `frame-id` holds that the just-hydrated
  snapshots no longer declare — the CANCEL half of the SSR hydration
  reconcile. `re-frame.machines.hydrate/rearm-after-timers!` is the only
  caller, and it runs this BEFORE arming the live set.

  `:rf/hydrate` replaces the runtime-db WHOLESALE, but this table is host
  state and survives that replacement untouched, so arming the replacement's
  live declarations is only half the operation. An `:after`-bearing state
  that hydrates into a no-`:after` state, an actor the replacement drops,
  and a shrunken delay set all leave a handle here that no installed
  snapshot claims. `schedule-after-timer!`'s leading `:on-supersede` cannot
  reach any of them: it touches the ONE `{:parent :spawn :delay}` key it is
  arming, so a declaration the replacement never mentions is never visited.
  The set DIFFERENCE has to be taken explicitly. Left alone, a handle
  lingers until it fires — the epoch / active-path gates suppress the
  eventual stale TRANSITION, but they release no host work.

  `live-decls` is the exact set of `[parent-id invoke-id delay-key]` triples
  the installed snapshots keep live; `present-actors` is the set of actor-ids
  those snapshots hold. Everything in this frame's inner table outside
  `live-decls` is cancelled, stamped with the closed-set `:reason` that names
  why it went away: `:on-destroy` when the replacement dropped the actor
  entirely, `:on-exit` when the actor survived but its declaring node is no
  longer on the active configuration. Both are the ORDINARY readings of the
  Spec 005 §Trace event catalogue vocabulary — hydration adds no seventh
  reason — so the scheduled→cancelled pairing a tool already renders on
  `(actor-id, state, epoch)` stays coherent across a hydration.

  Sibling frames are untouched (the table is per-frame), and a live
  declaration is left in place for the arm phase to retain / supersede.

  A BATCH like `cancel-frame-timers-on-restore!`: it snapshots the frame's keys
  and cancels each key's current occupant (`cancel-after-timer-entry!`)."
  [frame-id live-decls present-actors]
  (let [live (into #{}
                   (map (fn [[parent-id invoke-id delay-key]]
                          (after-timer-key parent-id invoke-id delay-key)))
                   live-decls)]
    (doseq [k (keys (get @after-timers frame-id))
            :when (not (contains? live k))]
      (cancel-after-timer-entry! frame-id k
                                 (if (contains? present-actors (:parent k))
                                   :on-exit
                                   :on-destroy))))
  nil)

(defn after-cancel-fx
  "fx handler for `:rf.machine/after-cancel`. Emitted on exit from an
  :after-bearing state node to release the host-clock timer handles of the
  prior visit's timers. The epoch-mismatch invariant backstops correctness
  if a timer fires before this fx runs; this handler is the fast-path that
  releases host handles and timer slots promptly.

  Each cancellation emits one `:rf.machine.timer/cancelled` trace with
  `:reason :on-exit` so the scheduled→fired→cancelled pairing in the Xray
  Handler section's AFTER TIMERS sub-section can attribute the cancel to
  the state-exit cause.

  The scan is bounded by the active frame's inner table — siblings'
  timers in other frames are not walked. The inner key is
  `{:parent ... :spawn ... :delay ...}`, so the only cross-key axis we
  iterate is `:delay` (one entry per :after map entry on the bearing
  state node — typically 1-3 entries)."
  [{frame-id :frame} args]
  (let [;; The cascade envelope frame is the fx-context `:frame`; a nil
        ;; stamp is an invariant failure (`:rf.error/no-frame-context`),
        ;; never a synthesised `:rf/default`.
        frame-id  (rf.frame/require-frame-stamp!
                    frame-id :rf.machine/after-cancel
                    {:where 'rf.machine/after-cancel
                     :event-id (:rf/parent-id args)})
        parent-id (:rf/parent-id args)
        invoke-id (vec (:rf/invoke-id args))]
    (doseq [k (keys (get @after-timers frame-id))
            :when (and (= parent-id (:parent k))
                       (= invoke-id (:spawn k)))]
      (cancel-after-timer-entry! frame-id k :on-exit))
    nil))

(defn cancel-actor-timers!
  "Cancel every in-flight `:after` timer owned by `parent-id` under
  `frame-id`. Emits one `:rf.machine.timer/cancelled` trace per entry
  with `:reason :on-destroy`. Called from the machine-destroy paths so
  trace consumers see the cancellation cause distinct from state-exit /
  frame-destroy cancellations.

  Pure side-effect — no return value. No-op when the actor has no
  in-flight timers. The transition engine's epoch invariant backstops
  any timer that fires between destroy and host-clock teardown; this
  helper releases the host-clock handle eagerly and emits the trace
  so the Xray Handler section can attribute the cancel to the actor's
  destroy event.

  A BATCH: it snapshots the actor's keys and cancels each key's current
  occupant (`cancel-after-timer-entry!`). The machine-destroy paths run it in
  one of the frame's events or inside a frame destroy, so no arm re-arms a key
  under the loop."
  [frame-id parent-id]
  (when (and frame-id parent-id)
    (doseq [k (keys (get @after-timers frame-id))
            :when (= parent-id (:parent k))]
      (cancel-after-timer-entry! frame-id k :on-destroy))))

(defn cancel-all-timers!
  "Cancel every in-flight :after timer the runtime is currently tracking
  and reset the timer table.

  0-arity: every frame's timers (fixture teardown — `reset-timers!`).
  Silent — fixture-reset is test-isolation cleanup, not a runtime
  cancellation event; emitting traces here would pollute the trace
  stream observed by the next test.

  1-arity: just the given frame's timers (`rf.frame/destroy-frame!` hook).
  Each cancelled timer emits one `:rf.machine.timer/cancelled` trace with
  `:reason :on-frame-destroy` so the Handler section's AFTER TIMERS
  sub-section can pair scheduled → cancelled on frame teardown.

  The timer table is partitioned per frame; the 1-arity variant releases
  the destroyed frame's host-clock handles without touching sibling
  frames' state.

  The 1-arity is a BATCH with the others' shape: it snapshots the frame's keys
  and cancels each key's current occupant (`cancel-after-timer-entry!`). No arm
  re-arms a key under it: the destroy runs it at step 6, after its claim has
  stopped the frame's events, and holds the id's reservation until step 10 has
  finished. The 0-arity fixture-reset stays a SILENT bulk release (no traces —
  test-isolation cleanup)."
  ([]
   (doseq [inner (vals @after-timers)
           entry (vals inner)]
     (release-entry-resources! entry))
   (reset! after-timers {}))
  ([frame-id]
   (doseq [k (keys (get @after-timers frame-id))]
     (cancel-after-timer-entry! frame-id k :on-frame-destroy))))

(defn cancel-frame-timers-on-restore!
  "Cancel every in-flight `:after` timer the given `frame-id` currently holds,
  emitting one `:rf.machine.timer/cancelled` trace per entry with
  `:reason :on-restore`.

  Epoch restore installs the captured durable frame-state (the machine
  snapshots in runtime-db) WHOLESALE, so timer LIVENESS reverts atomically —
  a pre-restore timer that fires later carries a stale per-path epoch and is
  silently dropped via `:rf.machine.timer/stale-after` (Spec 005 §Hierarchy
  interaction). But the host-clock HANDLE itself is NOT frame-state: it stays
  attached to the pre-restore timeline and would still fire (and dispatch a
  doomed-stale synthetic event) unless released. This is the restore
  counterpart of the `:on-frame-destroy` cleanup `cancel-all-timers!` does on
  teardown — it releases the orphaned host handles eagerly so the restored
  frame carries no leaked wall-clock timers from the unwound epochs
  (Managed-Effects §restore: \"epoch restore MUST NOT revive host work\").

  Published as the `:machines/on-frame-restored!` late-bind hook, consulted by
  the epoch restore boundary (`perform-restore!`) AFTER a successful install (the
  frame stays LIVE across a restore — it is a wholesale runtime-db swap, not a
  destroy). No-op when the frame holds no in-flight timers.

  A BATCH like `cancel-all-timers!`: it snapshots the frame's keys and cancels
  each key's current occupant (`cancel-after-timer-entry!`). Nothing re-arms a
  key or reseats the frame under it: it runs inside `perform-restore!`'s
  serialized section, which holds the frame's `:drain-lock`, so no event of the
  frame runs, a cross-thread `destroy-frame!` waits on the lock, and each
  `/cancelled` reaches listeners only once the section ends."
  [frame-id]
  (doseq [k (keys (get @after-timers frame-id))]
    (cancel-after-timer-entry! frame-id k :on-restore))
  nil)
