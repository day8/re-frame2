(ns re-frame.init-resolver-cljs-test
  "CLJS coverage for `(rf/init! ...)`'s explicit-adapter contract
  (rf2-agql, replaces rf2-84po).

  The JVM half (re-frame.boot-test) covers the boot semantics
  end-to-end. This namespace adds CLJS-specific coverage: requiring
  re-frame.adapter.reagent does NOT register the Reagent adapter
  anywhere — there is no default-adapter registry — so the consumer
  must always pass `reagent-adapter/adapter` explicitly.

  ns ends in -cljs-test so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; ---- fixture --------------------------------------------------------------
;;
;; Cold-start: dispose any installed adapter before each test so the
;; init! call we exercise installs fresh. We do NOT use the
;; make-reset-runtime-fixture here — it pre-installs an adapter for us, and
;; the unit under test IS init!.

(defn cold-start [test-fn]
  (adapter/dispose-adapter!)
  (test-fn)
  (adapter/dispose-adapter!))

(use-fixtures :each cold-start)

;; ---- tests ----------------------------------------------------------------

(deftest reagent-ns-load-has-no-side-effects
  (testing "requiring re-frame.adapter.reagent does NOT auto-install or auto-register"
    (is (nil? (adapter/current-adapter))
        "ns-load does not install the adapter")
    (is (map? reagent-adapter/adapter)
        "the adapter ns exports its spec as the public `adapter` var")
    (is (= 9 (count (filter fn? (vals reagent-adapter/adapter))))
        "the exported adapter map carries the full nine-fn contract")
    (is (= :rf.adapter/reagent (:kind reagent-adapter/adapter))
        "the adapter map carries its discriminator keyword under :kind")))

(deftest init-explicit-installs-reagent
  (testing "(rf/init! reagent/adapter) installs the Reagent adapter"
    (is (nil? (adapter/current-adapter))
        "precondition: no adapter installed")
    (rf/init! reagent-adapter/adapter)
    (is (identical? reagent-adapter/adapter (adapter/current-adapter-spec))
        "explicit init! installed the Reagent adapter (map identity)")
    (is (= :rf.adapter/reagent (adapter/current-adapter))
        "current-adapter returns the discriminator keyword per Spec 006")))

(deftest init-no-arg-raises-arity-error
  (testing "(rf/init!) with no args raises a language-level arity error (rf2-3ubmv — no-arg arity cut)"
    ;; Per rf2-3ubmv the no-arg arity was cut from the fn defn entirely
    ;; so `(rf/init!)` raises before reaching the runtime ex-info path.
    ;; CLJS surfaces this as an ordinary Error / TypeError depending on
    ;; compilation mode; we assert only that *something* throws and no
    ;; adapter is installed. Use `apply` to keep the intentional bad
    ;; arity a runtime assertion without a static compiler warning.
    (let [thrown (try
                   (apply rf/init! [])
                   nil
                   (catch :default e e))]
      (is (some? thrown)
          "rf/init! with no args raises (cut arity)"))
    (is (nil? (adapter/current-adapter))
        "the failed init! did NOT install any adapter")))

(deftest init-keyword-raises
  (testing "(rf/init! :reagent) raises — keyword form is not supported under rf2-agql"
    (let [thrown (try
                   (rf/init! :reagent)
                   nil
                   (catch :default e e))]
      (is (some? thrown)
          "rf/init! with a keyword raises")
      (is (= ":rf.error/no-adapter-specified"
             (some-> thrown ex-message))
          "ex-message carries the :rf.error/no-adapter-specified tag")
      (let [data (ex-data thrown)]
        (is (= :reagent (:received data))
            "ex-data echoes the offending keyword")))
    (is (nil? (adapter/current-adapter))
        "the failed init! did NOT install any adapter")))
