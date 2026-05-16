(ns day8.re-frame2-causa-mcp.tools.get-trace-buffer
  "Tool: `get-trace-buffer` — trace-bus reader (rf2-8xzoe.14, T-Insp-1
  of the causa-mcp inspection tranche).

  Returns a slice of the trace-bus events captured by the consumer
  app's running Spec 009 instrumentation. Filter vocabulary is the
  canonical Spec 009 set (`:operation`, `:op-type`, `:since`, `:frame`,
  `:severity`, `:event-id`, `:handler-id`, `:source`, `:origin`,
  `:dispatch-id`, `:since-ms`, `:between`, `:pred`). Pagination is the
  responsibility of the caller — pass `:limit` and `:offset` to slice
  the event vector after filtering.

  ## Wire-boundary contract

  This tool is **trace-stream-shaped** per the privacy catalogue in
  `tools/causa-mcp/src/.../privacy.cljs` — its payload is a vector of
  trace-event maps, every one of which carries the top-level
  `:sensitive?` boolean (per Spec 009). Three wire-pipeline mechanisms
  apply on the egress boundary:

  1. **W-6 size elision** — every trace event's value-bearing slots
     (`:event`, `:before`, `:after`, `:result`) was already routed
     through `re-frame.core/elide-wire-value` server-side by the
     runtime accessor (`day8.re-frame2-causa.runtime/get-trace-buffer`).
     This tool counts the resulting `{:rf.size/large-elided ...}`
     markers via `elision/count-elided-markers` and stamps the
     `:elided-large` envelope counter when non-zero.
  2. **B-1 privacy default-suppress** — events carrying
     `:sensitive? true` are dropped via `privacy/strip-sensitive`
     unless the caller opted in with `:include-sensitive? true`. The
     `:dropped-sensitive` envelope counter is stamped when non-zero.
  3. **W-1 token cap** — the dispatcher wraps the returned envelope
     through `token-cap/apply-cap` (5,000 tokens default, `max-tokens`
     arg overrides into `[500, 50000]`). When the rendered payload
     exceeds the cap, the result is replaced with the
     `{:rf.mcp/overflow ...}` marker (the dispatcher in
     `tools.cljs` runs this step; not the per-tool body).

  ## Args

  | Arg                   | Type    | Default | Notes                                  |
  |-----------------------|---------|---------|----------------------------------------|
  | `:frame`              | keyword | nil     | scope to one frame; nil → all frames   |
  | `:op-type`            | keyword | nil     | filter by operation type               |
  | `:since-ms`           | int     | nil     | wall-clock cutoff (ms)                 |
  | `:limit`              | int     | 50      | max events returned                    |
  | `:offset`             | int     | 0       | skip the first N events post-filter    |
  | `:include-sensitive?` | bool    | false   | opt back in to `:sensitive? true` items|
  | `:include-large?`     | bool    | false   | opt back in to large values            |

  ## Return shape

      {:ok? true
       :events <vec of trace events>
       :count <int>
       :dropped-sensitive <int?>   ; only when > 0
       :elided-large <int?>}       ; only when > 0

  Or on a runtime-side failure:

      {:ok? false :reason <kw> :hint <string>}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #14. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.privacy :as privacy]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

;; ---------------------------------------------------------------------------
;; Arg parsing.
;; ---------------------------------------------------------------------------

(def ^:private default-limit
  "Default `:limit` on a single page of trace events. 50 mirrors the
  Tool-Pair epoch-history depth default — agents trained on the cross-
  MCP idiom expect the same first-page size for trace-stream surfaces."
  50)

(defn- build-filter-opts
  "Project the raw MCP args object into the filter-opts map the runtime
  accessor expects. We pass-through the canonical Spec 009 filter slots
  plus `:limit` / `:offset` (which the runtime applies after filtering)
  + the `:include-sensitive?` / `:include-large?` opts the elide call
  honours."
  [args]
  (let [base (cond-> {}
               (wire/arg-keyword args :frame)
               (assoc :frame (wire/arg-keyword args :frame))

               (wire/arg-keyword args :op-type)
               (assoc :op-type (wire/arg-keyword args :op-type))

               (wire/arg-int args :since-ms)
               (assoc :since-ms (wire/arg-int args :since-ms))

               (wire/arg args :event-id)
               (assoc :event-id (wire/arg-keyword args :event-id))

               (wire/arg args :origin)
               (assoc :origin (wire/arg-keyword args :origin)))]
    (assoc base
      :include-sensitive? (privacy/parse-include-sensitive args)
      :include-large?     (elision/parse-include-large args))))

;; ---------------------------------------------------------------------------
;; Eval form composition.
;; ---------------------------------------------------------------------------

