(ns re-frame.adapter.reagent-slim-source-coord-form3-cljs-test
  "reagent-slim Form-3 coverage for the shared source-coordinate wrapper
  (rf2-e6hsn). The sibling slim suites cover Form-1, Form-2, fragments, and
  elision; NONE covered a real reagent-slim `create-class` (Form-3) flowing
  through `re-frame.views.source-coord-annotation/inject-source-coord-attr`.

  THE DEFECT this closes. `inject-source-coord-attr` recognises a Reagent-family
  Form-3 class structurally so it can pass it through UNCHANGED (a class root has
  no concrete DOM node to annotate — it must reach React as a class so its
  lifecycle methods install). Before rf2-e6hsn the `reagent-class?` predicate
  recognised ONLY the stock-Reagent marker (`prototype.reagentRender`). A real
  slim class carries the constructor tag `cljsReagentClass = true` plus
  `prototype.render` + `cljsReagentRender` — NEVER `prototype.reagentRender` — so
  it fell to the plain `fn?` Form-2 branch, was returned as an
  `inject-source-coord-attr$form-2-wrapper`, and was later invoked as an ordinary
  function rather than mounted as a class, LOSING its React lifecycle. slim is a
  first-class supported adapter (rf2-ukq8qt / PR #6087), so the wrapper must
  recognise its Form-3 shape.

  The wrapper is shared by BOTH debug annotations (`data-rf2-source-coord` and
  `data-rf-view` ride the same Hiccup walk); a Form-3 class root is exempt from
  both, so preserving the class identity is the whole contract for this branch —
  no attribute of either kind is injected.

  These are pure structural / lifecycle assertions (no real DOM); ns ends in
  -cljs-test so shadow-cljs's :node-test build picks it up under `npm run
  test:cljs`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [goog.object :as gobj]
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

;; ---- direct: slim Form-3 class passes through unwrapped -------------------

(deftest slim-form-3-class-passes-through-unwrapped
  (testing "a real reagent-slim `create-class` result flows through the shared
            source-coordinate wrapper UNCHANGED — same class identity, never
            re-wrapped as a Form-2 render fn (rf2-e6hsn). Pre-fix this returned
            an `inject-source-coord-attr$form-2-wrapper` fn and the class
            identity was lost."
    (let [slim-class (r2/create-class {:reagent-render (fn [] [:div "form-3 body"])
                                       :display-name   "SlimForm3"})
          out        (rf.views.source-coord-annotation/inject-source-coord-attr
                       :rf.slim-src-coord/form-3
                       "rf.slim-src-coord:form-3:1:1"
                       slim-class)]
      (is (identical? slim-class out)
          "the slim Form-3 class is returned by identity (not a Form-2 wrapper)")
      ;; The survived value still carries slim's Form-3 markers — proof it is the
      ;; class itself, not a plain wrapper fn the class was smuggled behind.
      (is (true? (gobj/get out "cljsReagentClass"))
          "returned value still carries slim's `cljsReagentClass` constructor tag")
      (is (some? (some-> (gobj/get out "prototype") (gobj/get "render")))
          "returned value still has its React `prototype.render` (lifecycle site)"))))

;; ---- stock-shape structural parity (additive, not a replacement) ----------

(deftest stock-reagent-shape-still-classified-form-3
  (testing "the stock-Reagent Form-3 marker (`prototype.reagentRender`) is STILL
            recognised — the slim branch is ADDITIVE. Stock Reagent is not on
            slim's classpath, so this uses the exact structural marker the
            predicate keys off as a faithful stand-in (a real stock class is
            exercised on the Reagent-bridge classpath); both supported class
            shapes are classified Form-3."
    (let [stock-shape (fn stock-form-3 [])]
      (set! (.-prototype stock-shape) #js {:reagentRender (fn [])})
      (let [out (rf.views.source-coord-annotation/inject-source-coord-attr
                  :rf.slim-src-coord/stock-shape
                  "rf.slim-src-coord:stock-shape:1:1"
                  stock-shape)]
        (is (identical? stock-shape out)
            "a stock-shaped Form-3 class passes through by identity")))))

;; ---- full runtime path via reg-view* --------------------------------------

(deftest slim-form-3-preserved-through-registered-view
  (testing "end-to-end: a Form-3 slim view registered via reg-view* renders to
            the class itself under debug/source annotation — the wrapper never
            invokes or re-wraps it (rf2-e6hsn acceptance). Pre-fix the rendered
            value was a Form-2 wrapper, not the class."
    (let [slim-class (r2/create-class {:reagent-render (fn [] [:div "rv-form-3"])})]
      (rf/reg-view* :rf.slim-src-coord/rv-form-3 (fn [] slim-class))
      (let [render (rf/view :rf.slim-src-coord/rv-form-3)
            out    (render)]
        (is (identical? slim-class out)
            "the registered Form-3 view yields the class by identity")))))

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
      (is (= 1 (count warnings))
          (str "expected EXACTLY ONE warning across 5 passes over the same "
               "Form-3 id; got " (count warnings) ": " (pr-str warnings)))
      (is (str/includes? (first warnings) "rf.slim-src-coord/warn-once-f3")
          "the single warning names the offending view-id"))))

;; ---- render + lifecycle intact on the survived class ----------------------

(deftest slim-form-3-survivor-retains-react-lifecycle
  (testing "the class that survives the wrapper still mounts as a React class:
            its `render` produces a React element and its `componentDidMount`
            lifecycle fires — the representative behaviour the pre-fix Form-2
            wrapping destroyed (rf2-e6hsn)."
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
      (is (identical? slim-class out) "sanity: identity preserved before mount")
      (let [el (.call (.. out -prototype -render) inst)]
        (is (some? el) "render produced a React element")
        (is (= "p" (.-type el)) "element type is the hiccup head tag"))
      (.call (.. out -prototype -componentDidMount) inst)
      (is (true? @mounted?)
          "componentDidMount fired the user :component-did-mount fn (lifecycle intact)")
      ;; Tidy: dispose the per-instance render Reaction created during render.
      (.call (.. out -prototype -componentWillUnmount) inst))))
