(ns re-frame.ui.react-render-cljs-test
  "CLJS emitter goldens against REAL React (react-dom/server 19.x):
  compiled views render through react/jsx-runtime to the exact markup the
  host produces — prop-name conversion, class/style rules, hoisting
  semantics, handler lowering with compile-time placeholder splicing, the
  ruled rf= memo comparator, foreign components, custom elements, spread.

  No DOM: renderToStaticMarkup exercises the render path; handler fns are
  plucked from the element tree and invoked with fake events against the
  S1 dispatch hook."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            ["react-dom/server" :as rds]
            [re-frame.registrar :as registrar]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.runtime :as rt]))

(defn- render [el] (rds/renderToStaticMarkup el))

(defonce dispatches (atom []))

(use-fixtures :each
  {:before (fn []
             (reset! dispatches [])
             (rt/set-dispatch-hook! (fn [ev] (swap! dispatches conj ev))))
   :after  (fn [] (rt/set-dispatch-hook! nil))})

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(ui/custom-element :fancy-input {:properties #{:help-text}})

(defview static-tree
  "Fully static subtree — hoists to one module constant."
  []
  [:div#about.panel {:style {:padding 16 :border-top "1px solid #ccc"}}
   [:h2.title "About"]
   [:p "Static, " [:em "hoisted"] "."]
   [:footer {:aria-label "footer" :tab-index 0} "(c) 2026 <re-frame2>"]])

(defview counter
  [{:keys [n locked?]}]
  [:div.counter
   [:h1 "Count: " n]
   [:button.btn {:on-click [:counter/inc 2] :disabled locked?} "+"]
   [:input {:value (str n) :on-input [:counter/set :rf.ui/value] :read-only true}]
   (when locked? [:p.warn "Locked"])
   (if (neg? n) [:p.neg "neg"] [:p.pos "pos"])])

(defview todo-row
  [{:keys [todo]}]
  (let [{:keys [id label done? priority]} todo]
    [:li.todo-item {:class {:done done?} :data-priority priority :aria-hidden false}
     [:input {:type :checkbox :checked done? :on-change [:todo/toggle id :rf.ui/checked]
              :read-only true}]
     [:span.label label]
     (when (= priority :high) [:span.flag "HIGH"])]))

(defview todo-list
  {:props [:map [:title :string] [:todos [:vector :map]]]}
  [{:keys [title todos]}]
  [:section.todos
   [:h2 title]
   [:ul (for [t todos]
          [todo-row {:key (:id t) :todo t}])]
   [:p.count (count todos) " items"]])

(defview svg-icon
  []
  [:svg {:view-box "0 0 10 10" :class "icon"}
   [:path {:stroke-width 2 :d "M0 0"}]
   [:use {:xlink-href "#i"}]])

(defview with-defaults
  [{:keys [a b] :or {a "A" b "B"}}]
  [:div [:span a] [:span b]])

(defview as-view
  [{:keys [known] :as all}]
  [:div
   [:span.known (str known)]
   [:span.count (str (count all))]])

(defview ns-props-view
  [{:cart/keys [item] :keys [plain]}]
  [:div [:span (str item)] [:span plain]])

(defview accepts-children
  [{:keys [title children]}]
  [:section [:h3 title] [:div.body children]])

(defview uses-children
  []
  [accepts-children {:title "T"}
   [:p "one"]
   [:p "two"]])

(defview custom-el-view
  [{:keys [help]}]
  [:fancy-input {:help-text help :data-x "d" :disabled true}])

(defview spread-view
  [{:keys [extra]}]
  [:div.card (ui/spread {:tab-index 3 :class "base"} extra)
   [:span "inner"]])

(defview trusted
  []
  [:div.content (ui/html "<b>bold & raw</b>")])

(defn plain-fc [props]
  (let [label (unchecked-get props "label")]
    (rt/jsx2 "b" (js-obj "children" label))))

(defview uses-foreign
  [{:keys [l]}]
  [:div [plain-fc {:label l}]])

;; `__proto__` is the one string key with prototype-setter grammar in a JS
;; object literal. Keep one dynamic fixture for each props surface so the
;; generated config object can be captured before React transforms it.
(defview proto-child [] [:span])
(defview proto-dom [{:keys [v]}] [:div {:__proto__ v}])
(defview proto-custom [{:keys [v]}] [:fancy-input {:__proto__ v}])
(defview proto-view [{:keys [v]}] [proto-child {:__proto__ v}])
(defview proto-foreign [{:keys [v]}] [plain-fc {:__proto__ v}])

;; ---------------------------------------------------------------------------
;; Renders
;; ---------------------------------------------------------------------------

(deftest static-subtree-renders-and-hoists
  ;; goldens are REACT's actual emission (react-dom/server 19.2.0):
  ;; className is our first prop pair; camel attr names (readOnly) emit
  ;; verbatim (HTML names are case-insensitive); checked/value defer to
  ;; the end of the tag (React's form-control handling)
  (is (= (str "<div class=\"panel\" id=\"about\" "
              "style=\"padding:16px;border-top:1px solid #ccc\">"
              "<h2 class=\"title\">About</h2>"
              "<p>Static, <em>hoisted</em>.</p>"
              "<footer aria-label=\"footer\" tabindex=\"0\">"
              "(c) 2026 &lt;re-frame2&gt;</footer></div>")
         (render (rt/jsx2 static-tree (js-obj)))))
  ;; the fully-static body hoists to a module constant: the render fn
  ;; returns the IDENTICAL element object across calls
  (let [rf (.-type static-tree)]
    (is (identical? (rf (js-obj)) (rf (js-obj)))
        "static subtree is one hoisted module constant")))

(deftest dynamic-props-and-branches-render
  (is (= (str "<div class=\"counter\"><h1>Count: 3</h1>"
              "<button class=\"btn\">+</button>"
              "<input readOnly=\"\" value=\"3\"/>"
              "<p class=\"pos\">pos</p></div>")
         (render (rt/jsx2 counter (js-obj "n" 3 "locked?" false)))))
  (is (= (str "<div class=\"counter\"><h1>Count: -1</h1>"
              "<button class=\"btn\" disabled=\"\">+</button>"
              "<input readOnly=\"\" value=\"-1\"/>"
              "<p class=\"warn\">Locked</p>"
              "<p class=\"neg\">neg</p></div>")
         (render (rt/jsx2 counter (js-obj "n" -1 "locked?" true))))))

(deftest keyed-list-and-view-nesting-render
  (is (= (str "<section class=\"todos\"><h2>T</h2><ul>"
              "<li class=\"todo-item done\" data-priority=\"high\" aria-hidden=\"false\">"
              "<input type=\"checkbox\" readOnly=\"\" checked=\"\"/>"
              "<span class=\"label\">a</span><span class=\"flag\">HIGH</span></li>"
              "<li class=\"todo-item\" data-priority=\"low\" aria-hidden=\"false\">"
              "<input type=\"checkbox\" readOnly=\"\"/>"
              "<span class=\"label\">b</span></li>"
              "</ul><p class=\"count\">2 items</p></section>")
         (render (rt/jsx2 todo-list
                          (js-obj "title" "T"
                                  "todos" [{:id 1 :label "a" :done? true :priority :high}
                                           {:id 2 :label "b" :done? false :priority :low}]))))
      "aria-* values always stringify: :aria-hidden false -> aria-hidden=\"false\""))

(deftest svg-name-conversion
  (is (= (str "<svg class=\"icon\" viewBox=\"0 0 10 10\">"
              "<path stroke-width=\"2\" d=\"M0 0\"></path>"
              "<use xlink:href=\"#i\"></use></svg>")
         (render (rt/jsx2 svg-icon (js-obj))))))

(deftest q2-or-defaults-absent-vs-nil
  ;; absent slot -> default; present-nil slot -> nil (NOT the default)
  (is (= "<div><span>A</span><span>B</span></div>"
         (render (rt/jsx2 with-defaults (js-obj)))))
  (is (= "<div><span>x</span><span></span></div>"
         (render (rt/jsx3 with-defaults (js-obj "a" "x" "b" nil) "k")))))

(deftest q2-as-materialization
  (is (= "<div><span class=\"known\">1</span><span class=\"count\">2</span></div>"
         (render (rt/jsx2 as-view (js-obj "known" 1 "cart/extra" 2))))
      ":as materializes ALL present slots as decoded keywords"))

(deftest q3-namespaced-slots
  (is (= "<div><span>7</span><span>p</span></div>"
         (render (rt/jsx2 ns-props-view (js-obj "cart/item" 7 "plain" "p"))))
      "namespace-preserving slot encoding: :cart/item -> \"cart/item\""))

(deftest q4-children-flow
  (is (= (str "<section><h3>T</h3>"
              "<div class=\"body\"><p>one</p><p>two</p></div></section>")
         (render (rt/jsx2 uses-children (js-obj))))))

(deftest custom-element-property-classification
  (is (= "<fancy-input helpText=\"h\" data-x=\"d\" disabled=\"\"></fancy-input>"
         (render (rt/jsx2 custom-el-view (js-obj "help" "h"))))
      "declared :help-text -> helpText JS property name; undeclared stay attrs"))

(deftest spread-conversion
  ;; merge semantics: overrides WIN per key ({:class "x"} replaces the
  ;; base's "base"); .card sugar renders first
  (is (= (str "<div tabindex=\"3\" class=\"card x\">"
              "<span>inner</span></div>")
         (render (rt/jsx2 spread-view
                          (js-obj "extra" {:class "x"}))))
      "spread merges (overrides win), sugar-first classes, names via the table"))

(deftest trusted-html-single-bypass
  (is (= "<div class=\"content\"><b>bold & raw</b></div>"
         (render (rt/jsx2 trusted (js-obj))))
      "ui/html bypasses escaping through the parent element"))

(deftest foreign-component-renders
  (is (= "<div><b>L</b></div>"
         (render (rt/jsx2 uses-foreign (js-obj "l" "L"))))))

(defn- has-own? [o k]
  (.call (.-hasOwnProperty (.-prototype js/Object)) o k))

(defn- captured-jsx-props [view value]
  (let [module   rt/jsx-runtime
        original (.-jsx module)
        captured (atom nil)]
    (try
      (set! (.-jsx module)
            (fn [_type props & _]
              (reset! captured props)
              #js {}))
      ((.-type view) (js-obj "v" value))
      @captured
      (finally
        (set! (.-jsx module) original)))))

(deftest prototype-setter-key-remains-an-own-prop-on-every-jsx-surface
  (doseq [[surface view]
          [[:dom proto-dom]
           [:custom-element proto-custom]
           [:view proto-view]
           [:foreign proto-foreign]]]
    (let [value (js-obj "surface" (name surface))
          props (captured-jsx-props view value)]
      (is (some? props) (name surface))
      (is (has-own? props "__proto__")
          (str (name surface) " has an own __proto__ data property"))
      (is (identical? value (unchecked-get props "__proto__"))
          (str (name surface) " retains the exact authored value"))
      (is (identical? (js/Object.getPrototypeOf props)
                      (.-prototype js/Object))
          (str (name surface) " keeps Object.prototype unchanged")))))

;; ---------------------------------------------------------------------------
;; Handlers — compile-time placeholder splice + dispatch hook
;; ---------------------------------------------------------------------------

(defn- find-prop
  "Walk a React element tree (as data) for the first `name` prop."
  [el name]
  (when (and el (.-props el))
    (or (unchecked-get (.-props el) name)
        (let [ch (.-children (.-props el))]
          (some #(find-prop % name)
                (cond
                  (nil? ch) []
                  (array? ch) ch
                  :else [ch]))))))

(deftest handler-vector-dispatches-and-splices
  (let [el      ((.-type counter) (js-obj "n" 3 "locked?" false))
        onClick (find-prop el "onClick")
        onInput (find-prop el "onInput")]
    (is (fn? onClick))
    (onClick (js-obj))
    (is (= [[:counter/inc 2]] @dispatches))
    (reset! dispatches [])
    (onInput (js-obj "target" (js-obj "value" "typed")))
    (is (= [[:counter/set "typed"]] @dispatches)
        ":rf.ui/value splices the event's target.value at dispatch time")))

(deftest checked-placeholder-splices
  (let [el ((.-type todo-row) (js-obj "todo" {:id 9 :label "x" :done? false
                                              :priority :low}))
        onChange (find-prop el "onChange")]
    (onChange (js-obj "target" (js-obj "checked" true)))
    (is (= [[:todo/toggle 9 true]] @dispatches))))

(deftest capture-free-handlers-hoist-and-dedupe
  ;; same capture-free vector in two renders -> the same fn object
  (let [el1 ((.-type counter) (js-obj "n" 1 "locked?" false))
        el2 ((.-type counter) (js-obj "n" 2 "locked?" false))]
    (is (identical? (find-prop el1 "onClick") (find-prop el2 "onClick"))
        "capture-free literal event vectors hoist to one module callback")))

(deftest unwired-dispatch-throws-loudly
  (rt/set-dispatch-hook! nil)
  (let [el ((.-type counter) (js-obj "n" 1 "locked?" false))
        onClick (find-prop el "onClick")]
    (is (thrown-with-msg? js/Error #"ui-dispatch-unwired"
                          (onClick (js-obj))))))

;; ---------------------------------------------------------------------------
;; The ruled rf= memo comparator
;; ---------------------------------------------------------------------------

(deftest memo-comparator-is-the-ruled-rf=
  (let [cmp (.-compare todo-list)]
    (is (fn? cmp))
    ;; fresh-but-equal CLJS data => equal (no repaint)
    (is (true? (cmp (js-obj "title" "T" "todos" [{:id 1}])
                    (js-obj "title" "T" "todos" [{:id 1}]))))
    ;; changed value => not equal
    (is (false? (cmp (js-obj "title" "T" "todos" [{:id 1}])
                     (js-obj "title" "T" "todos" [{:id 2}]))))
    ;; undeclared slots are invisible to the straight-line comparator
    (is (true? (cmp (js-obj "title" "T" "todos" [] "noise" 1)
                    (js-obj "title" "T" "todos" [] "noise" 2))))
    ;; NaN is repaint-stable (Object.is branch)
    (is (true? (cmp (js-obj "title" ##NaN "todos" [])
                    (js-obj "title" ##NaN "todos" []))))
    ;; -0/+0 compare EQUAL (deliberate divergence from raw Object.is)
    (is (true? (cmp (js-obj "title" -0.0 "todos" [])
                    (js-obj "title" 0.0 "todos" []))))
    ;; host objects fall through to identity
    (let [o (js-obj)]
      (is (true? (cmp (js-obj "title" o "todos" [])
                      (js-obj "title" o "todos" []))))
      (is (false? (cmp (js-obj "title" (js-obj) "todos" [])
                       (js-obj "title" (js-obj) "todos" [])))))
    ;; absent vs present-nil compare equal (undefined ~ null under rf=)
    (is (true? (cmp (js-obj "title" nil "todos" [])
                    (js-obj "todos" []))))))

(deftest as-view-generic-comparator
  (let [cmp (.-compare as-view)]
    (is (true? (cmp (js-obj "a" 1 "b" [1 2]) (js-obj "a" 1 "b" [1 2]))))
    (is (false? (cmp (js-obj "a" 1) (js-obj "a" 1 "c" 3)))
        ":as views compare the UNION of slots (generic comparison)")))

;; ---------------------------------------------------------------------------
;; Registrar entries (dev builds)
;; ---------------------------------------------------------------------------

(deftest views-register-in-the-view-kind
  ;; Order-robust across the shared node process (other suites reset the
  ;; registrar between tests): register through the same public entry the
  ;; emitted code calls and assert the entry shape. That defview EMITS
  ;; this call under the goog.DEBUG gate is pinned as an emission-form
  ;; test on the JVM (defview-grammar-jvm-test), where it is order-free.
  (rt/register-view! ::probe-view todo-list
                     {:view-id ::probe-view :doc "probe"})
  (let [meta* (registrar/handler-meta :view ::probe-view)]
    (is (some? meta*) "register-view! writes the registrar :view kind")
    (is (true? (:rf.ui/compiled? meta*)))
    (is (= "probe" (:doc meta*)))
    (is (= ::probe-view (get-in meta* [:rf.ui/manifest :view-id])))))
