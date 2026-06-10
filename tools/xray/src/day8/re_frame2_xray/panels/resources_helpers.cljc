(ns day8.re-frame2-xray.panels.resources-helpers
  "Pure-data projection + the `:rf.resource/*` trace-family declaration
  for Xray's Resources tab (Spec 016 §Xray and AI tooling).

  ## Why a `.cljc` helpers split

  Same contract as every other panel's `*_helpers.cljc`: the projection
  algebra (registry rows, live-instance rows, work-ledger rows, the
  route/resource graph, the lifecycle timeline, the invalidation graph,
  the cache-growth view, and the two lints) is PURE data — no substrate,
  no Reagent/UIx/Helix, no DOM. Keeping it here means the algebra runs
  under the JVM unit-test target and the CLJS view (`resources.cljs`)
  stays a thin hiccup renderer over these projections.

  ## Bundle isolation + decoupling from the resources artefact

  Xray does NOT `:require` anything under `implementation/resources/`.
  Resources is a POST-V1 OPTIONAL artefact (Spec 016 §Implementation
  status) and is NOT a hard dep of Xray. The panel reads everything
  decoupled, exactly the way the Routing tab reads the route slice and
  the Machine Inspector reads machine snapshots:

    - the STATIC resource registry via `(rf/registrations :resource)`
      (process-global registrar) — no require;
    - the LIVE per-frame instance table from the runtime-db partition
      slice at `[:rf.runtime/resources :entries]` — no require;
    - the LIVE per-frame work ledger from `[:rf.runtime/work-ledger]`;
    - the trace stream (`:rf.resource/*` op rows) from the trace buffer.

  The reserved key paths below are duplicated as small literal constants
  rather than read from `re-frame.resources.state` — duplicating three
  reserved keywords is the bundle-isolation-safe price of not adding a
  require edge from a tool into an optional artefact. They are the
  reserved runtime-db keys fixed in [Conventions §Reserved runtime-db
  keys]; a drift would be caught by the panel's CLJS wiring test.

  ## PRIVACY (Spec 016 §Xray and AI tooling — load-bearing)

  Tool surfaces PREFER SUMMARIES over raw values. Params, scopes, AND
  data get the SAME privacy + size elision — scopes carry user ids,
  tenant ids, locale, and impersonation markers, so a scope is exactly
  as sensitive as data. The panel NEVER renders a raw param/scope/data
  value; it renders a `summarize`d shape (type + bounded size + a small
  redaction-aware preview). Off-box egress (the MCP/AI tool accessors)
  routes the same values through the framework `egress-value` walker in
  `runtime.cljs`; this ns supplies the IN-PANEL summary that is safe to
  render to a human operator and bounded so a huge data blob never
  floods the panel.

  Xray MUST NOT become an owner by observing (Spec 016 §Active owners
  and causes): every projection here is a PURE READ. Nothing in this ns
  dispatches `:rf.resource/ensure`, attaches an owner, refetches, or
  extends GC — inspection has zero side effects on resource liveness."
  #?(:cljs (:require [clojure.string :as str])
     :clj  (:require [clojure.string :as str])))

;; ---------------------------------------------------------------------------
;; Reserved runtime-db paths (decoupled literals — see ns docstring).
;; ---------------------------------------------------------------------------

(def resources-key
  "Reserved runtime-db key for the resource cache subtree
  (`:rf.runtime/resources`). Per Spec 016 §Cache home and write
  authority. Duplicated literal — Xray does not require the resources
  artefact (bundle isolation)."
  :rf.runtime/resources)

(def work-ledger-key
  "Reserved runtime-db key for the frame work ledger subtree
  (`:rf.runtime/work-ledger`). Per Spec 016 §Frame work ledger."
  :rf.runtime/work-ledger)

(def entries-rel-path
  "Runtime-db-relative path to the cache entries map. Per Spec 016."
  [resources-key :entries])

(def tag-index-rel-path
  "Runtime-db-relative path to the reverse tag index."
  [resources-key :tag-index])

(def owner-index-rel-path
  "Runtime-db-relative path to the reverse owner index."
  [resources-key :owner-index])

;; ---------------------------------------------------------------------------
;; The `:rf.resource/*` trace family (Spec 016 §Xray and AI tooling).
;; ---------------------------------------------------------------------------
;;
;; The runtime EMITS these `:rf.event`-op-type rows (the emit seams
;; already exist in `re-frame.resources.events` — `:rf.resource/deduped`,
;; `:fetch-started`, `:invalidated`, `:owner-released`, `:removed`,
;; `:succeeded`/`:failed`/`:refresh-failed`, `:stale-suppressed`,
;; `:gc-fired`/`:gc-skipped`, `:work-abort-requested`, …). Xray DEFINES
;; the family (its closed operation set, per-op colour class, and a
;; human label) so the Resources tab + the Trace tab can colour, group,
;; and filter resource rows without re-deriving the vocabulary.
;;
;; Each op carries, where applicable: frame, work id, scope, resource
;; key/id, params summary, generation, request id, owner, cause, status
;; before/after, work status, resource/invalidated tags, freshness
;; timestamps, and redaction/size markers (Spec 016).

