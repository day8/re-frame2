(ns re-frame2-pair-mcp.tools.handler-meta
  "Tools: handler-meta + list-handlers (the `list-<things>` shape per
  NAMING.md).

  Direct introspection on the registrar — `where is `:user/login`
  registered?` answered without a wide-authority `eval-cljs` round-trip.

  ## Frame-targeting — the EP-0023 forward direction

  A frame's inspectable registration set is its RESOLVED IMAGE GENERATION: the
  same `(kind, id)` can resolve DIFFERENTLY per frame (two frames running
  different images each resolve their OWN descriptor). Both tools therefore
  accept an OPTIONAL `:frame` arg — a frame id (keyword) addressing the
  OPERATING frame's universe. ABSENT ⇒ the byte-identical process-global
  registrar path. PRESENT ⇒ the read routes through the per-frame runtime fns
  (`frame-registrar-describe` / `frame-registrar-list`), which consume the
  PUBLIC facade reads `(rf/handler-meta {:frame f :kind k :id id})` /
  `(rf/registrations {:frame f :kind k})` and resolve the
  `(kind, id)` set through that frame's OWN sealed image generation —
  surfacing the resolved descriptor's `:rf.provenance/ns` + inline/image +
  `:standard` facts (plus a normalized `:rf.image/coordinate` rollup naming
  WHICH source won) the process-global reads can't. `list-subscriptions` is the
  per-frame exemplar these now mirror.

  ## handler-meta

  Returns `(rf/handler-meta kind id)` for the requested
  `(kind, id)` pair — the registration-metadata map carrying the flat
  top-level source-coord keys `:ns` / `:line` / `:column` / `:file`
  (per Spec-Schemas `:rf/source-coord-meta` — merged flat onto
  `:rf/registration-metadata`), plus `:doc`, `:tags`, the registrar
  kind, and (per Spec 001 §The public registrar query API) whatever
  custom slots the `reg-*` macro emitted. The wire-pipeline decorates
  every map that carries a usable source-coord shape with an
  `:rf.source/uri` string — so the AI host renders an immediate
  jump-to-editor link off the handler-meta response.

  Supported kinds: `event`, `sub`, `fx`, `cofx`, `interceptor`, `view`,
  `frame`, `route`, `flow`, `head`, `error-projector`, `resource`,
  `mutation`, `resource-scope`, `machine` — the closed v1 registrar set
  (per Spec 001 §Registry model; the three resources-artefact kinds are
  EP-0016; `interceptor` is EP-0022). App-db schemas are NOT
  a registrar kind; their metadata lives in the schemas
  artefact's per-frame side-table, surfaced via `rf/app-schemas` /
  `rf/app-schema-meta-at`. The fourteen registrar kinds map directly to
  `rf/handler-meta`; `machine` routes
  through the dedicated `rf/machine-meta` surface (Spec 005 §Querying machines —
  machines are registered as `:event` handlers carrying
  `:rf/machine? true` with their spec in the `:rf/machine` slot, and
  `machine-meta` unwraps that slot).

  Returns `{:ok? false :reason :not-registered :kind k :id id}` when
  no slot is found (so the agent gets a structured signal — same
  shape `re-frame2-pair.runtime/registrar-describe` already uses).

  ## list-handlers

  Returns the full set of registered ids for a kind — the discovery
  surface. Agents call `list-handlers {kind \"event\"}` first to find
  out what's registered, then `handler-meta` to drill in.

  For every registrar kind (`event` / `sub` / `fx` / `cofx` /
  `interceptor` / `view` / `frame` / `route` / `flow` / `head` /
  `error-projector` / `resource` / `mutation` / `resource-scope`) the
  list comes from
  `re-frame2-pair.runtime/registrar-list`. For `machine` the list
  comes from `re-frame.core/machines` — every event handler flagged
  `:rf/machine? true`.

  ## Why not `eval-cljs`?

  `eval-cljs` is wide-authority by design (per `eval-cljs.cljs`'s
  launch-flag gate). The re-frame2-pair contract is: structured tools for the
  common case, eval-cljs for the unknown unknowns. `handler-meta` /
  `list-handlers` cover the most-frequent introspection asks
  (\"where's X defined\", \"what's registered\") with a narrow surface
  the agent can rely on across runtimes and editor-config postures."
  (:require [clojure.string :as str]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.result-envelope :as renv]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

;; ---------------------------------------------------------------------------
;; Kind normalisation.
;;
;; The MCP arg comes in as a JS string ("event", "sub", …). We coerce
;; to the runtime keyword the registrar uses. `machine` is the one
;; logical kind that doesn't map 1:1 to a registrar kind — it routes
;; through `rf/machine-meta` instead.
;; ---------------------------------------------------------------------------

(def ^:private registrar-kinds
  "Kinds that map directly to the registrar's `kind->id->metadata`
  table — the closed v1 registrar set (per Spec 001 §Registry model),
  including the three resources-artefact kinds `:resource` / `:mutation`
  / `:resource-scope` (Spec 001 §Registry model — `reg-resource` /
  `reg-mutation` / `reg-resource-scope`; EP-0016). They
  enumerate / describe through the same kind-agnostic
  `re-frame2-pair.runtime/registrar-list` + `registrar-describe`
  accessors as every other kind — so `list-handlers {kind \"resource-scope\"}`
  enumerates the named scope resolvers and `handler-meta {kind
  \"resource-scope\" id …}` surfaces a resolver's declared `:inputs` +
  `:whole-db?` cost (the EP-0016 disposition-2 inspectability promise).
  `registrar-describe`'s `strip-fns` keeps the nested handler
  fns (`:request` / `:tags` / `:invalidates` / `:populates` / `:resolve`)
  off the EDN wire so the serializable structure rides cleanly.

  `:interceptor` (EP-0022 / Spec 001 §Interceptors) is the registered-
  interceptor registrar kind — `reg-interceptor` stores an interceptor
  DESCRIPTOR (`{:before}` / `{:after}` / `{:before :after}` / `{:factory}`)
  under it, keyed by a qualified keyword id. Event/frame `:interceptors`
  chains carry REFERENCES (bare keyword / `[id arg]`) into this kind; the
  runtime resolves the refs to executable interceptor values at chain
  assembly. It enumerates / describes through the same kind-agnostic
  `registrar-list` + `registrar-describe` accessors as every other kind —
  so `list-handlers {kind \"interceptor\"}` enumerates the registered ids
  and `handler-meta {kind \"interceptor\" id …}` surfaces the descriptor
  (the `:rf/interceptor-descriptor` slot the registrar retains for tooling).
  This is what makes registered interceptors inspectable via the pair-MCP
  handler-meta tool.

  App-db schemas are intentionally absent — they are NOT
  a registrar kind; their metadata lives in the schemas artefact's
  per-frame side-table. `machine` is intentionally absent here too —
  it routes through `rf/machine-meta` (which inspects `:event`-kind
  metadata for the `:rf/machine?` flag) — but is in `supported-kinds`
  below."
  #{:event :sub :fx :cofx :interceptor :view :frame :route :flow :head
    :error-projector :resource :mutation :resource-scope})

