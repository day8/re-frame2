(ns re-frame.diag-value-summary-cljs-test
  "rf2-uwqale — EP-0015 diagnostic value-summary gate.

  Spec 015 §Data-Classification forbids raw application values in
  framework exception messages / ex-data: a value baked into a flattened
  message or ex-data slot is captured off-box (console, error boundary,
  host log, SSR error handler, observability) BEFORE the record projector
  can classify the path, and path-based projection cannot recover a value
  that no longer sits at a path. `re-frame.error/diag-value-summary`
  produces an EP-0015-safe SUMMARY (shape / type / count / keys / bounded
  head) — never the raw value.

  This gate pins: the summary NEVER reproduces the offending value, it
  carries shape diagnostics, and a sensitive/large leaf cannot round-trip
  through it.

  Dual-runtime: `-cljs-test` rides `npm run test:cljs`; the `.cljc` is
  also discovered on the JVM. Pure data — no runtime state."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.error :as error]))

(deftest summary-never-reproduces-the-value
  (testing "a sensitive scalar does not ride whole into the summary"
    (let [secret "super-secret-bearer-token-value-1234567890"
          s      (error/diag-value-summary secret)]
      (is (= :string (:type s)))
      (is (= (count secret) (:count s)))
      ;; The head is bounded — the whole secret is NOT present.
      (is (not= secret (:head s)))
      (is (<= (count (:head s)) 25))
      (is (not (re-find #"1234567890" (:head s)))
          "the bounded head truncated before the tail of the secret"))))

(deftest map-summary-keeps-keys-drops-values
  (testing "a map summary carries structural keys but never values"
    (let [m {:token "secret" :pdf "%PDF-1.4 huge blob" :n 7}
          s (error/diag-value-summary m)]
      (is (= :map (:type s)))
      (is (= 3 (:count s)))
      (is (= [:n :pdf :token] (:keys s)) "keys are sorted + structural")
      ;; No value reproduced anywhere in the summary.
      (is (not (contains? (set (vals s)) "secret")))
      (let [printed (pr-str s)]
        (is (not (re-find #"secret" printed)))
        (is (not (re-find #"%PDF" printed))
            "no map VALUE leaked into the summary's printed form")))))

(deftest vector-summary-is-shape-only
  (testing "a hiccup-shaped vector summary carries count, not children"
    (let [argv [:div {:on-click (fn [] nil)} "sensitive child text here xyzzy"]
          s    (error/diag-value-summary argv)]
      (is (= :vector (:type s)))
      (is (= 3 (:count s)))
      (let [printed (pr-str s)]
        (is (not (re-find #"xyzzy" printed))
            "no child content leaked into the vector summary")))))

(deftest scalar-and-keyword-heads
  (testing "keyword keeps its name; number/symbol get bounded heads"
    (is (= :keyword (:type (error/diag-value-summary :ws.app/request))))
    (is (= ":ws.app/request" (:head (error/diag-value-summary :ws.app/request))))
    (is (= :number (:type (error/diag-value-summary 42))))
    (is (= :symbol (:type (error/diag-value-summary 'reagent2.template/as-element))))))

(deftest nil-and-fn-and-set
  (testing "degenerate shapes summarise without throwing"
    (is (= {:type :nil} (error/diag-value-summary nil)))
    (is (= :fn (:type (error/diag-value-summary (fn [] nil)))))
    (is (= :set (:type (error/diag-value-summary #{1 2 3}))))
    (is (= 3 (:count (error/diag-value-summary #{1 2 3}))))))