(def trace-family-prefix
  "The reserved trace-family namespace prefix for resource trace rows.
  An operation keyword whose namespace is `\"rf.resource\"` belongs to
  the resource family. Per [Conventions §Reserved namespaces]."
  "rf.resource")

(def trace-ops
  "The closed `:rf.resource/*` trace-family operation set with the
  per-op semantic class + a short human label, ordered roughly by
  lifecycle. The `:class` keys the panel + Trace-tab colour mapping
  (`op-class->token`): `:lifecycle` (neutral progress), `:success`,
  `:failure`, `:dedupe` (a join/cache-hit, no new work), `:invalidation`,
  `:gc`, `:suppression` (stale reply suppressed — a correctness event),
  and `:hydration`. Per Spec 016 §Xray and AI tooling (the trace-family
  enumeration)."
  {:rf.resource/registered           {:class :lifecycle    :label "registered"}
   :rf.resource/ensure               {:class :lifecycle    :label "ensure"}
   :rf.resource/owner-attached       {:class :lifecycle    :label "owner attached"}
   :rf.resource/cache-hit            {:class :dedupe       :label "cache hit"}
   :rf.resource/deduped              {:class :dedupe       :label "deduped"}
   :rf.resource/fetch-started        {:class :lifecycle    :label "fetch started"}
   :rf.resource/work-started         {:class :lifecycle    :label "work started"}
   :rf.resource/work-abort-requested {:class :lifecycle    :label "abort requested"}
   :rf.resource/work-completed       {:class :success      :label "work completed"}
   :rf.resource/work-suppressed      {:class :suppression  :label "work suppressed"}
   :rf.resource/succeeded            {:class :success      :label "succeeded"}
   :rf.resource/failed               {:class :failure      :label "failed"}
   :rf.resource/refresh-failed       {:class :failure      :label "refresh failed"}
   :rf.resource/invalidated          {:class :invalidation :label "invalidated"}
   :rf.resource/refetch-decision     {:class :lifecycle    :label "refetch decision"}
   :rf.resource/owner-released       {:class :lifecycle    :label "owner released"}
   :rf.resource/gc-scheduled         {:class :gc           :label "gc scheduled"}
   :rf.resource/gc-fired             {:class :gc           :label "gc fired"}
   :rf.resource/gc-skipped           {:class :gc           :label "gc skipped"}
   :rf.resource/removed              {:class :lifecycle    :label "removed"}
   :rf.resource/stale-suppressed     {:class :suppression  :label "stale suppressed"}
   :rf.resource/hydrated             {:class :hydration    :label "hydrated"}
   :rf.resource/hydrate-refetch      {:class :hydration    :label "hydrate refetch"}})

(defn resource-trace-op?
  "True iff `operation` (a trace event's `:operation`) is a member of the
  `:rf.resource/*` trace family — either an explicitly-enumerated op in
  `trace-ops` OR any keyword in the reserved `rf.resource` namespace (so
  a future op the runtime adds is still recognised as family-member for
  colouring/filtering before this enum is extended)."
  [operation]
  (boolean
    (and (keyword? operation)
         (or (contains? trace-ops operation)
             (= trace-family-prefix (namespace operation))))))

(defn op-class
  "The semantic class keyword for a resource trace `operation` (one of
  `:lifecycle` / `:success` / `:failure` / `:dedupe` / `:invalidation` /
  `:gc` / `:suppression` / `:hydration`), or `:lifecycle` for an
  in-namespace op not yet enumerated. nil for a non-family op."
  [operation]
  (when (resource-trace-op? operation)
    (get-in trace-ops [operation :class] :lifecycle)))

(defn op-label
  "A short human label for a resource trace `operation`. Falls back to
  the bare op name for an in-namespace op not yet enumerated."
  [operation]
  (when (keyword? operation)
    (get-in trace-ops [operation :label] (name operation))))

;; ---------------------------------------------------------------------------
;; PRIVACY — summarization with size + redaction elision (Spec 016).
;; ---------------------------------------------------------------------------

(def ^:private default-preview-budget
  "Max characters of a value's `pr-str` rendered as the in-panel
  preview before the `:elided?` marker replaces the tail. Bounded so a
  large data blob never floods the panel; the off-box (AI/MCP) path uses
  the framework `egress-value` size walker instead."
  120)

(def redacted-sentinel
  "The framework sensitive-redaction sentinel. A value already redacted
  upstream (the runtime emits `:rf/redacted` for `:sensitive?` slots via
  `elide-wire-value`) renders as `[redacted]`, not as a raw preview."
  :rf/redacted)

(def large-elided-sentinel
  "The framework size-elision sentinel — a `:large?` slot the runtime
  elided on the wire."
  :rf.size/large-elided)

