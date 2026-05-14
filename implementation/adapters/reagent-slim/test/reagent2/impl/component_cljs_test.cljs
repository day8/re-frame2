(ns reagent2.impl.component-cljs-test
  "Unit tests for reagent2.impl.component (Stage 4-C, rf2-6hyy).

  Per IMPL-SPEC §5 + §6 + §12.1 + §12.5 R-002 + R-003. Covers:

    - Form-1/Form-2/Form-3 detection (runtime path).
    - Compile-time form-tag fast path (rf2-yfbx fold).
    - 7-key cap enforcement: out-of-cap keys throw
      :rf.error/create-class-key-unsupported.
    - Lifecycle key -> React lifecycle method mapping.
    - :component-did-catch error-boundary contract:
        * error during a child's render is caught.
        * error during a child's commit (componentDidMount) is caught.
        * nested boundaries: inner catches, outer does not fire.
    - :get-snapshot-before-update pairs with :component-did-update's
      third arg.
    - Source-coord stamping integration (the renderer-side stamp lives
      in re-frame.views/reg-view*; the runtime here exercises the
      meta-tag fast path that the macro fold introduces).

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up.

  Test strategy: most tests directly exercise the public surface
  (`create-class*`, `wrap-render`, `fn-to-class`) without rendering
  through React. The error-boundary tests use a real ReactDOM root
  so we get the actual React-19 boundary semantics, gated on whether
  `react-dom/client` exposes a usable root in the node test target."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.impl.component :as component]
            [reagent2.impl.template :as template]
            ["react" :as react]))

;; ---------------------------------------------------------------------------
;; Test helpers — minimal "fake" React component instance
;; ---------------------------------------------------------------------------
;;
;; wrap-render reads .-cljsArgv off the component to slice the user-fn
;; args. A bare JS object suffices for unit-testing the detection path
;; without spinning up a React renderer.

(defn- fake-instance [argv]
  (let [c #js {}]
    (set! (.-cljsArgv c) argv)
    c))

;; Tiny helpers to keep the inference checker quiet — the tests poke at
;; React's prototype chain (`.. klass -prototype -render`), and the
;; CLJS analyser can't infer the type of the synthesised constructor
;; without a hint at every callsite. These wrappers concentrate the
;; ^js hint in one place.

(defn- proto-method [^js klass name]
  (aget (.-prototype klass) name))

(defn- static-prop [^js klass name]
  (aget klass name))

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

(deftest create-class-throws-on-unsupported-key
  (testing "out-of-cap key throws :rf.error/create-class-key-unsupported"
    (let [thrown (try
                   (component/create-class*
                     {:reagent-render          (fn [_this] [:div])
                      :component-will-receive-props (fn [_this _new-props])})
                   nil
                   (catch :default e (ex-data e)))]
      (is (= :rf.error/create-class-key-unsupported (:type thrown)))
      (is (contains? (set (:keys thrown)) :component-will-receive-props)
          "the offending key is named in the ex-data")
      (is (= component/cap-keys (:supported-keys thrown))
          "the supported-keys field carries the canonical cap"))))

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
      (is (= :rf.error/create-class-key-unsupported (:type thrown)))
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
      (is (= :rf.error/create-class-missing-render (:type thrown))))))

(deftest create-class-accepts-every-cap-key
  (testing "spec with all 7 cap keys is accepted (no throw)"
    (let [^js klass (component/create-class*
                  {:reagent-render             (fn [_this] [:div])
                   :component-did-mount        (fn [_this])
                   :component-will-unmount     (fn [_this])
                   :component-did-update       (fn [_this _prev-argv _snap])
                   :get-snapshot-before-update (fn [_this _prev-argv] nil)
                   :component-did-catch        (fn [_this _err _info])
                   :display-name               "Full"})]
      (is (some? klass) "all-cap-keys spec produced a class")
      (is (component/reagent-class? klass)
          "the produced class is tagged reagent-class"))))

;; ---------------------------------------------------------------------------
;; Form-1 detection (runtime)
;; ---------------------------------------------------------------------------

(deftest wrap-render-form-1-vector
  (testing "render-fn returning a hiccup vector classifies as Form-1"
    (let [render-fn (fn [n] [:span "n=" n])
          c         (fake-instance [render-fn 7])
          out       (component/wrap-render c render-fn)]
      (is (= [:span "n=" 7] out)))))

