(ns re-frame2-pair-mcp.tools.boundary-step
  "Pluggable wire-boundary step-pipeline (rf2-3z0zi).

  ## Why a step-pipeline

  `invoke` (in `tools.cljs`) drives every MCP `tools/call` through a
  short, ordered sequence of phases:

      precheck → dispatch → apply-cache → apply-cap

  Before this ns landed the four phases were hardcoded inline in
  `invoke` as a hand-threaded Promise chain. Each phase had its own
  short-circuit rule (`apply-cache` skips `:isError`; `apply-cap`
  skipped nothing). The ordering invariant was prose-only — the
  `invoke` docstring described it; the impl had to be read top-to-
  bottom to verify it.

  This namespace is the round-2 parallel of round-1's named
  wire-pipeline win (`tools/wire-pipeline.cljs`): the same lens
  (turn an implicit ordering into named, declarative data) applied
  one level up. Each step is a map:

  ```clojure
  {:name           :apply-cap
   :run            (fn [ctx] Promise<ctx>)
   :short-circuit? (fn [ctx] boolean)}
  ```

  - `:name`           — namespaced for tracing / errors.
  - `:run`            — receives the live context map and returns a
                        Promise of the next context. Side-effects (LRU
                        writes, nREPL round-trips) are the step's own.
  - `:short-circuit?` — optional predicate over the post-`:run`
                        context. When truthy, the rest of the pipeline
                        is skipped — the current `:result` is the
                        final response. Pure on the context; cheap.

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
  `:precheck-hash` slot is the cheap-hash from rf2-36xod; the
  `apply-cache` step consumes it to record on a miss.

  ## Short-circuit semantics

  Each step's `:short-circuit?` is checked BEFORE its `:run` — the
  predicate decides 'should I bother running'. When truthy, the
  step is skipped and the pipeline continues to the next step
  (whose own predicate is then consulted).

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
  Promise of the next `ctx`. After every step, the `:short-circuit?`
  predicate is consulted — when truthy, the rest of the pipeline is
  skipped and `ctx` is returned as-is.

  Returns a Promise of the final `ctx`. Caller extracts `:result` for
  the MCP wire reply."
  [steps ctx]
  (reduce
    (fn [pr {:keys [run short-circuit?] :as _step}]
      (-> pr
          (.then
            (fn [ctx]
              (if (and short-circuit? (short-circuit? ctx))
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
