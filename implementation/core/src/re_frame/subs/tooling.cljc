(ns re-frame.subs.tooling
  "Subs tooling sibling of `re-frame.subs` — carries the two tool-facing
  introspection fns (`sub-topology`, `sub-cache-snapshot`) that pair
  tools (Xray, re-frame2-pair-mcp, re-frame-10x) query but no production
  application code reads.

  These fns live here, not in `re-frame.subs`, so that `:advanced` +
  `goog.DEBUG=false` DCE their bodies wholesale: no production code path
  reaches them, and this sibling ns is loaded only when a test fixture,
  tool, or dev preload requires it. A production counter bundle never
  loads this ns.

  Surface shape, mirroring the `re-frame.trace.tooling` split:
    - CLJS consumers needing the surface call
      `re-frame.subs.tooling/<name>` directly. Production counter
      bundles never load this ns and DCE the bodies wholesale.
    - JVM consumers reach the same fns via the `re-frame.subs/<name>` /
      `rf/sub-topology` / `rf/sub-cache` convenience aliases in
      `re-frame.subs` and `re-frame.core` (gated under
      `#?(:clj ...)`). JVM has no bundle to protect; the aliases
      cost nothing.

  Per Spec 002 §The public registrar query API and Spec 006
  §Subscription topology vs subscription tracking."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.derivation.node :as node]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- static topology ------------------------------------------------------
;;
;; Per Spec 002 §The public registrar query API and Spec 006 §Subscription
;; topology vs subscription tracking. `sub-topology` is the static dependency
;; graph you can derive from registrations alone — pure data over the
;; registrar, no app-db, no reactive runtime, no per-frame cache.
;; JVM-runnable.
;;
;; Shape (per Spec 002 §The public registrar query API row): a map of
;;   sub-id → {:input-kind <kind> :inputs <inputs>
;;             :doc <str?> :ns sym :line int :file str}
;; where `:input-kind` discriminates the input producer (Spec 006
;; §Subscription input producers) and `:inputs` carries the kind-specific
;; static edge set:
;;   :db          layer-1 / direct-app-db reader  →  :inputs []
;;   :static      literal `:<-` query-vectors      →  :inputs [[:q1] [:q2 a] ...]
;;   :parametric  an `input-fn` (query-parametric) →  :inputs :parametric
;; The parametric edge set is NOT statically enumerable — it depends on the
;; concrete outer query vector, so the static surface reports the
;; `:parametric` sentinel rather than fabricating un-materialized edges.
;; Realized parametric edges per concrete query vector are runtime cache
;; state, surfaced by `sub-cache-snapshot`'s `:realized-inputs` slot.
;; The source-coord / :doc keys are included only when the registration
;; carries them.

(defn sub-topology
  "Return the static dependency graph of every registered subscription.

  Pure data over the registrar — no app-db, no per-frame cache, no
  reactive runtime. Per Spec 002 §The public registrar query API and
  Spec 006 §Subscription topology vs subscription tracking.

  Shape: `{sub-id {:input-kind <kind> :inputs <inputs>
                   :doc ... :ns ... :line ... :file ...}}`.

  - `:input-kind` discriminates the sub's input producer (Spec 006
    §Subscription input producers): `:db` (layer-1 app-db reader),
    `:static` (literal `:<-` chain), or `:parametric` (an `input-fn`
    that computes its input query-vectors from the outer query-v). It
    is always present.
  - `:inputs` is the kind-specific static edge set, always present:
      - `:db`         → `[]` (reads `app-db` directly; no upstream subs).
      - `:static`     → the vector of literal `:<-` input query-vectors
                        (`[[:items] [:filter]]`) in declaration order, so
                        downstream tools can reconstruct the chain shape
                        the body fn expects. The args are preserved
                        (`:<- [:upstream :arg]` → `[:upstream :arg]`).
      - `:parametric` → the keyword `:parametric`. The realized edge set
                        depends on the concrete outer query vector and is
                        therefore NOT statically enumerable. Realized
                        parametric edges per concrete query vector live in
                        the runtime cache — see `sub-cache-snapshot`'s
                        `:realized-inputs` slot.
  - `:doc`, `:ns`, `:line`, `:file` are present when the registration
    carried them (`:ns` / `:line` / `:file` are auto-captured by the
    `reg-sub` macro per Spec 001 §Source-coordinate capture; `:doc`
    is user-supplied via the meta-map first arg).

  Returns `{}` when no subs are registered.

  JVM-runnable. The runtime cache state (`sub-cache`) is the dynamic
  counterpart and is CLJS-only."
  []
  (let [subs-meta (registrar/registrations :sub)]
    (reduce-kv
      (fn [acc sub-id meta]
        (let [input-kind (:input-kind meta)
              ;; `:static` reports the literal `:<-` input query-vectors
              ;; (args preserved); `:parametric` reports the `:parametric`
              ;; sentinel (the realized edge set is per-concrete-query-v
              ;; runtime state, not statically enumerable); `:db` and
              ;; `:runtime-db` (single-source layer-1-shaped readers — the
              ;; latter reading the runtime-db projection) and any
              ;; registration without a discriminator report `[]`.
              inputs     (case input-kind
                           :parametric :parametric
                           :static     (vec (:input-signals meta))
                           [])
              entry  (cond-> {:input-kind (or input-kind :db)
                              :inputs     inputs}
                       (contains? meta :doc)  (assoc :doc  (:doc  meta))
                       (contains? meta :ns)   (assoc :ns   (:ns   meta))
                       (contains? meta :line) (assoc :line (:line meta))
                       (contains? meta :file) (assoc :file (:file meta)))]
          (assoc acc sub-id entry)))
      {}
      subs-meta)))

