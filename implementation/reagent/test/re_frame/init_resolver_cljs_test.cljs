(ns re-frame.init-resolver-cljs-test
  "CLJS coverage for `(rf/init!)`'s default-adapter resolver (rf2-84po,
  resolves rf2-4cb6).

  The JVM half (re-frame.boot-test) covers the resolver semantics
  end-to-end. This namespace adds the CLJS-specific branches: under
  CLJS, requiring `re-frame.substrate.reagent` registers the Reagent
  adapter as the default at ns-load time, so a no-arg `(rf/init!)`
  picks Reagent without an explicit adapter argument. The multi-adapter
  branch is exercised by registering a synthetic second key alongside
  Reagent — observable behaviour is the same whether the second adapter
  is UIx or a fake; the resolver's arity is what matters.

  ns ends in -cljs-test so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.substrate.reagent :as reagent-adapter]))

;; ---- fixture --------------------------------------------------------------
;;
;; Cold-start: dispose any installed adapter before each test so the
;; init! call we exercise installs fresh. We do NOT use the
;; reset-runtime-fixture here — it pre-installs an adapter for us, and
;; the unit under test IS init!'s adapter resolution.

(defn cold-start [test-fn]
  (adapter/dispose-adapter!)
  (test-fn)
  (adapter/dispose-adapter!))

(use-fixtures :each cold-start)

;; ---- tests ----------------------------------------------------------------

(deftest reagent-ns-load-registers-as-default
  (testing "requiring re-frame.substrate.reagent registers :reagent as a default adapter at ns-load time"
    (is (contains? (adapter/registered-default-adapters) :reagent)
        "ns-load side-effect populates the registry")
    (is (identical? reagent-adapter/adapter
                    (get (adapter/registered-default-adapters) :reagent))
        "the registered spec is the public adapter map")))

(deftest plain-atom-not-registered-on-cljs
  (testing "on CLJS the plain-atom adapter is NOT a default-resolution candidate"
    ;; Per rf2-84po the plain-atom adapter on CLJS is reachable as a
    ;; programmer-explicit choice (init! plain-atom/adapter), but it
    ;; does not auto-register as a default — that keeps the
    ;; multi-adapter resolution policy meaningful.
    (is (not (contains? (adapter/registered-default-adapters) :plain-atom))
        "plain-atom is absent from the CLJS default-adapter registry")))

(deftest init-no-arg-picks-reagent-on-cljs
  (testing "(rf/init!) with no args resolves to the Reagent adapter (the only registered default in this build)"
    (is (nil? (adapter/current-adapter))
        "precondition: no adapter installed")
    (rf/init!)
    (is (identical? reagent-adapter/adapter (adapter/current-adapter))
        "no-arg init! installed the Reagent adapter via the registry")))

(deftest init-multiple-defaults-raises
  (testing "two default adapters registered → no-arg init! raises :rf.error/multiple-default-adapters"
    (let [synth-key   :test-fake-substrate
          synth-spec  plain-atom/adapter]
      (try
        (adapter/register-default-adapter! synth-key synth-spec)
        (is (= #{:reagent synth-key} (set (keys (adapter/registered-default-adapters))))
            "precondition: two adapters registered as defaults")
        (let [thrown (try
                       (rf/init!)
                       nil
                       (catch :default e e))]
          (is (some? thrown)
              "rf/init! with no args raises when >1 adapters registered")
          (is (= ":rf.error/multiple-default-adapters"
                 (some-> thrown ex-message))
              "the thrown ex-info carries the :rf.error/multiple-default-adapters tag")
          (let [data (ex-data thrown)]
            (is (= 'init! (:where data))
                "ex-data identifies the calling fn")
            (is (= :no-recovery (:recovery data))
                "ex-data flags :no-recovery — the call must be re-issued with a key")
            (is (= #{:reagent synth-key} (set (:keys data)))
                "ex-data enumerates exactly the registered keys"))
          (is (nil? (adapter/current-adapter))
              "the failed init! did NOT install any adapter"))
        (finally
          (adapter/unregister-default-adapter! synth-key))))))

(deftest init-keyword-disambiguates
  (testing "(rf/init! :reagent) bypasses default-resolution and uses the named adapter"
    (let [synth-key   :test-fake-substrate-3
          synth-spec  plain-atom/adapter]
      (try
        (adapter/register-default-adapter! synth-key synth-spec)
        ;; Two registered, but the keyword form sidesteps the multi-default error.
        (rf/init! :reagent)
        (is (identical? reagent-adapter/adapter (adapter/current-adapter))
            "init! :reagent installed the named adapter despite the multi-default state")
        (finally
          (adapter/unregister-default-adapter! synth-key))))))

(deftest init-zero-defaults-raises
  (testing "no default adapters registered → no-arg init! raises :rf.error/no-adapter-registered"
    ;; Drain the registry temporarily; restore on exit so subsequent
    ;; tests see the canonical post-ns-load state.
    (let [reagent-spec (get (adapter/registered-default-adapters) :reagent)]
      (try
        (adapter/unregister-default-adapter! :reagent)
        (is (empty? (adapter/registered-default-adapters))
            "precondition: registry is empty")
        (let [thrown (try
                       (rf/init!)
                       nil
                       (catch :default e e))]
          (is (some? thrown)
              "rf/init! with no args raises when zero adapters registered")
          (is (= ":rf.error/no-adapter-registered"
                 (some-> thrown ex-message))
              "the thrown ex-info carries the :rf.error/no-adapter-registered tag"))
        (finally
          (adapter/register-default-adapter! :reagent reagent-spec))))))
