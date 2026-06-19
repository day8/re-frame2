(ns re-frame.machines.reply
  "Spec 005 — share the uniform reply-envelope status/trace vocabulary for
  the two machine *async* completions (EP-0011 §Machine Completion /
  §Timer Reply; the canonical contract is `spec/Managed-Effects.md` §The
  uniform reply envelope). This is the machine family's slice of the
  shared `re-frame.reply` substrate, the same role
  `re-frame.http-reply` plays for `:rf.http/managed`.

  ## What this namespace is

  The internal seam that turns the two machine async completions — a
  spawned-actor finishing on a `:final?` leaf, and a fired-or-stale
  `:after` timer — into the ONE canonical reply-envelope shape every
  managed async family produces. **This is INTERNAL LOWERING ONLY: the
  PUBLIC statechart API is preserved exactly.** `:on-done` still receives
  `{:data … :result …}` and returns the new `:data`; `:on-error` is still
  a declarative parent transition; `:after`'s epoch-gated stale-drop is
  unchanged; actor-destroy semantics are unchanged. What this ns adds is
  the *uniform vocabulary*: the `:rf.work/machine` work-id, the closed
  `:status`, and reply-envelope-shaped trace facts — so machines, HTTP,
  resources, and routing classify completion the same way and tools read
  one stream (Managed-Effects §9).

  Two concerns, mirroring the two machine async completions:

   1. **Spawned-actor completion** (`spawn-work-id` / `success-reply` /
      `error-reply`). When a `:spawn`-spawned child reaches a `:final?`
      leaf, the runtime forms a canonical reply map internally — one
      closed `:status` (`:ok` for a plain final leaf, `:error` for an
      `:error?` error terminal), the child's `:output-key` result under
      `:value`, `:work/id` `[:rf.work/machine actor-id work-bearing-path
      generation]`, `:work/kind :machine`, `:work/status`,
      `:rf.frame/id`, and `:correlation {:actor-id … :parent-id …
      :invoke-id …}`. The PUBLIC `:on-done` `:data` callback is then driven
      with `(:value reply)` as its `:result`; `:on-error` with the
      reply's `:error`. A **late** child completion — the actor-id /
      spawn correlation no longer naming a live actor — is `:stale`
      (`stale-spawn-reply`), and its app target (the `:on-done` /
      `:on-error` routing) MUST NOT run.

   2. **`:after` timer staleness** (`after-suppression-gate` /
      `after-stale-trace`). The machine `:after` timer is THE existing
      specialized stale-gated instance of the reply pattern
      (EP-0011 §Timer Reply, Managed-Effects §Stale suppression): the
      synthetic timer-elapsed event carries the scheduling node's
      declaring path + per-path `:rf/after-epoch`, validated against the
      live snapshot on receipt. This ns expresses that existing drop as
      the envelope's stale-suppression *vocabulary* — the **declaring
      path + epoch are the data-only suppression gate** — and shapes the
      `:rf.machine.timer/stale-after` trace's carried/current correlation
      the reply-envelope way. The epoch-mismatch behaviour is unchanged;
      only the vocabulary is shared.

  Pure — no atoms, no dispatch, no I/O. Timestamps (`:completed-at`) are
  supplied by the caller (the host clock is read once at completion and
  threaded in); this ns never reads a clock. Trace summaries route every
  wire-bearing slot through the shared `re-frame.reply/trace-summary`
  (which calls `re-frame.elision/elide-wire-value`) — never a
  family-private elider (Managed-Effects §Tracing).

  ## Why there is no `target-obsolete?` gate here (rf2-wwfn7q)

  HTTP carries an `actor-destroy-target-obsolete?` predicate
  (`re-frame.http-reply`) that lowers a destroyed-actor reply to `:stale`/
  `:suppressed` (rather than `:cancelled`) when the reply TARGET names the
  destroyed actor. Machines have NO counterpart, **by design** — obsolete-
  target suppression is HTTP-specific. HTTP is the only EP-0011 surface that
  RE-DISPATCHES a reply at a caller-supplied event target (its `:on-failure`
  / origin-event head, which can name the actor or an ordinary app event), so
  TARGET-identity obsolescence is only definable there.

  A machine cancel is not a re-dispatch at a caller-supplied target: it is a
  TERMINAL completion/correlation row (`cancelled-actor-reply` — no `:value`,
  no dispatch, no caller target) that closes the work-ledger row as
  `:cancelled`. Machines already model obsolescence, but split by SEMANTICS,
  not target-identity: the LATE-OBSOLETE-arrival `:stale` cases are
  `stale-spawn-reply` (child finishes after the parent is gone),
  `stale-join-child-reply` (join child reports after the join resolved), and
  `after-stale-reply` (an `:after` timer fires after an epoch/path mismatch);
  PROACTIVE teardown is recorded as the `:cancelled` `cancelled-actor-reply`.
  Forcing HTTP's `target == actor` gate onto `cancelled-actor-reply` would
  blur those two genuinely distinct facts. All surfaces still route through
  the shared `re-frame.reply/suppress` and spell `:cancelled` identically —
  uniform where it matters; the obsolescence DETERMINATION is correctly
  surface-specific."
  (:require [re-frame.reply :as reply]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Work-id correlation (Managed-Effects §Work-id correlation).
;;
;; Machine head: `[:rf.work/machine actor-id work-bearing-path generation]`.
;;  - `actor-id`         — the finishing actor's INSTANCE id (the `<type>#<n>`
;;                         instance id, or an explicit `:fixed-actor-id`);
;;  - `work-bearing-path` — the `:spawn`-bearing node's declaring path
;;                         (the invocation path `:rf/invoke-id` the runtime
;;                         stamped on the child's `:data` at spawn time);
;;  - `generation`       — the monotonic spawn discriminator; for the
;;                         `<type>#<n>` instance-id form it is `n` (the
;;                         spawn-counter reading that minted this instance),
;;                         so a re-spawn under the same declaring path lands
;;                         on a fresh work id (one ATTEMPT, one `:work/id`,
;;                         per EP-0007). An explicit `:fixed-actor-id` actor
;;                         with no `#n` suffix carries generation 1.
;; ---------------------------------------------------------------------------

(defn actor-generation
  "Parse the spawn generation off an `actor-id` of the form
  `<type>#<n>` (the instance id the spawn-counter mints — see
  `lifecycle-fx.spawn/allocate-actor-id-in-runtime-db`). Returns the
  integer `n`, or 1 when the id carries no `#n` suffix (an explicit
  per-state-singleton `:fixed-actor-id` actor — one attempt, generation 1).
  nil-safe: returns nil for a nil id (no live counterpart). Public so the
  finalize path can read the LIVE spawn-slot occupant's generation as the
  `:current` counterpart of the stale-spawn carried/current gate."
  [actor-id]
  (if (nil? actor-id)
    nil
    (let [nm (when (keyword? actor-id) (name actor-id))
          i  (when nm #?(:clj  (.lastIndexOf ^String nm "#")
                         :cljs (.lastIndexOf nm "#")))]
      (if (and i (nat-int? i) (pos? i))
        (let [suffix (subs nm (inc i))
              ;; (rf2-ny0yrz C4) The CLJS branch must REJECT a non-fully-
              ;; numeric suffix BEFORE `js/parseInt`: `js/parseInt "3abc" 10`
              ;; leniently returns 3, whereas CLJ `Long/parseLong "3abc"`
              ;; THROWS → falls through to generation 1. A `:fixed-actor-id`
              ;; carrying a `#` followed by a malformed suffix would otherwise
              ;; mint DIFFERENT work-ids per platform (a determinism break).
              ;; Gating both platforms on a fully-numeric `#"\d+"` match makes
              ;; them agree: a malformed suffix defaults to generation 1.
              n      (when (re-matches #"\d+" suffix)
                       #?(:clj  (try (Long/parseLong suffix) (catch Exception _ nil))
                          :cljs (let [x (js/parseInt suffix 10)] (when-not (js/isNaN x) x))))]
          (or n 1))
        1))))

(defn spawn-work-id
  "Build the machine work-id for one spawned-actor attempt:
  `[:rf.work/machine actor-id work-bearing-path generation]`
  (Managed-Effects §Work-id correlation; EP-0011 §Machine Completion).
  `actor-id` is the finishing actor's INSTANCE id; `work-bearing-path` is
  the `:spawn`-bearing node's declaring path (the child's stamped
  invocation path `:rf/invoke-id`). `generation` is parsed off the
  `<type>#<n>` instance id (or 1 for an explicit `:fixed-actor-id`).
  `=`-comparable and EDN-serializable."
  [actor-id work-bearing-path]
  [:rf.work/machine actor-id (vec work-bearing-path) (actor-generation actor-id)])