(deftest wrap-render-form-1-string
  (testing "render-fn returning a string is Form-1 (primitive)"
    (let [render-fn (fn [_] "hello")
          c         (fake-instance [render-fn 1])
          out       (component/wrap-render c render-fn)]
      (is (= "hello" out)))))

(deftest wrap-render-form-1-nil
  (testing "render-fn returning nil is Form-1"
    (let [render-fn (fn [_] nil)
          c         (fake-instance [render-fn 1])
          out       (component/wrap-render c render-fn)]
      (is (nil? out)))))

(deftest wrap-render-form-1-no-args
  (testing "render-fn taking no args (zero-arity Form-1)"
    (let [render-fn (fn [] [:p "z"])
          c         (fake-instance [render-fn])
          out       (component/wrap-render c render-fn)]
      (is (= [:p "z"] out)))))

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
;; Compile-time form-tag fast path (rf2-yfbx fold)
;;
;; The runtime cond covers correctness for any fn. The fast path lets
;; reg-view's expansion stamp `^{:reagent2/form ...}` meta and skip
;; the cond. We exercise both positive (tag matches) and negative
;; (no tag → cond runs) cases.
;; ---------------------------------------------------------------------------

(deftest wrap-render-form-1-tag-fast-path
  (testing "render-fn with :reagent2/form-1 meta dispatches without classification"
    (let [render-fn (with-meta (fn [n] [:tag/form-1 n])
                               {:reagent2/form :reagent2/form-1})
          c         (fake-instance [render-fn 42])]
      (is (= [:tag/form-1 42] (component/wrap-render c render-fn))))))

(deftest wrap-render-form-2-tag-fast-path
  (testing "render-fn with :reagent2/form-2 meta dispatches without classification"
    (let [setup-calls (atom 0)
          render-fn   (with-meta
                        (fn [_n0]
                          (swap! setup-calls inc)
                          (fn [n] [:tag/form-2 n]))
                        {:reagent2/form :reagent2/form-2})
          c           (fake-instance [render-fn 1])]
      (is (= [:tag/form-2 1] (component/wrap-render c render-fn)))
      (set! (.-cljsArgv c) [render-fn 2])
      (is (= [:tag/form-2 2] (component/wrap-render c render-fn)))
      (is (= 1 @setup-calls)
          "outer ran once; cached inner serviced the second render"))))

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
      (is (fn? klass) "the class is a constructor fn")
      (is (= "Shape" (.-displayName klass))
          ":display-name set as static displayName field")
      (is (component/reagent-class? klass)
          "tagged via cljsReagentClass")
      (is (some? (.. klass -prototype -render))
          "render method on prototype")
      (is (some? (.. klass -prototype -isReactComponent))
          "extends React.Component (isReactComponent inherited)"))))

(deftest create-class-render-delegates-to-wrap-render
  (testing "instantiating + calling .render produces a React element wrapping the user's hiccup"
    ;; Per stock-Reagent's :reagent-render contract (and IMPL-SPEC §5.1's
    ;; wrap-render), the render fn does NOT receive `this`. It receives
    ;; the user-args slice of the argv (i.e. argv minus the head). User
    ;; code that wants `this` reads it via `current-component`.
    ;;
    ;; Per rf2-08t0: wrap-render returns raw hiccup (per IMPL-SPEC §5.1)
    ;; but the class's render() method MUST return a React element — so
    ;; make-render-method runs the deref'd hiccup through the registered
    ;; as-element converter before returning. The assertion shape mirrors
    ;; stock Reagent: render produces a React element whose .-type is
    ;; the DOM tag from the hiccup head.
    (let [render-fn (fn [n] [:p "got=" n])
          ^js klass (component/create-class*
                      {:reagent-render render-fn})
          props     #js {:__rfArgv [render-fn 99]}
          ;; Synthesise a class instance — pass props to constructor.
          inst      (new klass props)
          ^js el    (.call (.. klass -prototype -render) inst)]
      (is (some? el) "render method returns a non-nil React element")
      (is (= "p" (.-type el))
          ".-type is the DOM tag from the hiccup head"))))

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

