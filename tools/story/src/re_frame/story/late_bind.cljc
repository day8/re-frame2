(ns re-frame.story.late-bind
  "Late-binding hook registry for cross-namespace forward references
  within the Story artefact. Mirrors the framework's
  `re-frame.late-bind` pattern.

  Some Story leaf namespaces (frames, loaders) need to call into
  higher-layer Story namespaces (fx-stubs, assertions, play) but
  cannot `:require` them without introducing a cyclic load order —
  the higher-layer namespaces transitively `:require` the leaves.

  Producing namespaces register their callable at load time (or at
  `install-canonical-vocabulary!` time) via `set-fn!`; consuming
  namespaces fetch it via `get-fn` at call time. When the producer
  is absent (Stage 3-only builds where assertions haven't loaded),
  the lookup returns nil and the consumer no-ops.

  ## Hook keys

  - `:stub-observed-fx-ids`        — fx-stubs → assertions. Returns the
                                     set of ORIGINAL fx-ids a frame's
                                     `force-fx-stub` decorators redirected
                                     (read from the stub-call log). The
                                     assertions module unions it with the
                                     epoch tape's effects for
                                     `:rf.assert/effect-emitted`
                                     (a stubbed fx lands on the tape under
                                     its rewritten stub id, not its
                                     original). Signature: `(f frame-id) →
                                     #{fx-id …}`.

  - `:drop-assertion-accumulators` — play → frames. Called on frame
                                     teardown so the play module's
                                     per-frame `pending-exceptions` slot
                                     evicts its entry. Signature:
                                     `(f frame-id) → nil`.

  - `:drop-run-state`              — runner-events → frames. Called on
                                     frame teardown so the play-runner's
                                     per-frame run-state evicts its entry
                                     across the four process-global atoms
                                     (`run-state` / `runs-by-play` /
                                     `active-play` / `step-boundaries`).
                                     Without it a destroyed variant frame
                                     leaks its terminal play status + the
                                     toolbar's focused-play slot, and a
                                     re-allocated frame of the same id can
                                     observe the prior incarnation's
                                     run-state before its first fresh run
                                     overwrites it. `frames` cannot
                                     `:require` `runner-events` (cycle:
                                     runner-events → play → frames), so the
                                     teardown call routes through this hook.
                                     Signature: `(f frame-id) → nil`.

  - `:render-host`                 — story (via the canonical
                                     `install-render-host!`) → render. The
                                     CLJS host's view-render seam, consumed
                                     by `render-variant` to paint the active
                                     view. Signature: `(f render-inputs) →
                                     host-render-result`. Absent on the bare
                                     JVM (no DOM / substrate), so
                                     `render-variant` returns `:cannot-run`.

  - `:render-hiccup`               — a host that can render the active view
                                     to a HICCUP TREE (data) → the play
                                     runner's browser-tier executor
                                     (`re-frame.story.play.runner-events` →
                                     `re-frame.story.play.browser`). Signature:
                                     `(f frame-id) → hiccup-tree | nil`. This
                                     is the `:hiccup-structure` proof surface
                                     `:rf.assert/a11y-structural` consumes on
                                     the run path: a `:hiccup`-or-richer host
                                     installs it so the structural-a11y check
                                     can walk the rendered tree. The bare
                                     headless floor leaves it nil, so the
                                     executor refuses `:cannot-run` (never a
                                     vacuous pass over a nil tree)."
  )

(defonce
  ^{:doc "Map of hook-key → fn. Populated by the producing namespace
         (typically from `re-frame.story/install-canonical-vocabulary!`).
         Consumers look up via `get-fn`."}
  hooks
  (atom {}))

(defn set-fn!
  "Register a fn under `hook-key`. Idempotent — re-registration
  replaces the slot per Spec 001 hot-reload semantics."
  [hook-key f]
  (swap! hooks assoc hook-key f)
  nil)

(defn get-fn
  "Return the fn registered under `hook-key`, or nil if no producer
  has registered it yet. Callers MUST handle the nil case (the
  common pattern is `(when-let [f (late-bind/get-fn ...)] (f args))`)."
  [hook-key]
  (get @hooks hook-key))

(defn clear!
  "Drop every hook registration. Test-fixture only."
  []
  (reset! hooks {})
  nil)