;; ---------------------------------------------------------------------------
;; Canonical reply map for a spawned-actor completion (Managed-Effects §The
;; reply map / §Status taxonomy). A child reaching a plain `:final?` leaf is
;; `:status :ok`; an `:error?` error-terminal leaf is `:status :error`.
;; `:completed-at` is supplied by the caller; this ns never reads a clock.
;; ---------------------------------------------------------------------------

(defn- base-reply
  "The correlation/identity facts every spawned-actor reply carries,
  independent of status: `:work/id`, `:work/kind :machine`,
  `:rf.frame/id`, optional `:completed-at`, and
  `:correlation {:actor-id … :parent-id … :invoke-id …}`. The
  `:actor-id` is the finishing actor's live INSTANCE address; the
  `:invoke-id` is the declarative spawn invocation-path (the absolute
  prefix-path of the `:spawn`-bearing parent state) — the two are
  distinct identity facts (rf2-0ggtr5 / rf2-ws5thu). Optional facts are
  omitted when absent rather than nil-filled (Managed-Effects §The
  reply map)."
  [{:keys [actor-id parent-id work-bearing-path frame completed-at]}]
  (cond-> {:work/id   (spawn-work-id actor-id work-bearing-path)
           :work/kind :machine}
    (some? frame)        (assoc :rf.frame/id frame)
    (some? completed-at) (assoc :completed-at completed-at)
    true                 (assoc :correlation
                                (cond-> {:actor-id actor-id}
                                  (some? parent-id)         (assoc :parent-id parent-id)
                                  (some? work-bearing-path) (assoc :invoke-id (vec work-bearing-path))))))