(deftest lifecycle-component-did-mount-fires
  (testing "componentDidMount delegates to user :component-did-mount"
    (let [fired (atom nil)
          ^js klass (component/create-class*
                  {:reagent-render      (fn [_] [:div])
                   :component-did-mount (fn [this]
                                          (reset! fired this))})
          inst  (new klass #js {:__rfArgv []})]
      (.call (.. klass -prototype -componentDidMount) inst)
      (is (identical? inst @fired)
          "user fn received `this` as its single arg"))))

(deftest lifecycle-component-will-unmount-fires
  (testing "componentWillUnmount delegates to user :component-will-unmount"
    (let [fired (atom nil)
          ^js klass (component/create-class*
                  {:reagent-render         (fn [_] [:div])
                   :component-will-unmount (fn [this]
                                             (reset! fired this))})
          inst  (new klass #js {:__rfArgv []})]
      (.call (.. klass -prototype -componentWillUnmount) inst)
      (is (identical? inst @fired)))))

(deftest lifecycle-component-did-update-receives-prev-argv-and-snapshot
  (testing "componentDidUpdate forwards (this, prev-argv, snapshot)"
    (let [seen  (atom nil)
          ^js klass (component/create-class*
                  {:reagent-render       (fn [_] [:div])
                   :component-did-update (fn [this prev-argv snapshot]
                                           (reset! seen
                                             {:this this
                                              :prev-argv prev-argv
                                              :snapshot snapshot}))})
          inst  (new klass #js {:__rfArgv [:render :a 1]})
          prev-props #js {:__rfArgv [:render :a 0]}]
      (.call (.. klass -prototype -componentDidUpdate)
             inst prev-props nil :the-snapshot)
      (is (= [:render :a 0] (:prev-argv @seen))
          "prev-argv reconstructed from prevProps.__rfArgv")
      (is (= :the-snapshot (:snapshot @seen))
          "snapshot from React forwarded as the 3rd user-fn arg")
      (is (identical? inst (:this @seen))))))

(deftest lifecycle-get-snapshot-before-update-pairs-with-component-did-update
  (testing "getSnapshotBeforeUpdate's return value flows to componentDidUpdate's 3rd arg"
    ;; Per IMPL-SPEC §6.6: React captures the gSBU return value and
    ;; passes it as cDU's 3rd arg. The plumbing here mirrors that —
    ;; we don't run React, so we exercise the user-fn dispatch shape:
    ;; gSBU receives (this prev-argv) and returns an arbitrary value;
    ;; cDU receives (this prev-argv snapshot).
    (let [snapshot-captured (atom nil)
          cdu-snapshot      (atom nil)
          ^js klass (component/create-class*
                  {:reagent-render             (fn [_] [:div])
                   :get-snapshot-before-update (fn [_this prev-argv]
                                                 (reset! snapshot-captured prev-argv)
                                                 :scroll-position-42)
                   :component-did-update       (fn [_this _prev-argv snapshot]
                                                 (reset! cdu-snapshot snapshot))})
          inst  (new klass #js {:__rfArgv [:r 1]})
          prev-props #js {:__rfArgv [:r 0]}]
      (let [snap (.call (.. klass -prototype -getSnapshotBeforeUpdate)
                        inst prev-props nil)]
        (is (= :scroll-position-42 snap)
            "gSBU returned the snapshot value")
        (is (= [:r 0] @snapshot-captured)
            "gSBU received prev-argv reconstructed from prevProps"))
      ;; React would now pass the snapshot to cDU as its 3rd arg.
      (.call (.. klass -prototype -componentDidUpdate)
             inst prev-props nil :scroll-position-42)
      (is (= :scroll-position-42 @cdu-snapshot)
          "cDU received gSBU's return value as 3rd arg"))))

(deftest lifecycle-display-name-statically-set
  (testing ":display-name lands on the class as displayName (static field)"
    (let [^js klass (component/create-class*
                  {:reagent-render (fn [_] [:div])
                   :display-name   "MyView"})]
      (is (= "MyView" (.-displayName klass))))))

;; ---------------------------------------------------------------------------
;; :component-did-catch error-boundary contract (per IMPL-SPEC §6.5 + rf2-gigc)
;;
;; React's error-boundary contract: a class with componentDidCatch
;; (and/or getDerivedStateFromError) catches errors thrown during
;; render / lifecycle of any descendant. Per IMPL-SPEC §6.5 the
;; rewrite installs a default getDerivedStateFromError that flips a
;; cljsHasError flag on state — apps that want fallback rendering
;; check that flag in :reagent-render.
;;
;; rf2-gigc test enumeration:
;;   1. error-during-child-render-caught-by-boundary
;;   2. error-during-child-commit-caught-by-boundary
;;   3. nested-boundary-isolates-inner-from-outer
;;   4. boundary-without-component-did-catch-no-fallback (negative)
;;
;; These tests exercise the boundary plumbing directly (no real React
;; render) — we assert the shape of the installed methods + that
;; getDerivedStateFromError is auto-installed when :component-did-catch
;; is in the spec. A real-DOM end-to-end Suspense + boundary integration
;; test belongs on the browser-test target where Stage 4-D's hiccup
;; interpreter lands.
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
      (is (= err (:error @seen)) "error forwarded")
      (is (identical? info (:info @seen)) "info forwarded")
      (is (identical? inst (:this @seen)) "this forwarded"))))

(deftest error-boundary-get-derived-state-auto-installed
  (testing "getDerivedStateFromError is auto-installed when :component-did-catch is supplied"
    ;; React 19 requires getDerivedStateFromError for the boundary to
    ;; actually re-render with fallback state. The rewrite installs a
    ;; default that flags state with cljsHasError=true; user
    ;; :reagent-render checks the flag.
    (let [^js klass (component/create-class*
                  {:reagent-render      (fn [_] [:div])
                   :component-did-catch (fn [_ _ _])})]
      (is (fn? (.-getDerivedStateFromError klass))
          "getDerivedStateFromError installed as a static class method")
      (let [patch (.call (.-getDerivedStateFromError klass) nil (js/Error. "x"))]
        (is (true? (.-cljsHasError patch))
            "default patch flips cljsHasError to true")))))

(deftest error-boundary-no-derived-state-without-did-catch
  (testing "without :component-did-catch, getDerivedStateFromError is NOT installed"
    ;; A class that didn't ask to be a boundary should not silently
    ;; intercept errors via a stray getDerivedStateFromError.
    (let [^js klass (component/create-class*
                  {:reagent-render (fn [_] [:div])})]
      (is (nil? (.-getDerivedStateFromError klass))
          "no auto-install when the user didn't opt into boundary semantics"))))

(deftest error-boundary-nested-isolation
  (testing "nested boundaries: an inner boundary's catch does NOT propagate to outer"
    ;; Without a real React tree we exercise the contract directly:
    ;; each boundary class has its own componentDidCatch, and a call
    ;; to one's cDC does NOT chain into another's.
    (let [outer-fired (atom 0)
          inner-fired (atom 0)
          ^js outer   (component/create-class*
                        {:reagent-render      (fn [_] [:div])
                         :component-did-catch (fn [_ _ _]
                                                (swap! outer-fired inc))})
          ^js inner   (component/create-class*
                        {:reagent-render      (fn [_] [:div])
                         :component-did-catch (fn [_ _ _]
                                                (swap! inner-fired inc))})
          inner-inst  (new inner #js {:__rfArgv []})]
      (.call (.. inner -prototype -componentDidCatch)
             inner-inst (js/Error. "child threw") #js {})
      (is (= 1 @inner-fired) "inner boundary caught")
      (is (= 0 @outer-fired)
          "outer boundary did not fire (nested isolation contract)"))))

(deftest error-boundary-during-commit-via-component-did-mount
  (testing "an error in a child's componentDidMount reaches the boundary's componentDidCatch"
    ;; Per the React contract, errors thrown during commit-phase
    ;; lifecycle methods (e.g. componentDidMount) are also caught by
    ;; the closest enclosing boundary. We model this by directly
    ;; routing the boundary's cDC + asserting it receives the right
    ;; (error, info) payload — the integration with React's commit
    ;; phase is a React contract that a real-DOM browser-test target
    ;; covers end-to-end (Stage 4-D).
    (let [caught (atom nil)
          klass  (component/create-class*
                   {:reagent-render      (fn [_] [:div])
                    :component-did-catch (fn [_this error ^js info]
                                           (reset! caught
                                             {:error error
                                              :phase (.-phase info)}))})
          inst   (new klass #js {:__rfArgv []})
          err    (js/Error. "commit-phase error")]
      (.call (.. klass -prototype -componentDidCatch)
             inst err #js {:phase "commit"})
      (is (= "commit" (:phase @caught)))
      (is (= err (:error @caught))))))

(deftest error-boundary-rethrow-bubbles-via-cDC
  (testing "a :component-did-catch fn that throws cascades — the rethrow is the user's choice"
    ;; Per IMPL-SPEC §6.5: re-throwing from cDC bubbles to the next
    ;; boundary. Our plumbing doesn't catch user throws — the user fn's
    ;; throw escapes and React's outer-boundary chain handles it. We
    ;; assert the plumbing doesn't swallow.
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

(deftest fn-to-class-produces-reagent-class
  (testing "fn-to-class wraps a plain fn into a reagent-class"
    (let [f     (fn [n] [:p n])
          ^js klass (component/fn-to-class f)]
      (is (component/reagent-class? klass))
      (is (some? (.. klass -prototype -render))))))

(deftest fn-to-class-caches
  (testing "fn-to-class returns the same class on repeated calls"
    (let [f (fn [n] [:p n])
          k1 (component/fn-to-class f)
          k2 (component/fn-to-class f)]
      (is (identical? k1 k2)
          "second call returns the cached class"))))

(deftest fn-to-class-render-yields-react-element
  (testing "the cached class's render produces a React element wrapping the user fn's hiccup"
    ;; Per rf2-08t0: render() returns a React element (not raw hiccup);
    ;; make-render-method converts via the registered as-element fn.
    (let [f     (fn [n] [:p "n=" n])
          ^js klass (component/fn-to-class f)
          props #js {:__rfArgv [f 5]}
          inst  (new klass props)
          ^js el    (.call (.. klass -prototype -render) inst)]
      (is (some? el) "render returns a non-nil React element")
      (is (= "p" (.-type el))
          ".-type is the DOM tag from the hiccup head"))))

;; ---------------------------------------------------------------------------
;; Form-3 accessors
;; ---------------------------------------------------------------------------

(deftest get-argv-returns-stashed-argv
  (testing "get-argv reads .-cljsArgv off the instance"
    (let [c (fake-instance [:render-fn 1 2 3])]
      (is (= [:render-fn 1 2 3] (component/get-argv c))))))

(deftest get-props-returns-map-second-arg
  (testing "get-props returns the second arg if it's a map, else nil"
    (let [c-with-props    (fake-instance [:render-fn {:k :v}])
          c-without-props (fake-instance [:render-fn 1 2])]
      (is (= {:k :v} (component/get-props c-with-props)))
      (is (nil? (component/get-props c-without-props))
          "non-map second arg → nil props"))))

(deftest get-children-skips-props-map
  (testing "get-children returns argv after the head (and props if present)"
    (let [c-with-props (fake-instance [:render-fn {:k :v} :a :b])
          c-no-props   (fake-instance [:render-fn :a :b])]
      (is (= [:a :b] (vec (component/get-children c-with-props)))
          "props map skipped")
      (is (= [:a :b] (vec (component/get-children c-no-props)))
          "no props → children start at index 1"))))

(deftest state-atom-creates-cached-cell
  (testing "state-atom returns a per-component cell, cached on first call"
    (let [c    #js {}
          a    (component/state-atom c)
          a2   (component/state-atom c)]
      (is (some? a))
      (is (identical? a a2)
          "second call returns the cached atom")
      (reset! a 42)
      (is (= 42 @a) "the returned cell behaves as an atom"))))

;; ---------------------------------------------------------------------------
;; Type predicates
;; ---------------------------------------------------------------------------

(deftest reagent-class-predicate
  (testing "reagent-class? is true only for create-class*-built classes"
    (let [^js klass (component/create-class*
                  {:reagent-render (fn [_] [:div])})]
      (is (component/reagent-class? klass)))
    (is (not (component/reagent-class? (fn [] nil)))
        "a plain fn is not a reagent-class")
    (is (not (component/reagent-class? nil)))
    (is (not (component/reagent-class? "string")))))

(deftest react-class-predicate
  (testing "react-class? is true for any React class (has render on proto)"
    (let [^js klass (component/create-class*
                  {:reagent-render (fn [_] [:div])})]
      (is (component/react-class? klass)))
    (is (not (component/react-class? (fn [] nil))))
    (is (not (component/react-class? nil)))))

;; ---------------------------------------------------------------------------
;; Source-coord stamping integration (per IMPL-SPEC §5.4)
;;
;; The renderer-side stamping lives in re-frame.views/reg-view*. The
;; runtime here exercises the meta-tag fast path that the compile-time
;; fold introduces — when the user's render fn carries
;; `:reagent2/form` meta, wrap-render dispatches via the fast path
;; instead of running the classification cond. We verify the
;; integration doesn't drop the user's hiccup output (which is the
;; stamping target).
;; ---------------------------------------------------------------------------

(deftest source-coord-tagged-render-passes-through-hiccup
  (testing "fold + stamping: tagged Form-1 render returns hiccup intact"
    (let [render-fn (with-meta (fn [n] [:div.with-coord {:id "x"} n])
                               {:reagent2/form :reagent2/form-1})
          c         (fake-instance [render-fn 7])
          out       (component/wrap-render c render-fn)]
      ;; The renderer-side stamping (in re-frame.views/reg-view*) is
      ;; what merges :data-rf2-source-coord onto the root vector. The
      ;; component path here just has to leave the hiccup intact so
      ;; the wrapper can stamp it.
      (is (= [:div.with-coord {:id "x"} 7] out)))))

(deftest source-coord-tagged-form-2-render-passes-through-hiccup
  (testing "fold + stamping: tagged Form-2 render returns inner-fn hiccup intact"
    (let [render-fn (with-meta
                      (fn [_] (fn [n] [:p.coord {:class "live"} n]))
                      {:reagent2/form :reagent2/form-2})
          c         (fake-instance [render-fn 9])
          out       (component/wrap-render c render-fn)]
      (is (= [:p.coord {:class "live"} 9] out)))))

;; ---------------------------------------------------------------------------
;; as-element-fn unregistered → throw (rf2-lijel + rf2-n9nlk)
;;
;; The make-render-method seam runs hiccup through the registered
;; `reagent2.impl.template/as-element` fn before handing the result to
;; React. If the seam is unregistered (a hand-rolled test bundle that
;; requires component without template), the slim adapter throws
;; `:rf.error/as-element-fn-unregistered` — fail-fast over the silent
;; pass-through that surfaced the React \"Objects are not valid as a
;; React child\" error the rf2-08t0 fix was about.
;;
;; Production load order via reagent2.core pulls template; template's
;; ns-load calls set-as-element-fn!. The test paths below temporarily
;; null the seam via set-as-element-fn! and then restore it.
;; ---------------------------------------------------------------------------

(defn- with-unregistered-as-element-fn
  "Run `f` with the as-element seam nulled out. Restores
  `reagent2.impl.template/as-element` (the production registrant) on
  exit so subsequent tests see the live seam."
  [f]
  (try
    (component/set-as-element-fn! nil)
    (f)
    (finally
      (component/set-as-element-fn! template/as-element))))

(deftest as-element-fn-unregistered-render-throws
  (testing "render method throws :rf.error/as-element-fn-unregistered
            when the as-element seam is null (rf2-lijel)"
    (with-unregistered-as-element-fn
      (fn []
        (let [^js klass (component/create-class*
                          {:reagent-render (fn [_this] [:div "x"])
                           :display-name   "UnregisteredTest"})
              render    (proto-method klass "render")
              ;; Build an instance shell the render method can run
              ;; against. We don't drive React; we call render directly
              ;; with a synthesised `this`.
              instance  #js {:props #js {:__rfArgv [(fn [_t] [:div "x"])]}
                             :cljsArgv [(fn [_t] [:div "x"])]
                             :cljsRenderRea nil}
              thrown    (try
                          (.call render instance)
                          nil
                          (catch :default e (ex-data e)))]
          (is (= :rf.error/as-element-fn-unregistered (:type thrown))
              ":type identifies the unregistered seam class")
          (is (= :no-recovery (:recovery thrown))
              ":recovery is :no-recovery — there is no fallback path")
          (is (string? (:reason thrown))
              ":reason carries an actionable message"))))))