(def ^:private supported-kinds
  "The full set of kinds the tool accepts. The fourteen registrar kinds
  above plus the virtual `:machine` kind."
  (conj registrar-kinds :machine))

(defn- parse-kind
  "Coerce the `:kind` MCP arg to a keyword from `supported-kinds`, or
  return `nil` if the value is missing / unrecognised. Accepts both
  bare names (`\"event\"`) and EDN-shaped strings (`\":event\"`) — the
  same accommodation the rest of the re-frame2-pair-mcp args surface offers."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (let [trimmed (str/triml s)
          name    (if (str/starts-with? trimmed ":")
                    (subs trimmed 1)
                    trimmed)
          k       (keyword name)]
      (when (contains? supported-kinds k)
        k))))

(defn- parse-id
  "Coerce the `:id` MCP arg to a CLJS value. The id is supplied as an
  EDN-encoded string (`\":user/login\"`, `\"my.app/handler\"`,
  `\"[:rf/composite \\\"x\\\"]\"`) so callers can pass any registered
  id shape, including composite vectors used for sub-graph keys.

  Returns `[:ok parsed]` on success or `[:err reason]` on a read
  failure (`:missing-id` / `:invalid-id-edn`) — the shared
  `args/read-edn-arg` readability core. A bare
  keyword-shaped string (leading `:`) round-trips through `read-string`;
  a plain word like `\"foo\"` reads as a symbol — the caller's
  `:not-registered` lookup then surfaces that it isn't a registered id."
  [s]
  (args/read-edn-arg s :missing-id :invalid-id-edn))

(defn- kinds-hint
  "Comma-joined list of the supported kinds — used in error envelopes
  so a fat-fingered :kind gets a corrective hint."
  []
  (str/join ", " (sort (map name supported-kinds))))

;; ---------------------------------------------------------------------------
;; Frame targeting — the EP-0023 forward direction.
;;
;; The OPTIONAL `:frame` arg addresses the OPERATING frame's resolved image
;; generation. ABSENT ⇒ nil ⇒ the byte-identical default-registrar path.
;; PRESENT ⇒ the frame-id keyword threaded into the per-frame runtime fns. A
;; blank/missing value parses to nil (absence = default path — `:frame` is
;; optional); a non-blank value coerces via the colon-tolerant
;; `args/->frame-keyword` (the same coercion every other frame arg uses).
;; ---------------------------------------------------------------------------

