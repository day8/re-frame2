(ns day8.re-frame2-causa-mcp.tools.get-app-db-diff
  "Tool: `get-app-db-diff` — app-db diff for a named epoch
  (rf2-8xzoe.17, T-Insp-4 of the causa-mcp inspection tranche).

  Returns the slice diff for an epoch — what changed in `app-db`
  between `:db-before` and `:db-after`. Per the MUST inventory in
  `tools/causa-mcp/spec/004-Wire-Pipeline.md`:

  - **MUST 13** — default returns **changed-paths-with-cardinalities,
    NOT the nested before/after diff**. The agent gets a token-cheap
    `{:added [...] :removed [...] :changed [...]}` envelope from
    which it can drill in via `get-app-db` for any single changed
    path. The full nested diff is opt-in via `:mode :nested`.
  - **MUST 19** — direct-read privacy: `:include-sensitive?` /
    `:include-large?` default `false`. The runtime accessor routes
    `:db-before` + `:db-after` through `re-frame.core/elide-wire-value`
    pre-diff, so declared-sensitive paths are scrubbed BEFORE the
    diff is computed (a sensitive path that didn't actually change
    still surfaces as redacted on both sides; one that changed
    surfaces as redacted-but-changed).

  ## Wire-boundary contract

  - **W-6 size elision** — counted on the projected diff. In the
    default `:mode :changed-paths` projection the count is generally
    zero (paths are scalar vectors); the count is meaningful in
    `:mode :nested` where the projected value carries the runtime's
    marker substitutions.
  - **B-1 privacy** — not directly applicable (epoch-record's
    `:sensitive?` rollup gates the full record; a per-path strip
    would be a different design and isn't in scope).
  - **W-1 token cap** — dispatcher-level. Cap-reached hint
    `:switch-mode` (downshift to `:changed-paths` from `:nested`) or
    `:slice` (when even the changed-paths set is huge).

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:frame` | keyword | nil | scope to one frame |
  | `:epoch-id` | string | **required** | the epoch to diff |
  | `:mode` | keyword | `:changed-paths` | `:changed-paths` or `:nested` |
  | `:include-sensitive?` | bool | false | passes to runtime walker |
  | `:include-large?` | bool | false | passes to runtime walker |
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape

  Default `:mode :changed-paths`:

      {:ok? true
       :frame <kw>
       :epoch-id <id>
       :mode :changed-paths
       :diff {:added   [<path-vec> ...]
              :removed [<path-vec> ...]
              :changed [<path-vec> ...]
              :counts  {:added <int> :removed <int> :changed <int>}}}

  `:mode :nested`:

      {:ok? true
       :frame <kw>
       :epoch-id <id>
       :mode :nested
       :diff {:before <edn> :after <edn>}
       :elided-large <int?>}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #17. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [clojure.set :as set]
            [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.privacy :as privacy]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(defn build-form
  "Build the eval-form addressing the runtime accessor. Pure."
  [opts]
  (-> (ef/rt-call 'get-app-db-diff opts)
      (ef/emit)
      (ef/wrap-origin)))

;; ---------------------------------------------------------------------------
;; Changed-paths projection — MUST 13.
;;
;; Walks `before` + `after` recursively, emitting the path vectors
;; where one tree has a key the other doesn't (`:added` / `:removed`)
;; or both have the key but the value differs (`:changed`). Stops
;; recursion at scalar boundaries — a path that points at a deep
;; subtree-changed surfaces as one `:changed` entry, not one per
;; leaf, so the agent's first-pass diff stays bounded.
;; ---------------------------------------------------------------------------

(defn- diffable-map?
  "Recurse into maps only. Vectors / sets / scalars are leaves at the
  diff boundary — a vector that changed shape is one `:changed`
  entry, not N entries. Per spec/004 §Direct-read: changed-paths is
  the path-key-set, not a per-leaf walk."
  [x]
  (map? x))

(defn- changed-paths*
  "Recursive walk producing `{:added [...] :removed [...] :changed [...]}`.
  `path` is the path prefix accumulated so far."
  [path before after]
  (cond
    ;; Both nil — no diff.
    (and (nil? before) (nil? after))
    {:added [] :removed [] :changed []}

    ;; Equal at this level — no diff.
    (= before after)
    {:added [] :removed [] :changed []}

    ;; Recurse into maps; emit per-key diffs.
    (and (diffable-map? before) (diffable-map? after))
    (let [b-keys (set (keys before))
          a-keys (set (keys after))
          added   (vec (sort-by str (set/difference a-keys b-keys)))
          removed (vec (sort-by str (set/difference b-keys a-keys)))
          common  (set/intersection a-keys b-keys)
          per-k   (for [k (sort-by str common)
                        :let [b (get before k)
                              a (get after  k)]
                        :when (not= b a)]
                    (changed-paths* (conj path k) b a))]
      {:added   (mapv #(conj path %) added)
       :removed (mapv #(conj path %) removed)
       :changed (vec (mapcat :changed
                             (cons {:changed (if (seq per-k) [] [])}
                                   per-k)))
       ::sub-adds    (vec (mapcat :added   per-k))
       ::sub-removes (vec (mapcat :removed per-k))})

    ;; Map → non-map (or vice versa) at this level — one :changed entry.
    :else
    {:added   []
     :removed []
     :changed (if (empty? path) [] [path])}))

(defn changed-paths
  "Public projection — produce `{:added :removed :changed :counts}` from
  the `:before` / `:after` pair. Pure. Path vectors are sorted by
  printed form so output is deterministic across runs (stable test
  shape; stable agent behaviour)."
  [before after]
  (let [raw      (changed-paths* [] before after)
        adds     (vec (sort-by str (concat (:added raw) (::sub-adds raw))))
        removes  (vec (sort-by str (concat (:removed raw) (::sub-removes raw))))
        changes  (vec (sort-by str (:changed raw)))]
    {:added   adds
     :removed removes
     :changed changes
     :counts  {:added   (count adds)
               :removed (count removes)
               :changed (count changes)}}))

(defn shape-envelope
  "Shape the runtime's `{:ok? true :frame :epoch-id :diff {:before
  :after}}` response into the MCP envelope. Pure — tests pin the diff
  projection logic.

  `mode` selects the projection: `:changed-paths` (default) walks
  before/after into a path-key set; `:nested` ships the
  before/after pair verbatim."
  [runtime-envelope {:keys [mode]}]
  (let [{:keys [ok?] :as env} (if (map? runtime-envelope) runtime-envelope {})]
    (cond
      (false? ok?)
      env

      (not (true? ok?))
      {:ok?     false
       :reason  :unexpected-shape
       :runtime runtime-envelope
       :hint    "runtime/get-app-db-diff returned a non-envelope shape"}

      :else
      (let [{:keys [before after]} (:diff env)
            projected (if (= :nested mode)
                        {:before before :after after}
                        (changed-paths before after))
            elided    (elision/count-elided-markers projected)]
        (wire/with-indicators
          {:ok?      true
           :frame    (:frame env)
           :epoch-id (:epoch-id env)
           :mode     (or mode :changed-paths)
           :diff     projected}
          {:elided elided})))))

(defn get-app-db-diff-tool
  "MCP handler for `get-app-db-diff`. Pre-flight validates :epoch-id
  is present and :mode is one of #{:changed-paths :nested}; short-
  circuits before contacting the runtime."
  [conn args]
  (let [build-id (wire/arg-build args)
        frame    (wire/arg-keyword args :frame)
        epoch-id (wire/arg args :epoch-id)
        mode     (or (wire/arg-keyword args :mode) :changed-paths)
        incl?    (privacy/parse-include-sensitive args)
        incl-large? (elision/parse-include-large args)]
    (cond
      (nil? epoch-id)
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :missing-epoch-id
                       :hint   "Pass :epoch-id <uuid|string> — see get-epoch-history."}))

      (not (contains? #{:changed-paths :nested} mode))
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :invalid-mode
                       :given  mode
                       :hint   "mode must be :changed-paths or :nested"}))

      :else
      (let [runtime-opts (cond-> {:epoch-id           epoch-id
                                  :include-sensitive? incl?
                                  :include-large?     incl-large?}
                           frame (assoc :frame frame))
            form         (build-form runtime-opts)]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [runtime-envelope]
                     (wire/ok-text (shape-envelope runtime-envelope
                                                   {:mode mode}))))
            (.catch (fn [err] (probe/err->result :get-app-db-diff-failed err))))))))

(def descriptor
  {:name        "get-app-db-diff"
   :description (str "App-db diff for a named epoch. Default :mode "
                     ":changed-paths returns a token-cheap "
                     "{:added :removed :changed :counts} path-key "
                     "envelope; :mode :nested returns the full "
                     "{:before :after} pair (heavier but inspectable). "
                     "Drill into a single changed path via "
                     "get-app-db with the path arg.")
   :input-schema #js {:type "object"
                      :required #js ["epoch-id"]
                      :properties #js {:frame              #js {:type "string"}
                                       :epoch-id           #js {:type "string"}
                                       :mode               #js {:type "string"}
                                       :include-sensitive? #js {:type "boolean"}
                                       :include-large?     #js {:type "boolean"}
                                       :max-tokens         #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) get-app-db-diff-tool descriptor)
