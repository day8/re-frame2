(ns re-frame.story-mcp.run-result-roundtrip-test
  "The shared run-result contract across the pure run boundary, Story UI Test
  mode and the MCP consumers."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
  (testing "the pure CLJS run boundary emits a schema-valid run-result"
    (let [green (rf.story.result/run-result {:epoch-tape []})
          red   (rf.story.result/run-result
                  {:assertions [{:assertion :rf.assert/path-equals
                                 :payload [[:cart/total] 99]
                                 :passed? false :expected 99 :actual nil}]})]
      (is (rf.story/valid-run-result? green)
          (str "green result must conform; "
               (rf.story/explain-run-result green)))
      (is (rf.story/valid-run-result? red)
          (str "red result must conform; "
               (rf.story/explain-run-result red)))
      (is (= :pass (rf.story/result-status green)))
      (is (= :fail (rf.story/result-status red)))
      (is (true?  (rf.story/result-passed? green)))
      (is (false? (rf.story/result-passed? red)))
      (is (not (contains? green :passing?)) "NO :passing? boolean — :status is the verdict")
      (is (not (contains? red   :passing?))))))

(deftest result-moves-cljs-to-ui-to-mcp-with-no-translation
  (testing "a CLJS run-result is consumed UNCHANGED by Story UI Test mode
            AND serialised UNCHANGED through the story-mcp run-variant path —
            same keys, same :status, no shim anywhere"
    (let [cljs-result
          (rf.story.result/run-result
            {:variant/id :story.cart/red
             :assertions [{:assertion :rf.assert/path-equals
                           :payload [[:cart/total] 99]
                           :passed? false :expected 99 :actual nil}]})]

      (is (rf.story/valid-run-result? cljs-result))
      (is (= :fail (rf.story/result-status cljs-result)))

      (let [summary (assoc (rf.story.ui.state.tests/aggregate-summary (:assertions cljs-result))
                           :status (:status cljs-result))
            ui-state (rf.story.ui.state.tests/record-test-run {} :story.cart/red summary)
            ui-status (rf.story.ui.state.tests/variant-test-status ui-state :story.cart/red)]
        (is (= 1 (:failed summary)) "the UI fold sees the failing record")
        (is (false? (:all-passed? summary)))
        (is (= :fail ui-status)
            "Story UI Test mode reports the SAME verdict, read off the same :status"))

      (let [wire   (invoke "run-variant" {:variant-id "story.cart/red"})
            s      (:structuredContent wire)]
        (is (vector? (:content wire)))
        (is (= :story.cart/red (:frame s)))
        (is (= :fail (:status s))
            "the MCP wire verdict is the SAME :status the CLJS boundary minted")
        (is (not (contains? s :passing?))
            "no :passing? boolean appears on the wire")
        (is (rf.story/valid-run-result? s)
            (str "the MCP wire payload conforms to the SAME frozen schema; "
                 (rf.story/explain-run-result s)))))))

(deftest run-variant-wire-carries-plan-and-run-hash
  (testing "the MCP run-variant payload carries the :plan-hash / :run-hash the
            run minted — strings, reproduced on rerun, the plan hash equal to the
            public primitive over the variant's plan (never a pinned digest)"
    (is (string? (:run-hash (rf.story.result/run-result {:epoch-tape []})))
        "the pure CLJS run boundary mints one")
    (let [s1 (:structuredContent (invoke "run-variant" {:variant-id "story.cart/red"}))
          s2 (:structuredContent (invoke "run-variant" {:variant-id "story.cart/red"}))]
      (is (= :fail (:status s1)) "non-vacuity: the real red run, not an error shape")
      (is (string? (:plan-hash s1)))
      (is (string? (:run-hash s1)))
      (is (= (rf.story/plan-hash (rf.story/variant-plan :story.cart/red))
             (:plan-hash s1)))
      (is (= (:plan-hash s1) (:plan-hash s2)))
      (is (= (:run-hash s1) (:run-hash s2)))
      (is (rf.story/valid-run-result? s1)
          (str "the wire payload still conforms; " (rf.story/explain-run-result s1))))))

(deftest result-passed-agrees-with-result-status
  (testing "result-passed? is true IFF result-status is :pass — for every verdict"
    (doseq [[status result] [[:pass       {:status :pass       :assertions [] :checks [] :consumed-selectors #{}}]
                             [:fail       {:status :fail       :assertions [] :checks [] :consumed-selectors #{}}]
                             [:cannot-run {:status :cannot-run :assertions [] :checks [] :consumed-selectors #{}}]
                             [:error      {:status :error      :assertions [] :checks [] :consumed-selectors #{}}]]]
      (is (rf.story/valid-run-result? result) (str status " result must conform"))
      (is (= status (rf.story/result-status result)))
      (is (= (= :pass status) (rf.story/result-passed? result))
          (str "result-passed? must agree with :status for " status)))))

(deftest read-failures-speaks-the-same-status-language
  (testing "a run then read-failures both report :status — no :passing? boolean"
    (rf.story-mcp.config/set-allow-writes! true)
    (invoke "run-variant" {:variant-id "story.cart/red"})
    (let [rf (invoke "read-failures" {:variant-id "story.cart/red"})
          s  (:structuredContent rf)]
      (is (not (contains? s :passing?)) "NO :passing? on read-failures either")
      (is (= :fail (:status s))
          "the failing record drives :fail via the same aggregation rule"))))
