(ns re-frame.adapter.reagent-slim-source-coord-form3-cljs-test
  "A real reagent-slim `create-class` (Form-3) through
  `re-frame.views.source-coord-annotation/inject-source-coord-attr`. A class
  root has no DOM node to annotate, so the wrapper must pass it through
  UNCHANGED. A slim class carries `cljsReagentClass` and `cljsReagentRender`,
  never stock Reagent's `prototype.reagentRender`, so a predicate keyed only
  on the stock marker would wrap it as a Form-2 fn and lose its React
  lifecycle."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.views.source-coord-annotation :as rf.views.source-coord-annotation]
            [reagent2.core :as r2]
            ;; ns-load wires the hiccup -> React-element `as-element` seam that
            ;; the class's `render` method delegates through (lifecycle test).
            [reagent2.impl.template]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter}))

;; ---- helper: capture console.warn calls -----------------------------------

(defn- with-captured-console-warn
  "Replace js/console.warn with a recording shim around `thunk`. Returns a
  vector of the joined-string messages observed. Restores the original on the
  way out, even if thunk throws."
  [thunk]
  (let [calls    (atom [])
        original (.-warn js/console)]
    (try
      (set! (.-warn js/console)
            (fn [& args] (swap! calls conj (apply str args))))
      (thunk)
      @calls
      (finally
        (set! (.-warn js/console) original)))))

;; ---- one-shot non-DOM-root warning, truthful ------------------------------

(deftest slim-form-3-warns-non-dom-root-once
  (testing "a Form-3 class root is a non-DOM root: the wrapper emits the
            documented one-shot warning per id and injects NO attribute. The
            warning fires EXACTLY ONCE across repeated renders (truthful,
            one-shot — the warned-set is a process-wide defonce cleared between
            tests by the reset-runtime fixture)."
    (let [slim-class (r2/create-class {:reagent-render (fn [] [:div])})
          warnings   (with-captured-console-warn
                       (fn []
                         (dotimes [_ 5]
                           (rf.views.source-coord-annotation/inject-source-coord-attr
                             :rf.slim-src-coord/warn-once-f3
                             "rf.slim-src-coord:warn-once-f3:1:1"
                             slim-class))))]
      (is (= [1 true] [(count warnings) (str/includes? (str (first warnings)) "rf.slim-src-coord/warn-once-f3")])
          (str "exactly one warning across 5 passes, naming the view-id; got " (pr-str warnings))))))

;; ---- render + lifecycle intact on the survived class ----------------------

(deftest slim-form-3-survivor-retains-react-lifecycle
  (testing "the class that survives the wrapper still mounts as a React class:
            its `render` produces a React element and its `componentDidMount`
            lifecycle fires — the representative behaviour Form-2
            wrapping would destroy."
    (let [mounted?   (atom false)
          slim-class (r2/create-class
                       {:reagent-render      (fn [] [:p "lifecycle-intact"])
                        :component-did-mount (fn [_this] (reset! mounted? true))
                        :display-name        "SlimForm3Lifecycle"})
          out        (rf.views.source-coord-annotation/inject-source-coord-attr
                       :rf.slim-src-coord/lifecycle-f3
                       "rf.slim-src-coord:lifecycle-f3:1:1"
                       slim-class)
          ;; React.Component's constructor sets `this.props`, so the synthesised
          ;; instance reads its argv the same way a mounted instance would.
          inst       (new out #js {:__rfArgv [:form-3]})]
      (is (= [true "p"]
             [(identical? slim-class out) (.-type (.call (.. out -prototype -render) inst))])
          "the class passes through unchanged and still renders its hiccup head")
      (.call (.. out -prototype -componentDidMount) inst)
      (is (true? @mounted?)
          "componentDidMount fired the user :component-did-mount fn (lifecycle intact)")
      ;; Tidy: dispose the per-instance render Reaction created during render.
      (.call (.. out -prototype -componentWillUnmount) inst))))
