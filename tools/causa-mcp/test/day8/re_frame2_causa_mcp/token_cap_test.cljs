(ns day8.re-frame2-causa-mcp.token-cap-test
  "Unit tests for the W-1 token-cap-with-overflow-marker at the
  Causa-MCP boundary (rf2-8xzoe.5). Pins:

    - MUST 3 — A tool that cannot answer inside the 5,000-token budget
      MUST trim, summarise, slice, paginate, or dedupe rather than
      over-spend (spec/004 §1 L74).
    - MUST 5 — Every tool that returns to the agent MUST measure the
      rendered payload (post-EDN-encoding, post-JSON-wrap) against the
      cap before returning (spec/004 §1 L115).
    - MUST 6 — A tool that would exceed the cap MUST NOT silently
      truncate (spec/004 §1 L121).
    - MUST 7 — A tool exceeding the cap MUST return the structured
      overflow marker at the top of the payload (spec/004 §1 L122)
      with the causa-specific shape: `:cap`, `:would-be`, `:hint`
      (keyword from `{:switch-mode :paginate :slice :narrow-filter}`),
      and optional `:continuation {:cursor … :next-args …}`.
    - Clamp `[500, 50000]` on `max-tokens` per spec/004 §1 L237.

  These tests are the load-bearing pin for the eighteen downstream
  tool beads — every dispatcher that returns to the agent runs its
  envelope through `token-cap/apply-cap` at the egress boundary; the
  contract here is the one those tools inherit."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa-mcp.token-cap :as token-cap]))

;; ---------------------------------------------------------------------------
;; Helpers — build / read the `#js {:content #js [...]}` result shape.
;; ---------------------------------------------------------------------------

(defn- mk-result
  "Build a single-text-slot JS result containing `s`."
  [s]
  #js {:content #js [#js {:type "text" :text s}]})

(defn- result-text
  "Read the text from a single-slot JS result."
  [r]
  (-> (j/get r :content) (aget 0) (j/get :text)))

(defn- result-edn
  "Read + EDN-parse the text from a single-slot JS result."
  [r]
  (edn/read-string (result-text r)))

;; ---------------------------------------------------------------------------
;; Public surface — vars exist and are callable.
;; ---------------------------------------------------------------------------

(deftest public-surface-resolvable
  (testing "W-1 lands the public surface downstream tool dispatchers
            will require at the egress boundary"
    (is (fn? token-cap/clamp-max-tokens))
    (is (fn? token-cap/max-tokens-arg))
    (is (fn? token-cap/hint-for-tool))
    (is (fn? token-cap/overflow-payload))
    (is (fn? token-cap/apply-cap))
    (is (= 5000  token-cap/default-max-tokens))
    (is (= 500   token-cap/min-max-tokens))
    (is (= 50000 token-cap/max-max-tokens))
    (is (= #{:switch-mode :paginate :slice :narrow-filter}
           token-cap/hint-vocabulary)
        "spec/004 §1 closed `:hint` vocabulary")
    (is (= :narrow-filter token-cap/default-hint)
        "default hint is the generic re-call-with-narrower-args hint")))

;; ---------------------------------------------------------------------------
;; clamp-max-tokens — `[500, 50000]` per spec/004 §1 L237.
;; ---------------------------------------------------------------------------

(deftest clamp-max-tokens-nil-defaults-to-5000
  (is (= 5000 (token-cap/clamp-max-tokens nil))
      "absent input collapses to default-max-tokens"))

(deftest clamp-max-tokens-non-number-defaults
  (is (= 5000 (token-cap/clamp-max-tokens "1000")))
  (is (= 5000 (token-cap/clamp-max-tokens :foo)))
  (is (= 5000 (token-cap/clamp-max-tokens {}))))

(deftest clamp-max-tokens-zero-collapses-to-default
  ;; Causa intentionally does NOT honour pair2-mcp's `0`-disables-cap
  ;; escape hatch. The spec is normative.
  (is (= 5000 (token-cap/clamp-max-tokens 0))
      "0 is not a disable hatch on causa — defaults to 5000"))

(deftest clamp-max-tokens-below-floor-clamps-up
  (is (= 500 (token-cap/clamp-max-tokens 1)))
  (is (= 500 (token-cap/clamp-max-tokens 100)))
  (is (= 500 (token-cap/clamp-max-tokens 499))))

(deftest clamp-max-tokens-at-floor-passes
  (is (= 500 (token-cap/clamp-max-tokens 500))))

(deftest clamp-max-tokens-mid-range-passes
  (is (= 1000  (token-cap/clamp-max-tokens 1000)))
  (is (= 5000  (token-cap/clamp-max-tokens 5000)))
  (is (= 25000 (token-cap/clamp-max-tokens 25000))))

(deftest clamp-max-tokens-at-ceiling-passes
  (is (= 50000 (token-cap/clamp-max-tokens 50000))))

(deftest clamp-max-tokens-above-ceiling-clamps-down
  (is (= 50000 (token-cap/clamp-max-tokens 50001)))
  (is (= 50000 (token-cap/clamp-max-tokens 100000)))
  (is (= 50000 (token-cap/clamp-max-tokens 9999999))))

;; ---------------------------------------------------------------------------
;; max-tokens-arg — extract the per-call override from MCP args.
;; ---------------------------------------------------------------------------

(deftest max-tokens-arg-absent-defaults
  (is (= 5000 (token-cap/max-tokens-arg nil)))
  (is (= 5000 (token-cap/max-tokens-arg js/undefined)))
  (is (= 5000 (token-cap/max-tokens-arg #js {})))
  (is (= 5000 (token-cap/max-tokens-arg #js {:other 1}))))

(deftest max-tokens-arg-from-js-object
  (is (= 1500  (token-cap/max-tokens-arg #js {"max-tokens" 1500})))
  (is (= 10000 (token-cap/max-tokens-arg #js {"max-tokens" 10000}))))

(deftest max-tokens-arg-from-cljs-map
  ;; CLJS map shape — both keyword and stringified-key entry points.
  (is (= 2000 (token-cap/max-tokens-arg {:max-tokens   2000})))
  (is (= 2000 (token-cap/max-tokens-arg {"max-tokens" 2000}))))

(deftest max-tokens-arg-clamps-below-floor
  (is (= 500 (token-cap/max-tokens-arg #js {"max-tokens" 100}))))

(deftest max-tokens-arg-clamps-above-ceiling
  (is (= 50000 (token-cap/max-tokens-arg #js {"max-tokens" 999999}))))

;; ---------------------------------------------------------------------------
;; hint-for-tool — lookup with default fallback.
;; ---------------------------------------------------------------------------

(deftest hint-for-tool-unknown-falls-back-to-default
  (is (= :narrow-filter (token-cap/hint-for-tool "no-such-tool"))
      "missing table entry falls back to default-hint, not nil"))

(deftest hint-for-tool-returns-keyword-from-vocab
  ;; Whatever the table returns must be in the closed vocab — the
  ;; agent pattern-matches exhaustively on it. The table is empty at
  ;; W-1 landing; this test pins the shape contract so a future
  ;; catalogue-entry add can't slip a non-vocab keyword in.
  (doseq [[tool _hint] token-cap/hints-by-tool]
    (is (contains? token-cap/hint-vocabulary (token-cap/hint-for-tool tool))
        (str "hint for " tool " must be in hint-vocabulary"))))

;; ---------------------------------------------------------------------------
;; overflow-payload — the marker shape per spec/004 §1.
;; ---------------------------------------------------------------------------

(deftest overflow-payload-shape-minimum
  (let [m (token-cap/overflow-payload {:cap 5000 :would-be 12400 :hint :switch-mode})]
    (is (= {:rf.mcp/overflow {:limit    :reached
                              :cap      5000
                              :would-be 12400
                              :hint     :switch-mode}}
           m))
    (is (not (contains? (:rf.mcp/overflow m) :continuation))
        "no continuation supplied ⇒ slot absent")))

(deftest overflow-payload-with-continuation
  (let [m (token-cap/overflow-payload
            {:cap          5000
             :would-be     12400
             :hint         :paginate
             :continuation {:cursor "abc123" :next-args {:limit 50}}})]
    (is (= {:rf.mcp/overflow {:limit        :reached
                              :cap          5000
                              :would-be     12400
                              :hint         :paginate
                              :continuation {:cursor    "abc123"
                                             :next-args {:limit 50}}}}
           m))))

(deftest overflow-payload-includes-limit-reached-sentinel
  ;; The cross-MCP wire-vocab conformance schema (CausaOverflowBody)
  ;; requires `:limit :reached` on every overflow marker. The causa
  ;; spec snippet (004-Wire-Pipeline.md §1 L244-249) omits the
  ;; sentinel from the example for brevity, but the conformance
  ;; fixture (tools/mcp-conformance/wire-vocab/.../wire_vocab_test.clj
  ;; canonical-markers :causa-mcp) includes it. The pair2-mcp marker
  ;; shape carries the same sentinel.
  (let [m (token-cap/overflow-payload {:cap 5000 :would-be 6000 :hint :paginate})]
    (is (= :reached (get-in m [:rf.mcp/overflow :limit]))
        ":limit :reached pin per cross-MCP CausaOverflowBody schema")))

(deftest overflow-payload-matches-conformance-fixture
  ;; Byte-identical to the :causa-mcp fixture in
  ;; tools/mcp-conformance/wire-vocab/.../wire_vocab_test.clj
  ;; canonical-markers. A change to either side fails this assertion
  ;; loud — the cross-server contract is the pin.
  (is (= {:rf.mcp/overflow {:limit        :reached
                            :cap          5000
                            :would-be     12400
                            :hint         :switch-mode
                            :continuation {:cursor    "opaque-cursor-123"
                                           :next-args {:mode :sample}}}}
         (token-cap/overflow-payload
           {:cap          5000
            :would-be     12400
            :hint         :switch-mode
            :continuation {:cursor    "opaque-cursor-123"
                           :next-args {:mode :sample}}}))))

(deftest overflow-payload-out-of-vocab-hint-falls-back
  (let [m (token-cap/overflow-payload
            {:cap 5000 :would-be 12400 :hint :made-up-hint})]
    (is (= :narrow-filter (get-in m [:rf.mcp/overflow :hint]))
        "out-of-vocab hint MUST collapse to default-hint")))

(deftest overflow-payload-every-hint-keyword-roundtrips
  (doseq [h token-cap/hint-vocabulary]
    (is (= h (get-in (token-cap/overflow-payload
                       {:cap 5000 :would-be 6000 :hint h})
                     [:rf.mcp/overflow :hint])))))

;; ---------------------------------------------------------------------------
;; apply-cap — under-budget passes / at-budget passes / over-budget marker.
;; ---------------------------------------------------------------------------

(deftest apply-cap-under-budget-passes-unchanged
  ;; Small payload, generous cap. The wrapper MUST pass the result
  ;; through identity — no allocation, no walk side-effects.
  (let [r   (mk-result "hello")
        out (token-cap/apply-cap r {:tool "snapshot" :cap 5000})]
    (is (identical? r out)
        "under-budget path MUST return the result object unchanged")))

(deftest apply-cap-empty-content-passes
  (let [r   #js {:content #js []}
        out (token-cap/apply-cap r {:tool "snapshot" :cap 5000})]
    (is (identical? r out))))

(deftest apply-cap-nil-result-passes
  (is (nil? (token-cap/apply-cap nil {:tool "snapshot" :cap 5000}))))

(deftest apply-cap-at-budget-passes
  ;; Exactly-at-cap (`tokens == cap`) is UNDER the strict-greater
  ;; gate — `(> tokens cap)`. The boundary payload passes through.
  ;; (count s) / 4 = cap ⇒ build a string of exactly `cap * 4` chars.
  (let [cap  500
        s    (apply str (repeat (* cap 4) "x")) ; 2000 chars = 500 tokens
        r    (mk-result s)
        out  (token-cap/apply-cap r {:tool "snapshot" :cap cap})]
    (is (= 500 (quot (count s) 4))
        "test setup pin: payload is exactly cap tokens")
    (is (identical? r out)
        "at-budget (==cap) MUST pass — strict-greater gate")))

(deftest apply-cap-over-token-budget-truncates-and-stamps-marker
  ;; Payload over the primary token cap. The wrapper MUST emit the
  ;; overflow marker as the SOLE content slot — no leak of the
  ;; original payload (MUST 6: no silent truncation).
  (let [cap  500
        s    (apply str (repeat 4000 "x")) ; 1000 tokens, > 500 cap
        r    (mk-result s)
        out  (token-cap/apply-cap r {:tool "snapshot" :cap cap})
        edn-v (result-edn out)]
    (is (not (identical? r out))
        "over-budget MUST produce a fresh result, not the original")
    (is (contains? edn-v :rf.mcp/overflow)
        "MUST 7: structured overflow marker at the top of the payload")
    (let [marker (:rf.mcp/overflow edn-v)]
      (is (= cap (:cap marker))
          ":cap slot carries the cap that was exceeded")
      (is (= 1000 (:would-be marker))
          ":would-be carries the measured token count (count/4)")
      (is (contains? token-cap/hint-vocabulary (:hint marker))
          ":hint MUST be in the closed vocabulary"))
    (testing "original payload must NOT leak into the overflow result"
      (is (not (clojure.string/includes? (result-text out) (subs s 0 100)))
          "the original 4000-char body MUST NOT appear in the marker result"))))

(deftest apply-cap-over-byte-budget-trips-secondary-gate
  ;; rf2-ih7g4 defence-in-depth: a payload that escapes the
  ;; (count/4) token heuristic via dense-encoding (CJK, emoji, base64,
  ;; etc.) still trips the secondary `cap * 8` char-byte cap.
  ;;
  ;; Construct a payload whose token-estimate stays UNDER the cap but
  ;; whose char count exceeds `cap * byte-cap-multiplier` (8×). Token
  ;; estimate = (quot count 4), so for it to stay under `cap` we need
  ;; char count < `cap * 4`. But the byte cap is `cap * 8`. So we
  ;; cannot trip the byte cap without also tripping the token cap on
  ;; the SAME string — both are character-based.
  ;;
  ;; Therefore: the byte gate fires when char count > `cap * 8`. With
  ;; a 500-cap, byte-cap = 4000. A 4001-char payload has token-estimate
  ;; 1000 (also > 500 token cap). The byte gate fires alongside the
  ;; token gate; with chars > byte-cap, `:would-be` reports the CHAR
  ;; count (the more conservative number).
  (let [cap  500
        s    (apply str (repeat 4500 "x"))
        r    (mk-result s)
        out  (token-cap/apply-cap r {:tool "snapshot" :cap cap})
        marker (:rf.mcp/overflow (result-edn out))]
    (is (contains? (result-edn out) :rf.mcp/overflow)
        "byte gate independently trips the overflow path")
    (is (= 4500 (:would-be marker))
        "char gate trip ⇒ :would-be reports char count, not token count")))

(deftest apply-cap-over-budget-aggregated-across-multiple-slots
  ;; Multi-slot results share ONE cumulative budget — the wrapper sums
  ;; tokens across every `:text` slot.
  (let [cap   500
        s1    (apply str (repeat 1200 "x")) ; 300 tokens
        s2    (apply str (repeat 1200 "y")) ; 300 tokens — sum 600 > 500
        r     #js {:content #js [#js {:type "text" :text s1}
                                 #js {:type "text" :text s2}]}
        out   (token-cap/apply-cap r {:tool "snapshot" :cap cap})
        edn-v (result-edn out)]
    (is (contains? edn-v :rf.mcp/overflow)
        "cumulative-budget MUST trip when sum > cap, not per-slot")
    (is (= 600 (get-in edn-v [:rf.mcp/overflow :would-be])))))

(deftest apply-cap-default-cap-uses-5000
  ;; nil cap collapses to default-max-tokens (5000).
  (let [s   (apply str (repeat 20001 "x")) ; 5000.25 tokens (quot 20001 4 = 5000) — wait
        r   (mk-result s)
        ;; Build precisely 5001-token payload: 20004 chars
        s2  (apply str (repeat 20004 "x"))
        r2  (mk-result s2)
        out (token-cap/apply-cap r2 {:tool "snapshot"})]
    (is (= 5001 (quot (count s2) 4))
        "test setup: 20004 chars = 5001 tokens (just over default cap)")
    (is (contains? (result-edn out) :rf.mcp/overflow)
        "nil cap MUST default to 5000 and trip on 5001 tokens")
    (is (= 5000 (get-in (result-edn out) [:rf.mcp/overflow :cap]))
        ":cap slot reports the resolved default-max-tokens")))

(deftest apply-cap-uses-per-tool-hint-when-not-overridden
  ;; The hint table is empty at W-1 — every tool falls back to
  ;; default-hint. When the catalogue beads land, individual tools
  ;; will populate entries; this test pins the lookup-and-fallback
  ;; contract that those entries inherit.
  (let [cap  500
        s    (apply str (repeat 4000 "x"))
        r    (mk-result s)
        out  (token-cap/apply-cap r {:tool "snapshot" :cap cap})]
    (is (= :narrow-filter (get-in (result-edn out) [:rf.mcp/overflow :hint]))
        "unknown tool ⇒ default-hint")))

(deftest apply-cap-explicit-hint-override-wins
  (let [cap  500
        s    (apply str (repeat 4000 "x"))
        r    (mk-result s)
        out  (token-cap/apply-cap r {:tool "snapshot" :cap cap :hint :paginate})]
    (is (= :paginate (get-in (result-edn out) [:rf.mcp/overflow :hint]))
        "explicit :hint opt overrides the per-tool table")))

(deftest apply-cap-out-of-vocab-hint-override-falls-back
  (let [cap  500
        s    (apply str (repeat 4000 "x"))
        r    (mk-result s)
        out  (token-cap/apply-cap r {:tool "snapshot" :cap cap :hint :gibberish})]
    (is (= :narrow-filter (get-in (result-edn out) [:rf.mcp/overflow :hint]))
        "out-of-vocab override collapses to default-hint")))

(deftest apply-cap-continuation-splices-onto-marker
  (let [cap  500
        s    (apply str (repeat 4000 "x"))
        r    (mk-result s)
        cont {:cursor "epoch-1234" :next-args {:limit 25}}
        out  (token-cap/apply-cap r {:tool         "get-trace-buffer"
                                     :cap          cap
                                     :hint         :paginate
                                     :continuation cont})
        marker (:rf.mcp/overflow (result-edn out))]
    (is (= cont (:continuation marker))
        ":continuation MUST be spliced onto the marker when supplied")))

(deftest apply-cap-no-continuation-omits-slot
  (let [cap  500
        s    (apply str (repeat 4000 "x"))
        r    (mk-result s)
        out  (token-cap/apply-cap r {:tool "snapshot" :cap cap})
        marker (:rf.mcp/overflow (result-edn out))]
    (is (not (contains? marker :continuation))
        ":continuation MUST be omitted when not supplied — common path")))

(deftest apply-cap-result-shape-conforms-to-mcp-sdk
  ;; The replacement result MUST be a valid MCP `tools/call` reply
  ;; shape — `{:content [{:type "text" :text "..."}]}` — so the npm
  ;; MCP SDK can serialise it unchanged.
  (let [cap  500
        s    (apply str (repeat 4000 "x"))
        r    (mk-result s)
        out  (token-cap/apply-cap r {:tool "snapshot" :cap cap})]
    (is (array? (j/get out :content)) "content MUST be a JS array")
    (is (= 1 (.-length (j/get out :content)))
        "overflow result is a single content slot")
    (let [item (aget (j/get out :content) 0)]
      (is (= "text" (j/get item :type)))
      (is (string? (j/get item :text))))))

;; ---------------------------------------------------------------------------
;; Load-bearing spec/004 §1 assertion — the marker shape pinned end-to-end.
;; ---------------------------------------------------------------------------

(deftest spec-004-overflow-marker-shape-end-to-end
  (testing "MUST 7 — over-budget payload returns the spec-shaped
            `:rf.mcp/overflow` marker at the top of the payload, with
            :cap, :would-be, :hint (keyword from closed vocab), and
            optional :continuation"
    (let [cap      5000
          ;; Build a payload that overflows by a clean margin so the
          ;; numbers are easy to assert against.
          payload  (apply str (repeat 24000 "x")) ; 6000 tokens > 5000 cap
          r        (mk-result payload)
          out      (token-cap/apply-cap
                     r
                     {:tool         "get-trace-buffer"
                      :cap          cap
                      :hint         :paginate
                      :continuation {:cursor    "trace-cursor-42"
                                     :next-args {:limit 25}}})
          edn-v    (result-edn out)
          marker   (:rf.mcp/overflow edn-v)]
      (testing "marker key is the cross-MCP-reserved `:rf.mcp/overflow`"
        (is (contains? edn-v :rf.mcp/overflow))
        (is (= [:rf.mcp/overflow] (vec (keys edn-v)))
            "marker is the SOLE top-level key — the original payload
             is replaced, not augmented (MUST 6 no silent truncation)"))
      (testing "marker carries :cap"
        (is (= 5000 (:cap marker))))
      (testing "marker carries :would-be (the measured token count)"
        (is (= 6000 (:would-be marker))))
      (testing "marker carries :hint as a keyword from the closed vocab"
        (is (keyword? (:hint marker)))
        (is (contains? token-cap/hint-vocabulary (:hint marker)))
        (is (= :paginate (:hint marker))))
      (testing "marker carries :continuation as a structured map"
        (is (= {:cursor "trace-cursor-42" :next-args {:limit 25}}
               (:continuation marker)))))))

(deftest spec-004-no-silent-truncation
  (testing "MUST 6 — a tool that would exceed the cap MUST NOT silently
            truncate. The original payload bytes MUST NOT appear in the
            replacement result."
    (let [secret  "TRIPWIRE-PAYLOAD-MUST-NOT-LEAK"
          stuffing (apply str (repeat 4000 "x"))
          payload  (str secret stuffing)
          r        (mk-result payload)
          out      (token-cap/apply-cap r {:tool "snapshot" :cap 500})]
      (is (not (clojure.string/includes? (result-text out) secret))
          "the original payload MUST NOT bleed into the overflow result"))))
