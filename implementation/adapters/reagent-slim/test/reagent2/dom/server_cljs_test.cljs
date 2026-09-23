(ns reagent2.dom.server-cljs-test
  "Unit tests for reagent2.dom.server (Stage 4-E, rf2-6hyy).

  Per IMPL-SPEC §8 + §12.1 + §12.5 R-004. Covers:

    - Plain text content escaping (`&`, `<`, `>`).
    - Attribute serialisation (HTML attrs; keyword values stringified).
    - Boolean attrs (truthy → present without value; falsy → absent).
    - Void tags (no closing tag, no children emitted).
    - Fragments (`:<>`) — children only, no surrounding markup.
    - Nested hiccup.
    - Sequences as children.
    - `:dangerouslySetInnerHTML` raw-emission.
    - Tag shorthand (`:div.foo#bar`) merged into class/id attrs.
    - User-fn heads invoked + recurse.
    - React-component heads (`:>`, `:r>`, `:f>`) emit comment placeholder.
    - React context PROVIDER heads walk their children (rf2-iyz6j).

  Parity tests against `react-dom/server.renderToStaticMarkup` live
  in `reagent2.dom.parity-cljs-test` per IMPL-SPEC §8.7 + §12.5 R-004.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.core :as r]
            [reagent2.dom.server :as server]
            ["react" :as react]))

;; ---------------------------------------------------------------------------
;; Text content escaping
;; ---------------------------------------------------------------------------

(deftest text-content-escapes-special-chars
  (testing "ampersand, lt, gt are escaped in text content"
    (is (= "<div>a &amp; b</div>"
           (server/render-to-static-markup [:div "a & b"])))
    (is (= "<div>&lt;script&gt;</div>"
           (server/render-to-static-markup [:div "<script>"])))
    (is (= "<div>1 &lt; 2 &amp;&amp; 3 &gt; 0</div>"
           (server/render-to-static-markup [:div "1 < 2 && 3 > 0"])))))

(deftest text-content-quotes-and-apostrophe-escaped
  (testing "rf2-4dlxga: quotes (\" -> &quot;) and apostrophes (' -> &#39;)
            ARE escaped in text content — byte-equal to
            re-frame.ssr.html-helpers/escape-html's full 5-char set"
    (is (= "<div>say &quot;hi&quot;</div>"
           (server/render-to-static-markup [:div "say \"hi\""])))
    (is (= "<div>it&#39;s</div>"
           (server/render-to-static-markup [:div "it's"])))
    (is (= "<div>say &quot;hi&quot; &amp; &#39;bye&#39;</div>"
           (server/render-to-static-markup [:div "say \"hi\" & 'bye'"])))))

(deftest text-content-numbers-and-keywords
  (testing "numeric children stringify"
    (is (= "<div>42</div>"
           (server/render-to-static-markup [:div 42])))
    (is (= "<div>3.14</div>"
           (server/render-to-static-markup [:div 3.14])))))

(deftest text-content-nil-and-boolean-dropped
  (testing "nil and booleans render as empty (matches React)"
    (is (= "<div></div>"
           (server/render-to-static-markup [:div nil])))
    (is (= "<div></div>"
           (server/render-to-static-markup [:div true])))
    (is (= "<div></div>"
           (server/render-to-static-markup [:div false])))
    (is (= "<div>a</div>"
           (server/render-to-static-markup [:div nil "a" nil])))))

;; ---------------------------------------------------------------------------
;; Attribute serialisation
;; ---------------------------------------------------------------------------

(deftest attr-string-value
  (testing "string attribute value is escaped"
    (is (= "<div id=\"main\"></div>"
           (server/render-to-static-markup [:div {:id "main"}])))
    (is (= "<div title=\"a &quot;quote&quot;\"></div>"
           (server/render-to-static-markup [:div {:title "a \"quote\""}])))
    (is (= "<div title=\"&amp;\"></div>"
           (server/render-to-static-markup [:div {:title "&"}])))))

(deftest attr-keyword-value-stringified
  (testing "keyword value stringifies (per S3-005: every prop name here is an HTML attr)"
    (is (= "<div role=\"button\"></div>"
           (server/render-to-static-markup [:div {:role :button}])))))

(deftest attr-class-aliases
  (testing ":class and :className both emit `class`"
    (is (= "<div class=\"foo\"></div>"
           (server/render-to-static-markup [:div {:class "foo"}])))
    (is (= "<div class=\"foo\"></div>"
           (server/render-to-static-markup [:div {:className "foo"}])))))

(deftest attr-for-aliases
  (testing ":for and :htmlFor both emit `for`"
    (is (= "<label for=\"x\"></label>"
           (server/render-to-static-markup [:label {:for "x"}])))
    (is (= "<label for=\"x\"></label>"
           (server/render-to-static-markup [:label {:htmlFor "x"}])))))