(defn sub-cache-snapshot
  "Public read-only snapshot of a frame's sub-cache, projected to a
  Tool-Pair-friendly shape:
  `{query-v {:value v :ref-count n :input-kind k :realized-inputs [...]}}`.

  CLJS-only — on the JVM the cache exists for ref-counting purposes but
  the cached reactions are not deref-able, so this fn returns `nil`. Per
  Spec 002 §The public registrar query API and Tool-Pair §How AI tools
  attach.

  Per-entry slots:

  - `:value`           — the current deref of the cached reaction (`nil`
                         when the reaction throws on deref).
  - `:ref-count`       — the live consumer ref-count for the entry.
  - `:input-kind`      — the input-producer discriminator the sub was
                         registered with (`:db` / `:static` /
                         `:parametric`), looked up from the registrar.
                         Layer-1 / direct-app-db readers are `:db`.
  - `:realized-inputs` — the REALIZED input query-vectors for THIS
                         concrete cache entry — the literal `:<-` list for
                         `:static`, and the `(input-fn query-v)` result for
                         `:parametric` (Spec 006 §Subscription input
                         producers; the EP §Tooling live cache-entry view).
                         This is the runtime counterpart to the static
                         `sub-topology` `:inputs :parametric` sentinel: it
                         surfaces the concrete edges that the static surface
                         cannot enumerate. `[]` for a `:db` reader. Stored
                         on the entry at materialization, so its topology is
                         fixed for the entry's lifetime.

  Dev-only on CLJS too — the body is gated on `interop/debug-enabled?`
  (the `goog.DEBUG` mirror) so production builds elide both the cache
  walk and the deref-and-collect machinery. Pair tools that attach in
  production explicitly opt in by toggling the gate.

  Returns `nil` for missing or destroyed frames, and `nil` in
  production builds."
  [frame-id]
  #?(:cljs
     (when interop/debug-enabled?
       (when-let [cache (:sub-cache (frame/frame frame-id))]
         (let [subs-meta (registrar/registrations :sub)]
           (reduce-kv
             (fn [acc query-v entry]
               (let [sub-id     (when (vector? query-v) (first query-v))
                     meta       (get subs-meta sub-id)
                     ;; The cache entry's `:inputs` slot holds the realized
                     ;; input query-vectors for this concrete outer query-v
                     ;; (the literal `:<-` list for `:static`, the
                     ;; `(input-fn query-v)` result for `:parametric`) — see
                     ;; `re-frame.subs/compute-and-cache!`. Layer-1 readers
                     ;; carry `[]`.
                     realized   (vec (:inputs entry))]
                 (assoc acc query-v
                        {:value           (when-let [r (:reaction entry)]
                                            (try @r (catch :default _ nil)))
                         :ref-count       (or (:ref-count entry) 0)
                         :input-kind      (or (:input-kind meta) :db)
                         :realized-inputs realized})))
             {}
             @cache))))
     :clj
     nil))

