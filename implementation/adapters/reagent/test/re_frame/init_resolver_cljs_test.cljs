(ns re-frame.init-resolver-cljs-test
  "`(rf/init! ...)`'s explicit-adapter contract on the CLJS host, where
  there is no default-adapter registry; the JVM half is `re-frame.boot-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]))

;; ---- fixture --------------------------------------------------------------
;;
;; Cold-start, not make-reset-runtime-fixture: that pre-installs an adapter,
;; and the unit under test IS init!.

(defn- cold-start-fixture [test-fn]
  (rf.substrate.adapter/dispose-adapter!)
  (test-fn)
  (rf.substrate.adapter/dispose-adapter!))

(use-fixtures :each cold-start-fixture)

;; ---- tests ----------------------------------------------------------------

(deftest init-explicit-installs-reagent
  (testing "(rf/init! reagent/adapter) installs the Reagent adapter"
    (is (nil? (rf.substrate.adapter/current-adapter))
        "precondition: no adapter installed")
    (rf/init! rf.adapter.reagent/adapter)
    (let [installed (rf.substrate.adapter/current-adapter)]
      (is (= [true :rf.adapter/reagent] [(identical? rf.adapter.reagent/adapter installed) (:kind installed)])
          "explicit init! installed the Reagent adapter map, whose :kind is the Spec 006 discriminator"))))

(deftest init-no-arg-raises-arity-error
  (testing "(rf/init!) with no args raises a language-level arity error (there is no no-arg arity)"
    ;; The throw's type depends on compilation mode, so only that one is
    ;; thrown is asserted. `apply` keeps the bad arity out of the compiler's
    ;; static warning.
    (let [thrown (try
                   (apply rf/init! [])
                   nil
                   (catch :default e e))]
      (is (= [true nil] [(some? thrown) (rf.substrate.adapter/current-adapter)])
          "rf/init! with no args raises (no such arity) and installs no adapter"))))

(deftest init-keyword-raises
  (testing "(rf/init! :reagent) raises — the keyword form is not supported"
    (let [thrown (try
                   (rf/init! :reagent)
                   nil
                   (catch :default e e))]
      (is (= [:rf.error/no-adapter-specified :reagent nil]
             [(some-> thrown ex-data :rf.error/id) (some-> thrown ex-data :received)
              (rf.substrate.adapter/current-adapter)])
          "rf/init! with a keyword raises :rf.error/no-adapter-specified echoing the keyword, and installs no adapter"))))