(deftest attr-class-collection-joins
  (testing "class as a collection joins with spaces"
    (is (= "<div class=\"a b c\"></div>"
           (server/render-to-static-markup [:div {:class ["a" "b" "c"]}])))
    (is (= "<div class=\"a b\"></div>"
           (server/render-to-static-markup [:div {:class [:a :b]}])))))

(deftest attr-nil-and-false-omitted
  (testing "nil and false attribute values are omitted"
    (is (= "<div></div>"
           (server/render-to-static-markup [:div {:title nil}])))
    (is (= "<div></div>"
           (server/render-to-static-markup [:div {:disabled false}])))
    (is (= "<div></div>"
           (server/render-to-static-markup [:div {:hidden nil :title nil}])))))

(deftest attr-data-aria
  (testing "data-* and aria-* pass through verbatim"
    (is (= "<div data-id=\"7\"></div>"
           (server/render-to-static-markup [:div {:data-id "7"}])))
    (is (= "<div aria-label=\"close\"></div>"
           (server/render-to-static-markup [:div {:aria-label "close"}])))))

(deftest attr-camelcase-react-canonical-name
  (testing "camelCased prop names emit React 19's canonical output name"
    ;; rf2-ygknv finding 3: `attribute-name` is no longer a blanket lowercase.
    ;; React's attribute-name table governs the output:
    ;;   :tab-index → "tabindex"  (plain HTML camelCase → lowercased)
    ;;   :col-span  → "colSpan"   (React PRESERVES this camelCase token)
    ;; The previous "<td colspan>" assertion pinned a divergence from
    ;; react-dom/server; the corrected expectation matches React exactly.
    (is (= "<div tabindex=\"0\"></div>"
           (server/render-to-static-markup [:div {:tab-index "0"}])))
    (is (= "<td colSpan=\"2\"></td>"
           (server/render-to-static-markup [:td {:col-span "2"}])))))

;; ---------------------------------------------------------------------------
;; Boolean attributes
;; ---------------------------------------------------------------------------

(deftest boolean-attr-truthy
  (testing "true → emit name without value"
    (is (= "<input disabled>"
           (server/render-to-static-markup [:input {:disabled true}])))
    (is (= "<input checked>"
           (server/render-to-static-markup [:input {:checked true}])))
    (is (= "<input readonly>"
           (server/render-to-static-markup [:input {:read-only true}])))))

(deftest boolean-attr-falsy-omitted
  (testing "false → omit attribute entirely"
    (is (= "<input>"
           (server/render-to-static-markup [:input {:disabled false}])))
    (is (= "<input>"
           (server/render-to-static-markup [:input {:checked false}])))))

;; ---------------------------------------------------------------------------
;; Void tags
;; ---------------------------------------------------------------------------

(deftest void-tag-no-closing
  (testing "HTML5 void elements emit no closing tag"
    (is (= "<br>"     (server/render-to-static-markup [:br])))
    (is (= "<hr>"     (server/render-to-static-markup [:hr])))
    (is (= "<input>"  (server/render-to-static-markup [:input])))
    (is (= "<img src=\"x\">"
           (server/render-to-static-markup [:img {:src "x"}])))
    (is (= "<meta charset=\"utf-8\">"
           (server/render-to-static-markup [:meta {:charset "utf-8"}])))))

(deftest void-tag-children-dropped
  (testing "children passed to void elements are dropped"
    ;; React would warn here at runtime; we just emit the void tag.
    (is (= "<br>"
           (server/render-to-static-markup [:br "ignored"])))))

;; ---------------------------------------------------------------------------
;; Fragments
;; ---------------------------------------------------------------------------

(deftest fragment-emits-children-only
  (testing ":<> emits children with no surrounding markup"
    (is (= "<a></a><b></b>"
           (server/render-to-static-markup [:<> [:a] [:b]])))
    (is (= "ab"
           (server/render-to-static-markup [:<> "a" "b"])))
    (is (= ""
           (server/render-to-static-markup [:<>])))))

(deftest fragment-with-key-prop
  (testing ":<> with key map ignores props (key is React-internal)"
    (is (= "<a></a>"
           (server/render-to-static-markup [:<> {:key "k"} [:a]])))))

(deftest nested-fragments
  (testing "fragments nest cleanly"
    (is (= "<a></a><b></b><c></c>"
           (server/render-to-static-markup
            [:<> [:a] [:<> [:b] [:c]]])))))

;; ---------------------------------------------------------------------------
;; Nested hiccup
;; ---------------------------------------------------------------------------

(deftest nested-elements
  (testing "nested elements compose"
    (is (= "<ul><li>a</li><li>b</li></ul>"
           (server/render-to-static-markup
            [:ul [:li "a"] [:li "b"]])))))

(deftest nested-with-attrs
  (testing "nested elements carry their own attrs"
    (is (= "<div class=\"outer\"><span class=\"inner\">x</span></div>"
           (server/render-to-static-markup
            [:div {:class "outer"} [:span {:class "inner"} "x"]])))))

;; ---------------------------------------------------------------------------
;; Sequences as children
;; ---------------------------------------------------------------------------

