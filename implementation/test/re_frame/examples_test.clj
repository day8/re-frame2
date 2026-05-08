(ns re-frame.examples-test
  "Integration tests against the example apps in ../examples/. Each test
  exercises the full event → state → render pipeline as a real user would
  wire it, catching API ergonomics regressions that pure unit tests miss."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.flows :as flows]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (rf/init!)
  ;; Drop any cached require of ssr.core so each test re-evaluates the
  ;; example's namespace-level handlers against a fresh registrar.
  (remove-ns 'ssr.core)
  (test-fn))

(use-fixtures :each reset-runtime)

(deftest ssr-example-runs-end-to-end
  (testing "examples/ssr/core.cljc runs its built-in headless tests"
    (require 'ssr.core :reload)
    (let [result (@(resolve 'ssr.core/ssr-tests))]
      (is (= :ok result)
          "ssr.core/ssr-tests returned :ok — the full server flow worked"))))
