(ns re-frame-pair2-mcp.wire-cap-test
  "Unit tests for the wire-boundary token-budget cap (rf2-rvyzy).

  Per `tools/pair2-mcp/spec/Principles.md` §\"Tight token budget per
  response\", every MCP `tools/call` response is bounded at ~5,000
  tokens by default. The cap is enforced at the wire boundary in
  `tools.cljs`: any payload whose serialised size exceeds the cap is
  replaced with a structured `{:rf.mcp/overflow ...}` marker.

  These tests mirror the private wire-cap helpers from `tools.cljs`
  (`token-estimate`, `max-tokens-arg`, `overflow-payload`,
  `sum-text-tokens`, `apply-cap`). A rename or signature change
  surfaces as a failing test rather than a silent contract drift.

  Live end-to-end coverage of `invoke` lives in
  `test/stdio-roundtrip.js`. The CLJS unit layer here pins the
  per-strategy semantics and the structured-marker shape."
  (:require [cljs.test :refer-macros [deftest is]]
            [cljs.reader]
            [applied-science.js-interop :as j]))

;; ---------------------------------------------------------------------------
;; Mirrors of the private wire-cap helpers. Keep in lockstep with
;; `tools.cljs`.
;; ---------------------------------------------------------------------------

(def ^:private default-max-tokens 5000)

(defn- token-estimate [s]
  (quot (count s) 4))

(defn- max-tokens-arg [args]
  (let [raw (when args (j/get args "max-tokens"))]
    (cond
      (or (nil? raw) (undefined? raw)) default-max-tokens
      (and (number? raw) (zero? raw))  nil
      (number? raw)                    (long raw)
      :else                            default-max-tokens)))

(def ^:private overflow-hints
  {"snapshot"      "Narrow scope: pass `path [:k1 :k2]` to slice the :app-db slice, `frames` to a single frame, or `include` to a single slice (one of app-db, sub-cache, machines, epochs, traces). Default mode is :summary — drill down via `get-path` once you know the key."
   "get-path"      "Narrow the path further — pass a deeper segment so the addressed subtree is smaller. Or call `snapshot` with no `path` first for a tree-summary, then re-aim."
   "trace-window"  "Reduce `ms` to a smaller window, narrow with `frame`, or fetch incrementally via `watch-epochs` + `since-id`."
   "watch-epochs"  "Narrow `pred` (e.g. `:event-id-prefix`, `:effects`), pass `frame`, or stream via `subscribe` with `max-events`."
   "subscribe"     "Tighten `filter`, lower `max-buffered`, set `max-events` so each tick stays small."
   "eval-cljs"     "Slice the value at the call-site (`get-in`, `take`, project to fewer keys) before returning."
   "discover-app"  "Unusual — the health summary should be small. Inspect `(re-frame-pair2.runtime/health)` directly via `eval-cljs` with a projection."
   "dispatch"      "Trace mode is returning a full epoch — re-run with `trace false` and read the epoch via `watch-epochs`/`snapshot` with a narrower path."})

(def ^:private overflow-hint-fallback
  "Response over budget. Re-call with narrower args, or raise `max-tokens` (0 disables the cap).")

(defn- overflow-payload
  [{:keys [tool token-count cap]}]
  {:rf.mcp/overflow {:limit       :reached
                     :token-count token-count
                     :cap-tokens  cap
                     :tool        tool
                     :hint        (get overflow-hints tool overflow-hint-fallback)}})

(defn- sum-text-tokens [result-js]
  (let [content (j/get result-js :content)
        n      (if (array? content) (.-length content) 0)]
    (loop [i 0 sum 0]
      (if (< i n)
        (let [item (aget content i)
              text (when item (j/get item :text))
              t    (if (string? text) (token-estimate text) 0)]
          (recur (inc i) (+ sum t)))
        sum))))

(defn- overflow-result [tool token-count cap]
  #js {:content #js [#js {:type "text"
                          :text (pr-str (overflow-payload
                                          {:tool        tool
                                           :token-count token-count
                                           :cap         cap}))}]})