(deftest seq-children-flatten
  (testing "seq of hiccup forms (e.g. (map ...)) flatten as children"
    (is (= "<ul><li>a</li><li>b</li><li>c</li></ul>"
           (server/render-to-static-markup
            [:ul (map (fn [x] [:li x]) ["a" "b" "c"])])))))

(deftest seq-children-with-key-meta
  (testing ":key meta on sequence children is React-internal; not in HTML"
    (is (= "<ul><li>a</li><li>b</li></ul>"
           (server/render-to-static-markup
            [:ul (map-indexed (fn [i x]
                                ^{:key i} [:li x])
                              ["a" "b"])])))))

(deftest seq-children-with-key-prop
  (testing ":key in props map is React-internal; never in HTML"
    (is (= "<ul><li>a</li></ul>"
           (server/render-to-static-markup
            [:ul [:li {:key 1} "a"]])))))

(deftest mixed-children
  (testing "string + number + vector children all render"
    (is (= "<div>hello 42<span>x</span></div>"
           (server/render-to-static-markup
            [:div "hello " 42 [:span "x"]])))))

;; ---------------------------------------------------------------------------
;; Tag shorthand
;; ---------------------------------------------------------------------------

(deftest tag-shorthand-class
  (testing ":div.foo emits class=\"foo\""
    (is (= "<div class=\"foo\"></div>"
           (server/render-to-static-markup [:div.foo])))))