(defn build-form
  "Build the CLJS eval-form string the runtime evaluates. Pure (no I/O
  / Promise), so tests pin the form shape directly. The form calls
  `day8.re-frame2-causa.runtime/get-trace-buffer` with the filter-opts
  map and wraps the call in the `:causa-mcp` origin binding so any
  side-effect the runtime accessor performs (none today — it's
  read-only — but reserved for the contract) carries the right tag."
  [filter-opts]
  (-> (ef/rt-call 'get-trace-buffer filter-opts)
      (ef/emit)
      (ef/wrap-origin)))

;; ---------------------------------------------------------------------------
;; Result shaping.
;; ---------------------------------------------------------------------------

(defn- apply-limit-offset
  "Slice the events vector by `:offset` then `:limit`. Returns the
  sliced vector. Both default to no-op when missing / non-positive."
  [events limit offset]
  (let [o (max 0 (or offset 0))
        l (or limit default-limit)]
    (->> events
         (drop o)
         (take l)
         vec)))

(defn shape-envelope
  "Shape the runtime's `{:ok? true :events <vec> :count <int>}`
  response into the MCP envelope after applying the B-1 strip-step
  and the W-6 marker count. Pure — tests pin the full shaping logic
  without I/O.

  Inputs:
    - `runtime-envelope` — the raw map the runtime accessor returned
      (already `pr-str`'d and re-read by `nrepl/cljs-eval-value`).
    - `include-sensitive?` — boolean resolved from the MCP arg.
    - `limit` / `offset` — caller-supplied pagination knobs.

  Returns the envelope ready for `wire/ok-text`."
  [runtime-envelope include-sensitive? limit offset]
  (let [{:keys [ok?] :as env} (if (map? runtime-envelope) runtime-envelope {})]
    (cond
      ;; Surface a runtime-side failure verbatim — already structured.
      (false? ok?)
      env

      ;; Defensive: unexpected shape from the runtime side.
      (not (true? ok?))
      {:ok?     false
       :reason  :unexpected-shape
       :runtime runtime-envelope
       :hint    "runtime/get-trace-buffer returned a non-envelope shape"}

      :else
      (let [raw-events     (vec (:events env))
            paged          (apply-limit-offset raw-events limit offset)
            ;; B-1: drop :sensitive? true items unless caller opted in.
            [kept dropped] (privacy/strip-sensitive paged include-sensitive?)
            ;; W-6: count any size-elision markers the walker emitted.
            elided         (elision/count-elided-markers kept)
            total          (count raw-events)]
        (wire/with-indicators
          {:ok?           true
           :events        kept
           :count         (count kept)
           :total         total
           :limit         (or limit default-limit)
           :offset        (max 0 (or offset 0))}
          {:dropped dropped
           :elided  elided})))))

;; ---------------------------------------------------------------------------
;; Tool handler.
;; ---------------------------------------------------------------------------

(defn get-trace-buffer-tool
  "MCP handler for `get-trace-buffer`. Returns a Promise of the JS-shape
  MCP result."
  [conn args]
  (let [build-id (wire/arg-build args)
        opts     (build-filter-opts args)
        limit    (wire/arg-int args :limit default-limit)
        offset   (wire/arg-int args :offset 0)
        incl?    (:include-sensitive? opts)
        form     (build-form opts)]
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [runtime-envelope]
                 (wire/ok-text (shape-envelope runtime-envelope incl? limit offset))))
        (.catch (fn [err] (probe/err->result :get-trace-buffer-failed err))))))

;; ---------------------------------------------------------------------------
;; Descriptor + registration.
;; ---------------------------------------------------------------------------

(def descriptor
  "MCP tool descriptor for `get-trace-buffer`. The `:input-schema` is a
  JSON-Schema object the MCP SDK serialises on `tools/list`. Public so
  tests pin the catalogue surface."
  {:name        "get-trace-buffer"
   :description (str "Read a slice of the re-frame2 trace bus. Returns recent "
                     "trace events filtered by frame / op-type / since-ms / "
                     "origin / event-id. Sensitive events are dropped by "
                     "default (pass :include-sensitive? true to opt in). "
                     "Large values are elided to size markers by default "
                     "(pass :include-large? true to opt in). Default :limit 50.")
   :input-schema #js {:type "object"
                      :properties #js {:frame              #js {:type "string"}
                                       :op-type            #js {:type "string"}
                                       :since-ms           #js {:type "integer"}
                                       :event-id           #js {:type "string"}
                                       :origin             #js {:type "string"}
                                       :limit              #js {:type "integer"}
                                       :offset             #js {:type "integer"}
                                       :include-sensitive? #js {:type "boolean"}
                                       :include-large?     #js {:type "boolean"}
                                       :max-tokens         #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) get-trace-buffer-tool descriptor)