(defn success-reply
  "Build the canonical `:status :ok` reply map for a spawned child that
  reached a plain (non-error) `:final?` leaf (EP-0011 §Machine
  Completion). `value` is the child's `:output-key` result (nil when the
  final leaf declares no `:output-key`). `:work/status :completed`.

  `ctx` keys: `:actor-id`, `:parent-id`, `:work-bearing-path`, `:frame`,
  `:completed-at`."
  [ctx value]
  (assoc (base-reply ctx)
         :status      :ok
         :work/status :completed
         :value       value))

(defn error-reply
  "Build the canonical `:status :error` reply map for a spawned child that
  reached a designated ERROR terminal (`:error? true` `:final?` leaf) — the
  failure that drives the parent's `:spawn :on-error` transition (EP-0011
  §Machine Completion; Spec 005 §`:on-error`). `error` is the failure
  payload (the child's `:output-key` slot for an error leaf, or the
  action-exception envelope). `:work/status :failed`.

  The `:error` slot carries a family `:kind` per the reply-map contract:
  when `error` is already a map carrying `:kind` it rides verbatim;
  otherwise the raw payload is wrapped under
  `{:kind :rf.machine/spawn-error :value error}` so the closed reply-map
  schema's \"`:error` carries a family `:kind`\" invariant holds without
  perturbing the public payload the parent transition observes (the
  parent reads the raw error off `:event`, NOT off this reply)."
  [ctx error]
  (let [error-map (if (and (map? error) (some? (:kind error)))
                    error
                    {:kind :rf.machine/spawn-error :value error})]
    (assoc (base-reply ctx)
           :status      :error
           :work/status :failed
           :error       error-map)))

