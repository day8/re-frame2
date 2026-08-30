(ns re-frame.machines.result
  "The machine-transition engine's one result type. INTERNAL to the
  machines artefact: nothing outside `implementation/machines/src` reads
  these maps, and the accessors below are the engine's, not the app's.

  The engine's pure seams (`apply-transition-once`, `machine-transition-
  single`, `parallel-machine-transition`, `apply-initial-entry-cascade`)
  return a Result — success with the post-transition snapshot + emitted
  fx, or failure carrying diagnostic info about the guard / action /
  `:data`-fn that threw (or the bounded-depth limit that tripped).

  ## Shape

  The core keys are the Spec 005 §Level 1 public spellings, so the engine
  and the public surface never disagree about what a snapshot or an fx
  vector is called:

      ;; success
      {:status :ok :snapshot <snapshot> :fx <fx-vec>}

      ;; failure
      {:status :error :error <diagnostic-map>}

  On top of those the engine rides namespaced bookkeeping the lifecycle
  handler reads and nobody else should — `::handled?`, `::microsteps`,
  `::cascade`, `::parallel-done-handled?` on a success, and the
  `::depth-abort?` sentinel inside a failure's `:error` map. The public
  `re-frame.machines/machine-transition` strips every one of them and
  stamps the public `:kind`; see that facade for the projection.

  Use the constructors `ok` / `fail` / `depth-abort`, the predicates
  `ok?` / `fail?` / `depth-abort?`, the accessors `snap` / `fx` / `info`,
  and — for the pair-destructure-after-fail-check pattern — the
  `with-ok` macro:

      (if (fail? r)
        (fail-with r ...)
        (with-ok [snap fx] r
          ...body using snap fx...))"
  (:refer-clojure :exclude [ok?]))

#?(:clj (set! *warn-on-reflection* true))

(defn ok
  "Build a success Result carrying `snap` (post-transition snapshot) and
  `fx` (the emitted fx vector)."
  [snap fx]
  {:status :ok :snapshot snap :fx fx})

(defn fail
  "Build a failure Result carrying `info` (a diagnostic map describing
  which action / `:data`-fn threw, with keys like `:action-ref`,
  `:exception`, `:invoke-id`, `:decl-path`, `:transition`, `:state-path`)."
  [info]
  {:status :error :error info})

(defn ok?
  "True iff `r` is a success Result."
  [r]
  (and (map? r) (= :ok (:status r))))

(defn fail?
  "True iff `r` is a failure Result."
  [r]
  (and (map? r) (= :error (:status r))))

(defn fail-with
  "Build a failure Result by `merge`ing `extra` over the existing
  failure's `:error` map. Used by the outer cascade (transition, spawn)
  to enrich the inner failure (run-action / materialise-data) with the
  transition-level context (`:decl-path`, `:transition`, `:state-path`)
  before re-raising.

  Guarded with `(if (fail? r) … r)`, symmetric with the
  `with-handled` / `with-microsteps` / `with-cascade` siblings (which
  pass a failure through unchanged). A success returns unchanged —
  `fail-with` only enriches an actual failure's `:error`, never grafts a
  phantom `:error` onto a success (which would corrupt it into a hybrid
  map carrying both `:snapshot`/`:fx` AND `:error`)."
  [r extra]
  (if (fail? r)
    (assoc r :error (merge (:error r) extra))
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

  The `:error` map carries `::depth-abort? true` so the handler routing
  recognises this is a depth-abort — NOT a thrown action — and SKIPS the
  generic `:rf.error/machine-action-exception` re-emit (the engine already
  emitted the precise `:rf.error/machine-{always,raise}-depth-exceeded`
  category at the abort site, the single trace for the trip). `info` carries
  the diagnostic context (`:error-id`, `:actor-id`, `:depth`, `:path`,
  `:frame`) for callers / tests that inspect the `:error` map."
  [info]
  {:status :error :error (assoc info ::depth-abort? true)})

(defn depth-abort?
  "True iff `r` is a failure Result produced by a bounded-depth abort
  (`depth-abort`) — distinguishes a `:always` / `:raise` depth-limit trip
  from a thrown-action failure, so the handler routing skips the generic
  action-exception trace for the depth case. A success (or a
  thrown-action failure) returns false."
  [r]
  (and (fail? r) (true? (get-in r [:error ::depth-abort?]))))

(defn snap
  "Read the post-transition snapshot off a success Result. For pair
  destructures use the `with-ok` macro."
  [r]
  (:snapshot r))

(defn fx
  "Read the emitted fx vector off a success Result. For pair destructures
  use the `with-ok` macro."
  [r]
  (:fx r))

(defn info
  "Read the diagnostic info map off a failure Result."
  [r]
  (:error r))

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
     "Pair-destructure a success Result's `:snapshot` and `:fx` slots into
     `snap-sym` and `fx-sym`, evaluate `body` in their scope. The macro
     captures the dominant call-site pattern across the engine — the
     `(if (fail? r) ... (let [{snap :snapshot fx :fx} r] ...))` dance.

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
              ~snap-sym (:snapshot ~r-sym)
              ~fx-sym   (:fx ~r-sym)]
          ~@body))))