(defn- value-type-tag
  "A short, render-safe type tag for a value — used as the summary head
  so the operator sees the SHAPE without the raw contents."
  [v]
  (cond
    (nil? v)        "nil"
    (map? v)        "map"
    (set? v)        "set"
    (vector? v)     "vector"
    (sequential? v) "seq"
    (string? v)     "string"
    (keyword? v)    "keyword"
    (number? v)     "number"
    (boolean? v)    "boolean"
    :else           "value"))

(defn- value-size
  "A bounded count for a value — element count for a collection, char
  count for a string, else nil (scalars have no size)."
  [v]
  (cond
    (string? v) (count v)
    (coll? v)   (count v)
    :else       nil))

(defn summarize
  "PRIVACY-PRESERVING summary of a param / scope / data value for
  IN-PANEL rendering (Spec 016 §Xray and AI tooling — \"prefer summaries
  over raw values\"). Params, scopes, and data ALL flow through this same
  fn — a scope is exactly as sensitive as data (it carries user/tenant/
  locale/impersonation ids).

  Returns a render-safe map:

      {:type      \"map\"            ; the value's shape
       :size      2                  ; element / char count (nil for scalars)
       :preview   \"{:slug \\\"welc…\"  ; bounded pr-str, tail elided past budget
       :elided?   true              ; preview was truncated
       :redacted? false             ; value is the :rf/redacted sentinel
       :large?    false}            ; value is the :rf.size/large-elided sentinel

  A value already redacted/elided upstream (the runtime emits the
  framework sentinels for `:sensitive?` / `:large?` slots) keeps its
  sentinel status and renders no raw preview. NEVER returns the raw
  value — the caller renders the summary map, not the value."
  ([v] (summarize v nil))
  ([v {:keys [budget] :or {budget default-preview-budget}}]
   (let [redacted? (= v redacted-sentinel)
         large?    (= v large-elided-sentinel)
         pr        (when-not (or redacted? large?) (pr-str v))
         elided?   (and pr (> (count pr) budget))
         preview   (cond
                     redacted? "[redacted]"
                     large?    "[large — elided]"
                     elided?   (str (subs pr 0 budget) "…")
                     :else     pr)]
     {:type      (value-type-tag v)
      :size      (value-size v)
      :preview   preview
      :elided?   (boolean elided?)
      :redacted? redacted?
      :large?    large?})))

(defn scoped-key-summary
  "Summarize a scoped resource key `[scope resource-id params]` for a
  table row. The scope and params each go through `summarize` (PRIVACY:
  scope carries PII, params identify the remote read), while the
  resource-id is a plain keyword (a registry name, never PII). Returns
  `{:scope <summary> :resource-id <kw> :params <summary>}`. A malformed
  key (not a 3-vector) summarizes the whole key as a single value."
  [scoped-key]
  (if (and (vector? scoped-key) (= 3 (count scoped-key)))
    (let [[scope rid params] scoped-key]
      {:scope       (summarize scope)
       :resource-id rid
       :params      (summarize params)})
    {:scope       (summarize scoped-key)
     :resource-id nil
     :params      (summarize nil)}))

;; ---------------------------------------------------------------------------
;; Static resource registry projection (Spec 016 §Xray and AI tooling).
;; ---------------------------------------------------------------------------

(defn- describe-scope-policy
  "A human description of a resource's `:scope` POLICY (not a resolved
  scope value — the registry carries the policy). `:rf.scope/global` is
  the audit-surface flag (Spec 016 §scope audit surface)."
  [scope]
  (cond
    (= scope :rf.scope/global)      {:policy :global      :label "global (explicit claim)" :global? true}
    (= scope :rf.scope/from-caller) {:policy :from-caller :label "from caller"             :global? false}
    (fn? scope)                     {:policy :resolver    :label "resolver (fn)"           :global? false}
    (keyword? scope)                {:policy :resolver    :label (str "resolver " scope)   :global? false}
    (some? scope)                   {:policy :resolver    :label "resolver (data)"         :global? false}
    :else                           {:policy :missing     :label "MISSING"                 :global? false}))

(defn- request-summary
  "A one-line, render-safe summary of a resource's `:request` (a fn that
  lowers params → a Spec 014 managed-HTTP args map). The fn body is
  opaque, so the summary names the transport + that the request is
  fn-derived; the live wire details surface per-instance via the work
  ledger's `:transport`."
  [spec]
  (let [transport (or (:transport spec) :rf.http/managed)]
    {:transport transport
     :fn?       (boolean (:request spec))
     :label     (str (name transport)
                     (when (:request spec) " · request fn"))}))

