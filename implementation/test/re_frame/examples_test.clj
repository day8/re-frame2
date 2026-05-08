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
  ;; clear-all! also drops the framework-shipped fxs that register at
  ;; namespace load time (e.g. :rf.http/managed and its canned-stub
  ;; siblings). Reload the relevant ns so the toplevel reg-fx forms run
  ;; again and the framework substrate is back in place — the examples
  ;; routinely route :rf.http/managed via :fx-overrides to those stubs
  ;; (Spec 014 §Testing).
  (require 're-frame.http-managed :reload)
  ;; Drop any cached require of example namespaces so each test re-evaluates
  ;; their namespace-level handlers against a fresh registrar.
  (remove-ns 'ssr.core)
  (remove-ns 'state-machine-walkthrough.core)
  (test-fn))

(use-fixtures :each reset-runtime)

(deftest ssr-example-runs-end-to-end
  (testing "examples/ssr/core.cljc runs its built-in headless tests"
    (require 'ssr.core :reload)
    (let [result (@(resolve 'ssr.core/ssr-tests))]
      (is (= :ok result)
          "ssr.core/ssr-tests returned :ok — the full server flow worked"))))

(deftest state-machine-walkthrough-runs-headless
  (testing "examples/state-machine-walkthrough/core.cljc — every code block in
            ch.05 § Headless testing runs and matches the chapter's claims."
    (require 'state-machine-walkthrough.core :reload)
    (let [result (@(resolve 'state-machine-walkthrough.core/smoke-tests))]
      (is (= :ok result)
          "state-machine-walkthrough.core/smoke-tests returned :ok — the
           login-machine drove through happy path, retry-then-lockout, and
           pure machine-transition tests."))))
