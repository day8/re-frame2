(ns re-frame-pair2-mcp.tools.wire
  "MCP result helpers + per-call argument extraction (rf2-vrbwx split).

  Every tool returns an MCP `{:content [{:type \"text\" :text <edn-string>}]}`
  envelope, success or error. This namespace owns that wire shape plus
  the tiny `arg` / `arg-build` accessors every tool uses to pluck named
  args out of the JS-shaped `args` object the MCP host passes.

  Build-id resolution lives here too — `default-build-id` reads
  `SHADOW_CLJS_BUILD_ID` from `process.env`, falling back to `:app`."
  (:require [applied-science.js-interop :as j]))

;; ---------------------------------------------------------------------------
;; Config — build id.
;; ---------------------------------------------------------------------------

(defn default-build-id []
  (or (some-> (j/get-in js/process [:env :SHADOW_CLJS_BUILD_ID])
              keyword)
      :app))

;; ---------------------------------------------------------------------------
;; MCP result helpers.
;; ---------------------------------------------------------------------------

(defn ok-text [v]
  #js {:content #js [#js {:type "text" :text (pr-str v)}]})

(defn err-text [v]
  #js {:isError true
       :content #js [#js {:type "text" :text (pr-str v)}]})

(defn arg
  "Extract an MCP tool argument by name. Returns nil if absent."
  [args k]
  (let [v (j/get args (name k))]
    (when-not (or (nil? v) (undefined? v)) v)))

(defn arg-build [args]
  (or (some-> (arg args :build) keyword)
      (default-build-id)))