(defn registry-row
  "Project ONE static registry entry `[resource-id meta]` (a
  `(rf/registrations :resource)` row, whose `:rf/resource` slot carries
  the registration spec) into a render-safe registry row. Per Spec 016
  §Xray and AI tooling (the static resource registry):

      {:resource-id    :article/by-slug
       :doc            \"Article detail by slug.\"
       :params-schema  <summary>   ; schemas can be large — summarized
       :data-schema    <summary>
       :request        {:transport :rf.http/managed :fn? true :label …}
       :scope          {:policy :global :label … :global? true}
       :stale-after-ms 60000
       :gc-after-ms    300000
       :tags?          true        ; a :tags producer fn is declared
       :sensitive?     false
       :large?         false
       :source-coord   {:file … :line …}}

  `declaring-routes` is injected by the composite (it is a cross-source
  join over the route registry — see `attach-declaring-routes`)."
  [[resource-id meta]]
  (let [spec (:rf/resource meta)]
    {:resource-id    resource-id
     :doc            (or (:doc spec) (:doc meta))
     :params-schema  (summarize (:params-schema spec))
     :data-schema    (summarize (:data-schema spec))
     :request        (request-summary spec)
     :scope          (describe-scope-policy (:scope spec))
     :stale-after-ms (:stale-after-ms spec)
     :gc-after-ms    (:gc-after-ms spec)
     :tags?          (boolean (:tags spec))
     :sensitive?     (boolean (:sensitive? spec))
     :large?         (boolean (:large? spec))
     :source-coord   (when (or (:file meta) (:line meta))
                       {:file (:file meta) :line (:line meta)})
     :declaring-routes []}))

(defn- route-resource-ids
  "The set of resource-ids a route declares via its `:resources`
  metadata vector (each entry is a `{:resource <id> …}` map)."
  [route-meta]
  (into #{}
        (keep :resource)
        (:resources route-meta)))

(defn attach-declaring-routes
  "Cross-join the registry rows against the route registry: for each
  registry row, attach the vector of route-ids whose `:resources`
  metadata declares that resource (Spec 016 §Route integration — Xray
  can display which routes own a resource). `routes-map` is
  `(rf/registrations :route)` (`{<route-id> <meta>}`); pure, no require
  on the routing artefact."
  [rows routes-map]
  (let [rid->routes (reduce-kv
                      (fn [acc route-id route-meta]
                        (reduce (fn [acc rid]
                                  (update acc rid (fnil conj []) route-id))
                                acc (route-resource-ids route-meta)))
                      {} (or routes-map {}))]
    (mapv (fn [row]
            (assoc row :declaring-routes
                   (vec (sort-by str (get rid->routes (:resource-id row) [])))))
          rows)))

(defn project-registry
  "Project the full static resource registry into sorted render-safe
  rows with declaring-routes joined. `registrations` is
  `(rf/registrations :resource)`; `routes-map` is `(rf/registrations
  :route)` (or nil). Per Spec 016 §Xray and AI tooling."
  [registrations routes-map]
  (-> (mapv registry-row (or registrations {}))
      (attach-declaring-routes routes-map)
      (->> (sort-by (comp str :resource-id)) vec)))

;; ---------------------------------------------------------------------------
;; Live instance table projection (Spec 016 §Xray and AI tooling).
;; ---------------------------------------------------------------------------

(defn- derive-stale?
  "Pure freshness derivation (Spec 016 §Status semantics — `:stale?` is a
  derived value, never a stored fact): an entry is stale iff it was
  invalidated, or `now-ms` has passed its `:stale-at`. nil `now-ms` or a
  nil `:stale-at` falls back to the invalidation flag alone."
  [entry now-ms]
  (boolean
    (or (some? (:invalidated-at entry))
        (and now-ms (:stale-at entry) (>= now-ms (:stale-at entry))))))

(defn- gc-eligible?
  "An entry is GC-eligible (Spec 016 §Invalidation / §Stale and GC) when
  it has NO active owners — an inactive entry is left stale / eligible
  for `:gc-after-ms` cleanup. Owners keep a resource alive."
  [entry]
  (empty? (:active-owners entry)))

(defn instance-row
  "Project ONE live cache entry `[scoped-key entry]` into a render-safe
  instance-table row (Spec 016 §Xray and AI tooling — the live
  resource-instance table). PRIVACY: scope + params + data summarized.

      {:scoped-key      <opaque-id-for-react-key>
       :scope           <summary>     ; PII — summarized
       :resource-id     :article/by-slug
       :params          <summary>     ; identity — summarized
       :status          :loaded
       :stale?          false
       :has-data?       true
       :data            <summary>     ; payload — summarized
       :error           <summary>     ; failure envelope — summarized
       :refresh-error   <summary>
       :loaded-at       …  :stale-at … :invalidated-at …
       :generation      4
       :attempt         2
       :request-id      <id>
       :current-work    <work-id>
       :active-owners   [<owner> …]   ; owners are tokens, not PII
       :owner-count     1
       :tags            [<tag> …]
       :gc-eligible?    false}"
  [[scoped-key entry] now-ms]
  (let [{:keys [scope resource-id params]} (scoped-key-summary scoped-key)]
    {:scoped-key     scoped-key
     :scope          scope
     :resource-id    (or resource-id (:resource/id entry))
     :params         params
     :status         (:status entry)
     :stale?         (derive-stale? entry now-ms)
     :has-data?      (some? (:data entry))
     :data           (summarize (:data entry))
     :error          (when (:error entry) (summarize (:error entry)))
     :refresh-error  (when (:refresh-error entry) (summarize (:refresh-error entry)))
     :loaded-at      (:loaded-at entry)
     :stale-at       (:stale-at entry)
     :invalidated-at (:invalidated-at entry)
     :generation     (:generation entry)
     :attempt        (:attempt entry)
     :request-id     (:request-id entry)
     :current-work   (:current-work entry)
     :active-owners  (vec (:active-owners entry))
     :owner-count    (count (:active-owners entry))
     :tags           (vec (:tags entry))
     :gc-eligible?   (gc-eligible? entry)}))

