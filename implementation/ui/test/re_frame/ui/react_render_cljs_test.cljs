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
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.runtime :as rt]))

(defn- render [el] (rds/renderToStaticMarkup el))
(defn- current-render [id] (:render-fn (reactive/view-descriptor id)))
(defn- current-compare [id] (:compare-fn (reactive/view-descriptor id)))

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
  (let [rf (current-render ::static-tree)]
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

(defn- captured-jsx-props [view-id value]
  (let [module   rt/jsx-runtime
        original (.-jsx module)
        captured (atom nil)]
    (try
      (set! (.-jsx module)
            (fn [_type props & _]
              (reset! captured props)
              #js {}))
      ((current-render view-id) (js-obj "v" value))
      @captured
      (finally
        (set! (.-jsx module) original)))))

(deftest prototype-setter-key-remains-an-own-prop-on-every-jsx-surface
  (doseq [[surface view-id]
          [[:dom ::proto-dom]
           [:custom-element ::proto-custom]
           [:view ::proto-view]
           [:foreign ::proto-foreign]]]
    (let [value (js-obj "surface" (name surface))
          props (captured-jsx-props view-id value)]
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
  (let [el      ((current-render ::counter) (js-obj "n" 3 "locked?" false))
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
  (let [el ((current-render ::todo-row) (js-obj "todo" {:id 9 :label "x" :done? false
                                              :priority :low}))
        onChange (find-prop el "onChange")]
    (onChange (js-obj "target" (js-obj "checked" true)))
    (is (= [[:todo/toggle 9 true]] @dispatches))))

(deftest capture-free-handlers-hoist-and-dedupe
  ;; same capture-free vector in two renders -> the same fn object
  (let [el1 ((current-render ::counter) (js-obj "n" 1 "locked?" false))
        el2 ((current-render ::counter) (js-obj "n" 2 "locked?" false))]
    (is (identical? (find-prop el1 "onClick") (find-prop el2 "onClick"))
        "capture-free literal event vectors hoist to one module callback")))

(deftest unwired-dispatch-throws-loudly
  (rt/set-dispatch-hook! nil)
  (let [el ((current-render ::counter) (js-obj "n" 1 "locked?" false))
        onClick (find-prop el "onClick")]
    (is (thrown-with-msg? js/Error #"ui-dispatch-unwired"
                          (onClick (js-obj))))))

;; ---------------------------------------------------------------------------
;; The ruled rf= memo comparator
;; ---------------------------------------------------------------------------

(deftest memo-comparator-is-the-ruled-rf=
  (let [cmp (current-compare ::todo-list)]
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
  (let [cmp (current-compare ::as-view)]
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
  (rt/register-view! ::probe-view
                     (fn [_props] nil)
                     (fn [_prev _next] true)
                     "probe-view"
                     {:view-id ::probe-view
                      :hook-signature "hs1-probe"
                      :doc "probe"})
  (let [meta* (registrar/handler-meta :view ::probe-view)]
    (is (some? meta*) "register-view! writes the registrar :view kind")
    (is (true? (:rf.ui/compiled? meta*)))
    (is (= "probe" (:doc meta*)))
    (is (= ::probe-view (get-in meta* [:rf.ui/manifest :view-id])))))

(deftest registrar-hooks-observe-one-coherent-view-publication
  (let [id      ::registrar-hook-publication
        seen    (atom [])
        body    (fn [label]
                  (fn [_props]
                    (rt/jsx2 "output" (js-obj "children" label))))
        render1 (body "v1")
        render2 (body "v2")
        cmp1    (fn [_ _] true)
        cmp2    (fn [_ _] false)]
    ;; Registration hooks are process-lived by registrar contract, so this
    ;; one is permanently inert for every id except its unique fixture id.
    (registrar/add-registration-hook!
     (fn [{:keys [kind id now]}]
       (when (and (= :view kind) (= ::registrar-hook-publication id))
         (let [descriptor (reactive/view-descriptor id)]
           (swap! seen conj
                  (try
                    {:body-revision (reactive/view-generation id)
                     :manifest (:rf.ui/manifest now)
                     :descriptor-manifest (:manifest descriptor)
                     :render-fn (:render-fn descriptor)
                     :compare-fn (:compare-fn descriptor)
                     ;; Counterexample: the synchronous hook renders the
                     ;; just-published shell. Preparing after register! makes
                     ;; first load call nil and replacement render old code.
                     :markup (render (rt/jsx2 (:handler-fn now) (js-obj)))}
                    (catch :default e
                      {:error e})))))))
    (rt/register-view! id render1 cmp1 "HookPublication"
                       {:view-id id :hook-signature "hs1-hook" :version 1})
    (rt/register-view! id render2 cmp2 "HookPublication"
                       {:view-id id :hook-signature "hs1-hook" :version 2})
    (is (= 2 (count @seen)) "the hook ran on first registration and replacement")
    (is (every? #(nil? (:error %)) @seen)
        "the stable shell was renderable during both synchronous hooks")
    (is (= [0 1] (mapv :body-revision @seen)))
    (is (= ["<output>v1</output>" "<output>v2</output>"]
           (mapv :markup @seen))
        "first load sees a descriptor; reload sees the new body")
    (is (= [1 2] (mapv #(get-in % [:manifest :version]) @seen)))
    (is (= (mapv :manifest @seen) (mapv :descriptor-manifest @seen))
        "registrar manifest and render authority move as one observation")
    (is (identical? render1 (:render-fn (first @seen))))
    (is (identical? render2 (:render-fn (second @seen))))
    (is (identical? cmp1 (:compare-fn (first @seen))))
    (is (identical? cmp2 (:compare-fn (second @seen))))))

(deftest hmr-slot-publication-is-atomic-and-identities-are-stable
  (let [id      ::atomic-publication
        render1 (fn [_props] nil)
        render2 (fn [_props] nil)
        cmp1    (fn [_ _] true)
        cmp2    (fn [_ _] false)
        shell1  (rt/register-view! id render1 cmp1 "AtomicView"
                                   {:view-id id :hook-signature "hs1-a"})
        pair1   (reactive/view-shells id)
        seen    (atom [])
        stop    (reactive/subscribe-view!
                 id
                 #(swap! seen conj
                         {:body (reactive/view-generation id)
                          :remount (reactive/view-remount-generation id)
                          :descriptor (reactive/view-descriptor id)}))
        shell2  (rt/register-view! id render2 cmp2 "AtomicView"
                                   {:view-id id :hook-signature "hs1-b"})]
    (stop)
    (is (identical? shell1 shell2) "fresh-shell mutation is rejected")
    (is (identical? (:inner pair1) (:inner (reactive/view-shells id))))
    (is (= 1 (count @seen)) "one successful registration emits one notify")
    (let [{:keys [body remount descriptor]} (first @seen)]
      (is (= [1 1] [body remount]))
      (is (identical? render2 (:render-fn descriptor)))
      (is (identical? cmp2 (:compare-fn descriptor)))
      (is (= "hs1-b" (get-in descriptor [:manifest :hook-signature]))
          "listener observes descriptor and both revisions from one publication"))))

(deftest failed-first-registration-publishes-unavailable-compensation
  (let [id ::failed-publication
        seen (atom [])
        stop (volatile! nil)
        result (try
                 (with-redefs [registrar/register!
                               (fn [& _]
                                 (vreset! stop
                                          (reactive/subscribe-view!
                                           id
                                           #(swap! seen conj
                                                   [(reactive/view-generation id)
                                                    (reactive/view-remount-generation id)
                                                    (reactive/view-descriptor id)])))
                                 (throw (js/Error. "registrar boom")))]
                   (rt/register-view! id (fn [_props] nil) (fn [_ _] true)
                                      "FailedView"
                                      {:view-id id :hook-signature "hs1-a"}))
                 :unexpected-success
                 (catch :default _ :thrown))]
    (when @stop (@stop))
    (is (= :thrown result))
    (is (nil? (reactive/registered-view-revision id))
        "the failed first load is unavailable, never registered")
    (is (nil? (reactive/view-descriptor id))
        "a failed registration cannot become the dynamic render authority")
    (is (= [[1 1 nil]] @seen)
        "the provisional first-load shape is compensated by one fresh tombstone")))

(deftest failed-replacement-rolls-back-the-entire-view-publication
  (let [id      ::failed-replacement
        render1 (fn [_props] :v1)
        cmp1    (fn [_ _] true)
        shell1  (rt/register-view! id render1 cmp1 "RollbackV1"
                                   {:view-id id
                                    :hook-signature "hs1-a"
                                    :version 1})
        before  (reactive/view-descriptor id)
        pair     (reactive/view-shells id)
        seen     (atom [])
        cell     (reactive/make-cell id 0)
        provisional-capture (atom nil)
        stop     (reactive/subscribe-view!
                  id #(swap! seen conj
                             [(reactive/view-generation id)
                              (reactive/view-remount-generation id)]))
        result   (try
                   (with-redefs [registrar/register!
                                 (fn [& _]
                                   (is (= 1 (reactive/view-generation id))
                                       "candidate is prepared for registrar hooks")
                                   (is (= 2 (get-in (reactive/view-descriptor id)
                                                    [:manifest :version])))
                                   ;; Model the synchronous registrar callback
                                   ;; re-entering the stable shell before failure.
                                   (reactive/advance-generation!
                                    cell (reactive/view-generation id))
                                   (reset! provisional-capture
                                           (second
                                            (reactive/with-capture
                                             cell (fn [] :provisional))))
                                   (throw (js/Error. "replacement boom")))]
                     (rt/register-view! id (fn [_props] :v2) (fn [_ _] false)
                                        "RollbackV2"
                                        {:view-id id
                                         :hook-signature "hs1-b"
                                         :version 2}))
                   :unexpected-success
                   (catch :default _ :thrown))]
    (stop)
    (is (= :thrown result))
    (is (= 2 (reactive/view-generation id))
        "rollback never decrements past the observed provisional revision")
    (is (= 2 (reactive/view-remount-generation id))
        "changed candidate g+1 is restored to the old shape at fresh g+2")
    (is (identical? before (reactive/view-descriptor id))
        "the fresh compensating publication restores the last good descriptor")
    (is (identical? shell1 (:outer (reactive/view-shells id))))
    (is (identical? (:inner pair) (:inner (reactive/view-shells id))))
    (is (= "RollbackV1" (.-displayName shell1))
        "failed candidate display names do not leak")
    (is (= [[2 2]] @seen) "restoration notifies mounted shells exactly once")
    (is (= :stale (reactive/commit! cell @provisional-capture))
        "a capture of the provisional descriptor can never commit after rollback")
    (is (= 1 (reactive/generation cell))
        "the cell observed n+1 but was never forced backwards")
    (reactive/advance-generation! cell (reactive/view-generation id))
    (let [[value capture]
          (reactive/with-capture
           cell (fn [] ((:render-fn (reactive/view-descriptor id)) nil)))]
      (is (= :v1 value))
      (is (identical? cell (reactive/commit! cell capture)))
      (is (= 2 (reactive/generation cell))
          "the restored descriptor advances and commits; the cell is not stuck"))))

(deftest rollback-same-hook-shape-advances-body-only-and-stale-token-is-a-no-op
  (let [id ::rollback-same-shape
        descriptor {:render-fn identity :compare-fn =
                    :manifest {:hook-signature "hs1-a"}}
        _ (reactive/register-view-descriptor! id "hs1-a" descriptor)
        seen (atom 0)
        stop (reactive/subscribe-view! id #(swap! seen inc))
        publication
        (reactive/prepare-view-descriptor! id "hs1-a" (assoc descriptor :v 1))]
    (reactive/rollback-view-descriptor! publication)
    (stop)
    (is (= 2 (reactive/view-generation id))
        "restoration is a fresh monotone body publication")
    (is (= 0 (reactive/view-remount-generation id))
        "same hook shape does not remount")
    (is (identical? descriptor (reactive/view-descriptor id)))
    (is (= 1 @seen)))
  (let [id ::stale-rollback-token
        descriptor {:render-fn identity :compare-fn =
                    :manifest {:hook-signature "hs1-a"}}
        _ (reactive/register-view-descriptor! id "hs1-a" descriptor)
        stale (reactive/prepare-view-descriptor! id "hs1-a"
                                                 (assoc descriptor :v :stale))
        winner (reactive/prepare-view-descriptor! id "hs1-a"
                                                  (assoc descriptor :v :winner))
        _ (reactive/commit-view-descriptor! winner)
        seen (atom 0)
        stop (reactive/subscribe-view! id #(swap! seen inc))]
    (reactive/rollback-view-descriptor! stale)
    (stop)
    (is (= 2 (reactive/view-generation id)))
    (is (= :winner (:v (reactive/view-descriptor id))))
    (is (= 0 @seen) "a stale rollback token is a complete no-op")))

(deftest nested-failed-publications-restore-parent-transaction-authority
  (let [id ::nested-rollback
        d0 {:v 0} d1 {:v 1} d2 {:v 2}
        _ (reactive/register-view-descriptor! id "hs0" d0)
        cell (reactive/make-cell id 0)
        p1 (reactive/prepare-view-descriptor! id "hs1" d1)
        _ (reactive/advance-generation! cell 1)
        [_ cap1] (reactive/with-capture cell (fn [] :p1))
        p2 (reactive/prepare-view-descriptor! id "hs2" d2)
        _ (reactive/advance-generation! cell 2)
        [_ cap2] (reactive/with-capture cell (fn [] :p2))]
    (reactive/rollback-view-descriptor! p2)
    (is (= 1 (:v (reactive/view-descriptor id)))
        "nested failure restores the parent provisional descriptor")
    (is (= 3 (reactive/view-generation id)))
    (reactive/rollback-view-descriptor! p1)
    (is (= 0 (:v (reactive/view-descriptor id)))
        "the still-authoritative parent can compensate to last-known-good")
    (is (= 4 (reactive/view-generation id)))
    (is (= 4 (reactive/view-remount-generation id)))
    (is (= :stale (reactive/commit! cell cap1)))
    (is (= :stale (reactive/commit! cell cap2))))
  (let [id ::nested-child-fails-parent-succeeds
        d0 {:v 0} d1 {:v 1} d2 {:v 2}
        _ (reactive/register-view-descriptor! id "hs0" d0)
        p1 (reactive/prepare-view-descriptor! id "hs1" d1)
        p2 (reactive/prepare-view-descriptor! id "hs2" d2)]
    (reactive/rollback-view-descriptor! p2)
    (reactive/commit-view-descriptor! p1)
    (is (= 1 (:v (reactive/view-descriptor id))))
    (is (= 3 (reactive/registered-view-revision id))
        "parent success commits the fresh compensating revision")
    (is (= 3 (reactive/view-remount-generation id)))))

(deftest scheduler-reset-never-strands-an-already-loaded-defview
  (let [shell-before static-tree
        descriptor-before (reactive/view-descriptor ::static-tree)]
    (reactive/reset-scheduler!)
    (is (identical? shell-before static-tree))
    (is (identical? descriptor-before
                    (reactive/view-descriptor ::static-tree)))
    (is (= (str "<div class=\"panel\" id=\"about\" "
                "style=\"padding:16px;border-top:1px solid #ccc\">"
                "<h2 class=\"title\">About</h2>"
                "<p>Static, <em>hoisted</em>.</p>"
                "<footer aria-label=\"footer\" tabindex=\"0\">"
                "(c) 2026 &lt;re-frame2&gt;</footer></div>")
           (render (rt/jsx2 static-tree (js-obj))))
        "fixture/scheduler cleanup leaves the defview's live authority intact")))
