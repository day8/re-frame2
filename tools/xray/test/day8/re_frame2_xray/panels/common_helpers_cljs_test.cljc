(ns day8.re-frame2-xray.panels.common-helpers-cljs-test
  "Tests for the shared panel-helper surfaces — the 200-row cap and
  the trace-event tag reader.

  Dual-target naming (`.cljc` + `_cljs_test`) — same pattern as every
  other panel-helper test:

    - Cognitect's test-runner picks it up via `.*-test$`.
    - Shadow's `:node-test` build picks it up via `cljs-test$`."
  (:require #?(:clj  [clojure.test :refer [are deftest is testing]]
               :cljs [cljs.test    :refer-macros [are deftest is testing]])
            [day8.re-frame2-xray.panels.common-helpers :as common]))

;; ---- tag-of -------------------------------------------------------------

(deftest tag-of-reads-tags-slot
  (testing "the `:tags` slot wins; the flat fallback covers a nil or absent
            tag; and `false` under `:tags` is a value, not 'absent', so the
            fallback does not shadow it"
    (are [ev expected] (= expected (common/tag-of ev :k))
      {:tags {:k :ok}}             :ok
      {:k :ok}                     :ok
      {:tags {:k nil} :k :flat}    :flat
      {:tags {:k false} :k :flat}  false)))

;; ---- panel-row-cap ------------------------------------------------------

(deftest panel-row-cap-is-200
  (testing "the cap matches spec/007-UX-IA.md §Performance budget"
    (is (= 200 common/panel-row-cap))))

;; ---- cap-rows -----------------------------------------------------------

(deftest cap-rows-keeps-the-head-and-reports-what-it-hides
  ;; `[capped over-cap? hidden]` either side of the default cap. Whole-value
  ;; equality pins head retention and that rows pass through unchanged.
  (let [rows (fn [n] (mapv (fn [i] {:id i}) (range n)))
        cap  common/panel-row-cap]
    (are [input expected] (= expected (common/cap-rows input))
      (rows 50)          [(rows 50) false 0]
      (rows cap)         [(rows cap) false 0]     ; exactly at the cap
      (rows (+ 50 cap))  [(rows cap) true 50]
      nil                [[] false 0]
      []                 [[] false 0])
    (testing "an explicit cap overrides the default"
      (is (= [(rows 5) true 15] (common/cap-rows (rows 20) 5))))))

(deftest cap-rows-is-pure
  (testing "repeat invocations on the same input produce equal output"
    (let [rows (mapv (fn [i] {:id i}) (range 300))]
      (is (= (common/cap-rows rows)
             (common/cap-rows rows))))))
