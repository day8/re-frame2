(ns re-frame-pair2-mcp.wire-cap-test
  "Unit tests for the wire-boundary token-budget cap (rf2-rvyzy).

  Per `tools/pair2-mcp/spec/Principles.md` §\"Tight token budget per
  response\", every MCP `tools/call` response is bounded at ~5,000
  tokens by default. The cap is enforced at the wire boundary in
  `tools/cap.cljs`: any payload whose serialised size exceeds the cap
  is replaced with a structured `{:rf.mcp/overflow ...}` marker.

  Tests pin the public helpers directly from
  `re-frame-pair2-mcp.tools.cap`: `token-estimate`, `max-tokens-arg`,
  `overflow-payload`, `sum-text-tokens`, `apply-cap`,
  `overflow-hints`, `overflow-hint-fallback`, `default-max-tokens`.
  A rename or signature change surfaces as a failing test rather
  than a silent contract drift.

  Live end-to-end coverage of `invoke` lives in
  `test/stdio-roundtrip.js`. The CLJS unit layer here pins the
  per-strategy semantics and the structured-marker shape."
  (:require [cljs.test :refer-macros [deftest is]]
            [cljs.reader]
            [applied-science.js-interop :as j]
            [re-frame-pair2-mcp.tools.cap :as cap]))

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
  (is (zero? (cap/token-estimate "")))
  (is (zero? (cap/token-estimate "abc")))
  (is (= 1 (cap/token-estimate "abcd")))
  (is (= 250 (cap/token-estimate (big-string 1000))))
  (is (= 5000 (cap/token-estimate (big-string 20000)))))

;; ---------------------------------------------------------------------------
;; max-tokens-arg — per-call override resolution.
;; ---------------------------------------------------------------------------

