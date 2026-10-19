(ns re-frame.test-support-flush-views-cljs-test
  "`re-frame.test-support/flush-views!` where no view renders live: the
  plain-atom adapter on both hosts. There is no tree to commit, so the
  helper runs `f` and returns nil rather than dropping the state change `f`
  carries; with the adapter torn down it refuses as loudly as the
  `flush-render!` it wraps. The React commit itself is pinned per adapter
  by the browser lane: `re-frame.test-support-flush-views-dom-cljs-test`
  (Reagent) and `re-frame.adapter.uix-component-recipe-dom-cljs-test` (UIx)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(deftest flush-views-runs-f-and-returns-nil-with-no-live-tree
  (testing "plain-atom ships no flush-render!, and the state change still lands"
    (let [ran (atom 0)]
      (is (nil? (rf.test-support/flush-views! #(swap! ran inc)))
          "the 1-arity returns nil")
      (is (= 1 @ran) "f ran exactly once")
      (is (nil? (rf.test-support/flush-views!))
          "the 0-arity returns nil")
      (is (= 1 @ran) "the 0-arity runs nothing of the caller's"))))

(deftest flush-render-runs-f-when-the-adapter-has-no-commit
  (testing "the core delegation runs f under an adapter with no :flush-render!"
    (let [ran (atom 0)]
      (is (nil? (:flush-render! rf.substrate.plain-atom/adapter))
          "control: plain-atom carries no :flush-render! slot")
      (is (nil? (rf.substrate.adapter/flush-render! #(swap! ran inc))))
      (is (= 1 @ran) "f ran, so a dispatch handed to flush-render! is not dropped"))))

(deftest flush-views-refuses-once-the-adapter-is-torn-down
  (try
    (rf.substrate.adapter/dispose-adapter!)
    (let [ran (atom 0)
          id  (try (rf.test-support/flush-views! #(swap! ran inc))
                   nil
                   (catch #?(:clj Exception :cljs :default) e
                     (:rf.error/id (ex-data e))))]
      (is (= :rf.error/adapter-disposed id)
          "the same refusal flush-render! raises")
      (is (zero? @ran) "f did not run against a torn-down runtime"))
    (finally
      (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter))))
