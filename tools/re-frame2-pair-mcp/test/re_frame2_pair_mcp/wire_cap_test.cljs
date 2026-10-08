(ns re-frame2-pair-mcp.wire-cap-test
  "The wire-boundary token cap (`tools/cap.cljs`): per-call cap
  resolution, token summing over both wire slots, and replacement of an
  over-budget payload by the `{:rf.mcp/overflow ...}` marker."
  (:require [cljs.test :refer-macros [deftest is]]
            [cljs.reader]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.cap :as cap]))

(defn- ok-text-result [v]
  #js {:content #js [#js {:type "text" :text (pr-str v)}]})

(defn- read-edn [result-js]
  (cljs.reader/read-string (-> (j/get result-js :content) (aget 0) (j/get :text))))

(defn- big-string [n]
  (apply str (repeat n "x")))

(deftest max-tokens-arg-resolution
  (doseq [[args expected note]
          [[#js {} cap/default-max-tokens "absent ⇒ the default cap"]
           [nil cap/default-max-tokens "no args ⇒ the default cap"]
           [#js {"max-tokens" 0} nil "0 disables the cap"]
           [#js {"max-tokens" 1000} 1000 "a positive integer passes through"]]]
    (is (= expected (cap/max-tokens-arg args)) note)))

(deftest sum-payload-tokens-aggregates-across-slots
  (let [r #js {:content #js [#js {:type "text" :text (big-string 4000)}
                              #js {:type "text" :text (big-string 4000)}]}]
    (is (= 2000 (cap/sum-payload-tokens r)))))

(deftest apply-cap-nil-cap-disables-enforcement
  (let [r (ok-text-result {:k (big-string 100000)})]
    (is (identical? r (cap/apply-cap r {:tool "snapshot" :cap nil})))))

(deftest apply-cap-over-budget-emits-overflow-marker
  (let [out    (cap/apply-cap (ok-text-result {:huge (big-string 4000)}) {:tool "snapshot" :cap 500})
        marker (:rf.mcp/overflow (read-edn out))]
    (is (= {:limit :reached :tool "snapshot" :cap-tokens 500
            :hint (get cap/overflow-hints "snapshot")}
           (dissoc marker :token-count)))
    (is (> (:token-count marker) 500))
    ;; A namespace-lossy `clj->js` would ship the key as "overflow", and
    ;; hosts reading structuredContent would miss the marker.
    (is (some? (j/get (j/get out :structuredContent) "rf.mcp/overflow")))
    (is (not (true? (j/get out :isError))))))

(deftest apply-cap-over-budget-error-keeps-is-error
  ;; Without isError the marker is byte-for-byte what an over-cap success
  ;; returns, so the agent could not tell the call failed.
  (let [err #js {:isError true
                 :content #js [#js {:type "text" :text (pr-str {:ok? false :huge (big-string 4000)})}]}
        out (cap/apply-cap err {:tool "snapshot" :cap 500})]
    (is (contains? (read-edn out) :rf.mcp/overflow))
    (is (true? (j/get out :isError)))))

(deftest apply-cap-unknown-tool-uses-fallback-hint
  (let [out (cap/apply-cap (ok-text-result {:huge (big-string 4000)}) {:tool "no-such-tool" :cap 500})]
    (is (= tu/overflow-hint-fallback (:hint (:rf.mcp/overflow (read-edn out)))))))

;; `wire/ok-text` writes the same payload into `:content[*].text` and
;; `:structuredContent`, and both ride the wire, so the cap must size the
;; structured slot too. The mcp-base contract pins the same class
;; (`structured-content-counted-toward-budget`).
(deftest apply-cap-trips-on-huge-structured-content-under-small-text
  (let [r   #js {:content           #js [#js {:type "text" :text (pr-str {:ok? true})}]
                 :structuredContent (clj->js {:big-payload (big-string 30000)})}
        out (cap/apply-cap r {:tool "snapshot" :cap 1000})]
    (is (> (:token-count (:rf.mcp/overflow (read-edn out))) 1000)
        "the token count includes the structured-slot bytes")
    (is (<= (cap/sum-payload-tokens out) 1000)
        "the overflow replacement itself stays under the cap")))

(deftest apply-cap-short-circuits-on-wire-bounded-markers
  ;; No overflow of an overflow, even under a 1-token cap.
  (doseq [marker [{:rf.mcp/cache-hit {:hash 42 :unchanged-since 0
                                      :tool "snapshot" :via :result-hash
                                      :hint "..."}}
                  {:rf.mcp/overflow {:limit :reached :tool "snapshot"
                                     :cap-tokens 100 :token-count 200
                                     :hint "..."}}]]
    (let [r (ok-text-result marker)]
      (is (identical? r (cap/apply-cap r {:tool "snapshot" :cap 1}))
          (str (ffirst marker) " passes through unchanged")))))

(deftest apply-cap-caps-over-budget-lookalike-marker-key
  ;; The marker detector matches the EXACT key: a leading key that merely
  ;; starts with a marker key must not short-circuit past the cap.
  (let [r (ok-text-result {:rf.mcp/overflowed {:huge (big-string 8000)}})]
    (is (contains? (read-edn (cap/apply-cap r {:tool "snapshot" :cap 500}))
                   :rf.mcp/overflow))))
