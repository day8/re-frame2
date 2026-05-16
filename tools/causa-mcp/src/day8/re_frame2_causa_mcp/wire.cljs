(ns day8.re-frame2-causa-mcp.wire
  "MCP result helpers + per-call argument extraction for the causa-mcp
  T-Insp tranche (rf2-8xzoe T-Insp shared infra; sibling to pair2-mcp's
  `re-frame-pair2-mcp.tools.wire`).

  Every causa-mcp tool returns an MCP `{:content [{:type \"text\"
  :text <edn-string>}]}` envelope, success or error. This namespace
  owns that wire shape plus the tiny `arg` / `arg-keyword` /
  `arg-build` accessors every tool uses to pluck named args out of the
  JS-shaped `args` object the MCP host passes.

  Build-id resolution lives here too — `default-build-id` reads
  `SHADOW_CLJS_BUILD_ID` from `process.env`, falling back to `:app`."
  (:require [applied-science.js-interop :as j]
            [re-frame.mcp-base.vocab :as base-vocab]))

;; ---------------------------------------------------------------------------
;; Config — build id.
;; ---------------------------------------------------------------------------

(def ^:private cached-default-build-id
  "Cached at namespace load — the `SHADOW_CLJS_BUILD_ID` env var doesn't
  change at runtime, and re-reading it on every tool dispatch is wasted
  cycles. A delay defers the read until the first call — important for
  shadow-cljs hot-reload paths where the file may load before
  `js/process.env` is fully populated."
  (delay
    (or (some-> (j/get-in js/process [:env :SHADOW_CLJS_BUILD_ID])
                keyword)
        :app)))

(defn default-build-id [] @cached-default-build-id)

;; ---------------------------------------------------------------------------
;; MCP result helpers.
;; ---------------------------------------------------------------------------

(defn ok-text
  "Wrap `v` as a success MCP result. `v` is `pr-str`'d into the
  `:content[0].text` slot — the standard MCP single-text-slot shape."
  [v]
  #js {:content #js [#js {:type "text" :text (pr-str v)}]})

(defn err-text
  "Wrap `v` as an error MCP result (`:isError true`). Same shape as
  `ok-text` but flips the error flag so the agent host surfaces the
  envelope as a tool failure rather than a normal value."
  [v]
  #js {:isError true
       :content #js [#js {:type "text" :text (pr-str v)}]})

(defn with-indicators
  "Splice the cross-MCP indicator-field slots (`:dropped-sensitive`,
  `:elided-large`) onto a tool's envelope map. Cross-MCP convention:
  emit the slot iff its counter is positive (the zero-drop / zero-elide
  common path carries no counter so the wire stays minimal — a missing
  slot reads as zero).

  Mirrors `re-frame-pair2-mcp.tools.wire/with-indicators` byte-for-byte
  so the cross-MCP wire-vocab conformance test sees identical envelope
  shapes across the triplet (per `re-frame.mcp-base.vocab`'s indicator
  key constants)."
  [envelope {:keys [dropped elided]}]
  (cond-> envelope
    (pos? (or dropped 0)) (assoc :dropped-sensitive dropped)
    (pos? (or elided  0)) (assoc base-vocab/elided-large-key elided)))

;; ---------------------------------------------------------------------------
;; Arg pluckers — JS-side MCP args object.
;; ---------------------------------------------------------------------------

(defn arg
  "Extract an MCP tool argument by name. Returns nil if absent /
  `js/undefined`. Both the kebab-case canonical slot name and the
  string form of the keyword (with leading `:`) are checked because
  MCP clients sometimes serialise the cross-server `:include-sensitive?`
  / `:include-large?` slot names with the leading `:` preserved."
  [args k]
  (let [n (name k)
        v (or (j/get args n)
              (j/get args (str ":" n)))]
    (when-not (or (nil? v) (undefined? v)) v)))

(defn arg-keyword
  "Pluck an arg slot and coerce to a keyword via `(some-> v keyword)`.
  Returns nil when the slot is absent. Compresses the
  `(some-> (wire/arg args :foo) keyword)` pattern that recurs across
  per-tool bodies."
  [args k]
  (some-> (arg args k) keyword))

(defn arg-build
  "Resolve the shadow-cljs `:build-id` for this call. Per-call
  `:build` arg wins; falls back to `default-build-id`."
  [args]
  (or (arg-keyword args :build)
      (default-build-id)))

(defn arg-bool
  "Pluck a boolean arg slot. Accepts boolean passthrough, `\"true\"` /
  `\"false\"` / `\"1\"` / `\"0\"` / `\"yes\"` / `\"no\"` strings, and
  `nil` (→ `default`). Used by tools for `:include-sensitive?` /
  `:include-large?` and friends that aren't already covered by the
  privacy / elision parsers."
  ([args k] (arg-bool args k false))
  ([args k default]
   (let [v (arg args k)]
     (cond
       (nil? v)         default
       (boolean? v)     v
       (true? v)        true
       (false? v)       false
       (string? v)      (let [s (.toLowerCase v)]
                          (cond
                            (contains? #{"true" "1" "yes"} s) true
                            (contains? #{"false" "0" "no"} s) false
                            :else default))
       :else            default))))

(defn arg-int
  "Pluck an integer arg slot. Accepts native numbers and numeric
  strings; returns `default` for anything unparseable."
  ([args k] (arg-int args k nil))
  ([args k default]
   (let [v (arg args k)]
     (cond
       (nil? v)     default
       (number? v)  (long v)
       (string? v)  (let [n (js/parseInt v 10)]
                      (if (js/isNaN n) default n))
       :else        default))))

;; ---------------------------------------------------------------------------
;; Wire-bounded marker detection — used by the cap step.
;; ---------------------------------------------------------------------------

(def ^:private marker-prefixes
  ["{:rf.mcp/overflow"
   "{:rf.mcp/cache-hit"])

(defn marker?
  "Is `result-js` a wire-bounded `:rf.mcp/*` marker envelope? Returns
  true for `:rf.mcp/overflow` and `:rf.mcp/cache-hit` results — the
  envelopes the boundary steps emit themselves. Such envelopes are
  sub-cap by construction and must not be re-walked by later boundary
  steps."
  [result-js]
  (let [content (when result-js (j/get result-js :content))
        item    (when (array? content) (aget content 0))
        text    (when item (j/get item :text))]
    (and (string? text)
         (boolean (some #(.startsWith text %) marker-prefixes)))))
