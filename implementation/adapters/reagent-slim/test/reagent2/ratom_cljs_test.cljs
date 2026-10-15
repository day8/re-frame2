(ns reagent2.ratom-cljs-test
  "Reactive atom, dependency capture, disposal, scheduling and printing contracts."
  (:require [cljs.test :refer-macros [deftest is]]
            [reagent2.ratom :as ratom :refer-macros [reaction]]))

(deftest ratom-construction
  (let [a (ratom/atom 0 :validator number?)]
    (reset! a 5)
    (is (= 5 @a))
    (is (thrown? js/Error (reset! a "bad"))))
  (doseq [a [(ratom/atom 0) (ratom/make-reaction (fn [] 0))]]
    (is (satisfies? ratom/IReactiveAtom a))))

(deftest ratom-reset-swap
  (let [a (ratom/atom 0)]
    (is (= 7 (reset! a 7)))

    (is (= 8 (swap! a inc)))

    (is (= 18 (swap! a + 10)))
    (is (= 28 (swap! a + 5 5)))
    (is (= 38 (swap! a + 1 2 3 4)))))

(deftest ratom-watches
  (let [a     (ratom/atom 0)
        fired (atom [])]
    (add-watch a :w (fn [k _r prev nu] (swap! fired conj [k prev nu])))
    (reset! a 1)
    (reset! a 2)

    (is (= [[:w 0 1] [:w 1 2]] @fired)))

  (let [a     (ratom/atom 0)
        fired (atom 0)]
    (add-watch a :w (fn [_ _ _ _] (swap! fired inc)))
    (reset! a 1)
    (remove-watch a :w)
    (reset! a 2)
    (is (= 1 @fired))))

(deftest ratom-meta
  (let [a (ratom/atom 0 :meta {:k :v})
        b (with-meta a {:k :z})]
    (is (= [{:k :v} {:k :z} 0] [(meta a) (meta b) @b]))))

(deftest ratom-identity-equality
  (let [a (ratom/atom 1)
        b (ratom/atom 1)]
    (is (not= a b))))

(deftest reaction-equality-memo
  (let [a           (ratom/atom {:k 1})
        watch-calls (atom 0)
        r           (ratom/make-reaction (fn [] (:k @a)) :auto-run true)]
    (is (= 1 @r))
    (add-watch r :w (fn [_ _ _ _] (swap! watch-calls inc)))
    (reset! a {:k 1})
    (is (= 0 @watch-calls))
    (reset! a {:k 2})
    (is (= [1 2] [@watch-calls @r]))))

;; Auto-run makes these checks depend on capture rather than on-demand reads.
(deftest reaction-multiple-deps
  (let [a (ratom/atom 2)
        b (ratom/atom 3)
        r (ratom/make-reaction (fn [] (+ @a @b)) :auto-run true)]
    (is (= 5 @r))
    (reset! a 10)
    (is (= 13 @r))
    (reset! b 100)
    (is (= 110 @r))))

(deftest reaction-dependency-pruning
  (let [flag (ratom/atom true)
        a    (ratom/atom 1)
        b    (ratom/atom 100)
        runs (atom 0)
        r    (ratom/make-reaction (fn [] (swap! runs inc) (if @flag @a @b))
                                  :auto-run true)]
    (is (= 1 @r))

    (reset! flag false)
    (is (= 100 @r))

    (let [runs-before @runs]
      (reset! a 999)
      (is (= runs-before @runs)
          "mutating the dropped dependency `a` does not re-run r's body")
      (is (= 100 @r)))))

(deftest reaction-dispose-clears-watches
  (let [a              (ratom/atom 0)
        r              (ratom/make-reaction (fn [] @a) :auto-run true)
        _              @r
        watches-before (.-watches a)]
    (is (some? watches-before))
    (ratom/dispose! r)
    (let [watches-after (.-watches a)]

      (is (or (nil? watches-after)
              (empty? watches-after))))))

