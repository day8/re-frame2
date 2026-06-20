(ns re-frame2-pair-mcp.wire-cap-test
  "Unit tests for the wire-boundary token-budget cap (rf2-rvyzy).

  Per `tools/re-frame2-pair-mcp/spec/Principles.md` §\"Tight token budget per
  response\", every MCP `tools/call` response is bounded at ~5,000
  tokens by default. The cap is enforced at the wire boundary in
  `tools/cap.cljs`: any payload whose serialised size exceeds the cap
  is replaced with a structured `{:rf.mcp/overflow ...}` marker.

  Tests pin the public helpers directly from
  `re-frame2-pair-mcp.tools.cap`: `max-tokens-arg`, `overflow-payload`,
  `sum-payload-tokens`, `apply-cap`, `overflow-hints`, `default-max-tokens`.
  The two cross-MCP re-exports that production never used —
  `token-estimate` and `overflow-hint-fallback` — moved to
  `re-frame2-pair-mcp.test-utils` (rf2-ttspi7) and are pinned via `tu/`
  here. A rename or signature change surfaces as a failing test rather
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
;; token-estimate — the rule the spec pins.
;; ---------------------------------------------------------------------------

(deftest token-estimate-is-chars-div-4
  (is (zero? (tu/token-estimate "")))
  (is (zero? (tu/token-estimate "abc")))
  (is (= 1 (tu/token-estimate "abcd")))
  (is (= 250 (tu/token-estimate (big-string 1000))))
  (is (= 5000 (tu/token-estimate (big-string 20000)))))

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

(deftest max-tokens-arg-negative-rejected-with-invalid-arg
  ;; rf2-5rdit — a negative `:max-tokens` resolves to an
  ;; `{:rf.mcp/invalid-arg {...}}` rejection, NOT a negative cap (which
  ;; would over-trip apply-cap and lock the agent out). The `invoke`
  ;; chokepoint surfaces it as an isError result via `wire/err-text`.
  (let [out (cap/max-tokens-arg #js {"max-tokens" -1})]
    (is (cap/invalid-arg? out)
        "negative max-tokens-arg is a rejection, not a negative cap")
    (is (not (number? out)) "rejection is a marker map, not a (negative) cap")
    (let [body (get out :rf.mcp/invalid-arg)]
      (is (= :max-tokens (:arg body)))
      (is (= -1 (:value body)))
      (is (re-find #"(?i)0 disables" (:hint body)))))
  (is (cap/invalid-arg? (cap/max-tokens-arg #js {"max-tokens" -5}))))

(deftest invalid-arg?-discriminates
  (is (cap/invalid-arg? (cap/max-tokens-arg #js {"max-tokens" -1})))
  (is (not (cap/invalid-arg? (cap/max-tokens-arg #js {"max-tokens" 0}))))
  (is (not (cap/invalid-arg? (cap/max-tokens-arg #js {"max-tokens" 100}))))
  (is (not (cap/invalid-arg? (cap/max-tokens-arg #js {}))))
  (is (not (cap/invalid-arg? nil))))

;; ---------------------------------------------------------------------------
;; sum-payload-tokens — sums every `:text` slot.
;; ---------------------------------------------------------------------------

(deftest sum-payload-tokens-single-slot
  (let [r (ok-text-result {:hello "world"})]
    (is (pos? (cap/sum-payload-tokens r)))
    (is (= (tu/token-estimate (read-text r)) (cap/sum-payload-tokens r)))))

(deftest sum-payload-tokens-empty-content-is-zero
  (is (zero? (cap/sum-payload-tokens #js {:content #js []}))))

(deftest sum-payload-tokens-aggregates-across-slots
  (let [r #js {:content #js [#js {:type "text" :text (big-string 4000)}
                              #js {:type "text" :text (big-string 4000)}]}]
    (is (= 2000 (cap/sum-payload-tokens r)))))

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

(deftest apply-cap-overflow-marker-key-keeps-namespace-in-structured-slot
  ;; rf2-or8s29 — the overflow marker is built OUTSIDE the per-tool
  ;; callbacks (cap/result-io build-overflow-result); before the fix it
  ;; used a raw namespace-lossy `clj->js`, so SDK-friendly hosts reading
  ;; structuredContent saw `"overflow"` (the marker key truncated) and
  ;; missed the marker. Now it routes through `wire/result`.
  (let [big (apply str (repeat 4000 "x"))
        r   (ok-text-result {:huge big})
        out (cap/apply-cap r {:tool "snapshot" :cap 500})
        sc  (j/get out :structuredContent)]
    (is (some? sc) "the overflow marker carries a structuredContent slot")
    (is (some? (j/get sc "rf.mcp/overflow"))
        "the marker serialises to the fully-qualified \"rf.mcp/overflow\" key")
    (is (nil? (j/get sc "overflow"))
        "the namespace-truncated \"overflow\" key must NOT appear (the lossy old shape)")))

(deftest apply-cap-overflow-payload-is-itself-under-cap
  ;; The replacement marker must fit; otherwise we recurse on overflow.
  (let [big (apply str (repeat 8000 "x"))
        r   (ok-text-result {:huge big})
        out (cap/apply-cap r {:tool "snapshot" :cap 500})]
    (is (<= (cap/sum-payload-tokens out) 500)
        "The overflow marker itself must be under the cap")))

(deftest apply-cap-unknown-tool-uses-fallback-hint
  (let [big (apply str (repeat 8000 "x"))
        r   (ok-text-result {:huge big})
        out (cap/apply-cap r {:tool "no-such-tool" :cap 500})
        edn (read-edn out)
        marker (:rf.mcp/overflow edn)]
    (is (= "no-such-tool" (:tool marker)))
    (is (= tu/overflow-hint-fallback (:hint marker)))))

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
        toks (cap/sum-payload-tokens r)
        out  (cap/apply-cap r {:tool "snapshot" :cap toks})]
    (is (identical? r out))))

;; ---------------------------------------------------------------------------
;; :structuredContent counts toward the cap (rf2-ycfu1).
;;
;; `wire/ok-text` / `wire/err-text` write the SAME payload into BOTH
;; `:content[*].text` (pr-str EDN) and `:structuredContent` (clj->js JSON
;; projection) on EVERY result. Both ride the wire. The cap MUST size
;; the structured slot too — a small-:content / huge-:structuredContent
;; response that the text gate alone judges under-budget would otherwise
;; bust the MCP token budget. story-mcp already fixed this class
;; (rf2-mzndx); the mcp-base contract pins it (rf2-13wbe finding 2,
;; `structured-content-counted-toward-budget`).
;; ---------------------------------------------------------------------------

(defn- dual-coded-result
  "Build an MCP result in the real `wire/ok-text` dual-coded shape: a
  `:content[*].text` slot AND a `:structuredContent` JS object. `text-v`
  drives the EDN text slot; `structured-v` is clj->js'd into the
  structured slot (the npm-SDK JSON body)."
  [text-v structured-v]
  #js {:content          #js [#js {:type "text" :text (pr-str text-v)}]
       :structuredContent (clj->js structured-v)})

(deftest sum-payload-tokens-counts-structured-content
  ;; Small text slot, large structuredContent. The token sum must reflect
  ;; the structured JSON bytes, not just the text slot.
  (let [r          (dual-coded-result {:ok? true} {:big-payload (big-string 30000)})
        text-only  (tu/token-estimate (read-text r))
        total      (cap/sum-payload-tokens r)]
    (is (> total (+ text-only 5000))
        "structuredContent JSON bytes MUST be summed alongside the text slot")))

(deftest apply-cap-trips-on-huge-structured-content-under-small-text
  ;; THE acceptance test (rf2-ycfu1): a response whose `:content` text is
  ;; tiny but whose `:structuredContent` is huge MUST trip the overflow
  ;; marker. FAILS before the cap.cljs fix (the structured slot was never
  ;; counted, so the text-only sum stayed under cap and the raw oversize
  ;; body shipped); PASSES after.
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
    (is (<= (cap/sum-payload-tokens out) cap/default-max-tokens)
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

(deftest apply-cap-caps-over-budget-lookalike-marker-key
  ;; rf2-3xd9i9 regression: the marker detector used to match on the
  ;; marker-key PREFIX, so an over-budget payload whose LEADING key merely
  ;; STARTS WITH a marker key — e.g. `:rf.mcp/overflowed` — was wrongly
  ;; treated as an already-bounded marker and short-circuited PAST the cap
  ;; walk, shipping the raw over-budget body. With the exact-key fix the
  ;; lookalike is NOT a marker, so apply-cap still walks it and replaces
  ;; the over-budget body with the real `:rf.mcp/overflow` marker.
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

;; ---------------------------------------------------------------------------
;; cap-message — per-notification wire-cap for a single serialised EDN
;; message string (rf2-wz66k7).
;;
;; The `subscribe` streaming loop ships its per-tick payload as the
;; `:message` of a `notifications/progress` notification — which NEVER
;; crosses the `invoke` chokepoint where `apply-cap` runs on `tools/call`
;; RESULTS. `cap-message` is the per-notification gate that closes that
;; bypass: the SAME two-stage budget + the SAME `:rf.mcp/overflow` marker
;; shape, applied to the one pre-serialised message string.
;; ---------------------------------------------------------------------------

(deftest cap-message-passes-under-budget-string-untouched
  (let [msg (pr-str {:sub-id "s" :events [{:id 1} {:id 2}]})
        out (cap/cap-message msg {:tool "subscribe" :cap cap/default-max-tokens})]
    (is (= msg out) "an under-budget progress message rides the wire verbatim")))

(deftest cap-message-nil-cap-disables-enforcement
  ;; `max-tokens 0` resolves to a nil cap — the caller opted out; even a
  ;; huge message passes through unchanged.
  (let [msg (pr-str {:events [(big-string 100000)]})
        out (cap/cap-message msg {:tool "subscribe" :cap nil})]
    (is (= msg out) "nil cap disables the per-notification gate")))

(deftest cap-message-nil-message-is-passthrough
  (is (nil? (cap/cap-message nil {:tool "subscribe" :cap 500}))))

(deftest cap-message-over-budget-emits-overflow-marker
  ;; THE Finding-1 acceptance: an oversized per-tick message is REPLACED
  ;; with the `:rf.mcp/overflow` marker EDN, not shipped raw. Before the
  ;; fix the progress notification bypassed the cap entirely and a
  ;; multi-megabyte tick busted the per-notification token budget.
  (let [big (apply str (repeat 8000 "x"))
        msg (pr-str {:sub-id "s" :events [{:huge big}]})
        out (cap/cap-message msg {:tool "subscribe" :cap 500})
        edn (cljs.reader/read-string out)]
    (is (contains? edn :rf.mcp/overflow)
        "over-budget progress message MUST become the overflow marker")
    (let [marker (:rf.mcp/overflow edn)]
      (is (= :reached (:limit marker)))
      (is (= "subscribe" (:tool marker)))
      (is (= 500 (:cap-tokens marker)))
      (is (> (:token-count marker) 500))
      (is (string? (:hint marker)))
      (is (re-find #"(?i)filter|max-events|max-buffered" (:hint marker))
          "the marker carries the subscribe per-tool next-step hint"))
    (is (<= (tu/token-estimate out) 500)
        "the overflow-marker message itself stays under the cap")))

(deftest cap-message-respects-custom-cap-override
  ;; A small custom cap trips on a payload the default 5k would pass.
  (let [msg (pr-str {:events (vec (range 400))})
        under-default (cap/cap-message msg {:tool "subscribe" :cap cap/default-max-tokens})
        over-tight    (cap/cap-message msg {:tool "subscribe" :cap 1})]
    (is (= msg under-default) "passes the generous default")
    (is (contains? (cljs.reader/read-string over-tight) :rf.mcp/overflow)
        "trips the tight per-call override")))