(deftest max-tokens-arg-default-when-absent
  (is (= cap/default-max-tokens (cap/max-tokens-arg #js {})))
  (is (= cap/default-max-tokens (cap/max-tokens-arg nil))))

(deftest max-tokens-arg-zero-disables-cap
  (is (nil? (cap/max-tokens-arg #js {"max-tokens" 0}))))

(deftest max-tokens-arg-positive-integer-passed-through
  (is (= 1000 (cap/max-tokens-arg #js {"max-tokens" 1000})))
  (is (= 50000 (cap/max-tokens-arg #js {"max-tokens" 50000}))))

(deftest max-tokens-arg-non-number-falls-back-to-default
  (is (= cap/default-max-tokens (cap/max-tokens-arg #js {"max-tokens" "bogus"}))))

;; ---------------------------------------------------------------------------
;; sum-text-tokens — sums every `:text` slot.
;; ---------------------------------------------------------------------------

(deftest sum-text-tokens-single-slot
  (let [r (ok-text-result {:hello "world"})]
    (is (pos? (cap/sum-text-tokens r)))
    (is (= (cap/token-estimate (read-text r)) (cap/sum-text-tokens r)))))

(deftest sum-text-tokens-empty-content-is-zero
  (is (zero? (cap/sum-text-tokens #js {:content #js []}))))

(deftest sum-text-tokens-aggregates-across-slots
  (let [r #js {:content #js [#js {:type "text" :text (big-string 4000)}
                              #js {:type "text" :text (big-string 4000)}]}]
    (is (= 2000 (cap/sum-text-tokens r)))))

;; ---------------------------------------------------------------------------
;; apply-cap — the strategy entry point.
;; ---------------------------------------------------------------------------

(deftest apply-cap-passes-under-budget-payload-untouched
  (let [r (ok-text-result {:small :payload})
        out (cap/apply-cap r {:tool "snapshot" :cap cap/default-max-tokens})]
    (is (identical? r out))
    (is (= {:small :payload} (read-edn out)))))

(deftest apply-cap-nil-cap-disables-enforcement
  (let [r (ok-text-result {:k (big-string 100000)})
        out (cap/apply-cap r {:tool "snapshot" :cap nil})]
    (is (identical? r out))
    (is (not (contains? (read-edn out) :rf.mcp/overflow)))))

(deftest apply-cap-over-budget-emits-overflow-marker
  (let [;; A pr-str'd 4000-char string serialises to ~4002 chars ⇒
        ;; ~1000 tokens. Two of them ⇒ 2000+ tokens, over a 500 cap.
        big (apply str (repeat 4000 "x"))
        r   (ok-text-result {:huge big})
        out (cap/apply-cap r {:tool "snapshot" :cap 500})
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
        out (cap/apply-cap r {:tool "snapshot" :cap 500})]
    (is (<= (cap/sum-text-tokens out) 500)
        "The overflow marker itself must be under the cap")))

(deftest apply-cap-unknown-tool-uses-fallback-hint
  (let [big (apply str (repeat 8000 "x"))
        r   (ok-text-result {:huge big})
        out (cap/apply-cap r {:tool "no-such-tool" :cap 500})
        edn (read-edn out)
        marker (:rf.mcp/overflow edn)]
    (is (= "no-such-tool" (:tool marker)))
    (is (= cap/overflow-hint-fallback (:hint marker)))))

(deftest apply-cap-unknown-strategy-degrades-safely
  ;; Unknown strategy must NOT throw and must NOT ship the over-budget
  ;; payload. It falls back to the marker, same as :truncate-with-marker.
  (let [big (apply str (repeat 8000 "x"))
        r   (ok-text-result {:huge big})
        out (cap/apply-cap r {:tool "snapshot" :cap 500 :strategy :unknown-strategy})
        edn (read-edn out)]
    (is (contains? edn :rf.mcp/overflow))))

(deftest apply-cap-at-cap-exact-boundary-passes
  ;; <= cap passes; only > cap trips. Boundary check pins inclusive-low.
  (let [;; 400 chars ⇒ 100 tokens; pr-str adds quote overhead so final
        ;; text is ~402 chars ⇒ 100 tokens.
        s    (apply str (repeat 400 "x"))
        r    (ok-text-result s)
        toks (cap/sum-text-tokens r)
        out  (cap/apply-cap r {:tool "snapshot" :cap toks})]
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
        out      (cap/apply-cap r {:tool "snapshot" :cap cap/default-max-tokens})
        edn      (read-edn out)]
    (is (contains? edn :rf.mcp/overflow)
        "5MB payload MUST be replaced with overflow marker, not shipped raw")
    (is (<= (cap/sum-text-tokens out) cap/default-max-tokens)
        "Replacement payload MUST be under the cap")
    (let [marker (:rf.mcp/overflow edn)]
      (is (= :reached (:limit marker)))
      (is (= "snapshot" (:tool marker)))
      (is (> (:token-count marker) (* 200 cap/default-max-tokens))
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
      (is (contains? cap/overflow-hints t)
          (str "Missing overflow hint for tool: " t)))))

;; ---------------------------------------------------------------------------
;; apply-cap short-circuits on wire-bounded markers (rf2-gktyn).
;;
;; Cache-hit and overflow envelopes are emitted by the cache + cap
;; steps themselves; they are sub-cap by construction. Re-applying
;; the token walk to a marker is wasted work — and worse, if the
;; cap somehow tripped on a marker (it can't today, but a regression
;; could lower the cap below the marker's size), the result would
;; be an overflow OF an overflow.
;; ---------------------------------------------------------------------------

(deftest apply-cap-short-circuits-on-cache-hit-marker
  ;; Construct a result that LOOKS like a cache-hit marker. apply-cap
  ;; must pass it through identical, regardless of cap.
  (let [marker  {:rf.mcp/cache-hit {:hash 42 :unchanged-since 0
                                     :tool "snapshot" :via :result-hash
                                     :hint "..."}}
        r       (ok-text-result marker)
        out     (cap/apply-cap r {:tool "snapshot" :cap 1})]
    (is (identical? r out)
        "marker passes through unchanged even under a 1-token cap")))

(deftest apply-cap-short-circuits-on-overflow-marker
  (let [marker  {:rf.mcp/overflow {:limit :reached :tool "snapshot"
                                    :cap-tokens 100 :token-count 200
                                    :hint "..."}}
        r       (ok-text-result marker)
        out     (cap/apply-cap r {:tool "snapshot" :cap 1})]
    (is (identical? r out)
        "overflow marker passes through unchanged — no recursion")))

(deftest apply-cap-runs-walk-on-non-marker-payloads
  ;; Negative — a normal map that doesn't open with the marker
  ;; namespace MUST be subject to the cap walk.
  (let [big   (apply str (repeat 8000 "x"))
        r     (ok-text-result {:huge big})
        out   (cap/apply-cap r {:tool "snapshot" :cap 500})
        edn   (read-edn out)]
    (is (contains? edn :rf.mcp/overflow)
        "non-marker payload over budget is still capped")))
