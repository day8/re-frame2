(ns reagent2.impl.component-cljs-test
  "Component forms, class cap, lifecycle arguments and update-gate contracts."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.impl.component :as component]
            [reagent2.impl.template :as template]
            [reagent2.impl.batching :as batching]))

(defn- fake-instance
  [argv]
  (let [c #js {}]
    (set! (.-cljsArgv c) argv)
    c))

(defn- proto-method [^js klass name]
  (aget (.-prototype klass) name))

(deftest cap-keys-are-the-canonical-seven
  (is (= #{:component-did-mount
          :component-will-unmount
          :component-did-update
          :reagent-render
          :display-name
          :get-snapshot-before-update
          :component-did-catch}
         component/cap-keys)))

(deftest create-class-throws-listing-every-bad-key
  (let [thrown (try
                 (component/create-class*
                   {:reagent-render (fn [_this] [:div])
                    :component-will-receive-props (fn [_])
                    :should-component-update (fn [_])
                    :component-will-mount (fn [_])})
                 nil
                 (catch :default e (ex-data e)))]
    (is (= [:rf.error/create-class-key-unsupported component/cap-keys
            #{:component-will-receive-props :should-component-update :component-will-mount}]
           [(:rf.error/id thrown) (:supported-keys thrown) (set (:keys thrown))]))))

(deftest create-class-throws-on-missing-render
  (let [thrown (try
                 (component/create-class* {:display-name "Foo"})
                 nil
                 (catch :default e (ex-data e)))]
    (is (= :rf.error/create-class-missing-render (:rf.error/id thrown)))))

(deftest wrap-render-form-1-returns-the-render-output
  (doseq [[why render-fn args expected]
          [["render-fn returning a hiccup vector classifies as Form-1"
            (fn [n] [:span "n=" n]) [7] [:span "n=" 7]]
           ["render-fn returning a string is Form-1 (primitive)"
            (fn [_] "hello") [1] "hello"]
           ["render-fn returning nil is Form-1"
            (fn [_] nil) [1] nil]
           ["render-fn taking no args (zero-arity Form-1)"
            (fn [] [:p "z"]) [] [:p "z"]]]]
    (testing why
      (let [c (fake-instance (into [render-fn] args))]
        (is (= expected (component/wrap-render c render-fn)))))))

(deftest wrap-render-form-2-cached
  (let [setup-calls  (atom 0)
        render-calls (atom 0)
        outer (fn [n0]
                (swap! setup-calls inc)
                (fn [n]
                  (swap! render-calls inc)
                  [:span (+ n0 n)]))
        c (fake-instance [outer 10])]
    (is (= [:span 20] (component/wrap-render c outer)))
    (set! (.-cljsArgv c) [outer 5])
    (is (= [[:span 15] 1 2]
           [(component/wrap-render c outer) @setup-calls @render-calls]))))

(deftest lifecycle-mount-and-unmount-callbacks-receive-this
  (let [fired (atom [])
        ^js klass (component/create-class*
                    {:reagent-render         (fn [_] [:div])
                     :component-did-mount    (fn [this] (swap! fired conj [:mount this]))
                     :component-will-unmount (fn [this] (swap! fired conj [:unmount this]))})
        inst  (new klass #js {:__rfArgv []})]
    (.call (.. klass -prototype -componentDidMount) inst)
    (.call (.. klass -prototype -componentWillUnmount) inst)
    (is (= [[:mount inst] [:unmount inst]] @fired))))

(deftest lifecycle-unmount-clears-dirty-flag-rf2-mdgt8t
  (let [^js klass (component/create-class* {:reagent-render (fn [_] [:div])})
        inst     (new klass #js {:__rfArgv [(fn [_] nil)]})
        fu-calls (atom 0)]
    (set! (.-forceUpdate inst) (fn [] (swap! fu-calls inc)))
    (batching/queue-render! inst)
    (is (true? (.-cljsIsDirty inst)) "control: queued means dirty")
    (.call (.. klass -prototype -componentWillUnmount) inst)
    (batching/flush!)
    (is (zero? @fu-calls)
        "unmounted component was NOT forceUpdate'd on the drain")))

(deftest lifecycle-component-did-update-receives-prev-argv-prev-state-and-snapshot
  (let [seen  (atom nil)
        calls (atom 0)
        prev-state-sentinel #js {:probe "prev-state-sentinel"}
        ^js klass (component/create-class*
                {:reagent-render       (fn [_] [:div])
                 :component-did-update (fn [this prev-argv prev-state snapshot]
                                         (swap! calls inc)
                                         (reset! seen
                                           {:this this
                                            :prev-argv prev-argv
                                            :prev-state prev-state
                                            :snapshot snapshot}))})
        inst  (new klass #js {:__rfArgv [:render :a 1]})
        prev-props #js {:__rfArgv [:render :a 0]}]
    (.call (.. klass -prototype -componentDidUpdate)
           inst prev-props prev-state-sentinel :the-snapshot)
    (is (= [1 {:this       inst
               :prev-argv  [:render :a 0]
               :prev-state prev-state-sentinel
               :snapshot   :the-snapshot}]
           [@calls @seen]))))

(deftest error-boundary-component-did-catch-fires
  (let [seen  (atom nil)
        ^js klass (component/create-class*
                {:reagent-render      (fn [_] [:div])
                 :component-did-catch (fn [this error info]
                                        (reset! seen
                                          {:this this
                                           :error error
                                           :info info}))})
        inst  (new klass #js {:__rfArgv []})
        err   (js/Error. "boom")
        info  #js {:componentStack "<at Foo>"}]
    (.call (.. klass -prototype -componentDidCatch) inst err info)
    (is (= {:this inst :error err :info info} @seen))))

(deftest get-derived-state-from-error-only-on-a-boundary
  (is (nil? (.-getDerivedStateFromError
              ^js (component/create-class* {:reagent-render (fn [_] [:div])})))))

(deftest error-boundary-derived-state-syncs-into-reagent-atom-rf2-ygknv
  (let [^js klass (component/create-class*
                    {:reagent-render
                     (fn [_]
                       (let [this (component/current-component)
                             s    @(component/state-atom this)]
                         (if (:cljsHasError s)
                           [:div.fallback "Something went wrong"]
                           [:div.ok "child"])))
                     :component-did-catch (fn [_ _ _])})
        inst (new klass #js {:__rfArgv [(fn [_] nil)]})]
    (set! (.-forceUpdate inst) (fn [] nil))
    (let [^js el (.call (.. klass -prototype -render) inst)]
      (is (= "ok" (.-className (.-props el)))))
    (let [patch (.call (.-getDerivedStateFromError klass) nil (js/Error. "boom"))]
      (set! (.-state inst) (js/Object.assign #js {} (.-state inst) patch)))
    (let [^js el (.call (.. klass -prototype -render) inst)]
      (is (= "fallback" (.-className (.-props el)))))
    (.call (.. klass -prototype -componentWillUnmount) inst)))

(deftest error-boundary-no-spurious-marker-without-error-rf2-ygknv
  (let [^js klass (component/create-class*
                    {:reagent-render      (fn [_] [:div])
                     :component-did-catch (fn [_ _ _])})
        inst  (new klass #js {:__rfArgv [(fn [_] nil)]})]
    (set! (.-forceUpdate inst) (fn [] nil))
    (.call (.. klass -prototype -render) inst)
    (is (nil? @(component/state-atom inst))
        "no error → state atom untouched (no spurious :cljsHasError)")))

(deftest error-boundary-rethrow-bubbles-via-cDC
  (let [^js klass (component/create-class*
                {:reagent-render      (fn [_] [:div])
                 :component-did-catch (fn [_ _ _]
                                        (throw (js/Error. "rethrown")))})
        inst  (new klass #js {:__rfArgv []})
        thrown (try
                 (.call (.. klass -prototype -componentDidCatch)
                        inst (js/Error. "x") #js {})
                 nil
                 (catch :default e (.-message e)))]
    (is (= "rethrown" thrown)
        "user-fn rethrow escapes the plumbing untouched")))

(deftest fn-to-class-caches
  (let [f (fn [n] [:p n])
        k1 (component/fn-to-class f)
        k2 (component/fn-to-class f)]
    (is (identical? k1 k2)
        "second call returns the cached class")))

(deftest reagent-class-predicate
  (let [klass (component/create-class* {:reagent-render (fn [_] [:div])})
        xs    [klass (fn [] nil) nil "string"]]
    (is (= [[true false false false] [true false false false]]
           [(map (comp boolean component/reagent-class?) xs)
            (map (comp boolean component/react-class?) xs)]))))

(defn- with-unregistered-as-element-fn
  [f]
  (try
    (component/set-as-element-fn! nil)
    (f)
    (finally
      (component/set-as-element-fn! template/as-element))))

(deftest as-element-fn-unregistered-render-throws
  (with-unregistered-as-element-fn
    (fn []
      (let [^js klass (component/create-class*
                        {:reagent-render (fn [_this] [:div "x"])
                         :display-name   "UnregisteredTest"})
            render    (proto-method klass "render")
            instance  #js {:props #js {:__rfArgv [(fn [_t] [:div "x"])]}
                           :cljsArgv [(fn [_t] [:div "x"])]
                           :cljsRenderRea nil}
            thrown    (try
                        (.call render instance)
                        nil
                        (catch :default e (ex-data e)))]
        (is (= [:rf.error/as-element-fn-unregistered :no-recovery]
               [(:rf.error/id thrown) (:recovery thrown)]))))))

(defn- scu-call
  [^js klass prev-argv next-argv]
  (let [scu  (.. klass -prototype -shouldComponentUpdate)
        inst #js {:props #js {:__rfArgv prev-argv}}]
    (.call scu inst #js {:__rfArgv next-argv})))

(deftest scu-skips-only-equal-argv
  (let [^js klass (component/create-class* {:reagent-render (fn [_] [:div])})
        boom      #(reify IEquiv (-equiv [_ _] (throw (js/Error. "equiv boom"))))]
    (doseq [[why k prev next expected]
            [["= argv in fresh vectors skips" klass [:head {:a 1} [1 2 3]] [:head {:a 1} [1 2 3]] false]
             ["a Form-1/2 class carries the same gate" (component/fn-to-class (fn [_] [:div])) [:h 1] [:h 1] false]
             ["a changed arg renders" klass [:head {:a 1}] [:head {:a 2}] true]
             ["a missing prev argv renders" klass nil [:head] true]
             ["a missing next argv renders" klass [:head] nil true]
             ["a throwing comparison fails open" klass [(boom)] [(boom)] true]]]
      (is (= expected (scu-call k prev next)) why))
    (is (true? (.call (.. klass -prototype -shouldComponentUpdate) #js {:props #js {}} #js {}))
        "an undefined __rfArgv on both sides renders")))
