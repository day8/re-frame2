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
      :spawn-id …}`. The PUBLIC `:on-done` `:data` callback is then driven
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
  family-private elider (Managed-Effects §Tracing)."
  (:require [re-frame.reply :as reply]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Work-id correlation (Managed-Effects §Work-id correlation).
;;
;; Machine head: `[:rf.work/machine actor-id work-bearing-path generation]`.
;;  - `actor-id`         — the finishing actor's id (the `<type>#<n>`
;;                         instance id, or an explicit `:spawn-id`);
;;  - `work-bearing-path` — the `:spawn`-bearing node's declaring path
;;                         (`:rf/spawn-id` the runtime stamped on the
;;                         child's `:data` at spawn time);
;;  - `generation`       — the monotonic spawn discriminator; for the
;;                         `<type>#<n>` instance-id form it is `n` (the
;;                         spawn-counter reading that minted this instance),
;;                         so a re-spawn under the same declaring path lands
;;                         on a fresh work id (one ATTEMPT, one `:work/id`,
;;                         per EP-0007). An explicit `:spawn-id` actor with
;;                         no `#n` suffix carries generation 1.
;; ---------------------------------------------------------------------------

(defn actor-generation
  "Parse the spawn generation off an `actor-id` of the form
  `<type>#<n>` (the instance id the spawn-counter mints — see
  `lifecycle-fx.spawn/allocate-actor-id-in-runtime-db`). Returns the
  integer `n`, or 1 when the id carries no `#n` suffix (an explicit
  per-state-singleton `:spawn-id` actor — one attempt, generation 1).
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
              n      #?(:clj  (try (Long/parseLong suffix) (catch Exception _ nil))
                        :cljs (let [x (js/parseInt suffix 10)] (when-not (js/isNaN x) x)))]
          (or n 1))
        1))))

(defn spawn-work-id
  "Build the machine work-id for one spawned-actor attempt:
  `[:rf.work/machine actor-id work-bearing-path generation]`
  (Managed-Effects §Work-id correlation; EP-0011 §Machine Completion).
  `actor-id` is the finishing actor's id; `work-bearing-path` is the
  `:spawn`-bearing node's declaring path (the child's stamped
  `:rf/spawn-id`). `generation` is parsed off the `<type>#<n>` instance
  id (or 1 for an explicit `:spawn-id`). `=`-comparable and
  EDN-serializable."
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
  `:correlation {:actor-id … :parent-id … :spawn-id …}`. Optional facts
  are omitted when absent rather than nil-filled (Managed-Effects §The
  reply map)."
  [{:keys [actor-id parent-id work-bearing-path frame completed-at]}]
  (cond-> {:work/id   (spawn-work-id actor-id work-bearing-path)
           :work/kind :machine}
    (some? frame)        (assoc :rf.frame/id frame)
    (some? completed-at) (assoc :completed-at completed-at)
    true                 (assoc :correlation
                                (cond-> {:actor-id actor-id}
                                  (some? parent-id)         (assoc :parent-id parent-id)
                                  (some? work-bearing-path) (assoc :spawn-id (vec work-bearing-path))))))

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
                             (some? work-bearing-path) (assoc :spawn-id (vec work-bearing-path)))}
      (some? frame)        (assoc :rf.frame/id frame)
      (some? completed-at) (assoc :completed-at completed-at))))

;; ---------------------------------------------------------------------------
;; `:after` timer — the existing specialized stale-gated reply instance
;; (EP-0011 §Timer Reply; Managed-Effects §Stale suppression). The declaring
;; path + per-path `:rf/after-epoch` ARE the data-only suppression gate.
;; ---------------------------------------------------------------------------

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

  `ctx` keys: `:machine-id` (optional), `:state`, `:delay`,
  `:decl-path`, `:scheduled-epoch`, `:current-epoch`, `:frame`."
  [{:keys [machine-id state delay decl-path scheduled-epoch current-epoch frame]}]
  (cond-> {:status       :stale
           :stale?       true
           :stale/reason :rf.machine.timer/after-epoch-mismatch
           :work/kind    :timer
           :work/status  :suppressed
           :correlation  (cond-> {:state   state
                                  :delay   delay
                                  :carried (after-suppression-gate decl-path scheduled-epoch)
                                  :current (after-suppression-gate decl-path current-epoch)}
                           (some? machine-id) (assoc :machine-id machine-id))}
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
