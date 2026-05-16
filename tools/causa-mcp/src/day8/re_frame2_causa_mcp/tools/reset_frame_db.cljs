(ns day8.re-frame2-causa-mcp.tools.reset-frame-db
  "Tool: `reset-frame-db` — Class-1 named mutation (rf2-8xzoe.25,
  T-Mut-3 of the causa-mcp mutation tranche).

  Re-injects a value into a frame's `app-db`, bypassing the dispatch
  cascade. Schema-validates against the registered app-db schemas via
  the framework's `re-frame.core/reset-frame-db!`; the wrapper returns
  a boolean (`true` on success; `false` on any of the three documented
  failure rows — each emits a structured trace and leaves `app-db`
  unchanged):

  | Row | Trace `:op-type` keyword |
  |---|---|
  | unknown frame | `:rf.error/no-such-handler` (kind `:frame`) |
  | reset during drain | `:rf.epoch/reset-frame-db-during-drain` |
  | schema mismatch | `:rf.epoch/reset-frame-db-schema-mismatch` |

  Same projection rationale as `restore-epoch`: the framework returns
  a plain boolean, the per-row keyword lives on the trace bus, this
  tool surfaces `:reason :rf.epoch/reset-failed` and points at the
  bus.

  ## Restore-audit trail

  The framework's restore-audit ring captures every `reset-frame-db!`
  call alongside `restore-epoch` calls (per Tool-Pair §Time-travel —
  the restore-audit IS the audit of explicit-write operations). The
  audit row's `:tags :origin` carries the binding-extent value, so
  every reset this tool fires shows up tagged `:causa-mcp` in the
  next `get-epoch-history` read.

  ## Wire-boundary contract

  - **B-3 origin stamp** — synchronous-extent `*current-origin*`
    binding around the runtime call.
  - **B-1 privacy** — not applicable (ack envelope, not trace
    events).
  - **W-6 size elision** — counted defensively on the runtime
    envelope.
  - **W-1 token cap** — dispatcher-level via `tools.cljs/invoke`.

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:value` | EDN-map str | nil (REQUIRED) | the new `app-db` value |
  | `:frame` | keyword | nil | scope to one frame; nil → sole frame |
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape

      ;; success
      {:ok? true  :frame <kw> :origin :causa-mcp}

      ;; failure (one of the three rows; see trace bus for the
      ;; structured :rf.epoch/* keyword)
      {:ok? false :frame <kw> :origin :causa-mcp
       :reason :rf.epoch/reset-failed
       :hint   \"Reset failed — read the trace bus for the structured
                :rf.epoch/* row.\"}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #25. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [cljs.reader :as edn]
            [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(defn parse-value-arg
  "Coerce the `:value` MCP arg to a CLJS value. Accepts a CLJS map
  passthrough or an EDN-printed string. Returns `::malformed` for
  unparseable input; returns `::absent` for nil so the caller can
  distinguish 'absent' from 'parsed-to-nil' (a user could legitimately
  pass `nil` as a fresh `app-db`)."
  [v]
  (cond
    (nil? v)    ::absent
    (string? v) (try (edn/read-string v)
                     (catch :default _ ::malformed))
    :else       v))

(defn build-form
  "Build the eval-form addressing the runtime accessor. Pure."
  [opts]
  (-> (ef/rt-call 'reset-frame-db! opts)
      (ef/emit)
      (ef/wrap-origin)))

(defn shape-envelope
  "Pass the runtime's reset ack through; count any defensive elision
  markers. Pure — tests pin the indicator stamping logic."
  [runtime-envelope]
  (let [{:keys [ok?] :as env} (if (map? runtime-envelope) runtime-envelope {})]
    (cond
      (or (true? ok?) (false? ok?))
      (let [elided (elision/count-elided-markers env)]
        (wire/with-indicators env {:elided elided}))

      :else
      {:ok?     false
       :reason  :unexpected-shape
       :runtime runtime-envelope
       :hint    "runtime/reset-frame-db! returned a non-envelope shape"})))

(defn reset-frame-db-tool
  "MCP handler for `reset-frame-db`. Validates `:value` pre-flight."
  [conn args]
  (let [build-id  (wire/arg-build args)
        frame     (wire/arg-keyword args :frame)
        value-raw (wire/arg args :value)
        value     (parse-value-arg value-raw)]
    (cond
      (= ::absent value)
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :missing-value
                       :hint   "Pass :value <EDN-string> to inject (e.g. \"{:cart {}}\")."}))

      (= ::malformed value)
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :value-malformed
                       :given  value-raw
                       :hint   "Could not parse :value as EDN."}))

      :else
      (let [runtime-opts (cond-> {:value value}
                           frame (assoc :frame frame))
            form         (build-form runtime-opts)]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [runtime-envelope]
                     (wire/ok-text (shape-envelope runtime-envelope))))
            (.catch (fn [err] (probe/err->result :reset-frame-db-failed err))))))))

(def descriptor
  {:name        "reset-frame-db"
   :description (str "Re-inject :value into a frame's app-db via "
                     "re-frame.core/reset-frame-db! (bypasses dispatch "
                     "cascade; schema-validates). Returns :ok? true on "
                     "success; :ok? false :reason :rf.epoch/reset-failed "
                     "on any of the three documented failure rows "
                     "(unknown frame / reset during drain / schema "
                     "mismatch). The structured per-row :rf.epoch/* "
                     "keyword surfaces on the trace bus. Required "
                     ":value is an EDN string, e.g. \"{:cart {}}\".")
   :input-schema #js {:type "object"
                      :required #js ["value"]
                      :properties #js {:value      #js {:type "string"}
                                       :frame      #js {:type "string"}
                                       :max-tokens #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) reset-frame-db-tool descriptor)
