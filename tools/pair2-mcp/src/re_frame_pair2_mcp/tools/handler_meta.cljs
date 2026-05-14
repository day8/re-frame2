(ns re-frame-pair2-mcp.tools.handler-meta
  "Tools: handler-meta + registry-list (rf2-pctf8).

  Direct introspection on the registrar — `where is `:user/login`
  registered?` answered without a wide-authority `eval-cljs` round-trip.

  ## handler-meta

  Returns `(rf/handler-meta kind id)` for the requested
  `(kind, id)` pair — the registration-metadata map carrying the flat
  top-level source-coord keys `:ns` / `:line` / `:column` / `:file`
  (per Spec-Schemas `:rf/source-coord-meta` — merged flat onto
  `:rf/registration-metadata`), plus `:doc`, `:tags`, the registrar
  kind, and (per Spec 001 §The public registrar query API) whatever
  custom slots the `reg-*` macro emitted. The wire-pipeline (post-
  rf2-cibp8) decorates every map that carries a usable source-coord
  shape with an `:rf.source/uri` string — so the AI host renders an
  immediate jump-to-editor link off the handler-meta response.

  Supported kinds: `event`, `sub`, `fx`, `cofx`, `view`, `frame`,
  `route`, `flow`, `head`, `error-projector`, `machine` — the closed
  v1 registrar set (per Spec 001 §Registry model) minus `:app-schema`
  (intentionally empty registrar slot — its metadata lives in the
  schemas artefact's per-frame side-table, surfaced via
  `rf/app-schemas`). The ten registrar kinds map directly to
  `rf/handler-meta`; `machine` routes through the dedicated
  `rf/machine-meta` surface (Spec 005 §Querying machines — machines
  are registered as `:event` handlers carrying `:rf/machine? true`
  with their spec in the `:rf/machine` slot, and `machine-meta`
  unwraps that slot).

  Returns `{:ok? false :reason :not-registered :kind k :id id}` when
  no slot is found (so the agent gets a structured signal — same
  shape `re-frame-pair2.runtime/registrar-describe` already uses).

  ## registry-list

  Returns the full set of registered ids for a kind — the discovery
  surface. Agents call `registry-list {kind \"event\"}` first to find
  out what's registered, then `handler-meta` to drill in.

  For every registrar kind (`event` / `sub` / `fx` / `cofx` / `view`
  / `frame` / `route` / `flow` / `head` / `error-projector`) the list
  comes from `re-frame-pair2.runtime/registrar-list`. For `machine`
  the list comes from `re-frame.core/machines` — every event handler
  flagged `:rf/machine? true`.

  ## Why not `eval-cljs`?

  `eval-cljs` is wide-authority by design (per `eval-cljs.cljs`'s
  launch-flag gate). The pair2 contract is: structured tools for the
  common case, eval-cljs for the unknown unknowns. `handler-meta` /
  `registry-list` cover the most-frequent introspection asks
  (\"where's X defined\", \"what's registered\") with a narrow surface
  the agent can rely on across runtimes and editor-config postures."
  (:require [clojure.string :as str]
            [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.eval-form :as ef]
            [re-frame-pair2-mcp.tools.wire :as wire]
            [re-frame-pair2-mcp.tools.probe :as probe]))

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
  table — the closed v1 registrar set (per Spec 001 §Registry model)
  minus `:app-schema` (intentionally empty registrar slot — schema
  metadata lives in the schemas artefact's per-frame side-table).
  `machine` is intentionally absent here too — it routes through
  `rf/machine-meta` (which inspects `:event`-kind metadata for the
  `:rf/machine?` flag) — but is in `supported-kinds` below."
  #{:event :sub :fx :cofx :view :frame :route :flow :head :error-projector})

(def ^:private supported-kinds
  "The full set of kinds the tool accepts. The ten registrar kinds
  above plus the virtual `:machine` kind."
  (conj registrar-kinds :machine))

(defn- parse-kind
  "Coerce the `:kind` MCP arg to a keyword from `supported-kinds`, or
  return `nil` if the value is missing / unrecognised. Accepts both
  bare names (`\"event\"`) and EDN-shaped strings (`\":event\"`) — the
  same accommodation the rest of the pair2-mcp args surface offers."
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

  Returns `[:ok parsed]` on success or `[:err msg]` on a read failure.
  A bare keyword-shaped string (leading `:`) round-trips through
  `read-string`; a plain word like `\"foo\"` reads as a symbol — we
  reject that to surface the contract clearly (registered ids are
  keywords, not symbols)."
  [s]
  (cond
    (or (nil? s) (and (string? s) (str/blank? s)))
    [:err :missing-id]

    :else
    (let [trimmed (str/trim s)
          parsed  (try (cljs.reader/read-string trimmed)
                       (catch :default _ ::reader-fail))]
      (cond
        (= ::reader-fail parsed) [:err :invalid-id-edn]
        :else                    [:ok parsed]))))

(defn- kinds-hint
  "Comma-joined list of the supported kinds — used in error envelopes
  so a fat-fingered :kind gets a corrective hint."
  []
  (str/join ", " (sort (map name supported-kinds))))

;; ---------------------------------------------------------------------------
;; Tool — handler-meta.
;;
;; Eval-form composition: for the six registrar kinds we route through
;; `re-frame-pair2.runtime/registrar-describe` (already published; carries
;; the `:not-registered` envelope on miss). For `:machine` we wrap
;; `re-frame.core/machine-meta` directly — the runtime ns has no
;; machine-aware wrapper today, and adding one is out of scope (lives in
;; rf2-pctf8's caller side, not the runtime preload).
;; ---------------------------------------------------------------------------

(defn- registrar-form
  "Build the eval form that calls `re-frame-pair2.runtime/registrar-describe`
  for a registrar kind."
  [kind id]
  (ef/emit (ef/rt-call 'registrar-describe kind id)))

(defn- machine-form
  "Build the eval form that wraps `re-frame.core/machine-meta` with the
  same envelope shape `registrar-describe` returns — either the meta
  map or a structured `:not-registered` map. Keeps the tool's response
  shape uniform across kinds.

  The composed form is one expression so the eval is a single
  round-trip — composition is the same idiom every other tool uses."
  [id]
  (let [id-edn (pr-str id)]
    (str "(if-let [m (re-frame.core/machine-meta " id-edn ")]"
         "  m"
         "  {:ok? false :reason :not-registered :kind :machine :id " id-edn "})")))

(defn handler-meta-tool [conn args]
  (let [build-id (wire/arg-build args)
        kind-str (wire/arg args :kind)
        id-str   (wire/arg args :id)
        kind     (parse-kind kind-str)
        [id-tag id-val] (parse-id id-str)]
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

      :else
      (let [form (if (= :machine kind)
                   (machine-form id-val)
                   (registrar-form kind id-val))]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [v]
                     ;; Three envelope shapes resolve into one shape
                     ;; agents can rely on:
                     ;;   - hit (map with no :reason): merge :ok? true
                     ;;     and the requested kind/id so agents don't
                     ;;     have to remember what they asked for.
                     ;;   - miss (`{:ok? false :reason :not-registered}`):
                     ;;     pass through unchanged.
                     ;;   - eval returned non-map: surface as
                     ;;     :unexpected-shape — should never happen
                     ;;     against a healthy runtime.
                     (wire/ok-text
                       (cond
                         (not (map? v))
                         {:ok? false :reason :unexpected-shape
                          :kind kind :id id-val :value v}

                         (false? (:ok? v))
                         (assoc v :kind kind :id id-val)

                         :else
                         (assoc v :ok? true :kind kind :id id-val)))))
            (.catch (fn [err] (probe/err->result :handler-meta-failed err))))))))

;; ---------------------------------------------------------------------------
;; Tool — registry-list.
;; ---------------------------------------------------------------------------

(defn- list-form
  "Build the eval form returning the sorted id vector for a kind. For
  the six registrar kinds we route through
  `re-frame-pair2.runtime/registrar-list`; for `:machine` we wrap
  `re-frame.core/machines` (Spec 005 §Querying machines — every event
  handler with `:rf/machine? true`)."
  [kind]
  (if (= :machine kind)
    "(vec (sort (re-frame.core/machines)))"
    (ef/emit (ef/rt-call 'registrar-list kind))))

(defn registry-list-tool [conn args]
  (let [build-id (wire/arg-build args)
        kind-str (wire/arg args :kind)
        kind     (parse-kind kind-str)]
    (cond
      (nil? kind)
      (js/Promise.resolve
        (wire/err-text {:ok?    false
                        :reason :invalid-kind
                        :kind   kind-str
                        :hint   (str "kind must be one of: " (kinds-hint))}))

      :else
      (let [form (list-form kind)]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [v]
                     (wire/ok-text
                       {:ok?   true
                        :kind  kind
                        :ids   (vec v)
                        :count (count v)})))
            (.catch (fn [err] (probe/err->result :registry-list-failed err))))))))
