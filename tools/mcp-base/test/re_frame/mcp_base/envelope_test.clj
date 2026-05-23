(ns re-frame.mcp-base.envelope-test
  "Tests for the shared response-envelope helpers (rf2-ee38b.19) —
  the indicator-field 'omit when zero' splice and the wire-bounded
  marker detection."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.envelope :as envelope]
            [re-frame.mcp-base.vocab :as vocab]))

;; ---------------------------------------------------------------------------
;; with-indicators — the MUST-level 'omit when zero' rule.
;; ---------------------------------------------------------------------------

(deftest with-indicators-omits-zero-counts
  (testing "both zero ⇒ envelope unchanged (identity-preserving)"
    (let [env {:trace [1 2 3]}]
      (is (identical? env (envelope/with-indicators env {:dropped 0 :elided 0})))
      (is (identical? env (envelope/with-indicators env {})))
      (is (identical? env (envelope/with-indicators env {:dropped nil :elided nil})))))
  (testing "only dropped positive ⇒ only :dropped-sensitive slot"
    (is (= {:trace [1] :dropped-sensitive 3}
           (envelope/with-indicators {:trace [1]} {:dropped 3 :elided 0}))))
  (testing "only elided positive ⇒ only :elided-large slot"
    (is (= {:db {} :elided-large 2}
           (envelope/with-indicators {:db {}} {:dropped 0 :elided 2}))))
  (testing "both positive ⇒ both slots"
    (is (= {:trace [1] :dropped-sensitive 3 :elided-large 2}
           (envelope/with-indicators {:trace [1]} {:dropped 3 :elided 2})))))

(deftest with-indicators-uses-vocab-keys
  ;; The slots MUST be the canonical vocab keys, not literal keywords —
  ;; pin the dependency so a vocab rename propagates here.
  (let [r (envelope/with-indicators {} {:dropped 1 :elided 1})]
    (is (contains? r vocab/dropped-sensitive-key))
    (is (contains? r vocab/elided-large-key))))

;; ---------------------------------------------------------------------------
;; marker-text? — wire-bounded :rf.mcp/* marker detection.
;; ---------------------------------------------------------------------------

(defn- overflow-fixture []
  {vocab/overflow-key {:limit :reached :token-count 9000 :cap-tokens 5000
                       :tool "snapshot" :hint "narrow"}})

(deftest marker-text?-recognises-boundary-markers
  (testing "overflow marker text"
    (is (true? (envelope/marker-text? (pr-str (overflow-fixture))))))
  (testing "cache-hit marker text"
    (is (true? (envelope/marker-text? (pr-str {vocab/cache-hit-key {:tool "x"}})))))
  (testing "an ordinary tool payload is not a marker"
    (is (false? (envelope/marker-text? (pr-str {:trace [1 2 3]}))))
    (is (false? (envelope/marker-text? (pr-str {:db {:a 1}})))))
  (testing "nil-safe / non-string"
    (is (false? (envelope/marker-text? nil)))
    (is (false? (envelope/marker-text? 42)))))

(deftest marker-prefixes-are-derived-from-vocab
  ;; The prefixes MUST track the vocab keys so a key rename can't
  ;; silently desync the detector from the emitter. Both the flat and
  ;; the namespaced-map print forms are present.
  (is (some #(= % (str "{" vocab/overflow-key)) envelope/marker-prefixes))
  (is (some #(= % (str "{" vocab/cache-hit-key)) envelope/marker-prefixes))
  (is (some #(= % "#:rf.mcp{:overflow") envelope/marker-prefixes))
  (is (some #(= % "#:rf.mcp{:cache-hit") envelope/marker-prefixes)))

(deftest marker-text?-handles-both-print-forms
  ;; JVM `pr-str` emits the namespaced-map shorthand for a single-ns
  ;; map; CLJS emits the flat form. Both MUST be detected so the cap /
  ;; cache boundary steps recognise a marker regardless of host.
  (testing "namespaced-map form (JVM default)"
    (is (true? (envelope/marker-text? "#:rf.mcp{:overflow {:limit :reached}}")))
    (is (true? (envelope/marker-text? "#:rf.mcp{:cache-hit {:tool \"x\"}}"))))
  (testing "flat form (CLJS / *print-namespace-maps* false)"
    (is (true? (envelope/marker-text? "{:rf.mcp/overflow {:limit :reached}}")))
    (is (true? (envelope/marker-text? "{:rf.mcp/cache-hit {:tool \"x\"}}")))))