;; ---- algebra views -------------------------------------------------------
;;
;; Per [Derivations.md] and the projected Malli shapes in
;; [Spec-Schemas §`:rf/derivation-node`]. Every subscription —
;; `reg-sub` (layer-1 `:db`, static `:<-`, parametric input-fn),
;; `reg-runtime-sub`, `reg-frame-state-sub`, and each live sub-cache entry —
;; is an instance of the derivation/process algebra: a `:derivation`
;; (subscriptions and flows are derivations, never processes), whose output
;; is an ephemeral fact, evaluated on-demand, owned by its
;; subscription-cache entry.
;;
;; This is the *registrar-derived* view: it is assembled from registration
;; metadata at the surface that registers each source form, not from a
;; durable app value. It feeds the internal graph-inspection helper. These
;; functions ship NO public accessor — they live in the bundle-isolated
;; tooling sibling, consumed by Xray and conformance fixtures.
;;
;; The five fixed classifications for EVERY subscription node (the worked
;; equivalence in Derivations §Worked equivalence — subscriptions are the
;; canonical `:ephemeral` / `:on-demand` / `:subscription-cache-entry`
;; member):
;;   :kind       :derivation
;;   :storage    :ephemeral            (a cache/reaction value, not durable)
;;   :evaluation :on-demand            (evaluated when a reader subscribes)
;;   :lifecycle  :subscription-cache-entry
;;   :materialized? false              (an ephemeral output has no durable address)
;; The per-sub axes that vary are the declared INPUTS and the OUTPUT fact id.
;;
;; A subscription consumes the slice-1 vocabulary verbatim — it does NOT
;; redefine the `:rf/storage-class` / `:rf/evaluation-policy` /
;; `:rf/lifecycle` enums or the `:rf/derivation-node` shape (those are owned
;; by Spec-Schemas / Derivations).

(defn- declared-inputs
  "Project a sub's registration metadata into the algebra view's declared
  inputs (Derivations §Declared input). The input-kind discriminator drives
  the projection:

    :db          → [[:db []]]        — a layer-1 reader hands the WHOLE
                                       app-db value to the body, so the
                                       conservative declared input is the
                                       app-db projection root (Derivations
                                       §Subscription source form, the
                                       layer-1 example). NOT enumerable down
                                       to a path without a path-aware source
                                       form; correctness does not depend on
                                       the narrowing.
    :runtime-db  → [[:runtime []]]   — same, over the runtime-db partition
                                       (the framework `reg-runtime-sub`).
    :frame-state → [[:frame-state []]] — the framework-internal whole
                                       frame-state reader (reads across both
                                       partitions; reserved for internals).
    :static      → [[:sub q] ...]    — the literal `:<-` query-vectors, each
                                       lowered to a `[:sub query-vector]`
                                       declared input in declaration order.
    :parametric  → :parametric       — the realized edge set depends on the
                                       concrete outer query vector and is NOT
                                       statically enumerable (the don't-
                                       execute rule, Derivations §Static and
                                       live graphs). The static graph reports
                                       the `:parametric` marker; the live
                                       graph (sub-cache-algebra-view) reports
                                       realized `[:sub …]` edges.

  `:realized` (optional) supplies the realized input query-vectors for a
  concrete live cache entry — used by `sub-cache-algebra-view` to lower a
  parametric (or static) entry's actual edges to `[:sub query-vector]`."
  ([input-kind input-signals] (declared-inputs input-kind input-signals nil))
  ([input-kind input-signals realized]
   (cond
     ;; Live entry — realized query-vectors known; lower each to [:sub q].
     (some? realized)
     (mapv (fn [q] [:sub q]) realized)

     :else
     (case input-kind
       :db          [[:db []]]
       :runtime-db  [[:runtime []]]
       :frame-state [[:frame-state []]]
       :static      (mapv (fn [q] [:sub q]) input-signals)
       :parametric  :parametric
       ;; Legacy / pre-discriminator registration — conservative app-db read.
       [[:db []]]))))

(defn- node-base
  "The fixed-classification spine shared by every subscription algebra node
  (Derivations §The node shape): a `:derivation` whose ephemeral output is
  evaluated on-demand and owned by its subscription-cache entry. `id` is the
  node's canonical fact identity (Derivations §Fact identity) — the sub id
  for a static node, the concrete query vector for a live cache entry.

  The seven-key spine itself lives in the shared `re-frame.derivation.node`
  leaf (rf2-2mkflj); this thin wrapper supplies the subscription family's
  fixed classification."
  [id output]
  (node/node-base id output
                  {:kind          :derivation
                   :storage       :ephemeral
                   :evaluation    :on-demand
                   :lifecycle     :subscription-cache-entry
                   :materialized? false}))