(deftest reaction-dispose-is-idempotent
  (let [fired (atom [])
        r     (ratom/make-reaction
                (fn [] 1)
                :on-dispose (fn [_] (swap! fired conj :on-dispose-kwarg)))]
    (ratom/add-on-dispose! r (fn [_] (swap! fired conj :added-1)))
    (ratom/add-on-dispose! r (fn [_] (swap! fired conj :added-2)))
    (ratom/dispose! r)
    (is (= [:on-dispose-kwarg :added-1 :added-2] @fired)
        "first dispose! fired the kwarg then the array callbacks in registration order")
    (ratom/dispose! r)
    (is (= [:on-dispose-kwarg :added-1 :added-2] @fired)
        "second dispose! re-fired nothing (idempotent)")))

(deftest reaction-dispose-is-re-entrant-safe
  (let [fired (atom [])
        r     (ratom/make-reaction (fn [] 1))]

    (ratom/add-on-dispose! r (fn [this]
                               (swap! fired conj :re-entrant-cb)
                               (ratom/dispose! this)))
    (ratom/add-on-dispose! r (fn [_] (swap! fired conj :after-cb)))
    (ratom/dispose! r)
    (is (= [:re-entrant-cb :after-cb] @fired)
        "each callback fired exactly once despite the re-entrant dispose!; no recursion, no double-fire")))

(deftest reactive-predicate
  (is (false? (ratom/reactive?)))

  (let [seen (atom nil)
        r    (ratom/make-reaction
               (fn [] (reset! seen (ratom/reactive?)))
               :auto-run true)]
    @r
    (is (true? @seen))))

(deftest queued-reaction-drains-on-flush
  (let [a       (ratom/atom 1)
        r-vals  (atom [])
        r       (ratom/make-reaction (fn [] (* @a 10)))

        outer   (ratom/make-reaction
                  (fn [] @r)
                  :auto-run true)]

    @outer

    (add-watch outer :w (fn [_ _ _ nu] (swap! r-vals conj nu)))
    (reset! a 3)
    (ratom/flush!)

    (is (= [30] @r-vals))))

(deftest throwing-reaction-checked-recompute-preserves-error

  (let [a            (ratom/atom 1)

        inner        (ratom/make-reaction
                       (fn []
                         (when (> @a 1)
                           (throw (ex-info "boom" {:a @a})))
                         @a))

        outer        (ratom/make-reaction (fn [] @inner)
                                          :auto-run (fn [_this] nil))
        watch-fires  (atom [])]

    @outer
    (is (= 1 @inner) "precondition: inner computes cleanly at a=1")

    (add-watch inner :w (fn [_ _ _ nu] (swap! watch-fires conj nu)))

    (reset! a 2)
    (ratom/flush!)
    (is (thrown-with-msg? cljs.core/ExceptionInfo #"boom" @inner))
    (is (not (some false? @watch-fires))
        "the error recompute must not notify watchers with the catch
         block's `false` return")))

(deftest reaction-macro
  (let [a (ratom/atom 3)
        r (reaction (* @a @a))]
    (is (= 9 @r))
    (reset! a 4)
    (is (= 16 @r))))

(deftest ratom-printed-representation
  (doseq [[x expected]
          [[(ratom/atom 1) "#object[reagent2.ratom.RAtom {:val 1}]"]
           [(ratom/atom {:inner (ratom/atom 7)})
            "#object[reagent2.ratom.RAtom {:val {:inner #object[reagent2.ratom.RAtom {:val 7}]}}]"]
           [(ratom/make-reaction (fn [] 42)) "#object[reagent2.ratom.Reaction {:val 42}]"]]]
    (is (= expected (pr-str x)))))

(deftest pr-atom-does-not-capture-nested-derefs
  (let [inner (ratom/atom 7)
        outer (ratom/atom {:inner inner})
        runs  (volatile! 0)
        r     (ratom/make-reaction (fn [] (vswap! runs inc) (pr-str outer))
                                   :auto-run true)]
    @r
    (is (contains? (set (.-watching r)) outer))
    (reset! inner 8)
    (is (= 1 @runs))))
