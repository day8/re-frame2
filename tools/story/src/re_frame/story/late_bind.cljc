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

  - `:tap-stub-event`              — fx-stubs → frames. Called on
                                     every stub-event firing so the
                                     assertion module's per-frame
                                     emitted-fx accumulator records
                                     the call. Signature: `(f frame-id
                                     fx-id) → nil`.

  - `:drop-assertion-accumulators` — assertions+play → frames. Called
                                     on frame teardown so per-frame
                                     accumulators evict their entries.
                                     Signature: `(f frame-id) → nil`."
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
