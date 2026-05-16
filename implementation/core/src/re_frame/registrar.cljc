(ns re-frame.registrar
  "The global registrar — `(kind, id) → metadata` lookup.

  Per Spec 001, the registrar is a single per-process map keyed by kind.
  Frames isolate STATE; the registrar is shared across all frames.

  Reserved kinds (closed v1 set, per Spec 001 §Registry model):
    :event :sub :fx :cofx :view :frame :route :app-schema :head
    :error-projector :flow

  Per Spec 005, machine guards/actions are machine-scoped (declared in
  the machine's :guards / :actions map) and NOT registered as standalone
  kinds. The kinds set below is the closed v1 list.

  The `:app-schema` kind is RESERVED but the registrar slot is
  intentionally **empty** — `reg-app-schema` writes only to the
  schemas artefact's own per-frame side-table (`schemas/schemas-by-
  frame`), which is the single source of truth. The kind keyword is
  preserved for the test-support `:clear-kinds` interface and for
  Spec 001 §Registry model continuity; tools introspecting app-db
  schemas go through `schemas/app-schemas` / `schemas/app-schema-meta-at`.

  ## Production elision

  The :rf.registry/* trace emit sites in this namespace are gated on
  `re-frame.interop/debug-enabled?` (per Spec 009 §Production builds) so
  that the late-bind lookup, the call into trace/emit!, and the small
  metadata map allocation all disappear from `:advanced` production
  bundles where `goog.DEBUG` is `false`."
  (:require [re-frame.interop   :as interop]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

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

(defonce ^:private replacement-hooks
  ;; Subscribers to "an existing id was replaced". Each is called with
  ;; a map {:kind :id :was :now}. Registered by namespaces that need to
  ;; respond to hot-reload (e.g. subs.cljc invalidates its cache).
  (atom []))

(defn add-replacement-hook!
  "Register a fn called on every register! that replaces an existing
  registration. Args: a map {:kind kind :id id :was prev-meta :now new-meta}."
  [f]
  (swap! replacement-hooks conj f)
  nil)

(defonce ^:private registration-hooks
  ;; Subscribers to "any id was registered" — first-time OR re-registration.
  ;; Each is called with a map {:kind :id :was :now} (`:was` is nil on
  ;; first-time registration). Registered by namespaces that need to
  ;; validate cross-id invariants at registration time (e.g. routing's
  ;; `:url-bound?` "only one frame owns the URL" rule per Spec 012
  ;; §Multi-frame routing — rf2-w50qm). Fires AFTER the slot is written
  ;; so the hook can inspect the final registry state.
  (atom []))

(defn add-registration-hook!
  "Register a fn called on EVERY register! call — both first-time and
  re-registration. Args: a map `{:kind kind :id id :was prev-meta :now
  new-meta}`; `:was` is nil on first-time registration. Sibling to
  `add-replacement-hook!` (which only fires on re-registration). Used
  for cross-id invariant checks like routing's `:url-bound?` exclusivity
  (per Spec 012 §Multi-frame routing)."
  [f]
  (swap! registration-hooks conj f)
  nil)

;; ---- trace-emit helper ----------------------------------------------------
;;
;; The four :registry trace sites below share the late-bind lookup of
;; `:trace/emit!` (re-frame.trace depends on re-frame.registrar, so
;; `:require` would cycle). `:trace/emit!` is published once at
;; re-frame.trace load and never withdrawn, so the resolution is sticky
;; — `late-bind/get-fn-cached` memoises it (rf2-f72pd).
;;
;; Each call site keeps its OUTERMOST `(when interop/debug-enabled? ...)`
;; gate. That gate is the load-bearing condition Closure constant-folds
;; under `:advanced + goog.DEBUG=false` (Spec 009 §Production builds),
;; and the `:rf.registry/*` operation keywords are literal args at the
;; call sites — moving the gate inside this helper would leave those
;; literals reachable from the unconditional helper call and defeat the
;; elision-probe sentinels.

(defn- emit!
  "Invoke `:trace/emit!` with the `:registry` op-type, memoising the
  late-bind resolution through `late-bind/get-fn-cached` (rf2-f72pd —
  this fn previously held its own per-key `emit!-cache` atom; that
  pattern is now generalised in `re-frame.late-bind`). Callers MUST
  wrap invocations in `(when interop/debug-enabled? ...)` so Closure
  DCE elides the call and its literal args under `:advanced +
  goog.DEBUG=false`."
  [operation tags]
  (when-let [f (late-bind/get-fn-cached :trace/emit!)]
    (f :registry operation tags)))

(defn- emit-warning!
  "Invoke `:trace/emit!` with the `:warning` op-type. Sibling to
  `emit!` (which uses `:registry`). Callers MUST wrap invocations in
  `(when interop/debug-enabled? ...)` so Closure DCE elides the call
  and its literal args under `:advanced + goog.DEBUG=false`."
  [operation tags]
  (when-let [f (late-bind/get-fn-cached :trace/emit!)]
    (f :warning operation tags)))

;; ---- warn-once caches (Spec 001 §`:doc` is dev-warned when absent,
;;                        Spec 001 §Re-registration of a different
;;                        function — collision warning) ---------------------
;;
;; `:rf.warning/missing-doc` fires at most once per `(kind, id)` pair
;; within a runtime process. `:rf.warning/registration-collision`
;; uses the same suppression discipline. Mirrors the warn-once cache
;; pattern from re-frame.spec (boundary-without-spec) and
;; re-frame.views (plain-fn-under-non-default-frame-once).
;;
;; Caches sit alongside the registry; production elision (Spec 009
;; §Production builds) elides the consult+emit branches, but the
;; atom allocation itself is process-load-time and harmless.

(defonce ^:private missing-doc-warned
  (atom #{}))

(defonce ^:private collision-warned
  (atom #{}))

(defn clear-warning-caches!
  "Reset the warn-once caches for `:rf.warning/missing-doc` and
  `:rf.warning/registration-collision`. Tests use this between cases
  so each case starts from a clean slate.

  Per Spec 001 §`:doc` is dev-warned when absent: suppression is
  per-process; the cache is process-local state, not registry state."
  []
  (reset! missing-doc-warned #{})
  (reset! collision-warned   #{})
  nil)

(defn- source-coords
  "Extract the source-coord subset of a registration metadata map.
  Returns nil when no coord slot is present (programmatic / non-macro
  path). Mirrors the `{:ns :line :file :column}` envelope per Spec 001
  §Source-coordinate capture (CLJS reference)."
  [meta]
  (let [coords (select-keys meta [:ns :line :file :column])]
    (when (seq coords) coords)))

(defn- macro-path?
  "Truthy when the metadata map carries the macro-path signature —
  source coords merged in via `source-coords/merge-coords` (Spec 001
  §Source-coordinate capture). Per Spec 001 §`:doc` is dev-warned
  obligation 4, programmatic re-registrations through internal
  helpers that bypass the public macro path are out of scope for
  `:rf.warning/missing-doc`. The `:ns` slot is the canonical signal
  the macro layer reached `register!`."
  [meta]
  (contains? meta :ns))

(defn- missing-doc?
  "True when `meta` has no usable `:doc` slot: absent, nil, or an
  empty string (per Spec 001 §`:doc` obligation 1)."
  [meta]
  (let [d (:doc meta)]
    (or (nil? d)
        (and (string? d) (= "" d)))))

(defn- maybe-emit-missing-doc!
  "Emit `:rf.warning/missing-doc` once per `(kind, id)` when `meta`
  came from the public macro path and carries no usable `:doc`.
  Per Spec 001 §`:doc` is dev-warned when absent.

  Callers MUST wrap invocations in `(when interop/debug-enabled? ...)`
  so the production bundle DCEs the consult+emit branch (Spec 009
  §Production builds). The keyword `:rf.warning/missing-doc` is a
  literal arg at the call site — moving the gate inside this helper
  would leave the literal reachable from the unconditional helper
  call and defeat the elision sentinel."
  [kind id meta]
  (when (and (macro-path? meta) (missing-doc? meta))
    (let [k [kind id]]
      (when-not (contains? @missing-doc-warned k)
        (swap! missing-doc-warned conj k)
        (emit-warning! :rf.warning/missing-doc
                       (cond-> {:kind kind :id id}
                         (source-coords meta) (assoc :source-coords
                                                     (source-coords meta))))))))

(defn- maybe-emit-collision!
  "Emit `:rf.warning/registration-collision` once per `(kind, id)`
  when a re-registration swaps in a different handler-fn (different
  in fn identity).

  Per Spec 001 §Re-registration of a different function — collision
  warning. The existing `:rf.registry/handler-replaced` trace stays
  intact (with `:different-fn?` tag); this warning surface is the
  separate dev-nudge that single-source-of-truth tools surface to
  the developer. Same suppression discipline as missing-doc — fires
  once per `(kind, id)` to keep the dev stream readable.

  Callers MUST wrap invocations in `(when interop/debug-enabled? ...)`
  for production elision (Spec 009 §Production builds)."
  [kind id previous new-meta]
  (when (not= (:handler-fn previous) (:handler-fn new-meta))
    (let [k [kind id]]
      (when-not (contains? @collision-warned k)
        (swap! collision-warned conj k)
        (emit-warning! :rf.warning/registration-collision
                       (cond-> {:kind            kind
                                :id              id
                                :previous-coords (source-coords previous)}
                         (source-coords new-meta) (assoc :source-coords
                                                         (source-coords new-meta))))))))

(defn register!
  "Register an id under kind with the given metadata. Re-registering the
  same id replaces the slot atomically (per Spec 001 §Hot-reload semantics
  guarantee 1 — non-destructive to in-flight work; the runtime sees the
  new fn on the next lookup).

  When this is a re-registration, every replacement-hook fires and a
  :rf.registry/handler-replaced trace event is emitted on EVERY
  re-registration (per Spec 001 §Hot-reload trace surface + Spec 009 —
  devtools refresh their view from this event). The trace's `:tags`
  carry `:different-fn?` so tooling can branch idempotent reloads from
  real fn-identity changes without re-emitting through a separate
  surface — `(rf2-6w7zn)`."
  [kind id metadata]
  (when-not (valid-kind? kind)
    (throw (ex-info (str "re-frame2: unknown registry kind: " kind)
                    {:kind kind :id id})))
  ;; 2-level lookup as paired `get`s rather than `(get-in ... [kind id])` —
  ;; `get-in` allocates a path vector per call (rf2-mqv4m).
  (let [previous (-> @kind->id->metadata (get kind) (get id))]
    (swap! kind->id->metadata assoc-in [kind id] metadata)
    (cond
      ;; Re-registration path — fire hooks and emit handler-replaced.
      previous
      (let [different? (not= (:handler-fn previous) (:handler-fn metadata))]
        ;; Hot-reload notifications. Hooks run isolated — listener failures
        ;; don't propagate. Hooks fire on EVERY re-registration so dependent
        ;; namespaces can clean up their caches even on idempotent reloads
        ;; (the same fn shape is fine; closure state may differ).
        (doseq [f @replacement-hooks]
          (try (f {:kind kind :id id :was previous :now metadata
                   :different-fn? different?})
               (catch #?(:clj Throwable :cljs :default) _ nil)))
        ;; Per Spec 001 §Hot-reload trace surface (rf2-6w7zn): emit
        ;; `:rf.registry/handler-replaced` on EVERY re-registration —
        ;; not only when the handler-fn changes. Kinds like `:frame`
        ;; replace the slot without rotating `:handler-fn`, so a
        ;; `different?`-gated emit dropped legitimate re-registration
        ;; events on the floor. The `:different-fn?` tag is preserved
        ;; for tools that want to suppress idempotent-reload noise on
        ;; their side.
        ;;
        ;; The `interop/debug-enabled?` gate stays OUTERMOST so
        ;; `:advanced + goog.DEBUG=false` constant-folds the entire
        ;; branch (per Spec 009 §Production builds).
        (when interop/debug-enabled?
          (emit! :rf.registry/handler-replaced
                 {:kind kind :id id :different-fn? different?})
          ;; Per Spec 001 §Re-registration of a different function —
          ;; collision warning (rf2-45kaz). Fires alongside
          ;; handler-replaced when the fn-identity actually changed,
          ;; with the same per-(kind, id) suppression discipline so
          ;; the dev stream stays readable across hot-reload churn.
          (maybe-emit-collision! kind id previous metadata)))
      ;; First-time registration — emit handler-registered per Spec 009
      ;; §:op-type vocabulary. Hot-reload tools (10x, re-frame-pair) use
      ;; this to track when fresh ids appear in the registry.
      :else
      (when interop/debug-enabled?
        (emit! :rf.registry/handler-registered {:kind kind :id id})))
    ;; Per Spec 001 §`:doc` is dev-warned when absent (rf2-45kaz). Fires
    ;; on every reg-* call whose final metadata-map carries no usable
    ;; `:doc`, once per (kind, id) within the runtime process. Production
    ;; elides via the outer `interop/debug-enabled?` gate (Spec 009
    ;; §Production builds). Fires on BOTH first-time and re-registration
    ;; — the consult+emit body is suppressed by the per-(kind, id) cache
    ;; on subsequent calls; obligation 2 says re-registering the same id
    ;; without `:doc` does NOT re-fire the warning.
    (when interop/debug-enabled?
      (maybe-emit-missing-doc! kind id metadata))
    ;; Always-on registration hooks (rf2-w50qm): fire on BOTH first-time
    ;; and re-registration so cross-id invariants (e.g. routing's
    ;; `:url-bound?` exclusivity per Spec 012 §Multi-frame routing) can
    ;; be validated at the moment of any registration. Hooks run isolated
    ;; — listener failures don't propagate so a buggy hook can't block
    ;; the registration.
    (doseq [f @registration-hooks]
      (try (f {:kind kind :id id :was previous :now metadata})
           (catch #?(:clj Throwable :cljs :default) _ nil)))
    {:was previous :now metadata}))

(defn unregister!
  "Remove a single id under kind. Hot-reload code paths use this; user code
  rarely does."
  [kind id]
  (let [previous (-> @kind->id->metadata (get kind) (get id))]
    (swap! kind->id->metadata update kind dissoc id)
    ;; Per Spec 009 §:op-type vocabulary: :rf.registry/handler-cleared
    ;; fires on explicit removal so hot-reload tools can update their
    ;; views. Only emit when something was actually present.
    (when interop/debug-enabled?
      (when previous
        (emit! :rf.registry/handler-cleared {:kind kind :id id}))))
  nil)

(defn clear-kind!
  "Remove every id under kind. Test fixtures use this to reset state."
  [kind]
  (let [previous-ids (keys (get @kind->id->metadata kind))]
    (swap! kind->id->metadata dissoc kind)
    ;; Per Spec 009 §:op-type vocabulary: :rf.registry/handler-cleared
    ;; fires for each id so consumers see consistent registry transitions.
    (when interop/debug-enabled?
      (when (seq previous-ids)
        (doseq [id previous-ids]
          (emit! :rf.registry/handler-cleared {:kind kind :id id})))))
  nil)

(defn clear-all!
  "Remove every registration for every kind. Test fixtures use this.

  Also resets the per-process warn-once caches for
  `:rf.warning/missing-doc` and `:rf.warning/registration-collision`
  so each test case starts from a clean diagnostic state — without
  this, a test that re-registers an already-warned (kind, id) pair
  would silently miss the warning under suppression."
  []
  (reset! kind->id->metadata {})
  (reset! missing-doc-warned #{})
  (reset! collision-warned   #{})
  nil)

;; ---- lookup ---------------------------------------------------------------

(defn lookup
  "Return the metadata map registered for (kind, id), or nil.

  Uses paired `get` calls rather than `(get-in ... [kind id])` — `get-in`
  allocates a path vector per call (rf2-mqv4m), and `lookup` runs per
  dispatch (event handler), per fx (handler), and per sub (handler)."
  [kind id]
  (-> @kind->id->metadata (get kind) (get id)))

(defn handler
  "Return just the handler fn from the metadata, or nil. The handler is
  stored under :handler-fn in the metadata map by the kind-specific reg-*
  macros (events) or directly (subs/fx)."
  [kind id]
  (when-let [meta (lookup kind id)]
    (:handler-fn meta)))

;; ---- query API (per Spec 002 §The public registrar query API) -------------

(defn registrations
  "All ids registered under kind, with their metadata. Tools, agents,
  storybook resolution, all use this.

  Two arities:
    (registrations kind)
      Return the full `{id metadata}` map for kind, or `{}` if the kind
      has no registrations.
    (registrations kind pred-fn)
      Same shape, filtered: only entries for which `(pred-fn meta)`
      returns truthy are included. Returns `{}` when no entry matches.
      Tools (storybook resolvers, registry browsers, agent introspection)
      use this to narrow the result to a per-namespace, per-source-file,
      or per-marker subset without re-walking the registry map themselves.

  Per Spec 001 §The public registrar query API. The predicate's
  argument is the metadata-map only — the registration id is reachable
  from the metadata's `:ns` / `:line` / `:file` / `:doc` / `:tags` /
  custom slots (id-by-keyword filtering composes via the caller's own
  `filter` over the result map's keys when needed)."
  ([kind]
   (get @kind->id->metadata kind {}))
  ([kind pred-fn]
   (into {}
         (filter (fn [[_id meta]] (pred-fn meta)))
         (get @kind->id->metadata kind {}))))

(defn handler-meta
  "Public alias for lookup. Used by tooling."
  [kind id]
  (lookup kind id))

(defn ids
  "Just the id set for a kind."
  [kind]
  (-> (registrations kind) keys set))
