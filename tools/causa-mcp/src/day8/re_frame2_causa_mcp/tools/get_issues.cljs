(ns day8.re-frame2-causa-mcp.tools.get-issues
  "Tool: `get-issues` — recent errors / warnings / schema violations /
  hydration mismatches (rf2-8xzoe.20, T-Insp-7 of the causa-mcp
  inspection tranche).

  Returns the issue-tier slice of the trace bus — events whose
  `:op-type` falls in
  `#{:error :warning :rf.schema/violation :rf.hydration/mismatch}` per
  Spec 009 §Issue-tier event op-types. Pagination via `:limit` /
  `:offset`; severity filter via `:severity` (`:error` /
  `:warning` / `:all` (default)).

  ## Wire-boundary contract

  Trace-stream-shaped — all three wire-pipeline mechanisms fire:

  1. **B-1 privacy** — `:sensitive? true` issue events are dropped by
     default; `:include-sensitive? true` opts back in. The
     `:dropped-sensitive` counter rides on the envelope when non-zero.
  2. **W-6 size elision** — `:elided-large` counted on the kept
     issues.
  3. **W-1 token cap** — dispatcher-level.

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:severity` | keyword | `:all` | `:error`, `:warning`, or `:all` |
  | `:frame` | keyword | nil | scope to one frame; nil → all frames |
  | `:since-ms` | int | nil | wall-clock cutoff (ms) |
  | `:limit` | int | 50 | max issues returned |
  | `:offset` | int | 0 | skip the first N issues post-filter |
  | `:include-sensitive?` | bool | false | opt back in to `:sensitive? true` items |
  | `:include-large?` | bool | false | passes to the runtime walker |
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape

      {:ok? true
       :issues <vec of issue events>
       :count <int>                  ; issues returned
       :total <int>                  ; issues pre-strip
       :limit <int>
       :offset <int>
       :severity <:error|:warning|:all>
       :dropped-sensitive <int?>
       :elided-large <int?>}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #20. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.privacy :as privacy]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(def ^:const default-limit 50)

(def ^:const severity-vocabulary
  "Closed set of `:severity` arg values. `:all` is the default
  (no filter); `:error` and `:warning` narrow to the corresponding
  issue op-types (`:error` matches `:error` + `:rf.schema/violation`
  + `:rf.hydration/mismatch`; `:warning` matches `:warning` only)."
  #{:error :warning :all})

(def ^:const error-op-types
  "Op-types treated as errors by the `:severity :error` filter."
  #{:error :rf.schema/violation :rf.hydration/mismatch})

(def ^:const warning-op-types
  #{:warning})

(defn- severity-pred
  "Build the per-event predicate for `severity`. `:all` matches every
  issue (the runtime already filtered to issue-tier op-types);
  `:error` / `:warning` narrow further."
  [severity]
  (case severity
    :error   #(contains? error-op-types   (:op-type %))
    :warning #(contains? warning-op-types (:op-type %))
    :all     (constantly true)
    (constantly true)))

(defn build-form
  "Build the eval-form addressing the runtime accessor. Pure."
  [opts]
  (-> (ef/rt-call 'get-issues opts)
      (ef/emit)
      (ef/wrap-origin)))

(defn- apply-limit-offset [items limit offset]
  (let [o (max 0 (or offset 0))
        l (or limit default-limit)]
    (->> items (drop o) (take l) vec)))

(defn shape-envelope
  "Shape the runtime's `{:ok? true :issues <vec> :count <int>}`
  response into the MCP envelope. Pure — tests pin the shaping logic.

  The runtime accessor already filtered to issue-tier op-types; this
  fn applies the optional `:severity` narrowing, the B-1 strip-step,
  pagination, and the W-6 marker count."
  [runtime-envelope {:keys [severity include-sensitive? limit offset]}]
  (let [{:keys [ok?] :as env} (if (map? runtime-envelope) runtime-envelope {})]
    (cond
      (false? ok?)
      env

      (not (true? ok?))
      {:ok?     false
       :reason  :unexpected-shape
       :runtime runtime-envelope
       :hint    "runtime/get-issues returned a non-envelope shape"}

      :else
      (let [raw-issues   (vec (:issues env))
            sev          (or severity :all)
            sev-filtered (vec (filter (severity-pred sev) raw-issues))
            paged        (apply-limit-offset sev-filtered limit offset)
            [kept dropped] (privacy/strip-sensitive paged include-sensitive?)
            elided       (elision/count-elided-markers kept)]
        (wire/with-indicators
          {:ok?      true
           :issues   kept
           :count    (count kept)
           :total    (count sev-filtered)
           :limit    (or limit default-limit)
           :offset   (max 0 (or offset 0))
           :severity sev}
          {:dropped dropped :elided elided})))))

(defn get-issues-tool
  "MCP handler for `get-issues`. Returns a Promise of the JS-shape
  MCP result."
  [conn args]
  (let [build-id (wire/arg-build args)
        severity (or (wire/arg-keyword args :severity) :all)
        frame    (wire/arg-keyword args :frame)
        since-ms (wire/arg-int args :since-ms)
        limit    (wire/arg-int args :limit default-limit)
        offset   (wire/arg-int args :offset 0)
        incl?    (privacy/parse-include-sensitive args)
        incl-large? (elision/parse-include-large args)]
    (cond
      (not (contains? severity-vocabulary severity))
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :invalid-severity
                       :given  severity
                       :hint   "severity must be :error :warning or :all"}))

      :else
      (let [runtime-opts (cond-> {:include-sensitive? incl?
                                  :include-large?     incl-large?}
                           frame    (assoc :frame frame)
                           since-ms (assoc :since-ms since-ms))
            form         (build-form runtime-opts)]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [runtime-envelope]
                     (wire/ok-text
                       (shape-envelope runtime-envelope
                                       {:severity           severity
                                        :include-sensitive? incl?
                                        :limit              limit
                                        :offset             offset}))))
            (.catch (fn [err] (probe/err->result :get-issues-failed err))))))))

(def descriptor
  {:name        "get-issues"
   :description (str "Recent issue-tier events from the re-frame2 trace "
                     "bus (errors / warnings / schema violations / "
                     "hydration mismatches). Narrow via :severity "
                     "(:error / :warning / :all default). Pagination "
                     "via :limit / :offset. Sensitive events default-"
                     "dropped; pass :include-sensitive? true to opt in.")
   :input-schema #js {:type "object"
                      :properties #js {:severity           #js {:type "string"}
                                       :frame              #js {:type "string"}
                                       :since-ms           #js {:type "integer"}
                                       :limit              #js {:type "integer"}
                                       :offset             #js {:type "integer"}
                                       :include-sensitive? #js {:type "boolean"}
                                       :include-large?     #js {:type "boolean"}
                                       :max-tokens         #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) get-issues-tool descriptor)
