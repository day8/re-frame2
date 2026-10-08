(ns reagent2.impl.component-cljs-test
  "Unit tests for reagent2.impl.component, driving `create-class*`,
  `wrap-render` and `fn-to-class` directly rather than through React: the
  Form-1/2 classification, the 7-key cap, lifecycle and error-boundary
  plumbing, and the default shouldComponentUpdate. React's own routing (a
  throw reaching the nearest boundary, sCU bailouts) is proved by the
  `-dom-cljs-test` siblings under a real createRoot."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.impl.component :as component]
            [reagent2.impl.template :as template]
            [reagent2.impl.batching :as batching]))

(defn- fake-instance
  "A bare object carrying the `.-cljsArgv` wrap-render slices its args from."
  [argv]
  (let [c #js {}]
    (set! (.-cljsArgv c) argv)
    c))

(defn- proto-method [^js klass name]
  (aget (.-prototype klass) name))

;; ---------------------------------------------------------------------------
;; The 7-key cap (per IMPL-SPEC §6.1)
;; ---------------------------------------------------------------------------

(deftest cap-keys-are-the-canonical-seven
  (testing "cap-keys is exactly the 7 keys per IMPL-SPEC §6.1"
    (is (= #{:component-did-mount
            :component-will-unmount
            :component-did-update
            :reagent-render
            :display-name
            :get-snapshot-before-update
            :component-did-catch}
           component/cap-keys))))

(deftest create-class-throws-listing-every-bad-key
  (testing "all out-of-cap keys are listed at once"
    (let [thrown (try
                   (component/create-class*
                     {:reagent-render               (fn [_this] [:div])
                      :component-will-receive-props (fn [_])
                      :should-component-update      (fn [_])
                      :component-will-mount         (fn [_])})
                   nil
                   (catch :default e (ex-data e)))]
      (is (= :rf.error/create-class-key-unsupported (:rf.error/id thrown)))
      (is (= component/cap-keys (:supported-keys thrown))
          "the supported-keys field carries the canonical cap")
      (is (= #{:component-will-receive-props
               :should-component-update
               :component-will-mount}
             (set (:keys thrown)))
          "every out-of-cap key is listed (registration-time fail-fast)"))))

(deftest create-class-throws-on-missing-render
  (testing ":reagent-render is required"
    (let [thrown (try
                   (component/create-class* {:display-name "Foo"})
                   nil
                   (catch :default e (ex-data e)))]
      (is (= :rf.error/create-class-missing-render (:rf.error/id thrown))))))

;; ---------------------------------------------------------------------------
;; Form-1 detection (runtime)
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; Form-2 detection (runtime)
;; ---------------------------------------------------------------------------

(deftest wrap-render-form-2-cached
  (testing "render-fn returning a fn is classified as Form-2; inner fn cached"
    (let [setup-calls (atom 0)
          render-calls (atom 0)
          outer (fn [n0]
                  (swap! setup-calls inc)
                  (let [local-state n0]
                    (fn [n]
                      (swap! render-calls inc)
                      [:span (+ local-state n)])))
          c (fake-instance [outer 10])]
      (let [out1 (component/wrap-render c outer)]
        (is (= [:span 20] out1) "first render: setup ran + inner fn called with [10]")
        (is (= 1 @setup-calls))
        (is (= 1 @render-calls)))
      ;; Update the argv as React would on prop change.
      (set! (.-cljsArgv c) [outer 5])
      (let [out2 (component/wrap-render c outer)]
        (is (= [:span 15] out2)
            "second render: cached inner re-called; closes over local-state=10")
        (is (= 1 @setup-calls) "outer fn NOT re-run on subsequent render")
        (is (= 2 @render-calls) "inner fn ran a second time")))))

(deftest wrap-render-form-2-with-multiple-args
  (testing "Form-2 inner fn receives the full argv tail"
    (let [outer (fn [_a _b _c] (fn [a b c] [:i a b c]))
          c     (fake-instance [outer 1 2 3])]
      (is (= [:i 1 2 3] (component/wrap-render c outer))))))

;; ---------------------------------------------------------------------------
;; create-class produces a working React class
;;
;; We don't render through React in unit tests (no DOM in node-test);
;; we exercise the constructor + render-method shape directly.
;; ---------------------------------------------------------------------------

(deftest create-class-class-shape
  (testing "create-class* returns a constructor with React.Component proto"
    (let [^js klass (component/create-class*
                  {:reagent-render (fn [_this] [:div])
                   :display-name   "Shape"})]
      (is (= ["Shape" true true]
             [(.-displayName klass)
              (boolean (component/reagent-class? klass))
              (some? (.. klass -prototype -isReactComponent))])
          "displayName set, tagged as a reagent class, extends React.Component"))))

