(ns re-frame2-pair-mcp.tools.boundary-step
  "Pluggable wire-boundary step-pipeline.

  ## Why a step-pipeline

  `invoke` (in `tools.cljs`) drives every MCP `tools/call` through a
  short, ordered sequence of phases:

      precheck → dispatch → apply-cache → apply-cap

  Each phase carries its own short-circuit rule (`apply-cache` skips
  `:isError`; `apply-cap` skips nothing). Expressing the sequence as
  named, declarative data — rather than a hand-threaded Promise chain
  — makes the ordering invariant explicit and inspectable instead of
  prose-only.

  This namespace mirrors the named wire-pipeline in
  `tools/wire-pipeline.cljs`: the same lens (turn an implicit ordering
  into named, declarative data) applied one level up. Each step is a
  map:

  ```clojure
  {:name       :apply-cap
   :run        (fn [ctx] Promise<ctx>)
   :skip-when? (fn [ctx] boolean)}
  ```

  - `:name`       — namespaced for tracing / errors.
  - `:run`        — receives the live context map and returns a
                    Promise of the next context. Side-effects (LRU
                    writes, nREPL round-trips) are the step's own.
  - `:skip-when?` — optional predicate over the pre-`:run` context.
                    When truthy, THIS step is skipped (its `:run` is
                    not called) and the pipeline continues to the
                    next step. Pure on the context; cheap. The name
                    is deliberate: the semantics are skip-this-step,
                    NOT halt-the-chain.

  ## Context shape

  ```clojure
  {:conn          <nrepl-conn>
   :name          \"<tool-name>\"
   :args          <js-args>
   :extra         <mcp-extra>
   :result        <js-mcp-result or nil>
   :precheck-hash <int or nil>}
  ```

  Every step receives this context. The `:result` slot is the running
  payload — nil before `:dispatch` runs, populated thereafter. Steps
  that produce a result write it; steps that consume it read it. The
  `:precheck-hash` slot is the cheap precheck hash; the
  `apply-cache` step consumes it to record on a miss.

  ## Skip-when semantics

  Each step's `:skip-when?` is checked BEFORE its `:run` — the
  predicate decides 'should I bother running'. When truthy, the
  step is skipped and the pipeline continues to the next step
  (whose own predicate is then consulted). 'Skip' is per-step,
  not chain-halting.

  `apply-cap`'s predicate fires on a `marker?` result (a cache-hit
  or overflow envelope is already a wire-bounded marker — capping
  it is wasted work). `apply-cache`'s predicate fires on an
  `:isError` result (errors must not poison the cache).

  Predicates are pure over the context's current `:result` slot —
  no global state, no side effects. A skipped step leaves the
  context unchanged.

  ## Adding a step

  Land a new step map in `tools.cljs`'s `wire-boundary-pipeline` def
  and the orchestration picks it up. The four envisaged future steps
  (request-level redaction, metrics emission, path-prefix slicing,
  per-call elision toggle) plug in once at this layer."
  (:require [re-frame2-pair-mcp.tools.wire :as wire]))

(defn run-step-pipeline
  "Thread `ctx` through `steps` in order. Each step's `:run` returns a
  Promise of the next `ctx`. Before every step, the `:skip-when?`
  predicate is consulted — when truthy, THAT step's `:run` is not
  called; the pipeline continues to the next step.

  Returns a Promise of the final `ctx`. Caller extracts `:result` for
  the MCP wire reply."
  [steps ctx]
  (reduce
    (fn [pr {:keys [run skip-when?] :as _step}]
      (-> pr
          (.then
            (fn [ctx]
              (if (and skip-when? (skip-when? ctx))
                ctx
                (run ctx))))))
    (js/Promise.resolve ctx)
    steps))

(defn run-and-extract
  "Convenience — run the pipeline and unwrap the `:result` slot.
  Returns a Promise of the JS-shape MCP result.

  An empty pipeline or a step that never sets `:result` returns an
  `:unknown-tool` error envelope rather than `undefined` on the wire."
  [steps ctx]
  (-> (run-step-pipeline steps ctx)
      (.then (fn [{:keys [name result]}]
               (or result
                   (wire/err-text {:ok?    false
                                   :reason :pipeline-produced-no-result
                                   :tool   name}))))))
