(ns day8.re-frame2-causa-mcp.tools.restore-epoch
  "Tool: `restore-epoch` — Class-1 named mutation (rf2-8xzoe.24,
  T-Mut-2 of the causa-mcp mutation tranche).

  Rewinds a frame's `app-db` to the named epoch's `:db-after` via the
  framework's `re-frame.core/restore-epoch`. The framework wrapper
  returns a boolean (`true` on success; `false` on any of the six
  documented failure rows). This tool surfaces the six-row failure
  table via the framework's emitted trace stream — `subscribe :trace`
  or the next `get-trace-buffer` call reads the structured
  `:rf.epoch/*` row that names which row tripped:

  | Row | Trace `:op-type` keyword |
  |---|---|
  | unknown frame | `:rf.error/no-such-handler` (kind `:frame`) |
  | unknown epoch | `:rf.epoch/restore-unknown-epoch` |
  | schema mismatch | `:rf.epoch/restore-schema-mismatch` |
  | missing handler | `:rf.epoch/restore-missing-handler` |
  | version mismatch | `:rf.epoch/restore-version-mismatch` |
  | restore during drain | `:rf.epoch/restore-during-drain` |

  The accessor intentionally does NOT project the per-row keyword
  onto its own return shape — the framework returns a plain boolean,
  the row already lives on the bus, and double-projection would let
  the two drift. The tool returns `:reason :rf.epoch/restore-failed`
  with a hint pointing at the trace bus.

  ## Wire-boundary contract

  - **B-3 origin stamp** — synchronous-extent `*current-origin*`
    binding around the runtime call so the framework's restore-emitted
    traces tag `:origin :causa-mcp`.
  - **B-1 privacy** — not applicable (the response envelope is
    structured ack metadata, not trace events).
  - **W-6 size elision** — counted defensively on the runtime
    envelope.
  - **W-1 token cap** — dispatcher-level via `tools.cljs/invoke`.

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:epoch-id` | string | nil (REQUIRED) | the epoch-id to restore |
  | `:frame` | keyword | nil | scope to one frame; nil → sole frame |
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape

      ;; success
      {:ok? true  :frame <kw> :epoch-id <id> :origin :causa-mcp}

      ;; failure (one of the six rows; see trace bus for the
      ;; structured :rf.epoch/* keyword)
      {:ok? false :frame <kw> :epoch-id <id> :origin :causa-mcp
       :reason :rf.epoch/restore-failed
       :hint   \"Restore failed — read the trace bus for the structured
                :rf.epoch/* row.\"}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #24. Tool-Pair §Time-travel — Restore — the six-row failure
  surface contract. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(defn build-form
  "Build the eval-form addressing the runtime accessor. Pure."
  [opts]
  (-> (ef/rt-call 'restore-epoch! opts)
      (ef/emit)
      (ef/wrap-origin)))

(defn shape-envelope
  "Pass the runtime's restore ack through; count any defensive elision
  markers. Pure — tests pin the indicator stamping logic."
  [runtime-envelope]
  (let [{:keys [ok?] :as env} (if (map? runtime-envelope) runtime-envelope {})]
    (cond
      ;; Both success (true) and failure (false) carry the same
      ;; structural slots — flow through and stamp.
      (or (true? ok?) (false? ok?))
      (let [elided (elision/count-elided-markers env)]
        (wire/with-indicators env {:elided elided}))

      :else
      {:ok?     false
       :reason  :unexpected-shape
       :runtime runtime-envelope
       :hint    "runtime/restore-epoch! returned a non-envelope shape"})))

(defn restore-epoch-tool
  "MCP handler for `restore-epoch`. Validates `:epoch-id` pre-flight;
  runtime accessor validates again (defence in depth)."
  [conn args]
  (let [build-id (wire/arg-build args)
        frame    (wire/arg-keyword args :frame)
        epoch-id (wire/arg args :epoch-id)]
    (cond
      (or (nil? epoch-id) (and (string? epoch-id) (str/blank? epoch-id)))
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :missing-epoch-id
                       :hint   "Pass :epoch-id <uuid-string> from get-epoch-history."}))

      :else
      (let [runtime-opts (cond-> {:epoch-id epoch-id}
                           frame (assoc :frame frame))
            form         (build-form runtime-opts)]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [runtime-envelope]
                     (wire/ok-text (shape-envelope runtime-envelope))))
            (.catch (fn [err] (probe/err->result :restore-epoch-failed err))))))))

(def descriptor
  {:name        "restore-epoch"
   :description (str "Rewind a frame's app-db to the named epoch's "
                     ":db-after via re-frame.core/restore-epoch. Returns "
                     ":ok? true on success; :ok? false :reason "
                     ":rf.epoch/restore-failed on any of the six "
                     "documented failure rows (unknown frame / unknown "
                     "epoch / schema mismatch / missing handler / "
                     "version mismatch / restore during drain). The "
                     "structured per-row :rf.epoch/* keyword surfaces "
                     "on the trace bus (read via get-trace-buffer or "
                     "subscribe :trace). Required :epoch-id from "
                     "get-epoch-history.")
   :input-schema #js {:type "object"
                      :required #js ["epoch-id"]
                      :properties #js {:epoch-id   #js {:type "string"}
                                       :frame      #js {:type "string"}
                                       :max-tokens #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) restore-epoch-tool descriptor)
