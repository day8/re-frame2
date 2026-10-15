(ns re-frame.story-mcp.run-result-roundtrip-test
  "The shared run-result contract across the pure run boundary, Story UI Test
  mode and the MCP consumers."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.story :as rf.story]
            [re-frame.story.recorder :as rf.story.recorder]
            [re-frame.story.result :as rf.story.result]
            [re-frame.story.ui.state.tests :as rf.story.ui.state.tests]
            [re-frame.story-mcp.config :as rf.story-mcp.config]
            [re-frame.story-mcp.tools.wire-pipeline :as rf.story-mcp.tools.wire-pipeline]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-story
  [t]
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (rf.story-mcp.config/set-allow-writes! false)
  (rf.story-mcp.config/set-allow-sensitive-reads! false)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.story.recorder/clear!)
  ;; Keep epoch evidence out of the wire budget on a wider test classpath.
  (rf/configure! {:epoch-history {:depth 0}})
  (rf.story/reg-story :story.cart
    {:doc "A cart." :component :app.ui/cart :tags #{:dev :test} :args {}})
  (rf.story/reg-variant :story.cart/red
    {:doc "A failing cart variant."
     :tags #{:dev :test}
     :assertions [[:rf.assert/path-equals [:cart/total] 99]]})
  (try
    (t)
    (finally
      (rf/configure! {:epoch-history {:depth 50}}))))

(use-fixtures :each reset-story)

(defn- invoke [tool-name args]
  (rf.story-mcp.tools.wire-pipeline/invoke-tool tool-name (merge {:dedup false} args)))

(deftest cljs-result-conforms-to-the-frozen-schema
  (let [green (rf.story.result/run-result {:epoch-tape []})
        red   (rf.story.result/run-result
                {:assertions [{:assertion :rf.assert/path-equals
                               :payload [[:cart/total] 99]
                               :passed? false :expected 99 :actual nil}]})]
    (doseq [[result status passed?] [[green :pass true] [red :fail false]]]
      (is (= [true status passed? false]
             [(rf.story/valid-run-result? result) (rf.story/result-status result)
              (rf.story/result-passed? result) (contains? result :passing?)])
          (str (rf.story/explain-run-result result))))))

(deftest result-moves-cljs-to-ui-to-mcp-with-no-translation
  (let [cljs-result (rf.story.result/run-result
                      {:variant/id :story.cart/red
                       :assertions [{:assertion :rf.assert/path-equals
                                     :payload [[:cart/total] 99]
                                     :passed? false :expected 99 :actual nil}]})
        summary     (assoc (rf.story.ui.state.tests/aggregate-summary (:assertions cljs-result))
                           :status (:status cljs-result))
        ui-state    (rf.story.ui.state.tests/record-test-run {} :story.cart/red summary)
        wire        (invoke "run-variant" {:variant-id "story.cart/red"})
        s           (:structuredContent wire)]
    (is (= [true :fail] [(rf.story/valid-run-result? cljs-result) (rf.story/result-status cljs-result)]))
    (is (= [1 false :fail]
           [(:failed summary) (:all-passed? summary)
            (rf.story.ui.state.tests/variant-test-status ui-state :story.cart/red)]))
    (is (= [true :story.cart/red :fail false true]
           [(vector? (:content wire)) (:frame s) (:status s) (contains? s :passing?)
            (rf.story/valid-run-result? s)])
        (str (rf.story/explain-run-result s)))))

(deftest run-variant-wire-carries-plan-and-run-hash
  (is (string? (:run-hash (rf.story.result/run-result {:epoch-tape []}))))
  (let [s1 (:structuredContent (invoke "run-variant" {:variant-id "story.cart/red"}))
        s2 (:structuredContent (invoke "run-variant" {:variant-id "story.cart/red"}))]
    (is (= [:fail true true true]
           [(:status s1) (string? (:plan-hash s1)) (string? (:run-hash s1))
            (rf.story/valid-run-result? s1)])
        (str (rf.story/explain-run-result s1)))
    (is (= (rf.story/plan-hash (rf.story/variant-plan :story.cart/red))
           (:plan-hash s1) (:plan-hash s2)))
    (is (= (:run-hash s1) (:run-hash s2)))))

(deftest result-passed-agrees-with-result-status
  (doseq [[status passed?] [[:pass true] [:fail false] [:cannot-run false] [:error false]]]
    (let [result {:status status :assertions [] :checks [] :consumed-selectors #{}}]
      (is (= [true status passed?]
             [(rf.story/valid-run-result? result) (rf.story/result-status result)
              (rf.story/result-passed? result)])
          (str status)))))

(deftest read-failures-speaks-the-same-status-language
  (invoke "run-variant" {:variant-id "story.cart/red"})
  (let [s (:structuredContent (invoke "read-failures" {:variant-id "story.cart/red"}))]
    (is (= [false :fail] [(contains? s :passing?) (:status s)]))))
