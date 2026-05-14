(ns re-frame.mcp-base.cap-test
  "Unit tests for the cross-MCP cap pipeline (rf2-eyelu).

  Exercises the algorithm via a mock `ResultIO` reifying the protocol
  over CLJ maps. The per-server IO instances (pair2-mcp's JS-object
  reify, story-mcp's CLJ-map reify) are exercised against the real
  pipeline in their respective test suites; the unit tests here pin
  the algorithm itself."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.cap :as cap]
            [re-frame.mcp-base.overflow :as overflow]
            [re-frame.mcp-base.vocab :as vocab]))

;; ---------------------------------------------------------------------------
;; Mock ResultIO — CLJ-map shape, mirrors story-mcp's runtime instance.
;; ---------------------------------------------------------------------------

(def map-io
  "ResultIO over `{:content [{:type \"text\" :text \"...\"} ...]}` maps.
  Mirrors story-mcp's runtime instance; story-mcp's tests exercise the
  full registry-backed pipeline."
  (reify cap/ResultIO
    (content-texts [_ result]
      (map :text (:content result)))
    (build-overflow-result [_ marker _original]
      {:content          [{:type "text" :text (pr-str marker)}]
       :structuredContent marker})))

(defn- ok-text-result [v]
  {:content [{:type "text" :text (pr-str v)}]})

(defn- big-string [n]
  (apply str (repeat n "x")))

;; ---------------------------------------------------------------------------
;; max-tokens — per-call cap resolution.
;; ---------------------------------------------------------------------------

(deftest max-tokens-default-when-nil
  (is (= overflow/default-max-tokens (cap/max-tokens nil))))

(deftest max-tokens-zero-disables-cap
  (is (nil? (cap/max-tokens 0))))

(deftest max-tokens-positive-integer-passed-through
  (is (= 1000 (cap/max-tokens 1000)))
  (is (= 50000 (cap/max-tokens 50000))))

(deftest max-tokens-non-number-falls-back-to-default
  (is (= overflow/default-max-tokens (cap/max-tokens "bogus")))
  (is (= overflow/default-max-tokens (cap/max-tokens :not-a-number)))
  (is (= overflow/default-max-tokens (cap/max-tokens [1 2 3]))))

(deftest max-tokens-coerces-double-to-long
  (is (= 1000 (cap/max-tokens 1000.0)))
  (is (integer? (cap/max-tokens 1000.0))))

;; ---------------------------------------------------------------------------
;; sum-text-tokens — sums every :text slot via ResultIO.
;; ---------------------------------------------------------------------------

(deftest sum-text-tokens-single-slot
  (let [r (ok-text-result {:hello "world"})]
    (is (pos? (cap/sum-text-tokens map-io r)))
    (is (= (overflow/token-estimate (pr-str {:hello "world"}))
           (cap/sum-text-tokens map-io r)))))

(deftest sum-text-tokens-empty-content-is-zero
  (is (zero? (cap/sum-text-tokens map-io {:content []})))
  (is (zero? (cap/sum-text-tokens map-io {:content nil}))))

(deftest sum-text-tokens-aggregates-across-slots
  (let [r {:content [{:type "text" :text (big-string 4000)}
                     {:type "text" :text (big-string 4000)}]}]
    (is (= 2000 (cap/sum-text-tokens map-io r)))))

(deftest sum-text-tokens-skips-non-string-slots
  (let [r {:content [{:type "text" :text (big-string 4000)}
                     {:type "image"}
                     {:type "text"}]}]
    (is (= 1000 (cap/sum-text-tokens map-io r)))))

;; ---------------------------------------------------------------------------
;; apply-cap — the strategy entry point.
;; ---------------------------------------------------------------------------

(deftest apply-cap-passes-under-budget-payload-untouched
  (let [r   (ok-text-result {:small :payload})
        out (cap/apply-cap map-io r {:tool "snapshot" :cap overflow/default-max-tokens})]
    (is (identical? r out))))

(deftest apply-cap-nil-cap-disables-enforcement
  (let [r   (ok-text-result {:k (big-string 100000)})
        out (cap/apply-cap map-io r {:tool "snapshot" :cap nil})]
    (is (identical? r out))))

(deftest apply-cap-nil-result-passes-through
  (is (nil? (cap/apply-cap map-io nil {:tool "snapshot" :cap 5000}))))

(deftest apply-cap-over-budget-emits-overflow-marker
  (let [big (big-string 4000)
        r   (ok-text-result {:huge big})
        out (cap/apply-cap map-io r {:tool "snapshot" :cap 500 :hint "narrow scope"})
        marker (:structuredContent out)
        body   (get marker vocab/overflow-key)]
    (is (contains? marker vocab/overflow-key))
    (is (= :reached (:limit body)))
    (is (= "snapshot" (:tool body)))
    (is (= 500 (:cap-tokens body)))
    (is (pos? (:token-count body)))
    (is (> (:token-count body) 500))
    (is (= "narrow scope" (:hint body)))))

(deftest apply-cap-overflow-payload-is-itself-under-cap
  (let [big (big-string 8000)
        r   (ok-text-result {:huge big})
        out (cap/apply-cap map-io r {:tool "snapshot" :cap 500})]
    (is (<= (cap/sum-text-tokens map-io out) 500)
        "The overflow marker itself must be under the cap")))

(deftest apply-cap-absent-hint-uses-fallback
  (let [big (big-string 8000)
        r   (ok-text-result {:huge big})
        out (cap/apply-cap map-io r {:tool "no-such-tool" :cap 500})
        body (get-in out [:structuredContent vocab/overflow-key])]
    (is (= overflow/overflow-hint-fallback (:hint body)))))

(deftest apply-cap-unknown-strategy-degrades-safely
  ;; Unknown strategy must NOT throw and must NOT ship the over-budget
  ;; payload. It falls back to truncate-with-marker.
  (let [big (big-string 8000)
        r   (ok-text-result {:huge big})
        out (cap/apply-cap map-io r {:tool "snapshot"
                                     :cap 500
                                     :strategy :unknown-strategy})
        marker (:structuredContent out)]
    (is (contains? marker vocab/overflow-key))
    (is (= :reached (get-in marker [vocab/overflow-key :limit])))))

(deftest apply-cap-at-cap-exact-boundary-passes
  ;; <= cap passes; only > cap trips. Boundary check pins inclusive-low.
  (let [s    (big-string 400)
        r    (ok-text-result s)
        toks (cap/sum-text-tokens map-io r)
        out  (cap/apply-cap map-io r {:tool "snapshot" :cap toks})]
    (is (identical? r out))))

(deftest apply-cap-uses-result-io-build-fn
  ;; Verify the build-overflow-result hook is what produces the new
  ;; result — a custom IO can shape the result however it likes.
  (let [marker-only-io (reify cap/ResultIO
                         (content-texts [_ result] (map :text (:content result)))
                         (build-overflow-result [_ marker _]
                           {::custom-shape true :marker marker}))
        big (big-string 8000)
        r   (ok-text-result {:huge big})
        out (cap/apply-cap marker-only-io r {:tool "snapshot" :cap 500})]
    (is (true? (::custom-shape out))
        "build-overflow-result is the sole producer of the over-cap shape")
    (is (contains? (:marker out) vocab/overflow-key))))
