(ns re-frame2-pair-mcp.wire-cap-test
  "Unit tests for the wire-boundary token-budget cap.

  Per `tools/re-frame2-pair-mcp/spec/Principles.md` §\"Tight token budget per
  response\", every MCP `tools/call` response is bounded at ~5,000
  tokens by default. The cap is enforced at the wire boundary in
  `tools/cap.cljs`: any payload whose serialised size exceeds the cap
  is replaced with a structured `{:rf.mcp/overflow ...}` marker.

  Tests pin the public helpers directly from
  `re-frame2-pair-mcp.tools.cap`: `max-tokens-arg`, `overflow-payload`,
  `sum-payload-tokens`, `apply-cap`, `overflow-hints`, `default-max-tokens`.
  The two test-only helpers `token-estimate` and `overflow-hint-fallback`
  live in `re-frame2-pair-mcp.test-utils`; the token rule itself is
  mcp-base's own (`overflow/token-estimate`) and its suite pins it. A rename or signature change surfaces as a failing test rather
  than a silent contract drift.

  Live end-to-end coverage of `invoke` lives in
  `test/stdio-roundtrip.js`. The CLJS unit layer here pins the
  per-strategy semantics and the structured-marker shape."
  (:require [cljs.test :refer-macros [deftest is]]
            [cljs.reader]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.cap :as cap]))

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
;; max-tokens-arg — per-call override resolution.
;; ---------------------------------------------------------------------------

