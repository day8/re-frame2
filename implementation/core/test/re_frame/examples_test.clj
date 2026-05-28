(ns re-frame.examples-test
  "Integration tests against the example apps in ../examples/. Each test
  exercises the full event → state → render pipeline as a real user would
  wire it, catching API ergonomics regressions that pure unit tests miss.

  Per rf2-kx74 examples are grouped per substrate; the example sources
  (`ssr.core`, `ssr-streaming.core`, `state-machine-walkthrough.core`)
  live under
  ../examples/reagent/{ssr,ssr_streaming,state_machine_walkthrough}/ on
  disk. Per rf2-ee38b.25 each example's headless test fns moved OUT of the
  example source into a sibling `test/` namespace (`ssr.core-test`,
  `ssr-streaming.core-test`, `state-machine-walkthrough.core-test`) so the
  example source a learner reads is pure demonstrative code — matching the
  test-free split realworld / nine_states / boot already use."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  ;; clear-all! also drops the framework-shipped fxs that register at
  ;; namespace load time (e.g. :rf.http/managed and its canned-stub
  ;; siblings). Reload the relevant ns so the toplevel reg-fx forms run
  ;; again and the framework substrate is back in place — the examples
  ;; routinely route :rf.http/managed via :fx-overrides to those stubs
  ;; (Spec 014 §Testing).
  (require 're-frame.http-managed :reload)
  ;; rf2-cdmle — the canned-stub fxs (`:rf.http/managed-canned-success`,
  ;; `:rf.http/managed-canned-failure`) register from
  ;; re-frame.http-test-support, NOT re-frame.http-managed. Reload to
  ;; re-fire the registration body after clear-all!. The transitive
  ;; require from each example ns wouldn't re-evaluate the body (Clojure
  ;; require is idempotent without :reload-all), so reload here.
  (require 're-frame.http-test-support :reload)
  ;; Drop any cached require of example namespaces (and their sibling test
  ;; namespaces) so each test re-evaluates their namespace-level handlers
  ;; against a fresh registrar.
  (remove-ns 'ssr.core)
  (remove-ns 'ssr.core-test)
  (remove-ns 'ssr-streaming.core)
  (remove-ns 'ssr-streaming.core-test)
  (remove-ns 'state-machine-walkthrough.core)
  (remove-ns 'state-machine-walkthrough.core-test)
  (test-fn))

(use-fixtures :each reset-runtime)

(deftest ssr-example-runs-end-to-end
  (testing "examples/reagent/ssr — the sibling test ns drives the server flow"
    (require 'ssr.core-test :reload)
    (let [result (@(resolve 'ssr.core-test/ssr-tests))]
      (is (= :ok result)
          "ssr.core-test/ssr-tests returned :ok — the full server flow worked"))))

(deftest ssr-streaming-example-runs-end-to-end
  (testing "examples/reagent/ssr_streaming — the sibling test ns drives the stream"
    (require 'ssr-streaming.core-test :reload)
    (let [result (@(resolve 'ssr-streaming.core-test/streaming-tests))]
      (is (= :ok result)
          "ssr-streaming.core-test/streaming-tests returned :ok — the full
           server streaming flow (shell + per-card chunks + final payload)
           worked"))))

(deftest state-machine-walkthrough-runs-headless
  (testing "examples/reagent/state-machine-walkthrough — the sibling test ns
            drives every ch.09 §Headless testing scenario."
    (require 'state-machine-walkthrough.core-test :reload)
    (let [result (@(resolve 'state-machine-walkthrough.core-test/smoke-tests))]
      (is (= :ok result)
          "state-machine-walkthrough.core-test/smoke-tests returned :ok — the
           login-machine drove through happy path, retry-then-lockout, and
           pure machine-transition tests."))))
