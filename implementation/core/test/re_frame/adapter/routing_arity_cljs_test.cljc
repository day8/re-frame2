(ns re-frame.adapter.routing-arity-cljs-test
  "Arity fidelity of `route-hook!`'s routed closure on its three routes: ACTIVE
  (this adapter is installed, so `impl-fn` runs), CHAIN (another adapter is
  installed, so the previously registered link runs) and FALLBACK (nothing
  installed and no previous link, so `fallback-fn` runs as a zero-argument
  thunk). The closure spells out 0, 1 and 2 arguments and takes a variadic tail
  for more, so each route is driven at 0 to 4 arguments."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- cold-adapter [test-fn]
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (test-fn)
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!))

(use-fixtures :each cold-adapter)

;; `route-hook!` chains onto whatever is already registered under its key, so
;; each test takes a key of its own.
(def ^:private active-key   :rf.test/routing-arity-active)
(def ^:private chain-key    :rf.test/routing-arity-chain)
(def ^:private fallback-key :rf.test/routing-arity-fallback)

(def ^:private arg-vectors [[] [:a] [:a :b] [:a :b :c] [:a :b :c :d]])

(defn- call-at-every-arity
  "Call `f` at 0 to 4 arguments, written out rather than `apply`d so each
  explicit arity is entered the way a real caller enters it."
  [f]
  [(f) (f :a) (f :a :b) (f :a :b :c) (f :a :b :c :d)])

(defn- recorder
  "A hook impl answering `[tag args]`, so its result shows exactly what it received."
  [tag]
  (fn [& args] [tag (vec args)]))

(deftest routed-hook-forwards-every-arity-to-the-live-impl
  (rf.substrate.adapter/route-hook! rf.substrate.plain-atom/adapter active-key
                                    (recorder :impl) (constantly :fell-through))
  (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter)
  (is (= (mapv (fn [args] [:impl args]) arg-vectors)
         (call-at-every-arity (rf.late-bind/get-fn active-key)))))

(deftest routed-hook-falls-through-to-the-chain-at-every-arity
  (rf.substrate.adapter/route-hook! rf.substrate.plain-atom/adapter chain-key
                                    (recorder :inner) (constantly :inner-fallback))
  ;; An outer link for another adapter kind, inactive while plain-atom is installed.
  (rf.substrate.adapter/route-hook! (assoc rf.substrate.plain-atom/adapter :kind :rf.adapter/uix)
                                    chain-key
                                    (recorder :outer) (constantly :outer-fallback))
  (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter)
  (is (= (mapv (fn [args] [:inner args]) arg-vectors)
         (call-at-every-arity (rf.late-bind/get-fn chain-key)))))

(deftest routed-hook-falls-back-to-a-zero-arg-thunk-at-every-arity
  (rf.substrate.adapter/route-hook! rf.substrate.plain-atom/adapter fallback-key
                                    (recorder :impl) (recorder :fell-through))
  (is (= (repeat 5 [:fell-through []])
         (call-at-every-arity (rf.late-bind/get-fn fallback-key)))))
