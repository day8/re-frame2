(ns re-frame.late-bind-cache-test
  "The late-bind resolution cache and `chain-fn!` composition. Every dispatch
  and subscribe resolves hooks through `get-fn-cached`, so a stale slot serving
  a withdrawn hook is a silent correctness bug: nil resolutions are not cached,
  and `set-fn!` / `set-fns!` / `chain-fn!` / `invalidate-cache!` drop the slot.
  `chain-fn!` runs the last-registered step first, with the same args, and
  propagates every step's throw.

  Synthetic `:test/*` keys only; the fixture restores the private atoms."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.late-bind :as rf.late-bind]))

(defn isolate-hook-state [test-fn]
  (let [hooks-before     @(deref #'rf.late-bind/hooks)
        fn-cache-before  @(deref #'rf.late-bind/fn-cache)]
    (try
      (test-fn)
      (finally
        (reset! (deref #'rf.late-bind/hooks)    hooks-before)
        (reset! (deref #'rf.late-bind/fn-cache) fn-cache-before)))))

(use-fixtures :each isolate-hook-state)

(defn- cached?
  "True when `hook-key` currently has a populated cache slot."
  [hook-key]
  (contains? @(deref #'rf.late-bind/fn-cache) hook-key))

(deftest get-fn-cached-does-not-cache-nil-resolutions
  (let [k :test/g1-deferred]
    (is (nil? (rf.late-bind/get-fn-cached k)))
    (let [f (fn [] :late)]
      (rf.late-bind/set-fn! k f)
      (is (identical? f (rf.late-bind/get-fn-cached k))
          "a publication after a nil lookup is visible on the next call"))))

(deftest invalidate-cache!-drops-the-slot-so-the-next-lookup-re-resolves
  (let [k  :test/g1-invalidate
        f2 (fn [] :v2)]
    (rf.late-bind/set-fn! k (fn [] :v1))
    (rf.late-bind/get-fn-cached k)
    (swap! (deref #'rf.late-bind/hooks) assoc k f2)
    (rf.late-bind/invalidate-cache! k)
    (is (identical? f2 (rf.late-bind/get-fn-cached k)))))

(deftest set-fn!-invalidates-the-slot-so-hot-reload-swaps-the-fn
  (let [k  :test/g1-hot-reload
        f2 (fn [] :new)]
    (rf.late-bind/set-fn! k (fn [] :old))
    (rf.late-bind/get-fn-cached k)
    (rf.late-bind/set-fn! k f2)
    (is (identical? f2 (rf.late-bind/get-fn-cached k)))))

(deftest set-fns!-invalidates-each-cache-slot
  (let [k1    :test/rtk2e-inv-a
        k2    :test/rtk2e-inv-b
        new-a (fn [] :new-a)
        new-b (fn [] :new-b)]
    (rf.late-bind/set-fn! k1 (fn [] :old-a))
    (rf.late-bind/set-fn! k2 (fn [] :old-b))
    (run! rf.late-bind/get-fn-cached [k1 k2])
    (rf.late-bind/set-fns! {k1 new-a, k2 new-b})
    (is (= [new-a new-b] (mapv rf.late-bind/get-fn-cached [k1 k2])))))

(deftest chain-fn!-runs-the-last-registered-step-first-with-the-same-args
  (let [k    :test/g2-args
        seen (atom [])]
    (rf.late-bind/chain-fn! k (fn [a b] (swap! seen conj [:inner a b])))
    (rf.late-bind/chain-fn! k (fn [a b] (swap! seen conj [:outer a b])))
    ((rf.late-bind/get-fn k) 1 2)
    (is (= [[:outer 1 2] [:inner 1 2]] @seen))))

(deftest chain-fn!-propagates-per-step-throws
  (let [k          :test/g2-throw-outer
        inner-ran? (atom false)]
    (rf.late-bind/chain-fn! k (fn [_] (reset! inner-ran? true)))
    (rf.late-bind/chain-fn! k (fn [_] (throw (ex-info "boom-outer" {}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom-outer"
          ((rf.late-bind/get-fn k) :arg)))
    (is (false? @inner-ran?) "the throw stops the chain")))

(deftest chain-fn!-propagates-throw-from-a-previous-step
  (let [k :test/g2-throw-inner]
    (rf.late-bind/chain-fn! k (fn [_] (throw (ex-info "boom-inner" {}))))
    (rf.late-bind/chain-fn! k (fn [_] nil))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom-inner"
          ((rf.late-bind/get-fn k) :arg))
        "the outer step does not swallow the inner step's throw")))

(deftest chain-fn!-invalidates-the-cache-slot
  (let [k :test/g2-cache-invalidate]
    (rf.late-bind/set-fn! k (fn [_] :seed))
    (rf.late-bind/get-fn-cached k)
    (rf.late-bind/chain-fn! k (fn [_] nil))
    (is (not (cached? k)))))

(deftest racing-lookup-cannot-restore-a-superseded-fn
  ;; set-fn! publishes into `hooks` and then drops the slot. A reader that read
  ;; the old fn first and inserted it unconditionally afterwards would refill
  ;; the cleared slot for good (a JVM hot reload mid-event). Park the reader at
  ;; the memo insert, publish the replacement, then let it resume.
  (let [k       :test/d9x8-race
        old-fn  (fn [] :old)
        new-fn  (fn [] :new)
        parked  (promise)
        resume  (promise)
        insert! @#'rf.late-bind/cache-resolution!]
    (rf.late-bind/set-fn! k old-fn)
    (with-redefs [rf.late-bind/cache-resolution!
                  (fn [& args]
                    (deliver parked true)
                    (deref resume 3000 :timed-out)
                    (apply insert! args))]
      (let [reader (future (rf.late-bind/get-fn-cached k))]
        (is (= true (deref parked 3000 :timed-out)) "the reader parked mid-insert")
        (rf.late-bind/set-fn! k new-fn)
        (deliver resume true)
        (deref reader 3000 :timed-out)))
    (is (identical? new-fn (rf.late-bind/get-fn-cached k))
        "a lookup after the completed publication serves the replacement")))