(defn project-instances
  "Project a frame's live resource entries map `{<scoped-key> <entry>}`
  into sorted render-safe instance rows. Sorted by resource-id then
  generation (descending) so the most-recent attempt leads. `now-ms` is
  the freshness clock (the panel passes the current ms; tests pass a
  fixed clock). Per Spec 016 §Xray and AI tooling."
  ([entries] (project-instances entries nil))
  ([entries now-ms]
   (->> (or entries {})
        (mapv #(instance-row % now-ms))
        (sort-by (juxt (comp str :resource-id) (comp - (fnil identity 0) :generation)))
        vec)))

;; ---------------------------------------------------------------------------
;; Live work-ledger table projection (Spec 016 §Frame work ledger —
;; the 2026-06-10 EP amendment: per-frame work-ledger table joined to
;; resource entries).
;; ---------------------------------------------------------------------------

(def terminal-work-statuses
  "Terminal work-ledger statuses (Spec 016 §Ledger row retention). A
  terminal row is pruned on the linked entry's next successful
  transition; the panel marks them so the operator distinguishes live
  work from a recent-races tail."
  #{:completed :failed :timed-out :suppressed :cancelled})

(defn work-row
  "Project ONE work-ledger record `[work-id record]` into a render-safe
  row (Spec 016 §Frame work ledger / 2026-06-10 EP amendment). Raw host
  handles (AbortControllers, timeout handles, promises) live OUTSIDE
  durable frame-state in side tables and are STRUCTURALLY inaccessible to
  this projection — the ledger record carries only serializable facts:

      {:work-id      <id>
       :kind         :resource
       :resource-key <scoped-key-summary>  ; PRIVACY: scope/params summarized
       :resource-id  :article/by-slug
       :generation   4
       :status       :running
       :terminal?    false
       :owners       [<owner> …]
       :causes       [<cause> …]            ; causes are summarized (may carry data)
       :stale-key    <work-id>             ; one identity per record (= :work/id)
       :cancellable? true
       :deadline-at  1780752005100
       :attempt      2
       :transport    :rf.http/managed
       :outcome      <summary>}"
  [[work-id record]]
  (let [rkey (or (:resource/key record) (:resource-key record))
        {:keys [scope resource-id params]} (scoped-key-summary rkey)]
    {:work-id      work-id
     :kind         (or (:work/kind record) (:kind record))
     :resource-key {:scope scope :resource-id resource-id :params params}
     :resource-id  resource-id
     :generation   (:generation record)
     :status       (:status record)
     :terminal?    (contains? terminal-work-statuses (:status record))
     :owners       (vec (:owners record))
     :causes       (mapv summarize (:causes record))
     :stale-key    work-id
     :cancellable? (boolean (:cancellable? record))
     :deadline-at  (or (:deadline-at record) (:deadline record))
     :attempt      (or (:attempt record) (:retry-attempt record))
     :transport    (:transport record)
     :outcome      (when (contains? record :outcome) (summarize (:outcome record)))}))

(defn project-work-ledger
  "Project a frame's work-ledger map `{<work-id> <record>}` into sorted
  render-safe rows — non-terminal (live) work first, then the terminal
  recent-races tail; within each group sorted by generation descending.
  Per Spec 016 §Frame work ledger."
  [ledger]
  (->> (or ledger {})
       (mapv work-row)
       (sort-by (juxt :terminal? (comp - (fnil identity 0) :generation)))
       vec))

;; ---------------------------------------------------------------------------
;; Route / resource graph (Spec 016 §Route integration / §Xray).
;; ---------------------------------------------------------------------------

(defn- route-resource-node
  "Project ONE `:resources` entry on a route into a graph node. The
  `:params` / `:scope` resolvers are fns (opaque), so the node records
  THAT they are declared, the blocking flag (SSR wait point), the
  keep-previous flag, the local `:id` + `:after` dependency, and any
  `:when` guard — the structure Xray draws without parsing handlers."
  [entry]
  {:resource        (:resource entry)
   :blocking?       (boolean (:blocking? entry))
   :keep-previous?  (boolean (:keep-previous? entry))
   :local-id        (:id entry)
   :after           (vec (:after entry))
   :when?           (boolean (:when entry))
   :params-fn?      (boolean (:params entry))
   :scope-resolver? (boolean (:scope entry))})

(defn project-route-graph
  "Project the route registry into the route/resource graph (Spec 016
  §Route integration): for every route declaring `:resources`, a node
  carrying its declared resources, blocking-vs-non-blocking split, SSR
  wait points (the blocking nodes), and any `:after` dependency waterfall.
  `routes-map` is `(rf/registrations :route)`.

      [{:route-id     :route/article
        :path         \"/articles/:slug\"
        :resources    [{:resource :article/by-slug :blocking? true …} …]
        :blocking     [:article/by-slug]   ; SSR wait points
        :non-blocking [:comments/list]
        :ssr-wait?    true}                 ; route has ≥1 blocking resource
       …]

  Routes WITHOUT `:resources` are omitted (the graph is the resource
  view of the route table). Per Spec 016 §Route integration."
  [routes-map]
  (->> (or routes-map {})
       (keep (fn [[route-id route-meta]]
               (let [resources (:resources route-meta)]
                 (when (seq resources)
                   (let [nodes        (mapv route-resource-node resources)
                         blocking     (into [] (comp (filter :blocking?) (map :resource)) nodes)
                         non-blocking (into [] (comp (remove :blocking?) (map :resource)) nodes)]
                     {:route-id     route-id
                      :path         (:path route-meta)
                      :resources    nodes
                      :blocking     blocking
                      :non-blocking non-blocking
                      :ssr-wait?    (boolean (seq blocking))})))))
       (sort-by (comp str :route-id))
       vec))

;; ---------------------------------------------------------------------------
;; Lifecycle timeline + invalidation graph + cache-growth (from trace rows).
;; ---------------------------------------------------------------------------

(defn- trace-op
  "The `:operation` of a trace event, tolerating either the canonical
  `:operation` key or a bare `:op`."
  [ev]
  (or (:operation ev) (:op ev)))

(defn- trace-tags [ev] (or (:tags ev) {}))

(defn lifecycle-timeline
  "Project the resource trace rows in a trace buffer into a lifecycle
  TIMELINE — an ordered, render-safe row per `:rf.resource/*` event
  (Spec 016 §Xray and AI tooling — the lifecycle timeline). Each row:

      {:id          <trace-id>      ; stable per-process trace event id
       :operation   :rf.resource/fetch-started
       :label       \"fetch started\"
       :class       :lifecycle
       :resource-id :article/by-slug
       :resource-key <scoped-key-summary>  ; PRIVACY-summarized
       :generation  4
       :work-id     <id>
       :owner       <owner>
       :cause       <summary>        ; cause may carry data — summarized
       :status      {:before … :after …}}

  Pure over the trace vector; preserves buffer order (oldest-first). The
  panel renders this as the per-resource lifecycle strip; filtering by
  resource-id happens in the composite. Per Spec 016."
  [trace-buffer]
  (->> (or trace-buffer [])
       (filter #(resource-trace-op? (trace-op %)))
       (mapv (fn [ev]
               (let [op   (trace-op ev)
                     tags (trace-tags ev)
                     rkey (or (:resource-key tags) (:resource/key tags))]
                 {:id           (:id ev)
                  :operation    op
                  :label        (op-label op)
                  :class        (op-class op)
                  :resource-id  (or (:resource-id tags)
                                    (when (vector? rkey) (second rkey)))
                  :resource-key (when rkey (scoped-key-summary rkey))
                  :generation   (:generation tags)
                  :work-id      (or (:work-id tags) (:work/id tags))
                  :owner        (:owner tags)
                  :cause        (when (contains? tags :cause) (summarize (:cause tags)))
                  :status       {:before (:status-before tags)
                                 :after  (or (:status-after tags) (:status tags))}})))))

(defn invalidation-graph
  "Project the `:rf.resource/invalidated` trace rows into the
  invalidation / mutation graph (Spec 016 §Invalidation / §Xray — the
  invalidation/mutation graph). One row per invalidation event:

      {:id        <trace-id>
       :scope     <summary>          ; PRIVACY — scope summarized
       :tags      [<tag> …]          ; the invalidated tags (identity, not PII)
       :cause     <summary>          ; e.g. [:mutation :article/save id] — summarized
       :matched   [<scoped-key-summary> …]  ; entries hit
       :match-count N
       :refetched N}                 ; active-owner entries refetched

  Distinguishes a broad-tag storm (high `:match-count`) without flooding;
  a zero-match invalidation surfaces `:match-count 0` so 'no match in
  this scope' is visible. Pure over the trace vector. Per Spec 016."
  [trace-buffer]
  (->> (or trace-buffer [])
       (filter #(= :rf.resource/invalidated (trace-op %)))
       (mapv (fn [ev]
               (let [tags    (trace-tags ev)
                     matched (or (:matched tags) [])]
                 {:id          (:id ev)
                  :scope       (summarize (:scope tags))
                  :tags        (vec (:tags tags))
                  :cause       (when (contains? tags :cause) (summarize (:cause tags)))
                  :matched     (mapv scoped-key-summary matched)
                  :match-count (count matched)
                  :refetched   (or (:refetched tags) 0)})))))

(defn cache-growth
  "Project the live instance rows + the work ledger into the cache-growth
  view (Spec 016 §Paginated and previous data / §Xray — the cache-growth
  view). Aggregates per-resource-id:

      {:by-resource [{:resource-id :articles/list
                      :entry-count 12       ; cached entries (e.g. list pages)
                      :owned-count 1        ; entries with ≥1 active owner
                      :gc-eligible 11}      ; inactive → GC-eligible
                     …]
       :total-entries 14
       :total-gc-eligible 12
       :live-work 2}                        ; non-terminal ledger rows

  Surfaces unbounded list-param growth (many entries, few owners) so the
  operator sees a cache that is growing without owners pinning it. Pure
  over the projected instance + work rows. Per Spec 016."
  [instance-rows work-rows]
  (let [by-resource
        (->> instance-rows
             (group-by :resource-id)
             (mapv (fn [[rid rows]]
                     {:resource-id rid
                      :entry-count (count rows)
                      :owned-count (count (filter (comp pos? :owner-count) rows))
                      :gc-eligible (count (filter :gc-eligible? rows))}))
             (sort-by (comp - :entry-count))
             vec)]
    {:by-resource       by-resource
     :total-entries     (count instance-rows)
     :total-gc-eligible (count (filter :gc-eligible? instance-rows))
     :live-work         (count (remove :terminal? work-rows))}))

;; ---------------------------------------------------------------------------
;; Lints (Spec 016 §Xray and AI tooling — the two lints + the audit list).
;; ---------------------------------------------------------------------------

(defn global-scope-audit
  "The STANDING scope audit surface (Spec 016 §Xray scope diagnostics):
  enumerate every `:rf.scope/global` resource — the structural
  security-review list that replaces the old `/me` heuristic. Returns the
  registry rows whose scope policy is the explicit-global claim. Pure
  over the projected registry rows."
  [registry-rows]
  (->> registry-rows
       (filter (comp :global? :scope))
       vec))

(def ^:private session-ish-tokens
  "Substrings that make an explicit-global request look session-dependent
  — the downgraded-to-defense-in-depth heuristic (Spec 016: warn about
  SUSPICIOUS explicit-global, not compensate for a missing scope)."
  ["/me" "/current-user" "/current_user" "current-session" "/profile"
   "/account" "impersonat"])

(defn suspicious-global-warnings
  "Defense-in-depth lint (Spec 016 §Xray scope diagnostics): from the
  `:rf.scope/global` audit list, flag any whose registered doc /
  resource-id LOOKS session-dependent (`/me`, `/current-user`, profile/
  account/impersonation hints). NOT the boundary (a missing scope is a
  loud runtime error now); a hint the operator should re-examine an
  explicit-global claim. Returns `[{:resource-id … :hint \"…\"}]`."
  [registry-rows]
  (->> (global-scope-audit registry-rows)
       (keep (fn [row]
               (let [hay (str/lower-case
                           (str (:resource-id row) " " (:doc row)))]
                 (when (some #(str/includes? hay %) session-ish-tokens)
                   {:resource-id (:resource-id row)
                    :hint        (str "explicit :rf.scope/global on a resource that "
                                      "looks session-dependent — confirm the data is "
                                      "identical for every user/tenant.")}))))
       vec))

(defn scope-mismatch-lint
  "Scope-mismatch lint (Spec 016 §Xray — two lints): a cache ENTRY exists
  for resource R + params P under scope A while a LIVE subscription reads
  the same R + P under a DIFFERENT scope B and gets `:idle` (or a
  never-resolving `:loading`). The runtime tripwire for the cases the
  fail-closed scope rules don't catch.

  `instance-rows` is the projected live instances; `sub-reads` is a
  vector of `{:resource-id R :params <raw-params> :scope <raw-scope>}`
  observed live subscription reads. Returns mismatch pairs:

      [{:resource-id R
        :sub-scope <summary> :sub-params <summary>
        :entry-scope <summary>}]

  PRIVACY: the surfaced scopes/params are summarized. Matching is on
  resource-id + params identity (a sub reading params P that an entry
  caches under a DIFFERENT scope). Pure."
  [instance-rows sub-reads]
  (let [;; index entries by [resource-id params-preview] → set of scope previews
        entry-index
        (reduce (fn [acc row]
                  (update acc [(:resource-id row) (get-in row [:params :preview])]
                          (fnil conj #{}) (get-in row [:scope :preview])))
                {} instance-rows)]
    (->> (or sub-reads [])
         (keep (fn [{:keys [resource-id params scope]}]
                 (let [params-sum (summarize params)
                       scope-sum  (summarize scope)
                       k          [resource-id (:preview params-sum)]
                       entry-scopes (get entry-index k)]
                   ;; mismatch iff an entry exists for this R+P but under
                   ;; NO scope matching the sub's scope.
                   (when (and (seq entry-scopes)
                              (not (contains? entry-scopes (:preview scope-sum))))
                     {:resource-id resource-id
                      :sub-scope   scope-sum
                      :sub-params  params-sum
                      :entry-scope (summarize (first entry-scopes))}))))
         vec)))

(defn orphaned-owner-lint
  "Orphaned-owner lint (Spec 016 §Xray — two lints / §Release authority):
  an app-minted `[:lease …]` (or other app-kind) owner pinning an entry
  with no observed `:rf.resource/owner-released` for that owner in the
  trace. Route / machine / ssr owners are framework-released (route on
  nav supersession, machine on actor destroy, ssr on request teardown),
  so only APP-kind owners are linted here.

  `instance-rows` supplies the live `:active-owners`; `trace-buffer`
  supplies the observed `:rf.resource/owner-released` events. Returns
  `[{:owner <owner> :resource-id R}]` for each app-kind owner still
  pinning an entry with no release seen. Pure."
  [instance-rows trace-buffer]
  (let [released (->> (or trace-buffer [])
                      (filter #(= :rf.resource/owner-released (trace-op %)))
                      (keep #(:owner (trace-tags %)))
                      (into #{}))
        app-kind? (fn [owner]
                    (and (vector? owner)
                         (not (contains? #{:route :machine :ssr} (first owner)))))]
    (->> instance-rows
         (mapcat (fn [row]
                   (for [owner (:active-owners row)
                         :when (and (app-kind? owner)
                                    (not (contains? released owner)))]
                     {:owner owner :resource-id (:resource-id row)})))
         distinct
         vec)))

;; ---------------------------------------------------------------------------
;; Tool-accessor filtering (Spec 016 §Xray and AI tooling — the
;; list-resources / list-resource-instances / get-resource-state /
;; get-resource-history / list-resource-invalidations filter axes).
;; ---------------------------------------------------------------------------

(defn- scoped-key-matches?
  "Does a scoped key `[scope rid params]` match the supplied filter
  axes? `scope` / `resource-id` / `params` are compared against the RAW
  key parts (the accessor receives the raw runtime-db key); a nil filter
  axis is a wildcard."
  [scoped-key {:keys [scope resource-id params]}]
  (let [[ks krid kparams] (if (and (vector? scoped-key) (= 3 (count scoped-key)))
                            scoped-key
                            [nil nil nil])]
    (and (or (nil? scope)       (= scope ks))
         (or (nil? resource-id) (= resource-id krid))
         (or (nil? params)      (= params kparams)))))

(defn filter-instance-rows
  "Filter projected instance rows by the tool-accessor axes (Spec 016):
  `:resource-id`, `:status`, `:stale?`, `:tag`, `:owner`, `:request-id`.
  Each nil axis is a wildcard. (Scope/params/frame are applied upstream
  against the raw key; this filters the already-projected rows.) Pure."
  [rows {:keys [resource-id status stale? tag owner request-id]}]
  (->> rows
       (filter (fn [row]
                 (and (or (nil? resource-id) (= resource-id (:resource-id row)))
                      (or (nil? status)      (= status (:status row)))
                      (or (nil? stale?)      (= (boolean stale?) (:stale? row)))
                      (or (nil? tag)         (some #{tag} (:tags row)))
                      (or (nil? owner)       (some #{owner} (:active-owners row)))
                      (or (nil? request-id)  (= request-id (:request-id row))))))
       vec))

(defn select-raw-entries
  "Select the RAW runtime-db entries (`{<scoped-key> <entry>}`) matching
  the scope / resource-id / params key axes — the upstream filter the
  accessors apply BEFORE projection/elision (so an accessor can scope its
  read by the cache key before summarizing). Pure; the accessor still
  routes selected values through the off-box elision walker afterwards."
  [entries key-filter]
  (into {}
        (filter (fn [[k _]] (scoped-key-matches? k key-filter)))
        (or entries {})))

(defn filter-work-rows
  "Filter projected work-ledger rows by the accessor axes: `:resource-id`,
  `:status`, `:owner`, `:request-id` (= work-id), `:nav-token`. Pure."
  [rows {:keys [resource-id status owner request-id nav-token]}]
  (->> rows
       (filter (fn [row]
                 (and (or (nil? resource-id) (= resource-id (:resource-id row)))
                      (or (nil? status)      (= status (:status row)))
                      (or (nil? owner)       (some #{owner} (:owners row)))
                      (or (nil? request-id)  (= request-id (:work-id row)))
                      (or (nil? nav-token)
                          (some (fn [o] (and (vector? o) (some #{nav-token} o)))
                                (:owners row))))))
       vec))

(defn filter-history-rows
  "Filter projected lifecycle-timeline rows by the accessor axes:
  `:resource-id`, `:nav-token` (matches an owner carrying the token), and
  bound the result to the last `:limit` rows (bounded history — Spec 016:
  resource history MUST be bounded). Pure."
  [rows {:keys [resource-id nav-token limit]}]
  (let [filtered (->> rows
                      (filter (fn [row]
                                (and (or (nil? resource-id)
                                         (= resource-id (:resource-id row)))
                                     (or (nil? nav-token)
                                         (let [o (:owner row)]
                                           (and (vector? o)
                                                (some #{nav-token} o)))))))
                      vec)]
    (if (and limit (> (count filtered) limit))
      (vec (take-last limit filtered))
      filtered)))