(defn- with-source-coords
  "Attach the registrar's source-coordinate / schema / doc metadata to a
  node when present — the `:source` map (Derivations §Errors and
  diagnostics, via the shared `re-frame.derivation.node` leaf) and the
  `:schema` fact. Functions stay opaque tokens; we never serialize the
  executable body."
  [node meta]
  (cond-> (node/attach-source node meta)
    (contains? meta :schema) (assoc :schema (:schema meta))
    (contains? meta :doc)    (assoc :doc (:doc meta))
    ;; The body fn is an opaque token — surfaced under :derive so a tool
    ;; can show the function source/identity without it being serialized.
    (contains? meta :handler-fn) (assoc :derive (:handler-fn meta))))

(defn- static-node-for
  "Build the static algebra view for one registered sub from its registrar
  metadata. Pure data over the registrar — the same registration-known
  facts `sub-topology` reports, lowered into the `:rf/derivation-node`
  shape. `:source-form {:kind :reg-sub :id <id>}` records which source form
  it lowered from; a parametric sub additionally carries `:input-producer`
  (the opaque input-fn token)."
  [sub-id meta]
  (let [input-kind (or (:input-kind meta) :db)
        inputs     (declared-inputs input-kind (:input-signals meta))]
    (-> (node-base sub-id [:fact sub-id])
        (assoc :source-form {:kind :reg-sub :id sub-id}
               :inputs      inputs)
        (cond-> (= :parametric input-kind)
          (assoc :input-producer (:input-fn meta)))
        (with-source-coords meta))))

(defn sub-algebra-view
  "Return the STATIC derivation/process algebra view of every registered
  subscription ([Derivations.md], [Spec-Schemas §`:rf/derivation-node`]).

  Pure data over the registrar — no app-db, no per-frame cache, no reactive
  runtime. The algebra-view companion to `sub-topology`: where `sub-topology`
  reports the raw `{:input-kind :inputs …}` projection, this lowers every
  subscription into the normalized `:rf/derivation-node` shape the
  derivation/process algebra names, so a tool can show subscriptions, flows,
  resources, route facts, and machine selectors as ONE family.

  Shape: `{sub-id <derivation-node>}` where each node carries:

  - `:id`          — the sub id (its canonical fact identity when static).
  - `:kind`        — `:derivation` (subscriptions are derivations, never
                     processes — Derivations §Derivation).
  - `:source-form` — `{:kind :reg-sub :id <sub-id>}`.
  - `:inputs`      — the declared inputs (Derivations §Declared input):
                     `[[:db []]]` for a layer-1 `:db` reader, `[[:runtime []]]`
                     for a framework `reg-runtime-sub`, `[[:frame-state []]]`
                     for a `reg-frame-state-sub`, the literal `[:sub q]`
                     edges for a static `:<-` sub, or the `:parametric`
                     marker for an input-fn sub (whose realized edges are
                     per-concrete-query-v live state — the don't-execute
                     rule).
  - `:input-producer` — the opaque input-fn token, present only for a
                     `:parametric` node (Derivations §Static and live graphs).
  - `:output`      — `[:fact <sub-id>]` (an ephemeral fact, no durable address).
  - `:storage`     — `:ephemeral`.
  - `:evaluation`  — `:on-demand`.
  - `:lifecycle`   — `:subscription-cache-entry`.
  - `:materialized?` — `false`.
  - `:derive`      — the opaque body-fn token (never serialized).
  - `:source` / `:schema` / `:doc` — present when the registration carried
                     them (source coords auto-captured by `reg-sub`).

  Zero-arity returns the map for every registered sub (`{}` when none). The
  one-arity form returns the single node for `sub-id`, or `nil` if it is not
  registered. JVM-runnable — the registration metadata is partition-agnostic.

  Ships NO public accessor: this lives in the bundle-isolated tooling
  sibling and is consumed by Xray + the conformance fixtures; the public
  name is deferred until a third consumer needs it."
  ([]
   (let [subs-meta (registrar/registrations :sub)]
     (reduce-kv
       (fn [acc sub-id meta]
         (assoc acc sub-id (static-node-for sub-id meta)))
       {}
       subs-meta)))
  ([sub-id]
   (when-let [meta (registrar/lookup :sub sub-id)]
     (static-node-for sub-id meta))))

