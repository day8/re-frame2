(ns day8.re-frame2-machines-viz.chart.layout-error-cljs-test
  "rf2-4lyvh — pure-data tests for the ELK layout-failure surface.

  Pins the shapes the `chart.layout-error` ns produces:

    1. `input-summary` carries counts + direction + the layout-option
       KEY-SET (values omitted by design) and stays JVM-runnable.
    2. `error->data` handles the three thrown-value classes
       (nil, string, native error) without leaking environment-
       specific fields onto the trace bus.
    3. `layout-error-result` carries the empty-positions shape PLUS
       the `:layout-error` slot the chart projector reads to paint
       the in-panel indicator banner. Equality-pinned end-to-end so
       a refactor that drops the slot fails here.

  These pins guard the SHAPE consumers (Xray Issues panel; the
  chart's banner) read. `chart-cljs-test` (browser-only, sibling) is
  the place to wire a DOM-level pin for the banner itself; the data
  shape it reads must not drift, which is what this suite enforces.

  Cljc — the ns has no JS-interop touchpoints (it lives in
  `chart.layout-error` precisely so the JVM corpus can exercise it
  without loading elkjs/xyflow). Filename ends `-cljs-test` so the
  `:node-test` shadow build's `cljs-test$` regexp picks it up."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-machines-viz.chart.layout-error :as layout-error]))

;; ---- input-summary -----------------------------------------------------

(deftest input-summary-counts-flat-machine
  (testing "input-summary returns node/edge counts + region-count 0
            + parallel? false for a flat machine"
    (let [parsed   {:nodes [{:id :idle}
                            {:id :loading}
                            {:id :done}]
                    :edges [{:id :idle->loading}
                            {:id :loading->done}]
                    :parallel? false}
          summary  (layout-error/input-summary parsed :tb nil)]
      (is (= 3 (:node-count summary)) "node-count counts every node")
      (is (= 2 (:edge-count summary)) "edge-count counts every edge")
      (is (zero? (:region-count summary)) "no :region? nodes → region-count 0")
      (is (false? (:parallel? summary)) "flat machine → parallel? false")
      (is (= :tb (:direction summary)) "direction passes through")
      (is (= [] (:layout-option-ks summary))
          "nil layout-options → empty key-set vec"))))

(deftest input-summary-counts-parallel-machine
  (testing "input-summary counts region nodes separately + reports
            parallel? true for a parallel machine"
    (let [parsed  {:nodes [{:id :audio   :region? true}
                           {:id :display :region? true}
                           {:id :playing}
                           {:id :paused}
                           {:id :on}
                           {:id :off}]
                   :edges [{:id :a} {:id :b}]
                   :parallel? true}
          summary (layout-error/input-summary parsed :lr nil)]
      (is (= 6 (:node-count summary))
          "node-count includes synthetic region containers")
      (is (= 2 (:region-count summary))
          "region-count filters :region? nodes only")
      (is (true? (:parallel? summary)))
      (is (= :lr (:direction summary))))))

(deftest input-summary-layout-option-keys-only
  (testing "input-summary surfaces the option KEY-SET (not values)
            so caller-supplied option values do not bleed onto the
            trace bus"
    (let [parsed  {:nodes [] :edges []}
          opts    {"elk.algorithm" "layered"
                   "elk.direction" "DOWN"
                   "elk.spacing.nodeNode" "40"}
          summary (layout-error/input-summary parsed :tb opts)]
      (is (= ["elk.algorithm" "elk.direction" "elk.spacing.nodeNode"]
             (:layout-option-ks summary))
          "key-set is sorted + value-free")
      (is (not (contains? summary :layout-options))
          "no `:layout-options` slot — values must not leak"))))

;; ---- error->data -------------------------------------------------------

(deftest error->data-handles-nil
  (testing "error->data on nil returns the placeholder message"
    (is (= {:message "unknown error"}
           (layout-error/error->data nil)))))

(deftest error->data-handles-string
  (testing "error->data on a raw string returns it as :message
            (no name field — strings carry no class info)"
    (is (= {:message "elk: not a graph"}
           (layout-error/error->data "elk: not a graph")))))

(deftest error->data-handles-native-error
  (testing "error->data on a native Error / Throwable returns
            :message + :name. The native-error branch is cljs/clj-
            split inside the fn; this pin exercises whichever runtime
            the test compiles to."
    (let [e   #?(:cljs (js/Error. "boom")
                 :clj  (RuntimeException. "boom"))
          out (layout-error/error->data e)]
      (is (= "boom" (:message out)))
      (is (some? (:name out)) ":name field carries the error class"))))

(deftest error->data-omits-stack
  (testing "error->data never carries :stack — stacks are long,
            environment-specific, and bloat the trace bus. Pin the
            negative so a refactor that adds :stack fails here."
    (let [e   #?(:cljs (js/Error. "boom")
                 :clj  (RuntimeException. "boom"))
          out (layout-error/error->data e)]
      (is (not (contains? out :stack))))))

;; ---- layout-error-result -----------------------------------------------

(deftest layout-error-result-shape
  (testing "layout-error-result returns the canonical callback shape:
            empty positions + empty edge-points + :layout-error
            {:error :input-summary}. The (when result ...) guard at
            the chart callsite needs a TRUTHY map so the reset! still
            runs; that's what makes the banner appear instead of the
            silent no-op."
    (let [parsed  {:nodes [{:id :a}] :edges []}
          err     #?(:cljs (js/Error. "bad input")
                     :clj  (RuntimeException. "bad input"))
          result  (layout-error/layout-error-result err parsed :tb nil)]
      (is (map? result) "result is a map (so `when result` reset!s)")
      (is (= {} (:positions result)) "positions empty on failure")
      (is (= {} (:edge-points result)) "edge-points empty on failure")
      (is (some? (:layout-error result)) ":layout-error slot present")
      (is (= "bad input"
             (get-in result [:layout-error :error :message]))
          ":layout-error :error carries the error data")
      (is (= 1 (get-in result [:layout-error :input-summary :node-count]))
          ":layout-error :input-summary carries the parsed-graph summary"))))

(deftest layout-error-result-includes-direction-and-options-keys
  (testing "layout-error-result threads (direction, layout-option-ks)
            through onto :input-summary so the trace consumer can
            attribute the failure to a specific direction / option
            combination."
    (let [parsed  {:nodes [] :edges []}
          opts    {"elk.algorithm" "layered"}
          result  (layout-error/layout-error-result "boom" parsed :lr opts)]
      (is (= :lr (get-in result [:layout-error :input-summary :direction])))
      (is (= ["elk.algorithm"]
             (get-in result [:layout-error :input-summary :layout-option-ks]))))))