(deftest max-tokens-arg-resolution
  (doseq [[args expected note]
          [[#js {} cap/default-max-tokens "absent ⇒ the default cap"]
           [nil cap/default-max-tokens "no args ⇒ the default cap"]
           [#js {"max-tokens" 0} nil "0 disables the cap"]
           [#js {"max-tokens" 1000} 1000 "a positive integer passes through"]
           [#js {"max-tokens" 50000} 50000 "a positive integer passes through"]
           [#js {"max-tokens" "bogus"} cap/default-max-tokens
            "a non-number falls back to the default cap"]]]
    (is (= expected (cap/max-tokens-arg args)) note)))

;; ---------------------------------------------------------------------------
;; sum-payload-tokens — sums every `:text` slot.
;; ---------------------------------------------------------------------------

(deftest sum-payload-tokens-empty-content-is-zero
  (is (zero? (cap/sum-payload-tokens #js {:content #js []}))))

(deftest sum-payload-tokens-aggregates-across-slots
  (let [r #js {:content #js [#js {:type "text" :text (big-string 4000)}
                              #js {:type "text" :text (big-string 4000)}]}]
    (is (= 2000 (cap/sum-payload-tokens r)))))

;; ---------------------------------------------------------------------------
;; apply-cap — the strategy entry point.
;; ---------------------------------------------------------------------------

(deftest apply-cap-nil-cap-disables-enforcement
  (let [r (ok-text-result {:k (big-string 100000)})
        out (cap/apply-cap r {:tool "snapshot" :cap nil})]
    (is (identical? r out))
    (is (not (contains? (read-edn out) :rf.mcp/overflow)))))

(deftest apply-cap-over-budget-emits-overflow-marker
  (let [;; A pr-str'd 4000-char string serialises to ~4002 chars ⇒
        ;; ~1000 tokens, over a 500 cap.
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

(deftest apply-cap-overflow-marker-key-keeps-namespace-in-structured-slot
  ;; The overflow marker is built OUTSIDE the per-tool callbacks
  ;; (cap/result-io build-overflow-result) and routes through
  ;; `wire/result`, so SDK-friendly hosts reading structuredContent see
  ;; the fully-qualified marker key. A raw namespace-lossy `clj->js`
  ;; would truncate it to `"overflow"` and the host would miss the marker.
  (let [big (apply str (repeat 4000 "x"))
        r   (ok-text-result {:huge big})
        out (cap/apply-cap r {:tool "snapshot" :cap 500})
        sc  (j/get out :structuredContent)]
    (is (some? sc) "the overflow marker carries a structuredContent slot")
    (is (some? (j/get sc "rf.mcp/overflow"))
        "the marker serialises to the fully-qualified \"rf.mcp/overflow\" key")
    (is (nil? (j/get sc "overflow"))
        "the namespace-truncated \"overflow\" key must NOT appear (the namespace-lossy shape)")))

(deftest apply-cap-over-budget-error-keeps-is-error
  ;; `apply-cap` wraps an over-budget `:isError` result
  ;; like any other payload, and the replacement must keep `isError: true`.
  ;; Without it the marker is byte-for-byte what an over-cap success
  ;; returns, so the agent cannot tell the call failed.
  (let [big     (big-string 4000)
        err     #js {:isError true
                     :content #js [#js {:type "text" :text (pr-str {:ok? false :huge big})}]}
        err-out (cap/apply-cap err {:tool "snapshot" :cap 500})
        ok-out  (cap/apply-cap (ok-text-result {:huge big}) {:tool "snapshot" :cap 500})]
    (is (contains? (read-edn err-out) :rf.mcp/overflow)
        "the over-budget failure is replaced by the overflow marker")
    (is (true? (j/get err-out :isError))
        "an over-cap failure must not read as an over-cap success")
    (is (contains? (read-edn ok-out) :rf.mcp/overflow))
    (is (not (true? (j/get ok-out :isError)))
        "control: an over-cap success stays non-error")))

(deftest apply-cap-unknown-tool-uses-fallback-hint
  (let [big (apply str (repeat 8000 "x"))
        r   (ok-text-result {:huge big})
        out (cap/apply-cap r {:tool "no-such-tool" :cap 500})
        edn (read-edn out)
        marker (:rf.mcp/overflow edn)]
    (is (= "no-such-tool" (:tool marker)))
    (is (= tu/overflow-hint-fallback (:hint marker)))))

;; ---------------------------------------------------------------------------
;; :structuredContent counts toward the cap.
;;
;; `wire/ok-text` / `wire/err-text` write the SAME payload into BOTH
;; `:content[*].text` (pr-str EDN) and `:structuredContent` (clj->js JSON
;; projection) on EVERY result. Both ride the wire. The cap MUST size
;; the structured slot too — a small-:content / huge-:structuredContent
;; response that the text gate alone judges under-budget would otherwise
;; bust the MCP token budget. The mcp-base contract pins this class
;; (`structured-content-counted-toward-budget`).
;; ---------------------------------------------------------------------------

(defn- dual-coded-result
  "Build an MCP result in the real `wire/ok-text` dual-coded shape: a
  `:content[*].text` slot AND a `:structuredContent` JS object. `text-v`
  drives the EDN text slot; `structured-v` is clj->js'd into the
  structured slot (the npm-SDK JSON body)."
  [text-v structured-v]
  #js {:content          #js [#js {:type "text" :text (pr-str text-v)}]
       :structuredContent (clj->js structured-v)})

(deftest apply-cap-trips-on-huge-structured-content-under-small-text
  ;; THE load-bearing case: a response whose `:content` text is tiny but
  ;; whose `:structuredContent` is huge MUST trip the overflow marker.
  ;; Without counting the structured slot the text-only sum stays under
  ;; cap and the raw oversize body would ship; counting it trips the cap.
  (let [r   (dual-coded-result {:ok? true} {:big-payload (big-string 30000)})
        out (cap/apply-cap r {:tool "snapshot" :cap 1000})
        edn (read-edn out)]
    (is (contains? edn :rf.mcp/overflow)
        "huge :structuredContent over budget MUST be replaced with the overflow marker")
    (let [marker (:rf.mcp/overflow edn)]
      (is (= :reached (:limit marker)))
      (is (= "snapshot" (:tool marker)))
      (is (= 1000 (:cap-tokens marker)))
      (is (> (:token-count marker) 1000)
          "token-count reflects the structured-slot bytes the text gate alone would miss"))
    (is (<= (cap/sum-payload-tokens out) 1000)
        "the overflow replacement itself stays under cap")))

(deftest apply-cap-passes-small-dual-coded-payload-untouched
  ;; Negative: a dual-coded result whose BOTH slots are small passes
  ;; through unchanged — the structured-slot accounting must not
  ;; over-trip a genuinely small payload.
  (let [r   (dual-coded-result {:ok? true :v 1} {:ok? true :v 1})
        out (cap/apply-cap r {:tool "snapshot" :cap cap/default-max-tokens})]
    (is (identical? r out))))

;; ---------------------------------------------------------------------------
;; Per-tool hints — every catalogued tool has a tailored next-step.
;; ---------------------------------------------------------------------------

(deftest every-catalogued-tool-has-an-overflow-hint
  ;; Sanity: the hint table covers the tools whose payload size is a
  ;; function of runtime state (the surfaces flagged in §Tight token
  ;; budget), so we never ship "Response over budget" generic when a
  ;; sharper hint is available.
  (let [tools-with-data-volume #{"snapshot" "get-path" "trace-window" "watch-epochs"
                                 "eval-cljs" "discover-app"
                                 "dispatch"}]
    (doseq [t tools-with-data-volume]
      (is (contains? cap/overflow-hints t)
          (str "Missing overflow hint for tool: " t)))))

;; ---------------------------------------------------------------------------
;; apply-cap short-circuits on wire-bounded markers.
;;
;; Cache-hit and overflow envelopes are emitted by the cache + cap
;; steps themselves; they are sub-cap by construction. Re-applying
;; the token walk to a marker is wasted work — and worse, if the
;; cap tripped on a marker (it cannot while the cap exceeds the
;; marker's size, but a lower cap would allow it), the result would
;; be an overflow OF an overflow.
;; ---------------------------------------------------------------------------

(deftest apply-cap-short-circuits-on-wire-bounded-markers
  ;; A result that LOOKS like a cache-hit or overflow marker passes
  ;; through identical, regardless of cap — no overflow of an overflow.
  (doseq [marker [{:rf.mcp/cache-hit {:hash 42 :unchanged-since 0
                                      :tool "snapshot" :via :result-hash
                                      :hint "..."}}
                  {:rf.mcp/overflow {:limit :reached :tool "snapshot"
                                     :cap-tokens 100 :token-count 200
                                     :hint "..."}}]]
    (let [r   (ok-text-result marker)
          out (cap/apply-cap r {:tool "snapshot" :cap 1})]
      (is (identical? r out)
          (str (ffirst marker) " passes through unchanged even under a 1-token cap")))))

(deftest apply-cap-caps-over-budget-lookalike-marker-key
  ;; Regression guard: the marker detector matches on the EXACT marker
  ;; key, not a prefix. An over-budget payload whose LEADING key merely
  ;; STARTS WITH a marker key — e.g. `:rf.mcp/overflowed` — is NOT a
  ;; marker, so apply-cap still walks it and replaces the over-budget
  ;; body with the real `:rf.mcp/overflow` marker. A prefix match would
  ;; wrongly short-circuit PAST the cap walk and ship the raw body.
  (let [big   (apply str (repeat 8000 "x"))
        ;; Single-key map ⇒ pr-str renders `:rf.mcp/overflowed` as the
        ;; leading top-level key, the precise cap-bypass shape.
        r     (ok-text-result {:rf.mcp/overflowed {:huge big}})
        out   (cap/apply-cap r {:tool "snapshot" :cap 500})
        edn   (read-edn out)]
    (is (contains? edn :rf.mcp/overflow)
        "over-budget lookalike-keyed payload MUST be capped, not short-circuited")
    (is (not (contains? edn :rf.mcp/overflowed))
        "the raw over-budget lookalike body must NOT ride the wire")
    (is (<= (cap/sum-payload-tokens out) 500)
        "the overflow replacement itself stays under cap")))