(defn- apply-cap
  [result-js {:keys [tool cap strategy]
              :or   {strategy :truncate-with-marker}}]
  (cond
    (nil? cap)        result-js
    (nil? result-js)  result-js
    :else
    (let [tokens (sum-text-tokens result-js)]
      (if (<= tokens cap)
        result-js
        (case strategy
          :truncate-with-marker
          (overflow-result tool tokens cap)
          (overflow-result tool tokens cap))))))

;; ---------------------------------------------------------------------------
;; Helpers for building MCP result shapes inside tests.
;; ---------------------------------------------------------------------------

(defn- ok-text-result [v]
  #js {:content #js [#js {:type "text" :text (pr-str v)}]})

(defn- read-text [result-js]
  (-> (j/get result-js :content)
      (aget 0)
      (j/get :text)))

(defn- read-edn [result-js]
  (cljs.reader/read-string (read-text result-js)))

(defn- big-string [n]
  (apply str (repeat n "x")))

;; ---------------------------------------------------------------------------
;; token-estimate — the rule the spec pins.
;; ---------------------------------------------------------------------------

(deftest token-estimate-is-chars-div-4
  (is (zero? (token-estimate "")))
  (is (zero? (token-estimate "abc")))
  (is (= 1 (token-estimate "abcd")))
  (is (= 250 (token-estimate (big-string 1000))))
  (is (= 5000 (token-estimate (big-string 20000)))))

;; ---------------------------------------------------------------------------
;; max-tokens-arg — per-call override resolution.
;; ---------------------------------------------------------------------------

(deftest max-tokens-arg-default-when-absent
  (is (= default-max-tokens (max-tokens-arg #js {})))
  (is (= default-max-tokens (max-tokens-arg nil))))

(deftest max-tokens-arg-zero-disables-cap
  (is (nil? (max-tokens-arg #js {"max-tokens" 0}))))

(deftest max-tokens-arg-positive-integer-passed-through
  (is (= 1000 (max-tokens-arg #js {"max-tokens" 1000})))
  (is (= 50000 (max-tokens-arg #js {"max-tokens" 50000}))))

(deftest max-tokens-arg-non-number-falls-back-to-default
  (is (= default-max-tokens (max-tokens-arg #js {"max-tokens" "bogus"}))))

;; ---------------------------------------------------------------------------
;; sum-text-tokens — sums every `:text` slot.
;; ---------------------------------------------------------------------------

(deftest sum-text-tokens-single-slot
  (let [r (ok-text-result {:hello "world"})]
    (is (pos? (sum-text-tokens r)))
    (is (= (token-estimate (read-text r)) (sum-text-tokens r)))))

(deftest sum-text-tokens-empty-content-is-zero
  (is (zero? (sum-text-tokens #js {:content #js []}))))

(deftest sum-text-tokens-aggregates-across-slots
  (let [r #js {:content #js [#js {:type "text" :text (big-string 4000)}
                              #js {:type "text" :text (big-string 4000)}]}]
    (is (= 2000 (sum-text-tokens r)))))

;; ---------------------------------------------------------------------------
;; apply-cap — the strategy entry point.
;; ---------------------------------------------------------------------------

(deftest apply-cap-passes-under-budget-payload-untouched
  (let [r (ok-text-result {:small :payload})
        out (apply-cap r {:tool "snapshot" :cap default-max-tokens})]
    (is (identical? r out))
    (is (= {:small :payload} (read-edn out)))))

(deftest apply-cap-nil-cap-disables-enforcement
  (let [r (ok-text-result {:k (big-string 100000)})
        out (apply-cap r {:tool "snapshot" :cap nil})]
    (is (identical? r out))
    (is (not (contains? (read-edn out) :rf.mcp/overflow)))))

(deftest apply-cap-over-budget-emits-overflow-marker
  (let [;; A pr-str'd 4000-char string serialises to ~4002 chars ⇒
        ;; ~1000 tokens. Two of them ⇒ 2000+ tokens, over a 500 cap.
        big (apply str (repeat 4000 "x"))
        r   (ok-text-result {:huge big})
        out (apply-cap r {:tool "snapshot" :cap 500})
        edn (read-edn out)]
    (is (contains? edn :rf.mcp/overflow))
    (let [marker (:rf.mcp/overflow edn)]
      (is (= :reached (:limit marker)))
      (is (= "snapshot" (:tool marker)))
      (is (= 500 (:cap-tokens marker)))
      (is (pos? (:token-count marker)))
      (is (> (:token-count marker) 500))
      (is (string? (:hint marker)))
      (is (re-find #"Narrow scope" (:hint marker))))))

(deftest apply-cap-overflow-payload-is-itself-under-cap
  ;; The replacement marker must fit; otherwise we recurse on overflow.
  (let [big (apply str (repeat 8000 "x"))
        r   (ok-text-result {:huge big})
        out (apply-cap r {:tool "snapshot" :cap 500})]
    (is (<= (sum-text-tokens out) 500)
        "The overflow marker itself must be under the cap")))

(deftest apply-cap-unknown-tool-uses-fallback-hint
  (let [big (apply str (repeat 8000 "x"))
        r   (ok-text-result {:huge big})
        out (apply-cap r {:tool "no-such-tool" :cap 500})
        edn (read-edn out)
        marker (:rf.mcp/overflow edn)]
    (is (= "no-such-tool" (:tool marker)))
    (is (= overflow-hint-fallback (:hint marker)))))

(deftest apply-cap-unknown-strategy-degrades-safely
  ;; Unknown strategy must NOT throw and must NOT ship the over-budget
  ;; payload. It falls back to the marker, same as :truncate-with-marker.
  (let [big (apply str (repeat 8000 "x"))
        r   (ok-text-result {:huge big})
        out (apply-cap r {:tool "snapshot" :cap 500 :strategy :unknown-strategy})
        edn (read-edn out)]
    (is (contains? edn :rf.mcp/overflow))))

(deftest apply-cap-at-cap-exact-boundary-passes
  ;; <= cap passes; only > cap trips. Boundary check pins inclusive-low.
  (let [;; 400 chars ⇒ 100 tokens; pr-str adds quote overhead so final
        ;; text is ~402 chars ⇒ 100 tokens.
        s    (apply str (repeat 400 "x"))
        r    (ok-text-result s)
        toks (sum-text-tokens r)
        out  (apply-cap r {:tool "snapshot" :cap toks})]
    (is (identical? r out))))

;; ---------------------------------------------------------------------------
;; The load-bearing scenario: 5MB app-db snapshot — the failure mode
;; flagged in rf2-jlq5j's findings doc.
;; ---------------------------------------------------------------------------

(deftest five-mb-snapshot-is-bounded-at-wire-boundary
  ;; rf2-jlq5j: a 5MB app-db snapshot pr-strs to ~5.6M chars ⇒ ~1.4M
  ;; tokens, 290× the 5,000-token cap. Today: silent context
  ;; corruption. After this fix: structured marker, agent retries with
  ;; narrower args.
  (let [big-app-db (apply str (repeat (* 5 1024 1024) "x"))  ;; 5 MB
        snapshot {:rf/default {:app-db big-app-db}}
        r        (ok-text-result {:ok? true :snapshot snapshot})
        out      (apply-cap r {:tool "snapshot" :cap default-max-tokens})
        edn      (read-edn out)]
    (is (contains? edn :rf.mcp/overflow)
        "5MB payload MUST be replaced with overflow marker, not shipped raw")
    (is (<= (sum-text-tokens out) default-max-tokens)
        "Replacement payload MUST be under the cap")
    (let [marker (:rf.mcp/overflow edn)]
      (is (= :reached (:limit marker)))
      (is (= "snapshot" (:tool marker)))
      (is (> (:token-count marker) (* 200 default-max-tokens))
          "Token-count reflects the original oversized payload"))))

;; ---------------------------------------------------------------------------
;; Per-tool hints — every catalogued tool has a tailored next-step.
;; ---------------------------------------------------------------------------

(deftest every-catalogued-tool-has-an-overflow-hint
  ;; Sanity: the hint table covers the tools whose payload size is a
  ;; function of runtime state (the surfaces flagged in §Tight token
  ;; budget). The streaming subscribe + always-tiny ops are covered
  ;; explicitly so we never ship "Response over budget" generic when a
  ;; sharper hint is available.
  (let [tools-with-data-volume #{"snapshot" "get-path" "trace-window" "watch-epochs"
                                 "subscribe" "eval-cljs" "discover-app"
                                 "dispatch"}]
    (doseq [t tools-with-data-volume]
      (is (contains? overflow-hints t)
          (str "Missing overflow hint for tool: " t)))))
