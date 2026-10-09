(ns re-frame.mcp-base.cap-test
  "Unit tests for the cross-MCP cap pipeline, driven through mock
  `ResultIO` instances over CLJ maps. Each server's own suite drives its
  real IO instance through the same pipeline."
  (:require [clojure.test :refer [are deftest is]]
            [re-frame.mcp-base.cap :as rf.mcp-base.cap]
            [re-frame.mcp-base.overflow :as rf.mcp-base.overflow]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

(def map-io
  "Single-slot ResultIO: counts only the `:content[*].text` strings."
  (reify rf.mcp-base.cap/ResultIO
    (wire-payload-strings [_ result]
      (map :text (:content result)))
    (build-overflow-result [_ marker _original]
      {:content           [{:type "text" :text (pr-str marker)}]
       :structuredContent marker})))

(defn- ok-text-result [v]
  {:content [{:type "text" :text (pr-str v)}]})

(defn- big-string [n]
  (apply str (repeat n "x")))

;; ---------------------------------------------------------------------------
;; max-tokens — per-call cap resolution.
;; ---------------------------------------------------------------------------

(deftest max-tokens-resolves-the-cap
  (are [raw expected] (= expected (rf.mcp-base.cap/max-tokens raw))
    0                nil
    1                1
    2.9              2
    9007199254740991 9007199254740991))

(deftest max-tokens-non-number-falls-back-to-default
  (is (= rf.mcp-base.overflow/default-max-tokens (rf.mcp-base.cap/max-tokens nil)))
  (is (= rf.mcp-base.overflow/default-max-tokens (rf.mcp-base.cap/max-tokens "bogus"))))

(deftest max-tokens-rejects-an-out-of-domain-number
  ;; A negative, a fraction that would floor to a 0 cap, or a non-finite or
  ;; out-of-range value would otherwise lock the agent out of every
  ;; response or crash `(long raw)`, so each resolves to a rejection.
  (is (= {rf.mcp-base.vocab/invalid-arg-key {:arg   :max-tokens
                                             :value -1
                                             :hint  rf.mcp-base.cap/invalid-arg-hint}}
         (rf.mcp-base.cap/max-tokens -1)))
  (are [raw] (rf.mcp-base.cap/invalid-arg? (rf.mcp-base.cap/max-tokens raw))
    0.999
    ##Inf
    ##NaN
    1.0E20))

(deftest invalid-arg?-predicate-discriminates
  (is (rf.mcp-base.cap/invalid-arg? (rf.mcp-base.cap/max-tokens -1)))
  (is (not (rf.mcp-base.cap/invalid-arg? {:other :map})))
  (is (not (rf.mcp-base.cap/invalid-arg? nil))))

;; ---------------------------------------------------------------------------
;; sum-payload-tokens — sums every string slot via ResultIO.
;; ---------------------------------------------------------------------------

(deftest sum-payload-tokens-empty-content-is-zero
  (is (zero? (rf.mcp-base.cap/sum-payload-tokens map-io {:content []})))
  (is (zero? (rf.mcp-base.cap/sum-payload-tokens map-io {:content nil}))))

(deftest sum-payload-tokens-sums-every-string-slot
  (is (= 2000 (rf.mcp-base.cap/sum-payload-tokens
                map-io
                {:content [{:type "text" :text (big-string 4000)}
                           {:type "image"}
                           {:type "text" :text (big-string 4000)}]}))))

;; ---------------------------------------------------------------------------
;; apply-cap — the strategy entry point.
;; ---------------------------------------------------------------------------

(deftest apply-cap-nil-cap-disables-enforcement
  (let [r (ok-text-result {:k (big-string 100000)})]
    (is (identical? r (rf.mcp-base.cap/apply-cap map-io r {:tool "snapshot" :cap nil})))))

(deftest apply-cap-at-cap-exact-boundary-passes
  ;; 400 x's print as 402 chars, exactly 100 tokens: <= cap passes.
  (let [r (ok-text-result (big-string 400))]
    (is (identical? r (rf.mcp-base.cap/apply-cap map-io r {:tool "snapshot" :cap 100})))))

(deftest apply-cap-over-budget-emits-overflow-marker
  ;; 4000 x's print as 4010 chars: 1002 tokens, over a 500 cap on both
  ;; gates, and the marker reports the token estimate, not the char count.
  (let [r (ok-text-result {:huge (big-string 4000)})]
    (is (= {rf.mcp-base.vocab/overflow-key {:limit       :reached
                                            :token-count 1002
                                            :cap-tokens  500
                                            :tool        "snapshot"
                                            :hint        "narrow scope"}}
           (:structuredContent
             (rf.mcp-base.cap/apply-cap map-io r {:tool "snapshot" :cap 500 :hint "narrow scope"}))))
    (is (= rf.mcp-base.overflow/overflow-hint-fallback
           (get-in (rf.mcp-base.cap/apply-cap map-io r {:tool "snapshot" :cap 500})
                   [:structuredContent rf.mcp-base.vocab/overflow-key :hint]))
        "an absent hint falls back to the generic one")))

(deftest apply-cap-overflow-payload-is-itself-under-cap
  (let [r   (ok-text-result {:huge (big-string 8000)})
        out (rf.mcp-base.cap/apply-cap map-io r {:tool "snapshot" :cap 500})]
    (is (<= (rf.mcp-base.cap/sum-payload-tokens map-io out) 500))))

;; ---------------------------------------------------------------------------
;; The two-stage gate: the token sum over the cap, or the char sum over
;; 8x the cap. `over-cap?` / `reported-count` are pure over the two sums.
;; ---------------------------------------------------------------------------

(deftest over-cap?-trips-on-either-gate
  (are [tokens chars expected] (= expected (rf.mcp-base.cap/over-cap? tokens chars 100))
    101 0   true
    100 800 false
    50  801 true))

(deftest reported-count-is-always-in-token-units
  ;; The token estimate when the token gate tripped, else chars / 4.
  (are [tokens chars expected] (= expected (rf.mcp-base.cap/reported-count tokens chars 100))
    150 1600 150
    50  801  200
    100 801  200))

(deftest apply-cap-many-short-strings-trips-char-gate
  ;; The token estimate floors per string, so 3000 three-char slots sum to
  ;; 0 tokens and only the char gate (9000 > 8) can trip: the char-gated
  ;; arm reached through the live path, reporting 9000 / 4.
  (let [r {:content (vec (repeat 3000 {:type "text" :text "xxx"}))}]
    (is (= {rf.mcp-base.vocab/overflow-key {:limit       :reached
                                            :token-count 2250
                                            :cap-tokens  1
                                            :tool        "trace-window"
                                            :hint        rf.mcp-base.overflow/overflow-hint-fallback}}
           (:structuredContent (rf.mcp-base.cap/apply-cap map-io r {:tool "trace-window" :cap 1}))))))

;; ---------------------------------------------------------------------------
;; A consumer that duplicates the payload into `:structuredContent` must
;; count both copies.
;; ---------------------------------------------------------------------------

(def structured-io
  "Dual-slot ResultIO, story-mcp's shape: counts the `:content[*].text`
  strings plus a `pr-str` of `:structuredContent`."
  (reify rf.mcp-base.cap/ResultIO
    (wire-payload-strings [_ result]
      (cond-> (mapv :text (:content result))
        (some? (:structuredContent result))
        (conj (pr-str (:structuredContent result)))))
    (build-overflow-result [_ marker _original]
      {:content           [{:type "text" :text (pr-str marker)}]
       :structuredContent marker})))

(deftest structured-content-counted-toward-budget
  ;; The text slot is tiny; only the structured copy is over the cap.
  (let [r {:content           [{:type "text" :text "ok"}]
           :structuredContent {:big-payload (big-string 30000)}}]
    (is (contains? (:structuredContent
                     (rf.mcp-base.cap/apply-cap structured-io r {:tool "snapshot" :cap 1000}))
                   rf.mcp-base.vocab/overflow-key))))
