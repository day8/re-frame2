(ns re-frame-2.registrar
  "The global registrar — `(kind, id) → metadata` lookup.

  Per Spec 001, the registrar is a single per-process map keyed by kind.
  Frames isolate STATE; the registrar is shared across all frames.

  Reserved kinds (closed v1 set, per Spec 001 §Registry model):
    :event :sub :fx :cofx :view :frame :route :app-schema :head
    :error-projector :flow

  Per Spec 005, machine guards/actions are machine-scoped (declared in
  the machine's :guards / :actions map) and NOT registered as standalone
  kinds. The kinds set below is the closed v1 list.")

;; ---- the kind set ---------------------------------------------------------

(def kinds
  "The closed set of registry kinds for v1. Adding a new kind is a Spec change."
  #{:event :sub :fx :cofx :view :frame :route :app-schema :head
    :error-projector :flow})

(defn valid-kind? [k]
  (contains? kinds k))

;; ---- the registry state ---------------------------------------------------

(defonce
  ^{:doc "kind → id → metadata-map. Atomic. The registrar is per-process."}
  kind->id->metadata
  (atom {}))

;; ---- registration ---------------------------------------------------------

(defn register!
  "Register an id under kind with the given metadata. Re-registering the
  same id replaces the slot atomically (per Spec 001 §Hot-reload semantics
  guarantee 1 — non-destructive to in-flight work; the runtime sees the
  new fn on the next lookup)."
  [kind id metadata]
  (when-not (valid-kind? kind)
    (throw (ex-info (str "re-frame-2: unknown registry kind: " kind)
                    {:kind kind :id id})))
  (let [previous (get-in @kind->id->metadata [kind id])]
    (swap! kind->id->metadata assoc-in [kind id] metadata)
    ;; Trace event :rf.registry/handler-{registered,replaced} per Spec 009.
    ;; Trace integration is wired in re-frame-2.trace; we don't import it
    ;; here to keep the registrar dep-free and JVM-runnable.
    {:was previous :now metadata}))

(defn unregister!
  "Remove a single id under kind. Hot-reload code paths use this; user code
  rarely does."
  [kind id]
  (swap! kind->id->metadata update kind dissoc id)
  nil)

(defn clear-kind!
  "Remove every id under kind. Test fixtures use this to reset state."
  [kind]
  (swap! kind->id->metadata dissoc kind)
  nil)

(defn clear-all!
  "Remove every registration for every kind. Test fixtures use this."
  []
  (reset! kind->id->metadata {})
  nil)

;; ---- lookup ---------------------------------------------------------------

(defn lookup
  "Return the metadata map registered for (kind, id), or nil."
  [kind id]
  (get-in @kind->id->metadata [kind id]))

(defn handler
  "Return just the handler fn from the metadata, or nil. The handler is
  stored under :handler-fn in the metadata map by the kind-specific reg-*
  macros (events) or directly (subs/fx)."
  [kind id]
  (when-let [meta (lookup kind id)]
    (:handler-fn meta)))

;; ---- query API (per Spec 002 §The public registrar query API) -------------

(defn handlers
  "All ids registered under kind, with their metadata. Tools, agents,
  storybook resolution, all use this."
  [kind]
  (get @kind->id->metadata kind {}))

(defn handler-meta
  "Public alias for lookup. Used by tooling."
  [kind id]
  (lookup kind id))

(defn ids
  "Just the id set for a kind."
  [kind]
  (-> (handlers kind) keys set))

(defn all-kinds-with-counts
  "{kind → count} — useful in dev tooling overlays."
  []
  (into {}
        (map (fn [[k m]] [k (count m)]))
        @kind->id->metadata))
