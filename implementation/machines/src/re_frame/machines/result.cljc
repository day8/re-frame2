(ns re-frame.machines.result
  "One result type for the machine-transition engine. Per rf2-aa2rw / rf2-ra1he §P0 #2.

  The engine's pure surface (`apply-transition-once`, `machine-transition-
  single`, `parallel-machine-transition`, `apply-initial-entry-cascade`,
  and the public `machine-transition`) returns a Result map — either an
  `:ok` with the post-transition snapshot + emitted fx, or a `:fail`
  carrying diagnostic info about the action / `:data`-fn that threw.

  Pre-refactor this concept was spelled three different ways across the
  engine — `[::action-failed info]` vector sentinel, a
  `{:rf.machine/action-failure ...}` map shape inside `run-action`, and a
  `[:ok | :fail]` mini-ADT inside `materialise-data`. Six call-sites
  spelled the same `(if (and (vector? r) (= ::action-failed (first r))) ...)`
  disambiguation guard. This namespace replaces all three with one shape.

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
  `:state-path`) before re-raising."
  [r extra]
  (assoc r ::info (merge (::info r) extra)))

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
