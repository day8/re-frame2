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
  destructure with `::keys [snap fx]`.

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