;; ---------------------------------------------------------------------------
;; Late spawned-actor completion — stale suppression (Managed-Effects §Stale
;; suppression; EP-0011 §Machine Completion: "Any late child completion is
;; stale because the actor id or spawn correlation is no longer live").
;; ---------------------------------------------------------------------------

(defn stale-spawn-reply
  "Build the `:status :stale` reply for a late spawned-actor completion
  whose actor-id / spawn correlation is no longer live (the actor was
  already destroyed, or the spawn slot was reused). Per Managed-Effects
  §Stale suppression the app target (the `:on-done` / `:on-error`
  routing) MUST NOT run; this reply carries NO `:value` and produces no
  app mutation. `:stale/reason :rf.machine/actor-not-live`,
  `:work/status :suppressed`.

  `ctx` keys mirror `success-reply`'s (`:actor-id`, `:parent-id`,
  `:work-bearing-path`, `:frame`, `:completed-at`) plus an optional
  `:current-generation` — the generation currently occupying the spawn
  slot at completion (the LIVE counterpart), or nil when the slot is gone
  (the parent was destroyed, or the slot was never reused). Mirroring the
  `:after` path's carried/current gate, the correlation carries the
  carried-vs-current generation pair under
  `:generation {:carried <n> :current <m-or-nil>}` — `:carried` is the
  generation parsed off the finishing actor's id (the `<type>#<n>` suffix),
  `:current` is `:current-generation`. The pair is the data-only
  supersession gate: `:carried` != `:current` (including a nil `:current`
  — no live counterpart) is exactly why the completion is stale. The
  whole correlation map elides through the shared trace summary
  (`stale-spawn-trace`)."
  [ctx]
  (let [{:keys [actor-id parent-id work-bearing-path frame completed-at
                current-generation]} ctx
        carried-generation (actor-generation actor-id)]
    (cond-> {:status       :stale
             :stale?       true
             :stale/reason :rf.machine/actor-not-live
             :work/id      (spawn-work-id actor-id work-bearing-path)
             :work/kind    :machine
             :work/status  :suppressed
             :correlation  (cond-> {:actor-id   actor-id
                                    :generation {:carried carried-generation
                                                 :current current-generation}}
                             (some? parent-id)         (assoc :parent-id parent-id)
                             (some? work-bearing-path) (assoc :invoke-id (vec work-bearing-path)))}
      (some? frame)        (assoc :rf.frame/id frame)
      (some? completed-at) (assoc :completed-at completed-at))))

;; ---------------------------------------------------------------------------
;; `:spawn-all` join-child completion (Managed-Effects §The reply map /
;; §Status taxonomy / §Stale suppression; EP-0011 §Machine Completion).
;;
;; rf2-d63qtp — single `:spawn` finality lowers through `success-reply` /
;; `error-reply` / `stale-spawn-reply`; the `:spawn-all` join-child
;; completion (a child dispatching `[parent [:on-child-done child-id …]]` /
;; `:on-child-error` into the parent's join) previously folded into join
;; state + emitted join traces with NO canonical reply map, `:work/id`, or
;; `:rf.reply/*` facts. These helpers give the join-child path the SAME
;; uniform reply vocabulary the single-`:spawn` path already carries — the
;; PUBLIC join protocol (the parent dispatch, the resolution events, the
;; cancel-on-decision cascade) is unchanged; this is INTERNAL trace-stream
;; lowering only, so a join child's done / failed / late completion
;; classifies the same way HTTP / resources / single-`:spawn` do.
;;
;; A join child's work-id reuses the machine head keyed on the child's
;; SPAWNED instance id (the `<type>#<n>` actor address in the join-state
;; `:children` map) and the parent's `invoke-id` (the `:spawn-all`-bearing
;; node's declaring path) as the work-bearing path — one child attempt, one
;; `:work/id`, per EP-0007.
;; ---------------------------------------------------------------------------

(defn join-child-reply
  "Build the canonical reply map for a `:spawn-all` join-child completion
  that folded into the join (EP-0011 §Machine Completion; Managed-Effects
  §Status taxonomy). `kind` is `:done` (a `:on-child-done` arrival —
  `:status :ok` / `:work/status :completed`) or `:failed` (a
  `:on-child-error` arrival — `:status :error` / `:work/status :failed`).

  The work-id is `[:rf.work/machine spawned-id parent-invoke-id
  generation]` — the child's SPAWNED instance address (the `<type>#<n>`
  actor id in the join-state `:children` map) keyed on the parent's
  `:spawn-all`-bearing declaring path; one child attempt has one `:work/id`.
  `:work/kind :machine`.

  `ctx` keys: `:parent-id` (the join-owning parent INSTANCE), `:invoke-id`
  (the `:spawn-all`-bearing declaring path — the work-bearing path),
  `:child-id` (the logical child id off the arriving event), `:spawned-id`
  (the child's spawned instance address from `:children`), `:frame`,
  optional `:completed-at` (the firing dispatch's causal `:rf/time-ms`).
  `child-extra` is the child's forwarded payload (the terminal `:data`
  slice / error reason), carried under `:value` for a `:done` reply and
  wrapped as a family `:error` map for a `:failed` reply so the closed
  reply-map schema's value/error conventions hold. Optional facts are
  omitted (not nil-filled) when absent."
  [{:keys [parent-id invoke-id child-id spawned-id frame completed-at]} kind child-extra]
  (let [base (cond-> {:work/id   (spawn-work-id spawned-id invoke-id)
                      :work/kind :machine
                      :correlation
                      (cond-> {}
                        (some? parent-id)  (assoc :parent-id parent-id)
                        (some? invoke-id)  (assoc :invoke-id (vec invoke-id))
                        (some? child-id)   (assoc :child-id child-id)
                        (some? spawned-id) (assoc :spawned-id spawned-id))}
               (some? frame)        (assoc :rf.frame/id frame)
               (some? completed-at) (assoc :completed-at completed-at))]
    (if (= kind :failed)
      (assoc base
             :status      :error
             :work/status :failed
             :error       (let [e child-extra]
                            (if (and (map? e) (some? (:kind e)))
                              e
                              {:kind :rf.machine/spawn-all-child-error :value e})))
      (assoc base
             :status      :ok
             :work/status :completed
             :value       child-extra))))

(defn stale-join-child-reply
  "Build the `:status :stale` reply for a `:spawn-all` join-child
  completion that arrived AFTER the join already resolved (the
  post-resolution late-completion branch). Per Managed-Effects §Stale
  suppression the late completion MUST NOT mutate the join (the join is
  latched `:resolved?`); this reply carries NO `:value` and represents the
  drop the reply-envelope way — `:stale/reason
  :rf.machine.spawn-all/join-resolved`, `:work/status :suppressed`.

  Work-id matches `join-child-reply`'s so the suppressed late completion
  joins the same uniform work/reply row as the child's earlier (decisive
  or non-decisive) fold. `ctx` keys mirror `join-child-reply`'s; `kind`
  (`:done` / `:failed`) rides under `:correlation` as the would-be fold
  kind. Optional facts omitted when absent."
  [{:keys [parent-id invoke-id child-id spawned-id frame completed-at]} kind]
  (cond-> {:status       :stale
           :stale?       true
           :stale/reason :rf.machine.spawn-all/join-resolved
           :work/id      (spawn-work-id spawned-id invoke-id)
           :work/kind    :machine
           :work/status  :suppressed
           :correlation  (cond-> {}
                           (some? parent-id)  (assoc :parent-id parent-id)
                           (some? invoke-id)  (assoc :invoke-id (vec invoke-id))
                           (some? child-id)   (assoc :child-id child-id)
                           (some? spawned-id) (assoc :spawned-id spawned-id)
                           (some? kind)       (assoc :kind kind))}
    (some? frame)        (assoc :rf.frame/id frame)
    (some? completed-at) (assoc :completed-at completed-at)))

