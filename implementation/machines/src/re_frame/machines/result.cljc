(ns re-frame.machines.result
  "One result type for the machine-transition engine.

  The engine's pure surface (`apply-transition-once`, `machine-transition-
  single`, `parallel-machine-transition`, `apply-initial-entry-cascade`,
  and the public `machine-transition`) returns a Result map — either an
  `:ok` with the post-transition snapshot + emitted fx, or a `:fail`
  carrying diagnostic info about the action / `:data`-fn that threw. One
  shape is the engine's single success/failure result type.

  ## Shape

      ;; success
      {::tag :ok ::snap <snapshot> ::fx <fx-vec>}

      ;; failure
      {::tag :fail ::info <diagnostic-map>}

  Use the constructors `ok` / `fail` and the predicates `ok?` / `fail?`.
  The `::snap` / `::fx` / `::info` keys are public so callers can
  destructure with `::keys [snap fx]` — or, for the common
  pair-destructure-after-fail-check pattern, use the `with-ok` macro:

      (if (fail? r)
        (fail-with r ...)
        (with-ok [snap fx] r
          ...body using snap fx...))

  Single-field reads have plain accessor fns `snap` / `fx` / `info` so
  call sites don't need the `::result/` namespace prefix where only one
  slot is wanted.

  Rejected alternative — `defrecord Result [tag snap fx info]`: would let
  callers destructure with bare `{:keys [snap fx]}`, BUT changes the
  public key shape from `::tag` / `::snap` / `::fx` / `::info` (the
  `:rf/*` single-root namespaced scheme per `spec/Conventions.md`) to
  bare unqualified keys. External callers (`re-frame.machines/machine-
  transition` is publicly re-exported; core's smoke / pattern-smoke
  tests destructure via `::result/snap`) would all churn. The macro
  preserves the namespaced-key contract while giving call sites the
  ergonomic win.

  Bundle-isolation note: this ns is internal to the machines artefact.
  Nothing in `tools/` or `examples/` reaches into it; the public
  `re-frame.machines/machine-transition` surface returns Result values
  directly but consumers should use the predicates and accessors here
  rather than reading the `::tag` key by hand."
  (:refer-clojure :exclude [ok?]))

#?(:clj (set! *warn-on-reflection* true))

(defn ok
  "Build a success Result carrying `snap` (post-transition snapshot) and
  `fx` (the emitted fx vector)."
  [snap fx]
  {::tag :ok ::snap snap ::fx fx})

(defn fail
  "Build a failure Result carrying `info` (a diagnostic map describing
  which action / `:data`-fn threw, with keys like `:action-ref`,
  `:exception`, `:invoke-id`, `:decl-path`, `:transition`, `:state-path`)."
  [info]
  {::tag :fail ::info info})

(defn ok?
  "True iff `r` is an `:ok` Result."
  [r]
  (and (map? r) (= :ok (::tag r))))

(defn fail?
  "True iff `r` is a `:fail` Result."
  [r]
  (and (map? r) (= :fail (::tag r))))

(defn fail-with
  "Build a failure Result by `merge`ing `extra` over the existing
  `:fail` Result's `::info` map. Used by the outer cascade (transition,
  spawn) to enrich the inner failure (run-action / materialise-data) with
  the transition-level context (`:decl-path`, `:transition`,
  `:state-path`) before re-raising.

  Guarded with `(if (fail? r) … r)`, symmetric with the
  `with-handled` / `with-microsteps` / `with-cascade` siblings (which
  pass a non-`:ok` Result through unchanged). A non-`:fail` Result returns
  unchanged — `fail-with` only enriches an actual failure's `::info`, never
  grafts a phantom `::info` onto an `:ok` Result (which would corrupt it
  into a hybrid map carrying both `::snap`/`::fx` AND `::info`)."
  [r extra]
  (if (fail? r)
    (assoc r ::info (merge (::info r) extra))
    r))

(defn depth-abort
  "Build a `:fail` Result for a bounded-depth abort (`:always-depth-limit`
  / `:raise-depth-limit` tripped mid-macrostep).

  A runaway eventless / raise cycle (e.g. `a →:always b →:always a`) is NOT
  a benign no-op: XState v5 THROWS on such a cycle. A depth abort returns a
  `:fail` Result, which routes through the SAME failure path an action
  exception takes (`trace-action-failure!` short-circuits the handler to
  `{}`), so the macrostep surfaces as a FAILED macrostep — no snapshot
  write reaches runtime-db, preserving the atomic rollback Spec 005
  §Bounded depth requires (the pre-event snapshot stays committed), and the
  triggering user event is not silently consumed.

  The `::info` map carries `::depth-abort? true` so the handler routing
  recognises this is a depth-abort — NOT a thrown action — and SKIPS the
  generic `:rf.error/machine-action-exception` re-emit (the engine already
  emitted the precise `:rf.error/machine-{always,raise}-depth-exceeded`
  category at the abort site, the single trace for the trip). `info` carries
  the diagnostic context (`:error-id`, `:actor-id`, `:depth`, `:path`,
  `:frame`) for callers / tests that inspect the `::info`."
  [info]
  {::tag :fail ::info (assoc info ::depth-abort? true)})

(defn depth-abort?
  "True iff `r` is a `:fail` Result produced by a bounded-depth abort
  (`depth-abort`) — distinguishes a `:always` / `:raise` depth-limit trip
  from a thrown-action `:fail`, so the handler routing skips the generic
  action-exception trace for the depth case. A non-`:fail`
  Result (or a thrown-action `:fail`) returns false."
  [r]
  (and (fail? r) (true? (get-in r [::info ::depth-abort?]))))

(defn snap
  "Read the post-transition snapshot off an `:ok` Result. One-char-shorter
  spelling of `(::result/snap r)` — same semantics. For pair destructures
  use the `with-ok` macro."
  [r]
  (::snap r))

(defn fx
  "Read the emitted fx vector off an `:ok` Result. One-char-shorter
  spelling of `(::result/fx r)` — same semantics. For pair destructures
  use the `with-ok` macro."
  [r]
  (::fx r))

(defn info
  "Read the diagnostic info map off a `:fail` Result. One-char-shorter
  spelling of `(::result/info r)` — same semantics."
  [r]
  (::info r))

(defn with-handled
  "Stamp the optional `::handled?` flag onto an `:ok` Result. Per Spec 005
  §Transition resolution / §Parallel regions (005:1168-1171): a region of
  a parallel-region machine reports whether its inbound event resolved to
  a transition so the parent can emit the benign
  `:rf.machine.event/unhandled-no-op` trace exactly once when EVERY region
  declines (xstate-v5 parity; not an error). The flag is internal to the
  machines engine — `:fail` Results and non-region callers ignore it; the
  key is namespaced so it never collides with snapshot / fx slots."
  [r handled?]
  (if (ok? r) (assoc r ::handled? handled?) r))

(defn handled?
  "Read the `::handled?` flag off a Result. Defaults to `true` when absent
  so non-region single-machine results (which never stamp it) are never
  mistaken for declined events."
  [r]
  (get r ::handled? true))

(defn with-microsteps
  "Stamp the optional `::microsteps` count onto an `:ok` Result. Per Spec
  005 §Trace events: the macrostep's `:always` microstep count rides the
  Result so the lifecycle handler can stamp `:microsteps` on the outer
  `:rf.machine/transition` trace. Internal to the machines engine; the
  key is namespaced so it never collides with snapshot / fx slots."
  [r n]
  (if (ok? r) (assoc r ::microsteps n) r))

(defn microsteps
  "Read the `::microsteps` count off a Result. Defaults to 0 when absent
  (e.g. an unhandled-event / stale-timer / depth-exceeded path took no
  `:always` microsteps)."
  [r]
  (get r ::microsteps 0))

(defn with-cascade
  "Stamp the optional `::cascade` step-vector onto an `:ok` Result. Per
  Spec 005 §Transition cascade instrumentation: the ordered
  exit → action → entry (+ initial-descent) + `:always`-microstep step
  sequence the macrostep ran, so the lifecycle handler can carry it as
  the `:cascade` field on the outer `:rf.machine/transition` trace. This
  is the structured explanation of HOW the transition reached its
  after-state — the contract Xray's epoch panel renders.

  Internal to the machines engine; the key is namespaced so it never
  collides with snapshot / fx slots. `:fail` Results pass through
  unchanged (a throwing action halts the cascade; the partial step
  vector is not threaded)."
  [r steps]
  (if (ok? r) (assoc r ::cascade steps) r))

(defn cascade
  "Read the `::cascade` step-vector off a Result. Defaults to `[]` when
  absent (e.g. an unhandled-event / stale-timer / depth-exceeded path,
  or a `:fail` Result, ran no cascade steps)."
  [r]
  (get r ::cascade []))

(defn with-parallel-done
  "Stamp the `::parallel-done-handled?` flag onto an `:ok` Result. Per Spec
  005 §Final states §The done-state signal: when a parallel
  machine reaches all-regions-final AND its root declared `:on-done`, the
  parallel layer fires that `:on-done` (run action + emit fx) and marks the
  Result so the lifecycle boundary (`commit-or-finalize`) does NOT auto-
  destroy the machine — the transitionable parallel-completion signal keeps
  the machine alive. Absent / false ⇒ the existing whole-machine finalize
  runs (singleton auto-destroy / spawning-parent `:on-done`, D7). Internal to
  the machines engine; namespaced so it never collides with snapshot / fx
  slots."
  [r]
  (if (ok? r) (assoc r ::parallel-done-handled? true) r))

(defn parallel-done-handled?
  "Read the `::parallel-done-handled?` flag off a Result. Defaults to false
  when absent (no parallel `:on-done` fired — the whole-machine finalize path
  applies)."
  [r]
  (get r ::parallel-done-handled? false))

#?(:clj
   (defmacro with-ok
     "Pair-destructure an `:ok` Result's `::snap` and `::fx` slots into
     `snap-sym` and `fx-sym`, evaluate `body` in their scope. The macro
     captures the dominant call-site pattern across the engine — the
     `(if (fail? r) ... (let [{snap ::result/snap fx ::result/fx} r] ...))`
     dance — without churning the namespaced-key public-API contract.

         (if (fail? cascade-r)
           (fail-with cascade-r {...})
           (with-ok [snap-after fx] cascade-r
             ...body using snap-after, fx...))

     `snap-sym` / `fx-sym` may be any local names — `_` discards. The
     ok-vs-fail check is NOT performed here; this is destructure-only
     sugar, intended to follow an explicit `(fail? r)` branch. Use the
     plain `snap` / `fx` / `info` accessor fns for single-slot reads."
     [[snap-sym fx-sym] r & body]
     (let [r-sym (gensym "r")]
       `(let [~r-sym  ~r
              ~snap-sym (::snap ~r-sym)
              ~fx-sym   (::fx ~r-sym)]
          ~@body))))
