(ns reagent2.dom.server-cljs-test
  "Unit tests for `reagent2.dom.server/render-to-static-markup`. Byte parity
  with react-dom/server is pinned in `reagent2.dom.parity-cljs-test`, and the
  boolean-attribute roster in `reagent2.dom.boolean-attr-react-parity-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.core :as r]
            [reagent2.dom.server :as server]
            ["react" :as react]))

(deftest static-markup-of-each-hiccup-shape
  (let [item  (fn [x] [:li x])
        greet (fn [n p] [:span n p])]
    (doseq [[hiccup expected why]
            [[[:div nil] "<div></div>" "a nil child renders nothing"]
             [[:div false] "<div></div>" "a boolean child renders nothing"]
             [[:div nil "a" nil] "<div>a</div>" nil]
             [nil "" "top-level nil"]
             ["<b>" "&lt;b&gt;" "a top-level string is escaped"]
             [[:div {:id "main"}] "<div id=\"main\"></div>" nil]
             [[:div {:title "a \"quote\""}] "<div title=\"a &quot;quote&quot;\"></div>" "attribute values escape"]
             [[:div {:title "&"}] "<div title=\"&amp;\"></div>" nil]
             [[:div {:role :button}] "<div role=\"button\"></div>" "keyword values stringify"]
             [[:div {:class "foo"}] "<div class=\"foo\"></div>" nil]
             [[:div {:className "foo"}] "<div class=\"foo\"></div>" nil]
             [[:label {:for "x"}] "<label for=\"x\"></label>" nil]
             [[:label {:htmlFor "x"}] "<label for=\"x\"></label>" nil]
             [[:div {:class ["a" "b" "c"]}] "<div class=\"a b c\"></div>" nil]
             [[:div {:class [:a :b]}] "<div class=\"a b\"></div>" nil]
             [[:div {:title nil}] "<div></div>" nil]
             [[:div {:disabled false}] "<div></div>" nil]
             [[:div {:tab-index "0"}] "<div tabindex=\"0\"></div>" "plain camelCase lowercases"]
             [[:td {:col-span "2"}] "<td colSpan=\"2\"></td>" "React keeps colSpan's camelCase"]
             [[:br] "<br>" "a void element has no closing tag"]
             [[:img {:src "x"}] "<img src=\"x\">" nil]
             [[:br "ignored"] "<br>" "a void element drops its children"]
             [[:<> {:key "k"} [:a]] "<a></a>" "a fragment's props are React-internal"]
             [[:div.foo {:class "bar"}] "<div class=\"foo bar\"></div>" nil]
             [[:div#shorthand {:id "user"}] "<div id=\"user\"></div>" "the user's :id beats the shorthand"]
             [[:ul [item "a"] [item "b"]] "<ul><li>a</li><li>b</li></ul>" "a user-fn head is called"]
             [[greet "Mike" "!"] "<span>Mike!</span>" "a user fn receives every arg"]]]
      (is (= expected (server/render-to-static-markup hiccup)) (or why (pr-str hiccup))))))

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
;; React context Providers render their children: the canonical slim mount
;; `[rf/frame-provider {:frame f} [app]]` expands to an `:r>` Provider head, so
;; treating it as opaque would render an empty document. The contexts come from
;; the real `react/createContext`, because Provider detection depends on the
;; React version's symbols.
;; ---------------------------------------------------------------------------

(deftest context-provider-head-renders-children
  (testing "a context Provider scopes rather than renders, so the
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
  (testing "nested Providers compose, and content under a
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
  (testing "negative control: a context CONSUMER takes a RENDER FN
            as its child, not elements, so it must NOT be walked — it stays
            opaque like any other foreign component"
    (let [ctx (react/createContext :rf/none)]
      (is (= "<!--reagent-react-component-->"
             (server/render-to-static-markup
              [:r> (.-Consumer ctx) #js {} (fn [_v] [:div "nope"])]))))))

(deftest non-provider-react-component-still-opaque
  (testing "the Provider branch does not widen the walker: a genuine foreign
            React component head stays opaque even when it carries children"
    (let [Foo (fn [_] [:div "x"])]
      (is (= "<!--reagent-react-component-->"
             (server/render-to-static-markup [:> Foo {} [:div "dropped"]]))))))

;; ---------------------------------------------------------------------------
;; Form-2 user-fn heads
;;
;; A Form-2 component's outer fn is a one-shot setup that returns the
;; inner render closure: `(fn [x] (fn [x] [:li x]))`. Recursing on the
;; returned inner FN would hand `emit-element` a bare fn and throw
;; `:rf.error/static-markup-bad-element`, so the static path mirrors the
;; live `wrap-render` Form-1/Form-2 detection: when the head returns a fn,
;; it recalls it with the same args and recurses on its hiccup.
;; ---------------------------------------------------------------------------

(deftest form-2-inner-closure-receives-same-args
  (testing "the Form-2 inner closure is recalled with the SAME
            args as the outer setup (matches wrap-render's `(apply inner args)`)"
    ;; The outer fn ignores its args; the inner fn consumes them. If the
    ;; inner closure received no args (or wrong args) the span would
    ;; render empty / throw on arity.
    (let [greet (fn [_n _p] (fn [n p] [:span n p]))]
      (is (= "<span>Mike!</span>"
             (server/render-to-static-markup [greet "Mike" "!"]))))))

;; ---------------------------------------------------------------------------
;; Form-3 class heads
;;
;; A `create-class` head is a React class carrying its user
;; `:reagent-render` fn under `.-cljsReagentRender`. The static path
;; detects the reagent-class via `reagent-class?` (BEFORE the plain-fn
;; branch — calling the class directly would invoke its constructor, not
;; render) and renders the `:reagent-render` fn through the same
;; Form-1/Form-2 path. Lifecycle keys have no static-HTML meaning.
;; ---------------------------------------------------------------------------

(deftest form-3-reagent-render-is-form-2
  (testing "a create-class whose :reagent-render is itself
            Form-2 (returns an inner closure) renders the inner hiccup"
    (let [box (r/create-class
                {:display-name "box2"
                 :reagent-render (fn [_x] (fn [x] [:section x]))})]
      (is (= "<section>x</section>"
             (server/render-to-static-markup [box "x"]))))))

;; ---------------------------------------------------------------------------
;; Malformed hiccup
;; ---------------------------------------------------------------------------

(deftest empty-vector-throws
  (testing "empty hiccup vector throws ex-info"
    (is (thrown-with-msg? js/Error #":rf.error/static-markup-empty-vector"
          (server/render-to-static-markup [])))))

(deftest unknown-head-throws
  (testing "non-keyword/symbol/fn head throws ex-info"
    (is (thrown-with-msg? js/Error #":rf.error/static-markup-bad-tag"
          (server/render-to-static-markup [42 "x"])))))

(deftest unrenderable-child-throws-bad-element
  (testing "a child that is none of string / number / keyword / symbol /
            vector / seq throws the catalogued id, summarising the child
            rather than carrying it"
    (let [data (try (server/render-to-static-markup
                      [:div "ok" {:secret "app-owned-value"}])
                    nil
                    (catch :default e (ex-data e)))]
      (is (= :rf.error/static-markup-bad-element (:rf.error/id data)))
      (is (= 'reagent2.dom.server/render-to-static-markup (:where data)))
      (is (= :supply-a-renderable-child (:recovery data)))
      (is (= {:type :map :count 1} (:got/summary data)))
      (is (not (re-find #"app-owned-value" (pr-str data)))
          "the payload summarises the child, never carries its value"))))

;; ---------------------------------------------------------------------------
;; Event-handler props never reach static markup: an inline handler would be
;; dead in the browser, and a string-valued `:on-click "alert(1)"` would be an
;; XSS vector. Fn-valued props of any name drop too, so source text never leaks.
;; ---------------------------------------------------------------------------

(deftest event-handler-and-fn-props-are-stripped
  (doseq [[hiccup expected]
          [[[:div {:on-click "alert(1)"}] "<div></div>"]
           [[:div {:onClick "alert(1)"}] "<div></div>"]
           [[:div {:onclick "alert(1)"}] "<div></div>"]
           [[:input {:onchange "evil()"}] "<input>"]
           [[:button {:on-click "javascript:evil()"} "x"] "<button>x</button>"]
           [[:div {:custom-callback (fn [])}] "<div></div>"]
           [[:div {:key "k" :ref "r"}] "<div></div>"]
           ;; `on` without the event-handler shape is an ordinary attribute
           [[:div {:once "true"}] "<div once=\"true\"></div>"]
           [[:div {:onyx "x"}] "<div onyx=\"x\"></div>"]]]
    (is (= expected (server/render-to-static-markup hiccup)) (pr-str hiccup))))

;; ---------------------------------------------------------------------------
;; Attacker-controlled attribute and tag NAMES
;;
;; The threat model is the one re-frame.ssr accepts: an app splats an
;; attacker-controlled attribute map into hiccup (a CMS or JSON payload
;; read with keywordised keys), or builds a string head from data. A
;; serializer emitting attribute and tag names verbatim would let a key
;; carrying `=`, whitespace or a quote break out of the attribute and
;; install a live inline handler — on the ordinary value path AND on the
;; boolean path, which classifies any `data-*` / `aria-*` name as
;; stringifying.
;;
;; So this serializer THROWS, refusing exactly the names react-dom 19.3.0's
;; own predicates refuse (its attribute-name regex and its tag regex, not
;; re-frame.ssr's narrower grammar), with the two ids re-frame.ssr throws.
;; `oncommand` is on the silent-drop event-handler allowlist.
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
            stringifying, so it must not be appended raw — for true AND false"
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
  (testing "class-before-id shorthand parses to a NIL tag, which would render
            as the literal element `<null>`; it throws the same id"
    (let [{:keys [html error]} (render-outcome [:div.a#id "x"])]
      (is (nil? html) (str "no markup may be produced; got " (pr-str html)))
      (is (= :rf.error/invalid-tag-name (:rf.error/id error)))
      (is (nil? (:tag-name error)))
      (is (= :div.a#id (:source error))))))

(deftest valid-names-still-serialise-rf2-3x7nj-6-1
  (testing "controls: ordinary names render normally"
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
        "the boolean path emits a valid prefixed name"))
  (testing "react-dom accepts `x.y` and `_foo`; re-frame.ssr's narrower
            grammar would refuse both. They PIN the chosen grammar: a gate
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
;; Element-context text rules
;;
;; `<script>` / `<style>` bodies are HTML RAW TEXT: the parser never decodes
;; character references inside them, so entity-escaping their text corrupts
;; the CSS / JS (`'Open Sans'` would become `&#39;Open Sans&#39;`, `a && b`
;; would become `a &amp;&amp; b`). react-dom emits the text verbatim and
;; rewrites only an embedded closing-tag sequence. Separately, the parser
;; eats one LF after
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