(deftest create-class-render-delegates-to-wrap-render
  (testing "the class's render returns a React element built from the user's
            hiccup (the render fn gets the argv's args, not `this`)"
    (let [render-fn (fn [n] [:p "got=" n])
          ^js klass (component/create-class*
                      {:reagent-render render-fn})
          inst      (new klass #js {:__rfArgv [render-fn 99]})
          ^js el    (.call (.. klass -prototype -render) inst)]
      (is (= "p" (.-type el))))))

(deftest create-class-binds-current-component-during-render
  (testing "*current-component* is bound to `this` during render"
    (let [seen-cmp  (atom :sentinel)
          render-fn (fn []
                      (reset! seen-cmp (component/current-component))
                      [:div])
          ^js klass (component/create-class* {:reagent-render render-fn})
          props     #js {:__rfArgv [render-fn]}
          inst      (new klass props)]
      (is (nil? (component/current-component))
          "outside render, current-component is nil")
      (.call (.. klass -prototype -render) inst)
      (is (identical? inst @seen-cmp)
          "during render, current-component is the rendering instance")
      (is (nil? (component/current-component))
          "after render, the dynamic var is unbound again"))))

;; ---------------------------------------------------------------------------
;; Lifecycle plumbing (per IMPL-SPEC §6.4)
;; ---------------------------------------------------------------------------

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
  (testing "componentWillUnmount clears the dirty flag, so a
            component queued for re-render THEN unmounted is NOT forceUpdate'd
            on the next flush-render drain (stock-parity: stock clears the
            flag on unmount)."
    (let [^js klass (component/create-class* {:reagent-render (fn [_] [:div])})
          inst     (new klass #js {:__rfArgv [(fn [_] nil)]})
          fu-calls (atom 0)]
      (set! (.-forceUpdate inst) (fn [] (swap! fu-calls inc)))
      (batching/queue-render! inst)
      (is (true? (.-cljsIsDirty inst)) "control: queued means dirty")
      (.call (.. klass -prototype -componentWillUnmount) inst)
      (batching/flush!)
      (is (zero? @fu-calls)
          "unmounted component was NOT forceUpdate'd on the drain"))))

(deftest lifecycle-component-did-update-receives-prev-argv-prev-state-and-snapshot
  (testing "componentDidUpdate forwards (this, prev-argv, prev-state, snapshot),
            the documented fixed four-argument callback; a distinct value in
            every slot catches a dropped or shifted argument"
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
             [@calls @seen])))))