(defn- parse-frame
  "Coerce the OPTIONAL `:frame` MCP arg to a frame-id keyword (colon-tolerant
  via `args/->frame-keyword`) or nil when absent/blank (the default path)."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (args/->frame-keyword s)))

;; ---------------------------------------------------------------------------
;; Tool — handler-meta.
;;
;; Eval-form composition: for the fourteen registrar kinds we route through
;; `re-frame2-pair.runtime/registrar-describe` (already published; carries
;; the `:not-registered` envelope on miss). For `:machine` we wrap
;; `re-frame.core/machine-meta` directly — the runtime ns has no
;; machine-aware wrapper, so the wrapping lives on the tool's caller
;; side rather than in the runtime preload.
;; ---------------------------------------------------------------------------

(defn- registrar-form
  "Build the eval form for a registrar `(kind, id)` lookup.

  DEFAULT (`frame` nil): route through
  `re-frame2-pair.runtime/registrar-describe` — the process-global path, which
  carries the `:not-registered` envelope on a miss and the
  `:handler-fn-hash` augmentation.

  EXPLICIT FRAME (`frame` set): route through
  `re-frame2-pair.runtime/frame-registrar-describe`, which resolves the
  `(kind, id)` through the frame's OWN sealed image generation (the PUBLIC
  `(rf/handler-meta {:frame f …})` read) and surfaces the resolved
  descriptor's `:rf.provenance/ns` + inline/image + `:standard` facts plus
  the `:rf.image/coordinate` rollup. Same `:not-registered` / `handler-fn`
  hygiene as `registrar-describe`."
  [frame kind id]
  (if (some? frame)
    (ef/emit (ef/rt-call 'frame-registrar-describe frame kind id))
    (ef/emit (ef/rt-call 'registrar-describe kind id))))

(defn- machine-form
  "Build the eval form that wraps `re-frame.core/machine-meta` with the
  same envelope shape `registrar-describe` returns — either the meta
  map or a structured `:not-registered` map. Keeps the tool's response
  shape uniform across kinds.

  `dissoc :handler-fn` for the same reason `registrar-describe`
  drops it — a raw Function ref is unreadable on the EDN wire and would
  make the tool envelope misreport :unexpected-shape. The machine
  surface doesn't always carry one, but the dissoc is idempotent and
  cheap and keeps the response shape EDN-clean by construction across
  both kinds.

  The composed form is one expression so the eval is a single
  round-trip — composition is the same idiom every other tool uses."
  [id]
  (let [id-edn (pr-str id)]
    (str "(if-let [m (re-frame.core/machine-meta " id-edn ")]"
         "  (dissoc m :handler-fn)"
         "  {:ok? false :reason :not-registered :kind :machine :id " id-edn "})")))

(defn handler-meta-tool [conn args]
  (let [build-id   (wire/arg-build conn args)
        kind-str   (wire/arg args :kind)
        id-str     (wire/arg args :id)
        frame-str  (wire/arg args :frame)
        kind       (parse-kind kind-str)
        [id-tag id-val]       (parse-id id-str)
        frame-val             (parse-frame frame-str)]
    (cond
      (nil? kind)
      (js/Promise.resolve
        (wire/err-text {:ok?     false
                        :reason  :invalid-kind
                        :kind    kind-str
                        :hint    (str "kind must be one of: " (kinds-hint))}))

      (= :err id-tag)
      (js/Promise.resolve
        (wire/err-text {:ok?    false
                        :reason id-val
                        :id     id-str
                        :hint   (str "id must be an EDN-readable keyword, e.g. \":user/login\". "
                                     "For composite-key subs, pass the vector form.")}))

      ;; Machines are NOT registrar kinds and are absent from the
      ;; image generation resolver (Spec 005 — derived from :event handlers); a
      ;; frame-targeted machine lookup has no resolution. Refuse loudly rather
      ;; than resolve to a confusing :not-registered.
      (and (some? frame-val) (= :machine kind))
      (js/Promise.resolve
        (wire/err-text {:ok?    false
                        :reason :frame-unsupported-for-machine
                        :frame  frame-val
                        :kind   kind
                        :hint   (str "machines are not in the image generation resolver "
                                     "(they derive from :event handlers); drop :frame to "
                                     "query machines, or use a registrar kind for a "
                                     "frame-targeted read.")}))

      :else
      ;; Wrap the runtime form in the typed result codec so
      ;; an unserializable meta map (a `#object[Function]` slot that
      ;; slips past the runtime's `dissoc :handler-fn`, a `#js {…}`
      ;; literal) rides back as a STRUCTURED `:unserializable` envelope
      ;; — never a stringly `:unexpected-shape` re-parse, and never a
      ;; meta map smuggled as a STRING in
      ;; `:value`. `envelope->result`'s `on-value` receives the genuine
      ;; PARSED meta map (or nil), so the three logical shapes (hit /
      ;; miss / unserializable) each resolve cleanly.
      (let [inner-form (if (= :machine kind)
                         (machine-form id-val)
                         (registrar-form frame-val kind id-val))
            wrapped    (renv/wrap-form inner-form)
            ;; Stamp the resolved frame onto the result ONLY when one was
            ;; requested — the process-global default response stays
            ;; byte-identical (no spurious :frame key for the common case).
            stamp-frame (fn [m] (cond-> m
                                  (some? frame-val) (assoc :frame frame-val)))]
        (probe/eval-after-runtime!
          conn build-id wrapped :handler-meta-failed
          (fn [v]
            (let [result
                  (renv/envelope->result
                    v
                    (fn [meta-map]
                      ;; Two hit/miss shapes resolve into one agents can
                      ;; rely on:
                      ;;   - hit (map with no :reason): merge :ok? true
                      ;;     + the requested kind/id.
                      ;;   - miss (`{:ok? false :reason :not-registered}`):
                      ;;     pass through, stamped with kind/id.
                      ;;   - genuine non-map (should not happen against a
                      ;;     healthy runtime): surface :unexpected-shape.
                      (stamp-frame
                        (cond
                          ;; rf2-acckgr: a genuine shape defect (should
                          ;; not happen against a healthy runtime) —
                          ;; NOT a legitimate structured miss like
                          ;; :not-registered below. Stamp it with the
                          ;; codec's own error meta so `renv/error?`
                          ;; routes it through `wire/err-text` instead
                          ;; of silently riding back as ok-text.
                          (not (map? meta-map))
                          (renv/mark-codec-error
                            {:ok? false :reason :unexpected-shape
                             :kind kind :id id-val :value meta-map})

                          (false? (:ok? meta-map))
                          (assoc meta-map :kind kind :id id-val)

                          :else
                          (assoc meta-map :ok? true :kind kind :id id-val)))))
                  ;; An :unserializable / :eval-error envelope from the
                  ;; codec carries no kind/id; stamp them so the agent
                  ;; sees what it asked for.
                  result (if (renv/error? result)
                           (stamp-frame (assoc result :kind kind :id id-val))
                           result)]
              (if (renv/error? result)
                (wire/err-text result)
                (wire/ok-text result)))))))))

;; ---------------------------------------------------------------------------
;; Tool — list-handlers.
;; ---------------------------------------------------------------------------

(defn- list-form
  "Build the eval form returning the sorted id vector for a kind.

  DEFAULT (`frame` nil): the fourteen registrar kinds route through
  `re-frame2-pair.runtime/registrar-list`; `:machine` wraps
  `re-frame.core/machines` (Spec 005 §Querying machines — every event handler
  with `:rf/machine? true`).

  EXPLICIT FRAME (`frame` set): route through
  `re-frame2-pair.runtime/frame-registrar-list`, which enumerates only the
  ids the frame's OWN image generation carries for the kind (the PUBLIC
  `(rf/registrations {:frame f :kind k})` read, keys). `:machine` is rejected before
  reaching here when a frame is supplied (machines are not in the resolver)."
  [frame kind]
  (cond
    (= :machine kind) "(vec (sort (re-frame.core/machines)))"
    (some? frame)     (ef/emit (ef/rt-call 'frame-registrar-list frame kind))
    :else             (ef/emit (ef/rt-call 'registrar-list kind))))

(defn list-handlers-tool [conn args]
  (let [build-id  (wire/arg-build conn args)
        kind-str  (wire/arg args :kind)
        frame-str (wire/arg args :frame)
        kind      (parse-kind kind-str)
        frame-val (parse-frame frame-str)]
    (cond
      (nil? kind)
      (js/Promise.resolve
        (wire/err-text {:ok?    false
                        :reason :invalid-kind
                        :kind   kind-str
                        :hint   (str "kind must be one of: " (kinds-hint))}))

      ;; Machines are not in the image generation resolver.
      (and (some? frame-val) (= :machine kind))
      (js/Promise.resolve
        (wire/err-text {:ok?    false
                        :reason :frame-unsupported-for-machine
                        :frame  frame-val
                        :kind   kind
                        :hint   (str "machines are not in the image generation resolver; "
                                     "drop :frame to list machines, or use a registrar "
                                     "kind for a frame-targeted enumeration.")}))

      :else
      (let [form (list-form frame-val kind)]
        (probe/eval-after-runtime!
          conn build-id form :list-handlers-failed
          (fn [v]
            (wire/ok-text
              (cond-> {:ok?   true
                       :kind  kind
                       :ids   (vec v)
                       :count (count v)}
                (some? frame-val) (assoc :frame frame-val)))))))))
