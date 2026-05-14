(ns re-frame.story-layout-debug-cljs-test
  "CLJS smoke tests for Stage 6 (rf2-zhwd) — layout-debug decorator
  trio. JVM coverage in `re-frame.story-layout-debug-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.layout-debug :as layout-debug]))

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  (layout-debug/reset-wrap-counter!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- registration --------------------------------------------------------

(deftest three-decorators-register
  (testing "the three layout-debug decorators register at boot"
    (let [decs (story/registrations :decorator)]
      (is (contains? decs :rf.story/layout-debug.measure))
      (is (contains? decs :rf.story/layout-debug.outline))
      (is (contains? decs :rf.story/layout-debug.pseudo)))))

(deftest public-ids-exposed
  (testing "the three public id Vars match the canonical ids"
    (is (= :rf.story/layout-debug.measure story/layout-debug-measure-id))
    (is (= :rf.story/layout-debug.outline story/layout-debug-outline-id))
    (is (= :rf.story/layout-debug.pseudo  story/layout-debug-pseudo-id))))

;; ---- decorator resolution -----------------------------------------------

(deftest decorator-resolves-as-hiccup
  (testing "a variant referencing layout-debug.outline resolves to :hiccup"
    (story/reg-variant* :story.x/outlined
                        {:decorators [[:rf.story/layout-debug.outline]]})
    (let [pack (decorators/resolve-decorators :story.x/outlined)]
      (is (= 1 (count (:hiccup pack))))
      (is (empty? (:errors pack))))))

;; ---- wrap shape ---------------------------------------------------------

(deftest measure-wrap-returns-style-block
  (testing "the measure wrap fn produces [:div {:class \"…\"} [:style ...] body]"
    (let [wrap (-> (story/handler-meta :decorator :rf.story/layout-debug.measure)
                    :wrap)
          out  (wrap [:span "x"] {})]
      (is (vector? out))
      (is (= :div (first out)))
      (let [attrs (second out)]
        (is (true? (:data-rf-story-measure attrs)))
        (is (string? (:class attrs)))))))

(deftest pseudo-wrap-ref-args
  (testing "pseudo wrap reads ref-args via :decorator/args"
    (let [wrap (-> (story/handler-meta :decorator :rf.story/layout-debug.pseudo)
                    :wrap)
          out  (wrap [:span "x"] {:decorator/args [#{:hover :focus}]})
          attrs (second out)]
      (is (re-find #"force-focus" (:class attrs)))
      (is (re-find #"force-hover" (:class attrs))))))