;; ---------------------------------------------------------------------------
;; `:after` timer — the existing specialized stale-gated reply instance
;; (EP-0011 §Timer Reply; Managed-Effects §Stale suppression). The declaring
;; path + per-path `:rf/after-epoch` ARE the data-only suppression gate.
;; ---------------------------------------------------------------------------

(defn timer-work-id
  "Build the machine `:after` timer work-id for one timer attempt:
  `[:rf.work/timer logical-timer-id epoch]` (Managed-Effects §Work-id
  correlation — the Timer row; the machine `:after` is a specialized timer
  instance per EP-0011 §Timer Reply). The `logical-timer-id` is the timer's
  declaring path (`[actor-id decl-path...]` when an `actor-id` is known,
  else the bare declaring path) — the stable identity of THIS `:after` within
  the chart; the `epoch` is the per-path `:rf/after-epoch` the timer was
  SCHEDULED in, which discriminates one timer attempt from a re-armed one on
  node re-entry (one attempt has one work id — EP-0007). `=`-comparable and
  EDN-serializable. `decl-path` nil (the node was exited — no live
  counterpart) yields `[:rf.work/timer nil epoch]`, a valid distinct id.

  rf2-niarhz — without this the machine `:after` timer completions
  (`:rf.machine.timer/fired` / `:rf.machine.timer/stale-after`) carried the
  reply STATUS / work-status but NO `:work/id`, so they could not join into
  the uniform work/reply rows that every other managed async family
  correlates by (Managed-Effects §Tracing — \"one `:work/id`\")."
  [actor-id decl-path epoch]
  (let [logical-id (cond
                     (and (some? actor-id) (some? decl-path))
                     (into [actor-id] (vec decl-path))
                     (some? decl-path) (vec decl-path)
                     :else actor-id)]
    [:rf.work/timer logical-id epoch]))

(defn after-suppression-gate
  "Build the data-only suppression gate for an `:after` timer completion:
  `{:path <decl-path> :rf/after-epoch <epoch>}` (Managed-Effects §Stale
  suppression — \"machine `:after` epoch and declaring path still match
  the active snapshot\"). The CARRIED gate (captured at scheduling, riding
  the synthetic timer event) and the CURRENT gate (read from the live
  snapshot at expiry) are compared by `re-frame.reply/stale?`: a timer is
  LIVE iff its declaring path is still active AND its carried epoch equals
  the node's current per-path epoch; otherwise STALE. `path` is nil when
  the node was exited (no live counterpart), which `re-frame.reply/stale?`
  treats as a mismatch."
  [decl-path epoch]
  {:path           (when decl-path (vec decl-path))
   :rf/after-epoch epoch})

(defn after-stale-reply
  "Build the `:status :stale` reply for a stale `:after` timer (the
  declaring node was exited, or its per-path epoch advanced on re-entry).
  Per Managed-Effects §Stale suppression the timer's transition MUST NOT
  fire — this is the existing epoch-gated drop expressed in the shared
  reply vocabulary. Carries NO `:value` (no app mutation),
  `:work/kind :timer` (the timer family work-kind; the machine `:after` is
  a specialized timer instance per EP-0011 §Timer Reply),
  `:work/status :suppressed`, and the carried/current epoch facts under
  `:correlation`.

  rf2-niarhz — the reply now carries the canonical `:work/id`
  `[:rf.work/timer <decl-path> <scheduled-epoch>]` (the timer's attempt
  identity — see `timer-work-id`), so a stale `:after` completion joins into
  the uniform work/reply rows by the same key HTTP / resources / routing use.

  rf2-hawtjr — the reply now also threads the CAUSAL completion timestamp
  (`:completed-at`) when the timer-firing dispatch supplied one. The
  synthetic `:after`-elapsed dispatch is a causal token carrying the
  router-stamped `:rf/time-ms` (EP-0010 / the fresh fire-time read); a
  stale `:after` completion can mutate machine snapshot data is not the
  case here (the transition is suppressed), but Managed-Effects §Causal
  completion metadata / §Tracing want completion time to ride the reply
  wherever the firing token has it, so the suppressed-timer trace can be
  correlated in real time with the firing dispatch. Omitted (not
  nil-filled) when absent — an unscripted / no-cofx fire path carries no
  `:completed-at` (Managed-Effects §The reply map).

  `ctx` keys: `:actor-id` (optional — the timer's owning actor INSTANCE),
  `:state`, `:delay`, `:decl-path`, `:scheduled-epoch`, `:current-epoch`,
  `:frame`, optional `:completed-at`."
  [{:keys [actor-id state delay decl-path scheduled-epoch current-epoch frame
           completed-at]}]
  (cond-> {:status       :stale
           :stale?       true
           :stale/reason :rf.machine.timer/after-epoch-mismatch
           :work/id      (timer-work-id actor-id decl-path scheduled-epoch)
           :work/kind    :timer
           :work/status  :suppressed
           :correlation  (cond-> {:state   state
                                  :delay   delay
                                  :carried (after-suppression-gate decl-path scheduled-epoch)
                                  :current (after-suppression-gate decl-path current-epoch)}
                           (some? actor-id) (assoc :actor-id actor-id))}
    (some? frame)        (assoc :rf.frame/id frame)
    (some? completed-at) (assoc :completed-at completed-at)))

(defn after-fired-reply
  "Build the canonical reply for a machine `:after` timer that FIRED (live —
  its declaring path is still active and its carried epoch equals the node's
  current per-path epoch). rf2-niarhz — a fired `:after` timer is a CLOSED
  `:after` completion: `:status :ok` / `:work/status :completed`, carrying the
  canonical `:work/id` `[:rf.work/timer <decl-path> <epoch>]` and
  `:work/kind :timer` so its `:rf.machine.timer/fired` trace joins the uniform
  work/reply rows the same way every other managed async completion does
  (Managed-Effects §Tracing — \"completion classified as one of the five
  statuses\"). A timer carries no `:value` (its effect is the transition it
  triggers, not a payload).

  A GUARD-SUPPRESSED fired timer (the timer was live but its transition guard
  evaluated false, so no transition fired) is STILL a closed `:status :ok` /
  `:work/status :completed` completion — the timer fired and completed; the
  guard's no-transition decision is APP-level, not the stale-suppression
  correctness boundary (the timer was NOT stale). The distinction rides as
  `:guard-suppressed? true` under `:correlation` (data-only), keeping
  `:work/status` inside the closed `work-statuses` vocabulary. Pass
  `:guard-suppressed? true` for that case.

  rf2-hawtjr — the reply now also threads the CAUSAL completion timestamp
  (`:completed-at`) when the timer-firing dispatch supplied one. The
  synthetic `:after`-elapsed dispatch is a causal token carrying the
  router-stamped `:rf/time-ms` (EP-0010 / the fresh fire-time read), and a
  fired `:after` timer's transition can mutate machine snapshot `:data`
  (its `:action`), so per Managed-Effects §Causal completion metadata the
  completion time MUST ride the reply when it affects durable state. The
  finishing dispatch's `:rf/time-ms` (the same token the timer-fired
  guard / action read off `:rf.cofx`) is threaded in by the caller.
  Omitted (not nil-filled) when absent — an unscripted / no-cofx fire path
  carries no `:completed-at` (Managed-Effects §The reply map).

  `ctx` keys: `:actor-id` (optional — the timer's owning actor INSTANCE),
  `:state`, `:delay`, `:decl-path`, `:epoch`, `:frame`, optional
  `:guard-suppressed?`, optional `:completed-at`."
  [{:keys [actor-id state delay decl-path epoch frame guard-suppressed?
           completed-at]}]
  (cond-> {:status      :ok
           ;; A timer carries no payload — its effect is the transition it
           ;; triggers, not a value. The reply-map contract requires a `:value`
           ;; slot for `:status :ok` (the "omit when absent" rule uses
           ;; `contains?`, so an explicit nil is the conformant "completed with
           ;; no payload" shape); a nil value keeps the reply schema-valid
           ;; without inventing a synthetic payload.
           :value       nil
           :work/id     (timer-work-id actor-id decl-path epoch)
           :work/kind   :timer
           :work/status :completed
           :correlation (cond-> {:state state
                                 :delay delay
                                 :gate  (after-suppression-gate decl-path epoch)}
                          (some? actor-id) (assoc :actor-id actor-id)
                          guard-suppressed?  (assoc :guard-suppressed? true))}
    (some? frame)        (assoc :rf.frame/id frame)
    (some? completed-at) (assoc :completed-at completed-at)))

;; ---------------------------------------------------------------------------
;; Terminal CANCELLATION replies (Managed-Effects §Cancellation; EP-0011
;; §Cancellation: "Cancellation is represented as data, not as the absence
;; of a reply").
;;
;; rf2-sfunt8 — machine cancellation terminal paths (timer cancel on state
;; exit / destroy / supersede / frame-destroy; actor destroy;
;; `:spawn-all` join-survivor cancel) previously completed by ABSENCE of a
;; reply — their traces carried reason / state / epoch but no canonical
;; `:work/id`, `:rf.reply/status :cancelled`, `:rf.reply/work-status`, or
;; `:cancel/reason`. A cancelled timer / actor could therefore have a
;; scheduled / spawned START but no terminal EP-0011 reply row. These
;; helpers close the work attempt the reply-envelope way: cancellation as
;; DATA. The validated `:status :cancelled` shape requires BOTH
;; `:cancel/reason` AND `:cancelled? true` (cancellation is a positive
;; fact), and `:work/status :cancelled` (the closed work-status vocab).
;; ---------------------------------------------------------------------------

(def timer-cancel-reasons
  "The closed `:rf.machine.timer/cancelled` `:reason` set (rf2-82a0u) —
  the cancel-reason taxonomy a cancelled-timer reply's `:cancel/reason`
  carries: `:on-exit` (state exit), `:on-destroy` (actor destroy),
  `:on-resolution` (subscription-delay re-resolution), `:on-supersede`
  (in-place reschedule), `:on-frame-destroy` (frame teardown)."
  #{:on-exit :on-destroy :on-resolution :on-supersede :on-frame-destroy})

(defn cancelled-timer-reply
  "Build the `:status :cancelled` reply for a cancelled machine `:after`
  timer (EP-0011 §Cancellation; Managed-Effects §Cancellation). The timer's
  host-clock handle was released before it fired; cancellation completes the
  work attempt as DATA rather than the absence of a reply. Carries the
  canonical `:work/id` `[:rf.work/timer <logical-id> <epoch>]` (matching
  `after-fired-reply` / `after-stale-reply` so a cancelled timer joins the
  same uniform work/reply row its scheduling started), `:work/kind :timer`,
  `:work/status :cancelled`, the `:cancelled? true` marker, and
  `:cancel/reason` from `timer-cancel-reasons`. NO `:value` (the timer never
  fired).

  `ctx` keys: `:actor-id` (the timer's owning actor INSTANCE — optional),
  `:state`, `:delay`, `:decl-path`, `:epoch`, `:frame`, and `:reason` (one
  of `timer-cancel-reasons`)."
  [{:keys [actor-id state delay decl-path epoch frame reason]}]
  (cond-> {:status        :cancelled
           :cancelled?    true
           :cancel/reason reason
           :work/id       (timer-work-id actor-id decl-path epoch)
           :work/kind     :timer
           :work/status   :cancelled
           :correlation   (cond-> {:state state
                                   :delay delay
                                   :gate  (after-suppression-gate decl-path epoch)}
                            (some? actor-id) (assoc :actor-id actor-id))}
    (some? frame) (assoc :rf.frame/id frame)))

(defn cancelled-actor-reply
  "Build the `:status :cancelled` reply for a cancelled (destroyed) spawned
  actor whose work attempt is closed by teardown before it reached a
  `:final?` leaf (EP-0011 §Cancellation — actor-destroy cancellation
  completes through the same envelope). Reuses the machine work-id
  `[:rf.work/machine actor-id work-bearing-path generation]` so the
  cancelled completion joins the same uniform work/reply row the spawn
  started; `:work/kind :machine`, `:work/status :cancelled`, the
  `:cancelled? true` marker, and `:cancel/reason` (the destroy reason —
  e.g. `:explicit` for a direct / cascade destroy, `:on-join-resolution`
  for a join-survivor teardown). NO `:value` (the actor never produced an
  `:output-key` result).

  `ctx` keys: `:actor-id` (the destroyed actor INSTANCE), `:parent-id`
  (optional), `:work-bearing-path` (the spawn declaring path — optional),
  `:frame`, `:reason` (the cancel reason)."
  [{:keys [actor-id parent-id work-bearing-path frame reason]}]
  (cond-> {:status        :cancelled
           :cancelled?    true
           :cancel/reason reason
           :work/id       (spawn-work-id actor-id work-bearing-path)
           :work/kind     :machine
           :work/status   :cancelled
           :correlation   (cond-> {:actor-id actor-id}
                            (some? parent-id)         (assoc :parent-id parent-id)
                            (some? work-bearing-path) (assoc :invoke-id (vec work-bearing-path)))}
    (some? frame) (assoc :rf.frame/id frame)))

;; ---------------------------------------------------------------------------
;; Trace summaries (Managed-Effects §Tracing). Data-only summaries of the
;; canonical reply, routing every wire-bearing slot through the single
;; shared `re-frame.reply/trace-summary` → `re-frame.elision/elide-wire-value`
;; walker — never a family-private elider. The identity / correlation facts
;; ride verbatim.
;; ---------------------------------------------------------------------------

(defn trace-reply
  "Build a DATA-ONLY trace summary of a canonical machine reply map for a
  managed-async trace row. The wire-bearing slots (`:value`, `:error`,
  `:correlation`, `:meta`) elide through the single shared
  `re-frame.elision/elide-wire-value` walker (via
  `re-frame.reply/trace-summary`) — never a family-private elider
  (Managed-Effects §Tracing); the identity facts (`:status`, `:work/id`,
  `:work/kind`, `:work/status`, `:rf.frame/id`, `:completed-at`,
  `:stale/reason`) ride verbatim. `opts` is forwarded to the walker (e.g.
  `:frame`)."
  ([reply] (trace-reply reply nil))
  ([reply opts] (reply/trace-summary reply opts)))

(defn stale-spawn-trace
  "Build a DATA-ONLY trace summary of a `stale-spawn-reply` for the
  managed-async trace row that records a suppressed late spawned-actor
  completion — the spawn-path counterpart of the `:after` path's
  `after-stale-reply` → `trace-reply`. The wire-bearing slots (here
  `:correlation`, carrying the carried/current generation gate) elide
  through the single shared `re-frame.elision/elide-wire-value` walker via
  `trace-reply`; the identity facts (`:status`, `:work/id`, `:work/kind`,
  `:work/status`, `:stale/reason`, `:rf.frame/id`) ride verbatim. `opts`
  is forwarded to the walker (e.g. `:frame`)."
  ([reply] (stale-spawn-trace reply nil))
  ([reply opts] (trace-reply reply opts)))