(deftest lifecycle-get-snapshot-before-update-pairs-with-component-did-update
  (testing "getSnapshotBeforeUpdate gets (this prev-argv prev-state), and its
            return value is what React hands componentDidUpdate's snapshot"
    (let [gsbu-seen  (atom nil)
          cdu-seen   (atom nil)
          prev-state-sentinel #js {:probe "gsbu-prev-state"}
          ^js klass (component/create-class*
                  {:reagent-render             (fn [_] [:div])
                   :get-snapshot-before-update (fn [_this prev-argv prev-state]
                                                 (reset! gsbu-seen
                                                   {:prev-argv prev-argv
                                                    :prev-state prev-state})
                                                 :scroll-position-42)
                   :component-did-update       (fn [_this _prev-argv prev-state snapshot]
                                                 (reset! cdu-seen
                                                   {:prev-state prev-state
                                                    :snapshot snapshot}))})
          inst  (new klass #js {:__rfArgv [:r 1]})
          prev-props #js {:__rfArgv [:r 0]}]
      (is (= [:scroll-position-42 {:prev-argv [:r 0] :prev-state prev-state-sentinel}]
             [(.call (.. klass -prototype -getSnapshotBeforeUpdate)
                     inst prev-props prev-state-sentinel)
              @gsbu-seen]))
      (.call (.. klass -prototype -componentDidUpdate)
             inst prev-props prev-state-sentinel :scroll-position-42)
      (is (= {:prev-state prev-state-sentinel :snapshot :scroll-position-42}
             @cdu-seen)))))

;; ---------------------------------------------------------------------------
;; :component-did-catch error-boundary plumbing. A boundary gets a default
;; getDerivedStateFromError that flips cljsHasError, bridged into the public
;; state atom. Propagation under a real React is in
;; `reagent2.dom.error-boundary-dom-cljs-test`.
;; ---------------------------------------------------------------------------

(deftest error-boundary-component-did-catch-fires
  (testing ":component-did-catch fires with (this, error, info)"
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
      (is (= {:this inst :error err :info info} @seen)))))

(deftest error-boundary-get-derived-state-auto-installed
  (testing "a :component-did-catch class gets a static getDerivedStateFromError
            whose patch flips cljsHasError"
    (let [^js klass (component/create-class*
                  {:reagent-render      (fn [_] [:div])
                   :component-did-catch (fn [_ _ _])})]
      (is (true? (.-cljsHasError (.call (.-getDerivedStateFromError klass) nil (js/Error. "x")))))))
  (testing "a class that did not opt in gets none, so it cannot silently
            intercept a descendant's error"
    (is (nil? (.-getDerivedStateFromError
                ^js (component/create-class* {:reagent-render (fn [_] [:div])}))))))

(deftest error-boundary-derived-state-syncs-into-reagent-atom-rf2-ygknv
  (testing "the default getDerivedStateFromError
            marker (React this.state.cljsHasError) is bridged into the
            public Reagent state atom at render entry, so a boundary
            render reading (state-atom this) sees the marker and can
            show its fallback."
    (let [render-calls (atom [])
          ^js klass (component/create-class*
                      {:reagent-render
                       (fn [_]
                         ;; The boundary branches on the PUBLIC state API
                         ;; (the same cell reagent2.core/state derefs).
                         ;; A Form-1 render fn reads `this` via
                         ;; current-component (it does NOT receive it as
                         ;; an arg) — mirrors the production contract.
                         (let [this (component/current-component)
                               s    @(component/state-atom this)]
                           (swap! render-calls conj s)
                           (if (:cljsHasError s)
                             [:div.fallback "Something went wrong"]
                             [:div.ok "child"])))
                       :component-did-catch (fn [_ _ _])})
          inst  (new klass #js {:__rfArgv [(fn [_] nil)]})]
      (set! (.-forceUpdate inst) (fn [] nil))
      (let [^js el (.call (.. klass -prototype -render) inst)]
        (is (= "ok" (.-className (.-props el)))
            "before any error, the boundary renders its normal child"))
      ;; React applies the derived-state patch after a child throws:
      ;; getDerivedStateFromError returns #js {:cljsHasError true}, which
      ;; React merges into this.state. Replicate that here.
      (let [patch (.call (.-getDerivedStateFromError klass) nil (js/Error. "boom"))]
        (set! (.-state inst)
              (js/Object.assign #js {} (.-state inst) patch)))
      ;; React re-renders the boundary. The render-entry sync must now
      ;; surface the marker through the public state atom.
      (let [^js el (.call (.. klass -prototype -render) inst)]
        (is (true? (:cljsHasError @(component/state-atom inst)))
            "marker bridged into the Reagent state atom")
        (is (= "fallback" (.-className (.-props el)))
            "boundary render reading (state-atom this) shows the fallback UI"))
      (.call (.. klass -prototype -componentWillUnmount) inst))))

(deftest error-boundary-no-spurious-marker-without-error-rf2-ygknv
  (testing "a boundary that never caught an error
            keeps an empty public state atom (the sync writes only on
            the error-state transition)"
    (let [^js klass (component/create-class*
                      {:reagent-render      (fn [_] [:div])
                       :component-did-catch (fn [_ _ _])})
          inst  (new klass #js {:__rfArgv [(fn [_] nil)]})]
      (set! (.-forceUpdate inst) (fn [] nil))
      (.call (.. klass -prototype -render) inst)
      (is (nil? @(component/state-atom inst))
          "no error → state atom untouched (no spurious :cljsHasError)"))))

(deftest error-boundary-rethrow-bubbles-via-cDC
  (testing "a rethrow from :component-did-catch escapes the plumbing, so it
            reaches the next boundary"
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
          "user-fn rethrow escapes the plumbing untouched"))))

;; ---------------------------------------------------------------------------
;; fn-to-class
;; ---------------------------------------------------------------------------

(deftest fn-to-class-caches
  (testing "fn-to-class returns the same class on repeated calls"
    (let [f (fn [n] [:p n])
          k1 (component/fn-to-class f)
          k2 (component/fn-to-class f)]
      (is (identical? k1 k2)
          "second call returns the cached class"))))

;; ---------------------------------------------------------------------------
;; Type predicates
;; ---------------------------------------------------------------------------

(deftest reagent-class-predicate
  (let [klass (component/create-class* {:reagent-render (fn [_] [:div])})
        xs    [klass (fn [] nil) nil "string"]]
    (is (= [true false false false] (map (comp boolean component/reagent-class?) xs))
        "reagent-class? holds only for create-class*-built classes")
    (is (= [true false false false] (map (comp boolean component/react-class?) xs))
        "react-class? holds for a class with render on its prototype")))

;; ---------------------------------------------------------------------------
;; With no as-element converter registered (a bundle that loads component
;; without template), render fails fast rather than handing React raw hiccup.
;; ---------------------------------------------------------------------------

(defn- with-unregistered-as-element-fn
  "Run `f` with the as-element seam nulled out, restoring the template's
  converter on exit."
  [f]
  (try
    (component/set-as-element-fn! nil)
    (f)
    (finally
      (component/set-as-element-fn! template/as-element))))

(deftest as-element-fn-unregistered-render-throws
  (testing "render method throws :rf.error/as-element-fn-unregistered
            when the as-element seam is null"
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
                 [(:rf.error/id thrown) (:recovery thrown)])))))))

;; ---------------------------------------------------------------------------
;; The default shouldComponentUpdate skips only an `=` argv; a missing argv or
;; a throwing comparison renders (fail OPEN, unlike stock Reagent). React's
;; use of it is proved in reagent_slim_scu_argv_gate_dom_cljs_test.
;; ---------------------------------------------------------------------------

(defn- scu-call
  "Invoke `klass`'s shouldComponentUpdate with `this` bound to a synthesised
  instance carrying `prev-argv` under `.props.__rfArgv`, and React's incoming
  props carrying `next-argv`. Returns the boolean the gate produced."
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