(deftest tag-shorthand-id
  (testing ":div#bar emits id=\"bar\""
    (is (= "<div id=\"bar\"></div>"
           (server/render-to-static-markup [:div#bar])))))

(deftest tag-shorthand-class-and-id
  (testing ":div#bar.foo emits both (stock Reagent regex requires #id before .cls)"
    (let [out (server/render-to-static-markup [:div#bar.foo])]
      (is (or (= out "<div id=\"bar\" class=\"foo\"></div>")
              (= out "<div class=\"foo\" id=\"bar\"></div>"))))))

(deftest tag-shorthand-merge-with-user-class
  (testing "shorthand class is prepended to user class"
    (is (= "<div class=\"foo bar\"></div>"
           (server/render-to-static-markup [:div.foo {:class "bar"}])))))

(deftest tag-shorthand-user-id-wins
  (testing "user :id wins over shorthand id"
    (is (= "<div id=\"user\"></div>"
           (server/render-to-static-markup [:div#shorthand {:id "user"}])))))

;; ---------------------------------------------------------------------------
;; dangerouslySetInnerHTML
;; ---------------------------------------------------------------------------

(deftest dangerously-set-inner-html
  (testing ":dangerouslySetInnerHTML emits raw __html, no escaping"
    (is (= "<div><b>raw</b></div>"
           (server/render-to-static-markup
            [:div {:dangerouslySetInnerHTML {:__html "<b>raw</b>"}}])))
    (is (= "<div>&amp;</div>"
           (server/render-to-static-markup
            [:div {:dangerouslySetInnerHTML {:__html "&amp;"}}])))))

;; ---------------------------------------------------------------------------
;; Style attribute
;; ---------------------------------------------------------------------------

(deftest style-map-serialises
  (testing ":style map → CSS string"
    (let [out (server/render-to-static-markup
               [:div {:style {:color "red"}}])]
      (is (= "<div style=\"color:red\"></div>" out)))
    (let [out (server/render-to-static-markup
               [:div {:style {:cursor :pointer}}])]
      (is (= "<div style=\"cursor:pointer\"></div>" out)))))

;; ---------------------------------------------------------------------------
;; React-component heads (opaque under static markup)
;; ---------------------------------------------------------------------------

(deftest react-component-head-comment-placeholder
  (testing ":>, :r>, :f> emit a placeholder comment (opaque under static markup)"
    (let [Foo (fn [_] [:div "x"])]
      (is (= "<!--reagent-react-component-->"
             (server/render-to-static-markup [:> Foo {}])))
      (is (= "<!--reagent-react-component-->"
             (server/render-to-static-markup [:f> Foo])))
      (is (= "<!--reagent-react-component-->"
             (server/render-to-static-markup [:r> Foo #js {}]))))))

;; ---------------------------------------------------------------------------
;; React context Providers (rf2-iyz6j)
;;
;; A context Provider is NOT opaque foreign content: it renders nothing of
;; its own and its output IS its children. Before rf2-iyz6j the walker
;; lumped it in with the foreign-component placeholder above, so the
;; canonical slim mount `[rf/frame-provider {:frame f} [app]]` — which
;; expands to `[:r> (.-Provider frame-context) #js {:value f} …]` — emitted
;; `<!--reagent-react-component-->` and NOTHING ELSE. An empty document, no
;; error.
;;
;; These pin the walker itself. `server-subscribe-ssr-cljs-test` pins the
;; end-to-end canonical mount through `re-frame.core/frame-provider`.
;;
;; The contexts below are built with the REAL `react/createContext` rather
;; than a hand-rolled `$$typeof` literal. That is deliberate: the detection
;; is React-VERSION-dependent (on React 19 `ctx.Provider` IS `ctx`, tagged
;; `Symbol.for("react.context")`; on React <=18 `ctx.Provider` is a distinct
;; object tagged `Symbol.for("react.provider")`), so asking React for the
;; object is what makes a future symbol change fail LOUDLY here instead of
;; silently reverting to the dropped-subtree behaviour.
;; ---------------------------------------------------------------------------

(deftest context-provider-head-renders-children
  (testing "rf2-iyz6j: a context Provider scopes rather than renders, so the
            static walker walks THROUGH it — children reach the markup"
    (let [ctx      (react/createContext :rf/none)
          Provider (.-Provider ctx)]
      ;; `:r>` — the head the canonical frame-provider uses. Children start
      ;; at index 3 (the props slot at 2 is unconditional for `:r>`).
      (is (= "<div>hello</div>"
             (server/render-to-static-markup
              [:r> Provider #js {:value :frame/a} [:div "hello"]]))
          "the Provider's subtree is present, not a placeholder comment")
      ;; Multiple children, in order.
      (is (= "<p>a</p><p>b</p>"
             (server/render-to-static-markup
              [:r> Provider #js {:value :frame/a} [:p "a"] [:p "b"]])))
      ;; `:>` — the hiccup-props interop head, same Provider.
      (is (= "<div>hello</div>"
             (server/render-to-static-markup
              [:> Provider {:value :frame/a} [:div "hello"]])))
      ;; `:>` with the props slot OMITTED: `props-slot?` treats a non-map
      ;; first slot as the first CHILD, so children start at index 2.
      (is (= "<div>hello</div>"
             (server/render-to-static-markup
              [:> Provider [:div "hello"]])))
      ;; A childless Provider is legitimately empty — not a placeholder.
      (is (= "" (server/render-to-static-markup
                 [:r> Provider #js {:value :frame/a}]))))))

(deftest context-provider-head-nests-and-escapes
  (testing "rf2-iyz6j: nested Providers compose, and content under a
            Provider is escaped exactly as it is anywhere else"
    (let [outer (.-Provider (react/createContext :rf/none))
          inner (.-Provider (react/createContext :rf/none))]
      (is (= "<section><div>a &amp; b</div></section>"
             (server/render-to-static-markup
              [:r> outer #js {:value :frame/a}
               [:section
                [:r> inner #js {:value :frame/b}
                 [:div "a & b"]]]]))))))

(deftest context-consumer-head-stays-opaque
  (testing "rf2-iyz6j negative control: a context CONSUMER takes a RENDER FN
            as its child, not elements, so it must NOT be walked — it stays
            opaque like any other foreign component"
    (let [ctx (react/createContext :rf/none)]
      (is (= "<!--reagent-react-component-->"
             (server/render-to-static-markup
              [:r> (.-Consumer ctx) #js {} (fn [_v] [:div "nope"])]))))))

(deftest non-provider-react-component-still-opaque
  (testing "rf2-iyz6j does not widen the walker: a genuine foreign React
            component head is still opaque even when it carries children"
    (let [Foo (fn [_] [:div "x"])]
      (is (= "<!--reagent-react-component-->"
             (server/render-to-static-markup [:> Foo {} [:div "dropped"]]))))))

;; ---------------------------------------------------------------------------
;; User-fn heads (function-call path, matches stock Reagent)
;; ---------------------------------------------------------------------------

(deftest user-fn-head-invoked
  (testing "plain user-fn head is called and result recurses"
    (let [item (fn [x] [:li x])]
      (is (= "<ul><li>a</li><li>b</li></ul>"
             (server/render-to-static-markup
              [:ul [item "a"] [item "b"]]))))))

(deftest user-fn-head-passes-args
  (testing "user-fn receives all args from the hiccup vector"
    (let [greet (fn [name punct] [:span name punct])]
      (is (= "<span>Mike!</span>"
             (server/render-to-static-markup [greet "Mike" "!"]))))))

;; ---------------------------------------------------------------------------
;; Form-2 user-fn heads (rf2-o3hqr)
;;
;; A Form-2 component's outer fn is a one-shot setup that returns the
;; inner render closure: `(fn [x] (fn [x] [:li x]))`. The static path
;; previously invoked the head once and recursed on the returned inner
;; FN, which reached `emit-element` as a bare fn and threw
;; `:rf.error/static-markup-bad-element`. The fix mirrors the live
;; `wrap-render` Form-1/Form-2 detection: when the head returns a fn,
;; recall it with the same args and recurse on its hiccup.
;; ---------------------------------------------------------------------------

(deftest form-2-user-fn-head-renders
  (testing "rf2-o3hqr: a Form-2 head (outer setup fn returning an inner
            render closure) renders its inner hiccup, not a thrown bad-element"
    (let [item (fn [_x] (fn [x] [:li x]))]
      (is (= "<ul><li>a</li><li>b</li></ul>"
             (server/render-to-static-markup
              [:ul [item "a"] [item "b"]]))))))

(deftest form-2-inner-closure-receives-same-args
  (testing "rf2-o3hqr: the Form-2 inner closure is recalled with the SAME
            args as the outer setup (matches wrap-render's `(apply inner args)`)"
    ;; The outer fn ignores its args; the inner fn consumes them. If the
    ;; fix passed no args (or wrong args) to the inner closure the span
    ;; would render empty / throw on arity.
    (let [greet (fn [_n _p] (fn [n p] [:span n p]))]
      (is (= "<span>Mike!</span>"
             (server/render-to-static-markup [greet "Mike" "!"]))))))

(deftest form-2-closes-over-setup-state
  (testing "rf2-o3hqr: the Form-2 inner closure can close over a value
            computed in the outer setup (the canonical Form-2 reason)"
    (let [labelled (fn [prefix]
                     (fn [_prefix v]
                       [:div (str prefix ": " v)]))]
      (is (= "<div>n: 7</div>"
             (server/render-to-static-markup [labelled "n" 7]))))))

(deftest form-2-nested-in-form-1
  (testing "rf2-o3hqr: a Form-2 head nested inside a Form-1 head renders"
    (let [inner (fn [_x] (fn [x] [:em x]))
          outer (fn [x] [:p [inner x]])]
      (is (= "<p><em>hi</em></p>"
             (server/render-to-static-markup [outer "hi"]))))))

;; ---------------------------------------------------------------------------
;; Form-3 class heads (rf2-o3hqr)
;;
;; A `create-class` head is a React class carrying its user
;; `:reagent-render` fn under `.-cljsReagentRender`. The static path
;; detects the reagent-class via `reagent-class?` (BEFORE the plain-fn
;; branch — calling the class directly would invoke its constructor, not
;; render) and renders the `:reagent-render` fn through the same
;; Form-1/Form-2 path. Lifecycle keys have no static-HTML meaning.
;; ---------------------------------------------------------------------------

(deftest form-3-reagent-class-renders
  (testing "rf2-o3hqr: a create-class (Form-3) head renders its
            :reagent-render fn to HTML"
    (let [box (r/create-class
                {:display-name "box"
                 :reagent-render (fn [x] [:div.box x])})]
      (is (= "<div class=\"box\">hello</div>"
             (server/render-to-static-markup [box "hello"]))))))

(deftest form-3-reagent-render-is-form-2
  (testing "rf2-o3hqr: a create-class whose :reagent-render is itself
            Form-2 (returns an inner closure) renders the inner hiccup"
    (let [box (r/create-class
                {:display-name "box2"
                 :reagent-render (fn [_x] (fn [x] [:section x]))})]
      (is (= "<section>x</section>"
             (server/render-to-static-markup [box "x"]))))))

(deftest form-3-lifecycle-keys-ignored-in-static-markup
  (testing "rf2-o3hqr: Form-3 lifecycle callbacks do not fire under
            static markup (matches react-dom/server); only :reagent-render
            contributes to the HTML"
    (let [fired (atom false)
          box (r/create-class
                {:display-name "lifecycle-box"
                 :component-did-mount (fn [_this] (reset! fired true))
                 :reagent-render (fn [x] [:span x])})]
      (is (= "<span>z</span>"
             (server/render-to-static-markup [box "z"])))
      (is (false? @fired)
          ":component-did-mount must NOT fire during static markup rendering"))))

;; ---------------------------------------------------------------------------
;; Edge cases — empty / malformed
;; ---------------------------------------------------------------------------

(deftest top-level-nil-is-empty
  (testing "render-to-static-markup of nil → empty string"
    (is (= "" (server/render-to-static-markup nil)))))

(deftest top-level-string
  (testing "render-to-static-markup of a bare string → escaped string"
    (is (= "hello" (server/render-to-static-markup "hello")))
    (is (= "&lt;b&gt;" (server/render-to-static-markup "<b>")))))

(deftest empty-vector-throws
  (testing "empty hiccup vector throws ex-info"
    (is (thrown-with-msg? js/Error #":rf.error/static-markup-empty-vector"
          (server/render-to-static-markup [])))))

(deftest unknown-head-throws
  (testing "non-keyword/symbol/fn head throws ex-info"
    (is (thrown-with-msg? js/Error #":rf.error/static-markup-bad-tag"
          (server/render-to-static-markup [42 "x"])))))

;; ---------------------------------------------------------------------------
;; rf2-dwds9 HIGH: XSS surface — event handlers + fn props stripped
;;
;; The static-markup serializer must NOT emit React event-handler props
;; (`onClick`, `:on-click`, …) as HTML attributes. Doing so would (a)
;; serve no purpose (HTML inline-event handlers aren't bound to the
;; React handler), and (b) open an XSS vector: a string-valued
;; `:on-click "alert(1)"` would render as `onclick="alert(1)"`. Same
;; for function-valued props of any name — `(str f)` would leak the
;; source text into the attribute.
;;
;; `react-dom/server.renderToStaticMarkup` elides these; the rewrite
;; now matches.
;; ---------------------------------------------------------------------------

(deftest event-handler-string-stripped-rf2-dwds9
  (testing "rf2-dwds9: :on-click with string value does NOT emit
            onclick attribute (XSS vector closed)"
    (is (= "<div></div>"
           (server/render-to-static-markup [:div {:on-click "alert(1)"}])))
    (is (= "<div></div>"
           (server/render-to-static-markup [:div {:onClick "alert(1)"}])))
    (is (= "<button>x</button>"
           (server/render-to-static-markup
            [:button {:on-click "javascript:evil()"} "x"]))
        "no leaked onclick attribute on the rendered button")))

(deftest event-handler-fn-stripped-rf2-dwds9
  (testing "rf2-dwds9: fn-valued :on-click is stripped (does not
            emit `function () { ... }` source as the attribute value)"
    (let [handler (fn [_e])
          out (server/render-to-static-markup
               [:div {:on-click handler}])]
      (is (= "<div></div>" out)
          "no onclick attribute, no leaked source"))))

(deftest fn-valued-non-event-prop-stripped-rf2-dwds9
  (testing "rf2-dwds9: any fn-valued prop (not just `on*`) is stripped
            so source text never leaks into the attribute"
    (let [callback (fn [])
          out (server/render-to-static-markup
               [:div {:custom-callback callback}])]
      (is (= "<div></div>" out)))))

(deftest other-on-prefix-attrs-stripped-rf2-dwds9
  (testing "rf2-dwds9: camelCase `onChange`, `onSubmit`, `onMouseEnter`
            all stripped (full event-handler family)"
    (is (= "<form></form>"
           (server/render-to-static-markup
            [:form {:onSubmit "evil()" :onChange "evil2()"}])))
    (is (= "<div></div>"
           (server/render-to-static-markup
            [:div {:onMouseEnter "evil()"}])))))

(deftest on-not-event-prefix-passes-through
  (testing "rf2-dwds9: attribute names starting with `on` but NOT
            event-handler shape (e.g. `:once`) are NOT stripped —
            event-handler-prop? requires `on-x` (kebab) or `onX` (camel
            with uppercase letter after `on`)"
    (is (= "<div once=\"true\"></div>"
           (server/render-to-static-markup [:div {:once "true"}]))
        "`:once` (no `-` after `on`, no capital after `on`) is preserved")
    (is (= "<div onyx=\"x\"></div>"
           (server/render-to-static-markup [:div {:onyx "x"}]))
        "`:onyx` (lowercase letter after `on`) is preserved")))

;; ---------------------------------------------------------------------------
;; rf2-ut3mod: lowercase inline HTML event attributes must strip too
;;
;; `event-handler-prop?`'s structural check (`on-` kebab / `on[A-Z]`
;; camel) misses lowercase inline HTML event attributes — `:onclick`,
;; string `"onclick"`, `:onchange` — because there is no `-` and no
;; upper-case letter after `on`. Those are exactly the canonical names a
;; browser fires on, so a string-valued `:onclick "alert(1)"` rode
;; through to the wire as `onclick="alert(1)"`, an XSS vector of the
;; same class rf2-dwds9 closed for the structural (kebab/camel) forms.
;; ---------------------------------------------------------------------------

(deftest lowercase-onclick-keyword-stripped-rf2-ut3mod
  (testing "rf2-ut3mod: :onclick (all-lowercase keyword) with string
            value does NOT emit an onclick attribute (XSS vector closed)"
    (is (= "<div></div>"
           (server/render-to-static-markup [:div {:onclick "alert(1)"}])))
    (is (= "<button>x</button>"
           (server/render-to-static-markup
            [:button {:onclick "javascript:evil()"} "x"]))
        "no leaked onclick attribute on the rendered button")))

(deftest lowercase-onclick-string-key-stripped-rf2-ut3mod
  (testing "rf2-ut3mod: string key \"onclick\" with string value does
            NOT emit an onclick attribute"
    (is (= "<div></div>"
           (server/render-to-static-markup [:div {"onclick" "alert(1)"}])))))

(deftest lowercase-onchange-stripped-rf2-ut3mod
  (testing "rf2-ut3mod: :onchange (all-lowercase keyword) is stripped —
            another canonical lowercase event name, not just :onclick"
    (is (= "<input>"
           (server/render-to-static-markup [:input {:onchange "evil()"}])))))

(deftest lowercase-non-event-on-prefix-still-passes-through-rf2-ut3mod
  (testing "rf2-ut3mod: the new lowercase-event allowlist must not
            regress :once / :onyx — non-events keep passing through"
    (is (= "<div once=\"true\"></div>"
           (server/render-to-static-markup [:div {:once "true"}])))
    (is (= "<div onyx=\"x\"></div>"
           (server/render-to-static-markup [:div {:onyx "x"}])))))

(deftest key-and-ref-still-stripped
  (testing "regression: :key and :ref drops still work after the new
            event-prop filter was added"
    (is (= "<div></div>"
           (server/render-to-static-markup [:div {:key "k" :ref "r"}])))))

;; ---------------------------------------------------------------------------
;; rf2-3x7nj.6.1: attacker-controlled attribute and tag NAMES
;;
;; The threat model is the one re-frame.ssr already accepts: an app splats
;; an attacker-controlled attribute map into hiccup (a CMS or JSON payload
;; read with keywordised keys), or builds a string head from data. This
;; serializer emitted attribute and tag names verbatim, so a key carrying
;; `=`, whitespace or a quote broke out of the attribute and installed a
;; live inline handler — on the ordinary value path AND on the boolean
;; path, which classifies any `data-*` / `aria-*` name as stringifying.
;;
;; RULED: THROW, refusing exactly the names react-dom 19.3.0's own
;; predicates refuse (its attribute-name regex and its tag regex, not
;; re-frame.ssr's narrower grammar), with the two ids re-frame.ssr already
;; throws. `oncommand` joins the silent-drop event-handler allowlist.
;; ---------------------------------------------------------------------------

(defn- render-outcome
  "Render `hiccup`, returning `{:html s}` on success or `{:error data
  :message m}` when it throws, so a regression can assert BOTH that no
  markup escaped and which catalogued error was thrown."
  [hiccup]
  (try
    {:html (server/render-to-static-markup hiccup)}
    (catch :default e
      {:error (ex-data e) :message (ex-message e)})))

(def ^:private breakout-name
  "An attribute name that closes itself and opens a live `onclick`."
  "onclick=alert(1) x")

(deftest hostile-attribute-name-throws-rf2-3x7nj-6-1
  (testing "ordinary value path: a keyword key and a string key carrying an
            attribute breakout both throw, and no markup is produced"
    (doseq [k [(keyword breakout-name) breakout-name]]
      (let [{:keys [html error message]} (render-outcome [:div {k "y"} "hi"])]
        (is (nil? html)
            (str "no markup may be produced for key " (pr-str k)
                 "; got " (pr-str html)))
        (is (= :rf.error/ssr-invalid-attribute-name (:rf.error/id error)))
        (is (= k (:attribute error)) "the payload names the offending key")
        (is (= :rename-the-attribute-key (:recovery error)))
        (is (= 'reagent2.dom.server/render-to-static-markup (:where error)))
        (is (re-find #"\[:rf\.error/ssr-invalid-attribute-name\]" (str message)))))))

(deftest hostile-boolean-attribute-name-throws-rf2-3x7nj-6-1
  (testing "boolean value path: a data-*/aria-* prefixed name is classified
            stringifying and was appended raw — for true AND false"
    (doseq [[k v] [["data-x onclick=alert(1) x" true]
                   ["aria-x onclick=alert(1) x" false]]]
      (let [{:keys [html error]} (render-outcome [:div {k v} "hi"])]
        (is (nil? html)
            (str "no markup may be produced for " (pr-str k) " " v
                 "; got " (pr-str html)))
        (is (= :rf.error/ssr-invalid-attribute-name (:rf.error/id error)))
        (is (= k (:attribute error)))))))

(deftest oncommand-is-dropped-rf2-3x7nj-6-1
  (testing "oncommand is an event-handler attribute (Chromium fires it on a
            `command` event), so every casing drops silently, like onclick"
    (doseq [k [:oncommand "ONCOMMAND" :OnCommand]]
      (is (= "<div></div>"
             (server/render-to-static-markup [:div {k "alert(1)"}]))
          (str (pr-str k) " must not reach the markup")))))

(deftest hostile-tag-name-throws-rf2-3x7nj-6-1
  (testing "a string head built from data cannot smuggle attributes into
            the start tag"
    (let [head "img/src=\"x\"/onerror=alert(1)"
          {:keys [html error message]} (render-outcome [head])]
      (is (nil? html) (str "no markup may be produced; got " (pr-str html)))
      (is (= :rf.error/invalid-tag-name (:rf.error/id error)))
      (is (= head (:tag-name error)))
      (is (= head (:source error)))
      (is (= :use-a-valid-element-name (:recovery error)))
      (is (re-find #"\[:rf\.error/invalid-tag-name\]" (str message)))))
  (testing "class-before-id shorthand parses to a NIL tag, which rendered as
            the literal element `<null>`; it now throws the same id"
    (let [{:keys [html error]} (render-outcome [:div.a#id "x"])]
      (is (nil? html) (str "no markup may be produced; got " (pr-str html)))
      (is (= :rf.error/invalid-tag-name (:rf.error/id error)))
      (is (nil? (:tag-name error)))
      (is (= :div.a#id (:source error))))))

(deftest valid-names-still-serialise-rf2-3x7nj-6-1
  (testing "controls: ordinary names render exactly as before"
    (is (= "<div data-x=\"1\"></div>"
           (server/render-to-static-markup [:div {:data-x "1"}])))
    (is (= "<div aria-label=\"close\"></div>"
           (server/render-to-static-markup [:div {:aria-label "close"}])))
    (is (= "<svg viewBox=\"0 0 1 1\"></svg>"
           (server/render-to-static-markup [:svg {:viewBox "0 0 1 1"}])))
    (is (= "<svg xmlns:xlink=\"http://www.w3.org/1999/xlink\"></svg>"
           (server/render-to-static-markup
            [:svg {(keyword "xmlns:xlink") "http://www.w3.org/1999/xlink"}])))
    (is (= "<div online=\"x\"></div>"
           (server/render-to-static-markup [:div {:online "x"}])))
    (is (= "<div data-flag=\"true\"></div>"
           (server/render-to-static-markup [:div {:data-flag true}]))
        "the boolean path still emits a valid prefixed name"))
  (testing "react-dom accepts `x.y` and `_foo`; re-frame.ssr's narrower
            grammar would refuse both. They PIN the ruled grammar: a gate
            copying the SSR grammar reds here"
    (is (= "<div x.y=\"1\"></div>"
           (server/render-to-static-markup [:div {(keyword "x.y") "1"}])))
    (is (= "<div _foo=\"1\"></div>"
           (server/render-to-static-markup [:div {:_foo "1"}]))))
  (testing "the same payload under a VALID name still serialises, so the
            throw is about the name and never the value"
    (is (= "<div title=\"alert(1)\"></div>"
           (server/render-to-static-markup [:div {:title "alert(1)"}]))))
  (testing "tag names react-dom accepts still render, including `a_b`,
            which re-frame.ssr's tag grammar would refuse"
    (is (= "<my-element></my-element>"
           (server/render-to-static-markup [:my-element])))
    (is (= "<font-face></font-face>"
           (server/render-to-static-markup [:font-face])))
    (is (= "<a_b></a_b>"
           (server/render-to-static-markup [:a_b])))))

;; ---------------------------------------------------------------------------
;; rf2-3x7nj.6.2: element-context text rules
;;
;; `<script>` / `<style>` bodies are HTML RAW TEXT: the parser never decodes
;; character references inside them, so entity-escaping their text corrupts
;; the CSS / JS (`'Open Sans'` became `&#39;Open Sans&#39;`, `a && b` became
;; `a &amp;&amp; b`). react-dom emits the text verbatim and rewrites only an
;; embedded closing-tag sequence. Separately, the parser eats one LF after
;; `<pre>` / `<listing>` / `<textarea>`, and react-dom prefixes one
;; compensating LF when the sole string body starts with one.
;; ---------------------------------------------------------------------------

(deftest raw-text-elements-emit-verbatim-rf2-3x7nj-6-2
  (testing "a <style> body is CSS, not HTML — quotes and `>` survive"
    (is (= "<style>body { font-family: 'Open Sans' } td > p { margin: 0 }</style>"
           (server/render-to-static-markup
            [:style "body { font-family: 'Open Sans' } td > p { margin: 0 }"]))))
  (testing "a <script> body is JS — `<` and `&&` survive"
    (is (= "<script>if (a < b && c) go()</script>"
           (server/render-to-static-markup [:script "if (a < b && c) go()"]))))
  (testing "an embedded closing sequence is still neutralised, in any case,
            by a language-level escape of its `s`"
    (is (= "<script>x = '</\\u0073cript><b>'</script>"
           (server/render-to-static-markup [:script "x = '</script><b>'"])))
    (is (= "<script>a <\\u0053CRIPT b</script>"
           (server/render-to-static-markup [:script "a <SCRIPT b"])))
    (is (= "<style>a{}</\\73 tyle><b></style>"
           (server/render-to-static-markup [:style "a{}</style><b>"])))
    (is (= "<style></\\53 TYLE></style>"
           (server/render-to-static-markup [:style "</STYLE>"]))))
  (testing "controls: RCDATA <title> and ordinary elements still escape"
    (is (= "<title>a &lt; b</title>"
           (server/render-to-static-markup [:title "a < b"])))
    (is (= "<div>td &gt; p &#39;x&#39;</div>"
           (server/render-to-static-markup [:div "td > p 'x'"])))))

(deftest leading-newline-compensation-rf2-3x7nj-6-2
  (testing "pre / listing / textarea: a sole string body starting with LF
            gets one compensating LF, so the authored LF survives parsing"
    (is (= "<pre>\n\n  indented</pre>"
           (server/render-to-static-markup [:pre "\n  indented"])))
    (is (= "<listing>\n\nx</listing>"
           (server/render-to-static-markup [:listing "\nx"])))
    (is (= "<textarea>\n\nx</textarea>"
           (server/render-to-static-markup [:textarea "\nx"]))))
  (testing "controls: no leading LF, a multi-child body, and an ordinary
            element are all untouched"
    (is (= "<pre>x</pre>"
           (server/render-to-static-markup [:pre "x"])))
    (is (= "<pre>\n<b>x</b></pre>"
           (server/render-to-static-markup [:pre "\n" [:b "x"]])))
    (is (= "<div>\nx</div>"
           (server/render-to-static-markup [:div "\nx"])))))
