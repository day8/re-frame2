(ns re-frame.ui.react-render-cljs-test
  "CLJS emitter goldens against REAL React (react-dom/server 19.x):
  compiled views render through react/jsx-runtime to the exact markup the
  host produces — prop-name conversion, class/style rules, hoisting
  semantics, handler lowering with compile-time placeholder splicing, the
  ruled rf= memo comparator, foreign components, custom elements, spread.

  No DOM: renderToStaticMarkup exercises the render path; focused handler
  tests publish an ownership-free candidate through the internal EventOwner
  test seam, then invoke the same stable committed callback a DOM node gets."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            ["react-dom/server" :as rds]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.events :as events]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.runtime :as rt]))

(defn- strip-view-evidence
  "Drop the DEV host-root view-evidence annotation (`data-rf2-source-coord` /
  `data-rf-view`) the compiler stamps on a view's compiler-owned root element
  (rf2-hac8p). These goldens assert prop-name conversion, class/style rules,
  hoisting, slots and handler lowering — the host-root annotation is noise here;
  its own coverage is the dedicated emit-annotation tests, the parity corpus,
  and `test:elision`."
  [html]
  (str/replace html #"\s+data-rf(?:2-source-coord|-view)=\"[^\"]*\"" ""))

(defn- render [el] (strip-view-evidence (rds/renderToStaticMarkup el)))
(defn- current-render [id] (:render-fn (reactive/view-descriptor id)))
(defn- current-compare [id] (:compare-fn (reactive/view-descriptor id)))

(defn- with-captured-console-warn
  [thunk]
  (let [prior (.-warn js/console)
        calls (atom [])]
    (set! (.-warn js/console)
          (fn [& args] (swap! calls conj (mapv str args))))
    (try
      {:value (thunk) :warnings @calls}
      (finally
        (set! (.-warn js/console) prior)))))

(defn- register-hmr-version!
  [id version hook-signature display-name]
  (rt/register-view!
   id
   (fn [_props] version)
   (fn [_prev _next] true)
   display-name
   {:view-id id
    :hook-signature hook-signature
    :version version}))

(defonce dispatches (atom []))
(defonce dispatch-opts-seen (atom []))

(use-fixtures :each
  {:before (fn []
             (reset! dispatches [])
             (reset! dispatch-opts-seen []))})

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

(defview dynamic-handler-probe [{:keys [handler]}]
  [:button {:on-click handler} "dynamic"])

(defview unregistered-handler-probe []
  [:button {:on-click [::intentionally-unregistered]} "warn"])

(defview passive-ref-explicitness-probe [{:keys [authored-ref]}]
  [:button {:ref authored-ref
            :on-wheel {:event [::passive-ref-event]
                       :passive true}}
   "ref"])

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

;; ui/spread-safe — the literal safe-spread policy (rf2-isdqjv). Owned props
;; (:value / :on-change / :type) are literal; the caller attr map is forwarded
;; safely: owned wins, :class composes owned-first, aria-*/data-*/title pass.
(defview safe-spread-view
  [{:keys [attr]}]
  [:input.form-control
   (ui/spread-safe {:value "owned" :on-change [:set :rf.ui/value] :type "text"}
                   attr)])

;; rf2-m5h0f — evaluation-order probes. The mark helpers append to `eval-order`
;; and return an ordinary attr value, so a rendered `ui/spread-safe` records the
;; ACTUAL order (and count) its owned + caller expressions ran in. The authored
;; order is owned-then-caller; both hosts must observe exactly that.
(defonce ^:private eval-order (atom []))
(defn- omark! [v] (swap! eval-order conj [:owned v]) v)
(defn- cmark! [v] (swap! eval-order conj [:caller v]) v)
(defn- oboom! [] (swap! eval-order conj [:owned :boom]) (throw (ex-info "owned boom" {})))
(defn- cboom! [] (swap! eval-order conj [:caller :boom]) (throw (ex-info "caller boom" {})))

;; owned + caller both literal-at-site so their value expressions ride the
;; compiled spread-safe path (not the caller's construction site).
(defview order-probe []
  [:input.op (ui/spread-safe {:title (omark! "t")} {:data-x (cmark! "c")})])
(defview order-owned-throws []
  [:input.op (ui/spread-safe {:title (omark! "t") :lang (oboom!)}
                             {:data-x (cmark! "c")})])
(defview order-caller-throws []
  [:input.op (ui/spread-safe {:title (omark! "t")} {:data-x (cboom!)})])
;; a runtime caller carrying a denied key: the owned expr must have already run
;; when the every-build deny throws.
(defview order-denial [{:keys [attr]}]
  [:input.op (ui/spread-safe {:title (omark! "t") :value "owned"
                              :on-change [:set :rf.ui/value]}
                             attr)])

;; rf2-j4len — owned :class + :title are DYNAMIC (from props) so they can be nil.
(defview nil-owned-probe
  [{:keys [oc ot caller]}]
  [:input (ui/spread-safe {:class oc :title ot} caller)])

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

;; DEV bare-view-alias diagnostic fixtures (rf2-vxgfnd.95.15). `alias-source-view`
;; is a real registered view; `bare-view-alias-copy` is a plain `(def …)` var
;; copy of it — def does NOT carry `:rf.ui/view` metadata, so the compiler
;; classifies `[bare-view-alias-copy …]` as a FOREIGN head, whose runtime value
;; is nonetheless the registered shell. `uses-canonical-alias` references the
;; view by its canonical name (a :view head — quiet), and `uses-foreign` above
;; is a genuine foreign component (also quiet).
(defview alias-source-view [{:keys [x]}] [:span x])
(def bare-view-alias-copy alias-source-view)
(defview uses-bare-alias      [] [bare-view-alias-copy {:x "aliased"}])
(defview uses-canonical-alias [] [alias-source-view {:x "canonical"}])

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

(deftest safe-spread-passthrough-and-conversion
  (let [html (render (rt/jsx2 safe-spread-view
                              (js-obj "attr" {:aria-label "Name"
                                              :data-testid "n"
                                              :title "t"
                                              :class "extra"
                                              :placeholder "p"})))]
    ;; aria-*/data-*/title/placeholder pass through per the 004B rule table
    (is (str/includes? html "aria-label=\"Name\"") "aria-* passes")
    (is (str/includes? html "data-testid=\"n\"") "data-* passes")
    (is (str/includes? html "title=\"t\"") ":title passes")
    (is (str/includes? html "placeholder=\"p\"") "other attrs pass")
    ;; :class composes — owned/sugar classes first, then the caller's
    (is (str/includes? html "class=\"form-control extra\"") ":class composes owned-first")
    ;; owned props render and are unclobbered
    (is (str/includes? html "value=\"owned\"") "owned :value renders")
    (is (str/includes? html "type=\"text\"") "owned :type renders")))

(deftest safe-spread-owned-props-win-over-caller
  ;; :type is not a denied key, so a caller may name it — but OWNED wins.
  (let [html (render (rt/jsx2 safe-spread-view
                              (js-obj "attr" {:type "password" :class "x"})))]
    (is (str/includes? html "type=\"text\"") "owned :type wins over the caller's")
    (is (str/includes? html "class=\"form-control x\"") "owned classes first")))

(deftest safe-spread-denied-key-throws-in-every-build
  ;; A runtime caller map carrying an owned/structural key is rejected — the
  ;; guard is NOT goog.DEBUG-gated (the advanced-build proof rides the
  ;; -elision-prod-test companion). :value is the controlled contract.
  (doseq [[k v] [[:value "evil"] [:checked true] [:ref "r"]
                 [:on-change [:hijack]]]]
    (let [data (try (render (rt/jsx2 safe-spread-view
                                     (js-obj "attr" {k v})))
                    nil
                    (catch :default e (ex-data e)))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id data))
          (str "caller " k " is denied in every build"))
      (is (= 're-frame.ui/spread-safe (:where data)))))
  ;; the same site through general ui/spread does NOT throw (visible-cost escape)
  (is (string? (render (rt/jsx2 spread-view (js-obj "extra" {:value "ok"}))))
      "general ui/spread accepts any key — it is the visible-cost escape"))

(deftest safe-spread-caller-key-canonicalization-deny
  ;; rf2-izep3 — the every-build deny compares the CANONICAL emitted name, so
  ;; alternate spellings (namespaced keyword / string / symbol), a non-map
  ;; caller, and a non-nameable key are all rejected through the actual CLJS
  ;; render/convert path — not only the exact keyword keys.
  (testing "namespaced/string/symbol aliases of a denied key are rejected"
    (doseq [attr [{:caller/ref "r"} {"ref" "r"} {:some/value "v"}
                  {"checked" "c"} {:x/on-change [:hijack]}]]
      (let [data (try (render (rt/jsx2 safe-spread-view (js-obj "attr" attr)))
                      nil (catch :default e (ex-data e)))]
        (is (= :rf.error/ui-tree-malformed (:rf.error/id data))
            (str "alias denied: " (pr-str attr)))
        (is (= 're-frame.ui/spread-safe (:where data))))))
  (testing "a non-map caller (a sequence of pairs) is rejected"
    (is (= :rf.error/ui-tree-malformed
           (:rf.error/id (try (render (rt/jsx2 safe-spread-view
                                               (js-obj "attr" [[:aria-label "x"]])))
                              nil (catch :default e (ex-data e)))))))
  (testing "a non-nameable key is rejected"
    (is (= :rf.error/ui-tree-malformed
           (:rf.error/id (try (render (rt/jsx2 safe-spread-view (js-obj "attr" {5 "x"})))
                              nil (catch :default e (ex-data e)))))))
  (testing "exact allowed keys (incl. a string spelling) still pass"
    (is (string? (render (rt/jsx2 safe-spread-view
                                  (js-obj "attr" {:aria-label "n" "data-x" "d"})))))))

(deftest safe-spread-owned-handler-family-denied-through-render
  ;; rf2-xdvob — the ADVERSARIAL slot-divergence case. safe-spread-view owns
  ;; :on-change (React slot onChange). A caller spelling the SAME emitted family —
  ;; already-camel onChange, capture onChangeCapture, kebab-capture, namespaced —
  ;; must be denied through the real render/convert path. Pre-fix these bypassed
  ;; the name-only deny and installed a handler in the owned event slot.
  (doseq [attr [{"onChange" [:hijack]} {:onChange [:hijack]}
                {"onChangeCapture" [:hijack]} {:on-change-capture [:hijack]}
                {:x/on-change [:hijack]}]]
    (let [data (try (render (rt/jsx2 safe-spread-view (js-obj "attr" attr)))
                    nil (catch :default e (ex-data e)))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id data))
          (str "owned handler family denied through render: " (pr-str attr)))
      (is (= 're-frame.ui/spread-safe (:where data)))))
  (testing "an unrelated event family passes (safe-spread-view owns only on-change)"
    (is (str/includes? (render (rt/jsx2 safe-spread-view (js-obj "attr" {:on-focus [:ok]})))
                       "value=\"owned\"")
        "a different event family is allowed")))

(deftest safe-spread-caller-class-spelling-canonicalizes-to-slot
  ;; rf2-xdvob — an alternate :class spelling (namespaced / string) routes through
  ;; :class COMPOSITION (owned classes first), NOT a bypassing raw-name attr set.
  ;; Pre-fix a namespaced/string spelling missed the identity-based :class branch
  ;; and emitted className WITHOUT composing (and diverged from the JVM key).
  (doseq [attr [{:ns/class "extra"} {"class" "extra"}]]
    (is (str/includes? (render (rt/jsx2 safe-spread-view (js-obj "attr" attr)))
                       "class=\"form-control extra\"")
        (str "caller class spelling composes owned-first: " (pr-str attr)))))

(deftest safe-spread-authored-owned-then-caller-eval-order
  ;; rf2-m5h0f — the authored (spread-safe owned caller) order is owned-then-
  ;; caller on CLJS (matching the JVM). Each expression runs EXACTLY once.
  (testing "success: owned before caller, once each"
    (reset! eval-order [])
    (is (string? (render (rt/jsx2 order-probe (js-obj)))))
    (is (= [[:owned "t"] [:caller "c"]] @eval-order)
        "owned evaluates before caller, each exactly once"))
  (testing "owned throws: the caller is never reached"
    (reset! eval-order [])
    (try (render (rt/jsx2 order-owned-throws (js-obj))) (catch :default _))
    (is (= [[:owned "t"] [:owned :boom]] @eval-order)
        "owned ran (and threw) before any caller expression"))
  (testing "caller throws: owned already ran"
    (reset! eval-order [])
    (try (render (rt/jsx2 order-caller-throws (js-obj))) (catch :default _))
    (is (= [[:owned "t"] [:caller :boom]] @eval-order)
        "owned evaluated first, then the caller expression threw"))
  (testing "caller denial throws: owned already ran"
    (reset! eval-order [])
    (let [data (try (render (rt/jsx2 order-denial (js-obj "attr" {:ref "r"})))
                    nil (catch :default e (ex-data e)))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id data)) "the deny fired")
      (is (= [[:owned "t"]] @eval-order)
          "owned evaluated before the every-build caller deny threw"))))

(deftest safe-spread-nil-owned-prop-absent-and-host-parity
  ;; rf2-j4len — a nil-normalized owned DYNAMIC prop is ABSENT before layering,
  ;; so the caller value survives; a non-nil owned value wins; class composes
  ;; owned-first. General for dynamic owned props (:class AND :title). This is
  ;; the CLJS half; the JVM half (already correct) pins the same result.
  (let [caller {:class "cc" :title "ct"}]
    (testing "nil owned dynamic prop is absent: the caller value survives"
      (let [html (render (rt/jsx2 nil-owned-probe
                                  (js-obj "oc" nil "ot" nil "caller" caller)))]
        (is (str/includes? html "class=\"cc\"") "nil owned :class -> caller :class survives")
        (is (str/includes? html "title=\"ct\"") "nil owned :title -> caller :title survives")))
    (testing "non-nil owned wins; class composes owned-first"
      (let [html (render (rt/jsx2 nil-owned-probe
                                  (js-obj "oc" "oc" "ot" "ot" "caller" caller)))]
        (is (str/includes? html "class=\"oc cc\"") "class composes owned-first")
        (is (str/includes? html "title=\"ot\"") "non-nil owned :title wins")))
    (testing "owned present, caller absent: owned renders"
      (let [html (render (rt/jsx2 nil-owned-probe
                                  (js-obj "oc" "ocls" "ot" "otitle" "caller" {})))]
        (is (str/includes? html "class=\"ocls\""))
        (is (str/includes? html "title=\"otitle\""))))))

(deftest trusted-html-single-bypass
  (is (= "<div class=\"content\"><b>bold & raw</b></div>"
         (render (rt/jsx2 trusted (js-obj))))
      "ui/html bypasses escaping through the parent element"))

(deftest foreign-component-renders
  (is (= "<div><b>L</b></div>"
         (render (rt/jsx2 uses-foreign (js-obj "l" "L"))))))

;; ---------------------------------------------------------------------------
;; DEV bare-view-alias diagnostic (rf2-vxgfnd.95.15) — runtime behaviour.
;; A bare `(def alias other/view)` var copy used as a component head resolves at
;; runtime to the registered view shell; the DEV foreign-head guard warns once
;; (deduped by view id), naming the lost checks + the canonical/namespace-alias
;; recovery. Canonical view heads and genuine foreign components stay quiet.
;; ---------------------------------------------------------------------------

(defn- alias-warnings [warnings]
  (filter (fn [call]
            (some #(str/includes? % "bare var alias of a registered view") call))
          warnings))

(deftest bare-view-alias-head-warns-once-and-dedupes
  (rt/clear-bare-view-alias-warned!)
  (let [{:keys [warnings]}
        (with-captured-console-warn
         (fn []
           ;; renders through the aliased shell — still valid markup
           (is (= "<span>aliased</span>" (render (rt/jsx2 uses-bare-alias (js-obj)))))
           ;; a SECOND render must not re-warn (process-lifetime dedup by view id)
           (render (rt/jsx2 uses-bare-alias (js-obj)))))
        warns (alias-warnings warnings)]
    (is (= 1 (count warns))
        "a bare-alias foreign head warns exactly once, deduped across renders")
    (let [msg (str/join " " (first warns))]
      (is (str/includes? msg ":rf.ui.compile/bare-view-alias")
          "the warning carries the compile-tier diagnostic id (no Spec 009 row)")
      (is (str/includes? msg "alias-source-view")
          "the warning names the aliased view")
      (is (str/includes? msg "canonical")
          "the warning names the canonical-name / namespace-alias recovery"))))

(deftest canonical-and-genuine-foreign-heads-never-warn
  (rt/clear-bare-view-alias-warned!)
  (let [{:keys [warnings]}
        (with-captured-console-warn
         (fn []
           (render (rt/jsx2 uses-canonical-alias (js-obj)))   ; canonical :view head
           (render (rt/jsx2 uses-foreign (js-obj "l" "L")))))] ; genuine foreign (plain fn)
    (is (empty? (alias-warnings warnings))
        "a canonical view head and a genuine foreign component both stay quiet")))

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

(deftest foreign-spread-props-literal-wins-by-presence-and-proto-ownership
  ;; rf2-xu095 — the FOREIGN `ui/spread` merge helper, executed directly. A
  ;; foreign boundary is OPEN: forwarded keys pass through VERBATIM as own props;
  ;; the compiled literal object WINS every collision BY PRESENCE (even an
  ;; explicit null literal), retaining false/0; and the magic `__proto__` key
  ;; stays a verbatim OWN prop, never a prototype mutation.
  (testing "verbatim forwarded names; ordinary + nil collision precedence; false/0 retained"
    (let [fwd     {:label "hi" :data/x "keep" :id "fwd" :title "fwd-title"}
          literal (js-obj)]
      ;; literal owns: id (ordinary win), title=null (nil win), disabled=false, count=0
      (unchecked-set literal "id" "lit")
      (unchecked-set literal "title" nil)      ; explicit own null, as a nil dynamic literal materializes
      (unchecked-set literal "disabled" false)
      (unchecked-set literal "count" 0)
      (let [o (rt/foreign-spread-props fwd literal)]
        (is (= "hi" (unchecked-get o "label")) "plain forwarded name is verbatim")
        (is (= "keep" (unchecked-get o "data/x")) "namespaced forwarded name is verbatim ns/name")
        (is (= "lit" (unchecked-get o "id")) "ordinary collision: literal wins")
        (is (has-own? o "title") "nil-literal collision still materializes the key by presence")
        (is (nil? (unchecked-get o "title")) "nil literal WINS the collision — forwarded value does not survive")
        (is (false? (unchecked-get o "disabled")) "false literal retained")
        (is (= 0 (unchecked-get o "count")) "0 literal retained"))))
  (testing "forwarded __proto__ is an OWN prop; output prototype unchanged"
    (let [sentinel (js-obj "tag" "fwd-proto")
          o        (rt/foreign-spread-props {:__proto__ sentinel} (js-obj))]
      (is (has-own? o "__proto__") "forwarded __proto__ is an own data property")
      (is (identical? sentinel (unchecked-get o "__proto__")) "verbatim forwarded value")
      (is (identical? (js/Object.getPrototypeOf o) (.-prototype js/Object))
          "output keeps Object.prototype — no prototype mutation")))
  (testing "literal __proto__ wins the collision and stays an own prop"
    (let [lit-proto (js-obj "tag" "lit-proto")
          literal   (js-obj)]
      (js/Object.defineProperty
       literal "__proto__" #js {:value lit-proto :writable true :enumerable true :configurable true})
      (let [o (rt/foreign-spread-props {:__proto__ (js-obj "tag" "fwd-proto")} literal)]
        (is (has-own? o "__proto__") "own data property, not a prototype mutation")
        (is (identical? lit-proto (unchecked-get o "__proto__")) "literal __proto__ wins the collision")
        (is (identical? (js/Object.getPrototypeOf o) (.-prototype js/Object))
            "output keeps Object.prototype unchanged")))))

;; ---------------------------------------------------------------------------
;; Handlers — committed callback identity + compile-time placeholder provenance
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

(defn- fake-frame-ops []
  {:frame ::test-frame
   :dispatch (fn [event opts]
               (swap! dispatch-opts-seen conj opts)
               (swap! dispatches conj event))
   :dispatch-sync (fn [event opts]
                    (swap! dispatch-opts-seen conj opts)
                    (swap! dispatches conj event))})

(defn- committed-render
  [owner view-id props]
  (let [[element capture]
        (events/with-capture
         owner ::test-frame #((current-render view-id) props))]
    (events/commit! owner capture (fake-frame-ops))
    element))

(deftest handler-vector-dispatches-and-splices
  (let [owner   (events/make-owner ::counter)
        el      (committed-render owner ::counter
                                  (js-obj "n" 3 "locked?" false))
        onClick (find-prop el "onClick")
        onInput (find-prop el "onInput")]
    (is (fn? onClick))
    (onClick (js-obj))
    (is (= [[:counter/inc 2]] @dispatches))
    (is (= :ui (:source (first @dispatch-opts-seen))))
    (is (= {:view-id ::counter :classification :vector}
           (select-keys (:source-detail (first @dispatch-opts-seen))
                        [:view-id :classification]))
        "dispatch provenance is stamped at the authored event site")
    (reset! dispatches [])
    (onInput (js-obj "target" (js-obj "value" "typed")))
    (is (= [[:counter/set "typed"]] @dispatches)
        ":rf.ui/value splices the event's target.value at dispatch time")))

(deftest checked-placeholder-splices
  (let [owner (events/make-owner ::todo-row)
        el (committed-render
            owner ::todo-row
            (js-obj "todo" {:id 9 :label "x" :done? false :priority :low}))
        onChange (find-prop el "onChange")]
    (onChange (js-obj "target" (js-obj "checked" true)))
    (is (= [[:todo/toggle 9 true]] @dispatches))))

(deftest committed-site-callback-is-stable-across-render-commits
  (let [owner (events/make-owner ::counter)
        el1 (committed-render owner ::counter
                              (js-obj "n" 1 "locked?" false))
        el2 (committed-render owner ::counter
                              (js-obj "n" 2 "locked?" false))]
    (is (identical? (find-prop el1 "onClick") (find-prop el2 "onClick"))
        "one mounted lexical site keeps one callback while commit retargets data")))

(deftest abandoned-first-render-does-not-publish-its-callback
  (let [owner (events/make-owner ::counter)
        [abandoned _]
        (events/with-capture
         owner ::test-frame
         #((current-render ::counter) (js-obj "n" 1 "locked?" false)))
        committed (committed-render owner ::counter
                                    (js-obj "n" 2 "locked?" false))]
    (is (not (identical? (find-prop abandoned "onClick")
                         (find-prop committed "onClick")))
        "an uncommitted candidate cannot seed the owner's stable callback table")))

(deftest dynamic-handler-values-classify-behind-one-stable-site
  (let [owner (events/make-owner ::dynamic-handler-probe)
        render-one #(committed-render
                     owner ::dynamic-handler-probe (js-obj "handler" %))
        vector-el (render-one [::runtime-vector :rf.ui/value])
        callback  (find-prop vector-el "onClick")]
    (callback (js-obj "target" (js-obj "value" "not-spliced")))
    (is (= [[::runtime-vector :rf.ui/value]] @dispatches)
        "a placeholder-looking keyword in runtime data stays ordinary data")
    (let [seen (atom nil)
          event (js-obj "kind" "native")
          fn-el (render-one #(reset! seen %))]
      (is (identical? callback (find-prop fn-el "onClick"))
          "runtime type changes retarget meaning, not callback identity")
      (callback event)
      (is (identical? event @seen)))
    (reset! dispatches [])
    (let [once-callback (find-prop
                         (render-one {:event [::once] :once true}) "onClick")]
      (once-callback (js-obj))
      (once-callback (js-obj))
      (is (= [[::once]] @dispatches)
          ":once is enforced behind the stable React callback"))
    (is (nil? (find-prop (render-one nil) "onClick"))
        "nil removes the committed handler")
    (is (thrown-with-msg?
         js/Error #"classify by type"
         (render-one 42))
        "invalid runtime values fail at render, before commit")))

(deftest handler-dev-warnings-use-the-trace-catalogue-operations
  (let [traces (atom [])
        key    ::event-warning-capture]
    (trace/register-listener! key #(swap! traces conj %))
    (try
      (committed-render (events/make-owner ::unregistered-handler-probe)
                        ::unregistered-handler-probe (js-obj))
      (committed-render
       (events/make-owner ::dynamic-handler-probe)
       ::dynamic-handler-probe
       (js-obj "handler" [::runtime-vector :rf.ui/value]))
      (let [operations (into #{} (map :operation) @traces)
            warning (some #(when (= :rf.warning/placeholder-in-dynamic-vector
                                    (:operation %))
                             %)
                          @traces)
            site (get-in (reactive/view-descriptor ::dynamic-handler-probe)
                         [:manifest :sites :events 0])]
        (is (contains? operations :rf.warning/unregistered-event-id))
        (is (contains? operations :rf.warning/placeholder-in-dynamic-vector)
            "both advisory conditions are structured trace events")
        (is (= {:operation :rf.warning/placeholder-in-dynamic-vector
                :op-type :warning
                :recovery :warned-and-continued}
               (select-keys warning [:operation :op-type :recovery])))
        (is (= {:event [::runtime-vector :rf.ui/value]
                :placeholder :rf.ui/value
                :reason (str "placeholder keywords splice only in literal "
                             "compiled event vectors; this runtime vector "
                             "dispatches the keyword as ordinary data")
                :view-id ::dynamic-handler-probe
                :site-id (:sid site)
                :source-coord (:source-coord site)
                :occurrence-path (:path site)}
               (select-keys
                (:tags warning)
                [:event :placeholder :reason :view-id :site-id
                 :source-coord :occurrence-path]))
            "the warning envelope matches the Spec 009 catalogue exactly"))
      (finally
        (trace/unregister-listener! key)))))

(deftest unmarked-dynamic-function-ref-is-rejected-without-invocation
  (let [called (atom 0)
        owner  (events/make-owner ::passive-ref-explicitness-probe)
        callback-ref (fn [_node] (swap! called inc))
        error (try
                (events/with-capture
                 owner ::test-frame
                 #((current-render ::passive-ref-explicitness-probe)
                   (js-obj "authored-ref" callback-ref)))
                nil
                (catch :default e e))]
    (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data error))))
    (is (= :unmarked-callback-ref (:kind (ex-data error))))
    (is (zero? @called)
        "the rejected unmarked function is never invoked as a callback ref")))

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

(deftest hmr-publication-listener-failure-never-starves-siblings-or-rolls-back-commit
  ;; rf2-vxgfnd.215 — drive a throw before, between, and after the two good
  ;; listeners. A bare doseq aborts at the throw, skips whichever siblings
  ;; follow it, and runtime/register-view! then falsely treats the committed
  ;; publication as registrar failure.
  (doseq [order [[:throw :a :b] [:a :throw :b] [:a :b :throw]]]
    (let [label   (name (first order))
          id      (keyword "hmr-listener-order" (str (hash order)))
          shell   (register-hmr-version! id 0 "hs-order" (str "Order-" label))
          seen    (atom [])
          stops   (mapv (fn [entry]
                          (reactive/subscribe-view!
                           id
                           (case entry
                             :throw #(throw (js/Error. (str "boom-" label)))
                             :a     #(swap! seen conj :a)
                             :b     #(swap! seen conj :b))))
                        order)
          {:keys [value warnings]}
          (with-captured-console-warn
            #(register-hmr-version! id 1 "hs-order" (str "Order-" label)))]
      (doseq [stop stops] (stop))
      (is (identical? shell value)
          "a post-commit listener failure is not reported as registration failure")
      (is (= [:a :b] @seen) "both good siblings run exactly once in every ordering")
      (is (= 1 (reactive/view-generation id)))
      (is (= 0 (reactive/view-remount-generation id)))
      (is (= 1 (get-in (reactive/view-descriptor id) [:manifest :version])))
      (is (identical? shell (registrar/handler :view id))
          "registrar and descriptor authority remain committed and coherent")
      (is (= 1 (count warnings)) "one bounded warning reports the publication failure")
      (let [warning (str/join " " (first warnings))]
        (is (str/includes? warning (pr-str id)) "warning names the view")
        (is (str/includes? warning "commit") "warning names the publication phase")
        (is (str/includes? warning "revision 1") "warning names the committed revision")))))

(deftest hmr-publication-detects-falsy-thrown-values-by-presence
  ;; JS permits `throw false` and `throw null`. First-failure selection must
  ;; carry an explicit presence bit; `or`, `some?`, and truthiness all lose one
  ;; of these values and can silently report a later error instead.
  (doseq [[label thrown-value expected] [[:false false "false"] [:null nil "nil"]]]
    (let [id    (keyword "hmr-falsy-listener" (name label))
          _     (register-hmr-version! id 0 "hs-falsy" (str "Falsy-" (name label)))
          seen  (atom 0)
          stop1 (reactive/subscribe-view! id #(throw thrown-value))
          stop2 (reactive/subscribe-view! id #(throw (js/Error. "secondary")))
          stop3 (reactive/subscribe-view! id #(swap! seen inc))
          {:keys [warnings]}
          (with-captured-console-warn
            #(register-hmr-version! id 1 "hs-falsy" (str "Falsy-" (name label))))]
      (stop1) (stop2) (stop3)
      (is (= 1 @seen) "a falsy throw cannot starve a later sibling")
      (is (= 1 (count warnings)))
      (is (str/includes? (str/join " " (first warnings)) expected)
          "the first falsy thrown value, not the secondary error, is reported"))))

(deftest hmr-publication-listener-set-is-snapshotted-before-delivery
  (let [id      ::listener-snapshot
        _       (reactive/register-view-descriptor! id "hs-snapshot" {:version 0})
        seen    (atom [])
        changed (atom false)
        stop-b  (volatile! nil)
        stop-c  (volatile! nil)
        stop-a  (reactive/subscribe-view!
                 id
                 (fn []
                   (swap! seen conj :a)
                   (when (compare-and-set! changed false true)
                     (@stop-b)
                     (vreset! stop-c
                              (reactive/subscribe-view! id #(swap! seen conj :c))))))]
    (vreset! stop-b (reactive/subscribe-view! id #(swap! seen conj :b)))
    (reactive/register-view-descriptor! id "hs-snapshot" {:version 1})
    (is (= [:a :b] @seen)
        "unsubscribe/subscribe during fan-out affects only the next publication")
    (reactive/register-view-descriptor! id "hs-snapshot" {:version 2})
    (is (= [:a :b :a :c] @seen))
    (stop-a)
    (when @stop-c (@stop-c))))

(deftest reentrant-hmr-publication-owns-its-snapshot-and-newer-revision
  (let [id     ::reentrant-listener-publication
        _      (reactive/register-view-descriptor! id "hs-reentrant" {:version 0})
        seen   (atom [])
        nested (atom false)
        stop-a (reactive/subscribe-view!
                id
                (fn []
                  (let [version (:version (reactive/view-descriptor id))]
                    (swap! seen conj [:a version])
                    (when (and (= 1 version) (compare-and-set! nested false true))
                      (reactive/register-view-descriptor!
                       id "hs-reentrant" {:version 2})))))
        stop-b (reactive/subscribe-view!
                id #(swap! seen conj [:b (:version (reactive/view-descriptor id))]))]
    (reactive/register-view-descriptor! id "hs-reentrant" {:version 1})
    (stop-a) (stop-b)
    (is (= [[:a 1] [:a 2] [:b 2] [:b 2]] @seen)
        "nested publication completes its own snapshot before the outer resumes")
    (is (= 2 (:version (reactive/view-descriptor id)))
        "the outer transaction never rolls back the nested winner")
    (is (= 2 (reactive/view-generation id)))))

(deftest rollback-listener-failure-preserves-primary-and-completes-compensation
  (let [id       ::rollback-listener-failure
        shell    (register-hmr-version! id 0 "hs-rb-a" "RollbackListenerV1")
        primary  (js/Error. "registrar primary")
        seen     (atom 0)
        stop-bad (reactive/subscribe-view! id #(throw (js/Error. "rollback listener")))
        stop-ok  (reactive/subscribe-view! id #(swap! seen inc))
        {:keys [value warnings]}
        (with-captured-console-warn
          #(try
             (with-redefs [registrar/register! (fn [& _] (throw primary))]
               (register-hmr-version! id 1 "hs-rb-b" "RollbackListenerV2"))
             ::unexpected-success
             (catch :default e e)))]
    (stop-bad) (stop-ok)
    (is (identical? primary value)
        "a rollback listener failure cannot mask the registrar's primary failure")
    (is (= 1 @seen) "compensation still reaches the good sibling")
    (is (= 2 (reactive/view-generation id)) "rollback is a fresh monotone publication")
    (is (= 2 (reactive/view-remount-generation id)))
    (is (= 0 (get-in (reactive/view-descriptor id) [:manifest :version])))
    (is (identical? shell (registrar/handler :view id)))
    (is (= "RollbackListenerV1" (.-displayName shell))
        "the primary registrar failure still restores the prior diagnostic name")
    (is (= 1 (count warnings)))
    (is (str/includes? (str/join " " (first warnings)) "rollback"))))

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

(deftest stale-failure-cannot-clobber-a-reentrant-winners-debug-name
  (let [id       ::reentrant-name-winner
        shell    (rt/register-view! id (fn [_props] :v1) (fn [_ _] true)
                                    "NameV1"
                                    {:view-id id
                                     :hook-signature "hs-name"
                                     :version 1})
        reentered? (atom false)
        result   (try
                   (with-redefs [registrar/register!
                                 (fn [& _]
                                   (when (compare-and-set! reentered? false true)
                                     (rt/register-view!
                                      id (fn [_props] :v3) (fn [_ _] false)
                                      "NameV3"
                                      {:view-id id
                                       :hook-signature "hs-name"
                                       :version 3})
                                     (throw (js/Error. "stale v2 failure"))))]
                     (rt/register-view! id (fn [_props] :v2) (fn [_ _] false)
                                        "NameV2"
                                        {:view-id id
                                         :hook-signature "hs-name"
                                         :version 2}))
                   :unexpected-success
                   (catch :default _ :thrown))]
    (is (= :thrown result))
    (is (= 3 (get-in (reactive/view-descriptor id) [:manifest :version]))
        "the reentrant registration remains the descriptor authority")
    (is (= "NameV3" (.-displayName shell))
        "the stale outer failure cannot restore its pre-publication name")
    (is (= "NameV3$Body"
           (.-displayName (:inner (reactive/view-shells id))))
        "the winning inner shell name is fenced by the same publication token")))

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