(defn sub-cache-algebra-view
  "Return the LIVE derivation/process algebra view of a frame's sub-cache —
  one `:rf/derivation-node` per concrete cached entry ([Derivations.md]
  §Static and live graphs).

  The live counterpart to `sub-algebra-view`: where the static view reports
  the `:parametric` marker for an input-fn sub, the live view reports the
  REALIZED `[:sub query-vector]` input edges for each concrete entry (the
  edges the static graph cannot enumerate), keyed by the entry's query
  vector — its canonical fact identity when concrete (Derivations §Fact
  identity).

  Per-entry node:

  - `:id`          — the concrete query vector (the live fact identity).
  - `:kind`        — `:derivation`.
  - `:source-form` — `{:kind :reg-sub :id <sub-id>}`.
  - `:inputs`      — the realized `[[:sub q] …]` edges for THIS entry (the
                     literal `:<-` list for a static sub, the
                     `(input-fn query-v)` result for a parametric sub, `[]`
                     for a layer-1 / runtime-db / frame-state reader). Stored
                     on the entry at materialization, so the topology is
                     fixed for the entry's lifetime.
  - `:output`      — `[:fact <query-v>]`.
  - `:storage` / `:evaluation` / `:lifecycle` / `:materialized?` — the same
                     fixed classifications as the static node.
  - `:value`       — the current deref of the cached reaction (a value
                     summary; redaction is the graph-inspection helper's job
                     per Derivations §Redaction metadata — `nil` when the
                     reaction throws on deref).
  - `:ref-count`   — the entry's live consumer ref-count, the lifecycle
                     evidence the `:subscription-cache-entry` owner is kept
                     alive by its readers.
  - `:input-kind`  — the registered input-producer discriminator, for tools
                     that want the source-form kind alongside the realized
                     edges.

  CLJS-only and dev-only — gated on `interop/debug-enabled?` (the
  `goog.DEBUG` mirror) so production builds DCE the cache walk. Returns
  `nil` for a missing/destroyed frame, and `nil` on the JVM + in production
  (the cached reactions are not deref-able on the JVM)."
  [frame-id]
  #?(:cljs
     (when interop/debug-enabled?
       (when-let [cache (:sub-cache (frame/frame frame-id))]
         (let [subs-meta (registrar/registrations :sub)]
           (reduce-kv
             (fn [acc query-v entry]
               (let [sub-id     (when (vector? query-v) (first query-v))
                     meta       (get subs-meta sub-id)
                     input-kind (or (:input-kind meta) :db)
                     ;; The cache entry's `:inputs` slot holds the realized
                     ;; input query-vectors for this concrete outer query-v
                     ;; (the literal `:<-` list for `:static`, the
                     ;; `(input-fn query-v)` result for `:parametric`,
                     ;; `[]` for a single-source reader) — see
                     ;; `re-frame.subs/compute-and-cache!`.
                     realized   (vec (:inputs entry))
                     node       (-> (node-base query-v [:fact query-v])
                                    (assoc :source-form {:kind :reg-sub :id sub-id}
                                           :inputs      (declared-inputs input-kind
                                                                         (:input-signals meta)
                                                                         realized)
                                           :input-kind  input-kind
                                           :ref-count   (or (:ref-count entry) 0)
                                           :value       (when-let [r (:reaction entry)]
                                                          (try @r (catch :default _ nil))))
                                    (with-source-coords meta))]
                 (assoc acc query-v node)))
             {}
             @cache))))
     :clj
     nil))

;; ---- bundle-isolation sentinel ------------------------------------------
;;
;; `implementation/scripts/check-bundle-isolation.cjs`
;; greps the counter bundle for this exact string. The string lives
;; ONLY in this file's source body — no other namespace, no docstring,
;; no test fixture references it — so its presence in the production
;; counter bundle proves that the tooling sibling's body got pulled
;; in (most likely via a stray `:require` from a core/* ns). The
;; sentinel survives `:advanced` because string literals are not
;; renamed; it sits outside any `interop/debug-enabled?` gate so DCE
;; cannot drop the literal independently of the surrounding ns body.

(defonce ^:private bundle-isolation-sentinel
  "rf.subs.tooling/sentinel:rf2-bmzq0-2026-05-16:do-not-rename")
