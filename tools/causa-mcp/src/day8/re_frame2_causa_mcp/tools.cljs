(ns day8.re-frame2-causa-mcp.tools
  "MCP tool dispatcher for causa-mcp (rf2-8xzoe T-Insp tranche).

  Each tool is implemented in its own file under
  `day8.re-frame2-causa-mcp.tools.<tool-name>`; this ns is the
  façade — it owns `tool-descriptors-js` (the static catalogue the MCP
  `tools/list` handler serves) and `invoke` (the `tools/call` dispatch
  glue with the W-1 token-cap boundary step).

  ## Why a thin façade rather than the pair2-mcp four-step pipeline

  pair2-mcp's `re-frame-pair2-mcp.tools/wire-boundary-pipeline` runs
  four steps (precheck, dispatch, apply-cache, apply-cap) under a
  pluggable `boundary-step/run-step-pipeline` orchestrator. Causa-mcp's
  T-Insp tranche needs only **dispatch + apply-cap**: there is no
  request-level cache (B-2 lands later as its own tranche), and the
  per-tool B-1 privacy filter + W-6 size-elision marker count live
  inside the per-tool bodies (they're per-payload, not per-envelope).
  A two-step inline composition is faster to read than a one-element
  pipeline ceremony; the pipeline structure lands when a third step
  arrives.

  ## Per-tool registration

  Tools register themselves into `handler-registry` at load-time via
  `(register-tool! \"get-trace-buffer\" handler-fn descriptor-map)`.
  The single-source-of-truth pattern means adding a new tool is one
  file plus one `(register-tool! ...)` call — no central name-table
  edit. The T-Insp tranche imports the nine tool nss below; each ns
  performs its registration as a top-level side-effect."
  (:require [applied-science.js-interop :as j]
            [day8.re-frame2-causa-mcp.token-cap :as token-cap]
            [day8.re-frame2-causa-mcp.wire :as wire]
            ;; T-Insp tranche tool nss — each registers itself at load
            ;; (rf2-8xzoe.14..22). Listed in the bead-numbered order so a
            ;; reader trace the cluster's commit sequence.
            [day8.re-frame2-causa-mcp.tools.get-trace-buffer]
            [day8.re-frame2-causa-mcp.tools.get-machine-list]
            [day8.re-frame2-causa-mcp.tools.get-handlers]
            [day8.re-frame2-causa-mcp.tools.get-source-coord]
            [day8.re-frame2-causa-mcp.tools.get-machine-state]
            [day8.re-frame2-causa-mcp.tools.get-issues]
            [day8.re-frame2-causa-mcp.tools.get-epoch-history]
            [day8.re-frame2-causa-mcp.tools.get-app-db]
            [day8.re-frame2-causa-mcp.tools.get-app-db-diff]
            [day8.re-frame2-causa-mcp.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Descriptor catalogue.
;; ---------------------------------------------------------------------------

(defn tool-descriptors
  "All registered descriptor maps in insertion order. Public so tests
  pin the catalogue shape (and the `server.cljs` `tools/list` handler
  consumes it)."
  []
  (registry/all-descriptors))

(defn- ->descriptor-js
  "Convert a per-tool descriptor map (CLJS) to the JS shape the MCP
  SDK serialises for `tools/list`. Each descriptor is
  `{:name :description :input-schema}`; the schema is already a JS-
  shaped JSON-Schema object so it rides through `clj->js` unchanged."
  [{:keys [name description input-schema]}]
  #js {:name        name
       :description description
       :inputSchema (or input-schema #js {:type "object"})})

(defn tool-descriptors-js
  "The Causa-shaped tool catalogue, as a JS array of descriptor maps.
  Consumed by `server.cljs`'s `tools/list` handler. The array is freshly
  built per call (cheap — bounded by the registered-tool count) so a
  late-loaded tool ns is visible without a server restart."
  []
  (clj->js (mapv ->descriptor-js (tool-descriptors))))

;; ---------------------------------------------------------------------------
;; Dispatch glue.
;; ---------------------------------------------------------------------------

(defn- unknown-tool-result [name]
  (wire/err-text {:ok?    false
                  :reason :unknown-tool
                  :tool   name
                  :hint   "Run `tools/list` for the registered catalogue."}))

(defn- dispatch-tool*
  "Route a `tools/call` to the per-tool implementation. Unknown tools
  resolve to an `isError` result rather than throwing — keeps the
  server loop simple."
  [conn name args]
  (if-let [handler (registry/handler-for name)]
    (handler conn args)
    (js/Promise.resolve (unknown-tool-result name))))

(defn invoke
  "Dispatch a `tools/call` invocation. Returns a Promise resolving to
  the MCP result object.

  ## Two-step wire boundary

  1. **dispatch** — per-tool implementation. Returns the JS-shape MCP
     result (the per-tool body already ran the W-6 elision marker
     count + B-1 privacy gate inside its envelope).
  2. **apply-cap** — W-1 token-budget enforcement
     (`token-cap/apply-cap`). Responses whose serialised size exceeds
     the per-call cap (default 5,000 tokens; `max-tokens` arg can
     override into `[500, 50000]`) are replaced with the
     `{:rf.mcp/overflow ...}` marker. Already-marker results bypass
     (the marker envelopes are sub-cap by construction).

  `extra` carries the MCP `extra` payload (signal +
  sendNotification + `_meta.progressToken`). Non-streaming tools
  (every T-Insp tool) ignore it; the slot is kept on the surface so a
  later streaming-band tranche lands without an `invoke` rename."
  [conn name args _extra]
  (-> (dispatch-tool* conn name args)
      (.then (fn [result-js]
               (if (wire/marker? result-js)
                 result-js
                 (token-cap/apply-cap
                   result-js
                   {:tool name
                    :cap  (token-cap/max-tokens-arg args)}))))))
