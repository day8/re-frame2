(ns re-frame.ssr-emit-test
  "Spec 011 §XSS at output boundaries and the hiccup emitters' wire rules,
  driven through the public walker `emit/render-to-string` rather than the
  per-attribute helper `ssr_attr_filter_test` calls directly. The walker
  composes `attr-string` inside its own element arms, so a walker that
  bypassed it would pass every per-attribute test while leaking a hostile
  prop onto the wire."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.ssr.ui-tree :as rf.ssr.ui-tree]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- v1
  "Wrap a structural node as a version-1 tree for `emit-ui-tree`."
  [node]
  (assoc node :rf.ui/tree-version 1))

(defn- assert-emitters-agree
  "Both SSR paths — the sync hiccup emitter and the structural-tree
  serialiser — emit the same raw-text body
  `expected-inner` for a `tag` carrying the single string `content`."
  [tag content expected-inner]
  (let [tag-name (name tag)
        expected (str "<" tag-name ">" expected-inner "</" tag-name ">")]
    (is (= expected (rf.ssr.emit/render-to-string [tag content] {}))
        (str "sync render-to-string byte-mismatch for <" tag-name ">"))
    (is (= expected (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag tag :children [content]})))
        (str "emit-ui-tree byte-mismatch for <" tag-name ">"))))

;; ===========================================================================
;; Strip-prop XSS rule through the hiccup walker
;; ===========================================================================

(deftest hostile-props-are-stripped
  (testing "One row per strip class (handler, fn value, prototype-pollution
            key) and per element arm (open/close, void, nested descent), each
            pinned to the exact markup"
    (doseq [[label tree expected]
            [["a kebab handler beside a child"
              [:div {:on-click "alert(1)" :id "x"} [:p "shell body"]]
              "<div id=\"x\"><p>shell body</p></div>"]
             ["a function value"
              [:span {:data-cb (fn [] nil)}]
              "<span></span>"]
             ["__proto__"
              [:div {:__proto__ "polluted" :id "x"}]
              "<div id=\"x\"></div>"]
             ["a prototype-pollution key matched case-insensitively"
              [:div {:Constructor "polluted" :id "x"}]
              "<div id=\"x\"></div>"]
             ["void <input>, kebab handler"
              [:input {:on-change "x()" :id "x"}]
              "<input id=\"x\">"]
             ["a handler several levels deep"
              [:div [:section [:ul [:li {:onClick "deep()" :class "item"} "deep"]]]]
              "<div><section><ul><li class=\"item\">deep</li></ul></section></div>"]
             ;; Lower-case touch handlers have no upper-case tail char and no
             ;; hyphen, so only the allowlist strips them.
             ["lower-case touch handler :ontouchstart"
              [:div {:ontouchstart "alert(document.cookie)" :id "x"} [:p "body"]]
              "<div id=\"x\"><p>body</p></div>"]]]
      (is (= expected (rf.ssr.emit/render-to-string tree {}))
          (str "render-to-string — " label)))))

(deftest render-to-string-strips-props-through-registered-view-root
  (testing "A view whose root DOM element carries a hostile handler still
            emits stripped through the callable-head branch"
    (rf/reg-view ^{:rf/id :test/hostile-root} hostile-root-view []
      [:div {:on-click "alert(document.cookie)" :id "v"}
       [:p "safe body"]])
    (let [html (rf.ssr.emit/render-to-string [(rf/view :test/hostile-root)] {})]
      (is (str/includes? html "id=\"v\""))
      (is (not (str/includes? html "alert(document.cookie)"))))))

(deftest render-to-string-strips-props-when-root-attrs-injected
  (testing "The injected `data-rf-render-hash` lands while the user's handler
            on the same root element is dropped"
    (is (= "<div id=\"root\" data-rf-render-hash=\"deadbeef\"><p>x</p></div>"
           (rf.ssr.emit/render-to-string
             [:div {:onClick "alert(1)" :id "root"} [:p "x"]]
             {:render-hash "deadbeef"})))))

;; ===========================================================================
;; Inline <script>/<style> STRING content is author content: emitted verbatim
;; with only React's closing-sequence rewrite, no entity escaping, and one
;; raw-text semantics across all three SSR paths.
;; ===========================================================================

(deftest raw-text-body-content-is-emitted-verbatim-not-escaped
  (assert-emitters-agree :script "if (a < b) { x() }" "if (a < b) { x() }")
  (assert-emitters-agree :style "a > .b { color: red }" "a > .b { color: red }"))

(deftest raw-text-body-closing-sequence-is-rewritten-case-insensitively
  (testing "An embedded `(<|</)script`/`style` sequence is rewritten as
            react-dom/server's scriptRegex/styleRegex do, on all three paths"
    ;; <script>: s/S -> s / S (a valid JS *and* JSON string escape).
    (assert-emitters-agree :script "var x = '</script>';"
                           "var x = '</\\u0073cript>';")
    (assert-emitters-agree :script "a</ScRiPt>b" "a</\\u0053cRiPt>b")
    (assert-emitters-agree :script "a<script>b" "a<\\u0073cript>b")
    ;; <style>: s/S -> \73 / \53  (trailing space terminates the CSS hex escape).
    (assert-emitters-agree :style "@import '</style>';"
                           "@import '</\\73 tyle>';")
    (assert-emitters-agree :style "x</StYlE>y" "x</\\53 tYlE>y")))

(deftest raw-text-classification-is-case-insensitive-on-the-tag
  (is (= "<Script>if (a < b)</Script>"
         (rf.ssr.emit/render-to-string [:Script "if (a < b)"] {})))
  (is (= "<STYLE>a > .b { }</STYLE>"
         (rf.ssr.emit/render-to-string [:STYLE "a > .b { }"] {}))))

(deftest render-to-string-allows-empty-or-element-only-raw-text-tags
  (is (= "<script src=\"/main.js\"></script>"
         (rf.ssr.emit/render-to-string [:script {:src "/main.js"}] {}))))

;; ===========================================================================
;; Void classification is case-insensitive: the void set is keyed lower-case,
;; and `[:BR]` must not emit a <BR></BR> pair.
;; ===========================================================================

(deftest render-to-string-void-classification-is-case-insensitive
  (is (= "<BR>" (rf.ssr.emit/render-to-string [:BR] {})))
  (is (= "<DIV>x</DIV>" (rf.ssr.emit/render-to-string [:DIV "x"] {}))
      "case-folding classifies; it does not make a non-void tag void"))

;; ===========================================================================
;; The Reagent-native interop head `:>` cannot be rendered without React on
;; the JVM; it fails loud rather than dumping component + props as raw text.
;; ===========================================================================

(deftest render-to-string-rejects-reagent-native-head
  (let [some-component (fn [_props] [:div "react"])
        thrown         (try (rf.ssr.emit/render-to-string
                              [:> some-component {:secret "leak-me"} [:span "child"]] {})
                            (catch clojure.lang.ExceptionInfo e e))]
    (is (= :rf.error/ssr-reagent-native-head (:rf.error/id (ex-data thrown))))))

;; ===========================================================================
;; Root-attrs (the `data-rf-render-hash` marker) thread past fragment and seq
;; roots onto the first DOM-tag element, exactly once.
;; ===========================================================================

(deftest render-hash-threads-through-fragment-root
  (is (= "<div data-rf-render-hash=\"deadbeef\">a</div><div>b</div>"
         (rf.ssr.emit/render-to-string [:<> [:div "a"] [:div "b"]] {:render-hash "deadbeef"}))
      "the marker lands on the first DOM child only")
  (is (= "<div data-rf-render-hash=\"deadbeef\">y</div>"
         (rf.ssr.emit/render-to-string [:<> [:<> [:div "y"]]] {:render-hash "deadbeef"}))
      "a fragment whose first child is a fragment still places the marker"))

(deftest fragment-props-map-is-not-a-child
  (testing "A `:<>` fragment's props map at slot 1 is not a child: emitting it
            would put the map's EDN on the wire, and `[:<> {:key i} …]` inside
            a `for` is the canonical idiom. Its attributes are dropped, not
            refused, as React and reagent-slim drop them"
    (doseq [[tree expected]
            [[[:<> {:key "k"} [:div "x"]]                              "<div>x</div>"]
             [[:<> {:class "nope" :onClick "alert(1)"} [:div "x"]]     "<div>x</div>"]
             [[:<> {:key "k"}]                                         ""]
             [[:<> (list [:div "a"]) [:div "b"]]                       "<div>a</div><div>b</div>"]]]
      (is (= expected (rf.ssr.emit/render-to-string tree {}))
          (pr-str tree)))))

(deftest render-hash-threads-through-a-fragment-that-has-props
  (testing "Skipping the props slot keeps the marker threading onto the first
            child; with the map as the first child it would vanish"
    (is (= "<div data-rf-render-hash=\"deadbeef\">a</div><div>b</div>"
           (rf.ssr.emit/render-to-string
             [:<> {:key "k"} [:div "a"] [:div "b"]] {:render-hash "deadbeef"})))
    (is (= "<div data-rf-render-hash=\"deadbeef\">y</div>"
           (rf.ssr.emit/render-to-string
             [:<> {:key "outer"} [:<> {:key "inner"} [:div "y"]]]
             {:render-hash "deadbeef"})))))

(deftest render-hash-threads-through-lazy-seq-root
  (is (= "<p data-rf-render-hash=\"deadbeef\">1</p><p>2</p>"
         (rf.ssr.emit/render-to-string (for [i [1 2]] [:p i]) {:render-hash "deadbeef"}))
      "the marker lands on the first DOM child of a seq root, once")
  (is (= "<div>x</div>"
         (rf.ssr.emit/render-to-string (list [:div "x"]) {}))
      "without :render-hash a seq root emits no marker"))

;; ===========================================================================
;; :rf/suspense-boundary is a streaming-only marker; the standard emitter
;; rejects it rather than emitting a phantom <suspense-boundary> element.
;; ===========================================================================

(deftest render-to-string-rejects-suspense-boundary-outside-stream
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":rf.error/ssr-suspense-boundary-outside-stream"
                        (rf.ssr.emit/render-to-string
                          [:rf/suspense-boundary
                           {:id :b1 :fallback [:span "loading"]}
                           [:div "resolved"]]
                          {}))))

;; ===========================================================================
;; Scalar children and the escape asymmetry.
;; ===========================================================================

(deftest render-to-string-number-child-stringifies
  (is (= "<p>count=7</p>" (rf.ssr.emit/render-to-string [:p "count=" 7] {}))))

(deftest render-to-string-boolean-child-is-dropped
  (testing "`[:div (when cond? …)]` yields `false`/`nil`; neither renders a word"
    (is (= "<p>ab</p>"
           (rf.ssr.emit/render-to-string [:p "a" true "b" false nil] {})))))

(deftest render-to-string-text-node-escapes-all-five-entities
  (is (= "<p>&amp;&lt;&gt;&quot;&#39;</p>"
         (rf.ssr.emit/render-to-string [:p "&<>\"'"] {}))))

(deftest render-to-string-attr-value-escapes-only-amp-and-quote
  (testing "`<`, `>` and `'` are legal inside a double-quoted attribute value,
            so over-escaping them would corrupt legitimate values"
    (is (= "<div data-x=\"a&amp;b&quot;<>'\"></div>"
           (rf.ssr.emit/render-to-string [:div {:data-x "a&b\"<>'"}] {})))))

;; ===========================================================================
;; A Var head (`#'component`) is `ifn?` but not `fn?` on the JVM; the walker
;; resolves it as a component.
;; ===========================================================================

(defn var-component [label] [:span label])

(deftest render-to-string-var-headed-component
  (is (= "<span>ok</span>"
         (rf.ssr.emit/render-to-string [#'var-component "ok"] {})))
  (is (= "<span data-rf-render-hash=\"deadbeef\">ok</span>"
         (rf.ssr.emit/render-to-string [#'var-component "ok"] {:render-hash "deadbeef"}))
      "the root marker threads through the Var head onto its resolved root"))

;; ===========================================================================
;; A malformed head fails loud; stringifying the vector would ship its
;; children raw (`[nil "<script>…"]`).
;; ===========================================================================

(deftest emit-rejects-malformed-hiccup-head
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":rf.error/invalid-hiccup-head"
                        (rf.ssr.emit/render-to-string [nil "<script>alert(1)</script>"] {}))))

(deftest collection-heads-are-malformed
  (testing "A collection head is `ifn?` (it looks up its argument) but is not
            a component"
    (let [el [{:a "<b>"} :a]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/invalid-hiccup-head"
                            (rf.ssr.emit/render-to-string el {}))))))

;; ===========================================================================
;; `:dangerouslySetInnerHTML` renders as the element's raw body, in the order
;; `reagent2.dom.server` uses: a void element ignores it, otherwise it wins
;; over any children.
;; ===========================================================================

(deftest dangerously-set-inner-html-renders-raw
  (doseq [[label tree expected]
          [["the other attributes stay; the body is not re-escaped"
            [:section [:p {:id "a" :dangerouslySetInnerHTML {:__html "a &amp; b"}}]]
            "<section><p id=\"a\">a &amp; b</p></section>"]
           ["the raw body wins over children"
            [:div {:dangerouslySetInnerHTML {:__html "<i>raw</i>"}} "ignored"]
            "<div><i>raw</i></div>"]
           ["a nil __html is an empty body"
            [:div {:dangerouslySetInnerHTML {:__html nil}} "ignored"]
            "<div></div>"]
           ["a void element ignores the prop"
            [:br {:dangerouslySetInnerHTML {:__html "<b>x</b>"}}]
            "<br>"]]]
    (is (= expected (rf.ssr.emit/render-to-string tree {}))
        (str "render-to-string — " label))))

;; Any value answering `:__html` by lookup supplies the body: the Reagent
;; bridge's `unsafe-html` value relies on it (pinned on CLJS by
;; `re-frame.adapter-unsafe-html-ssr-cljs-test`). A JVM `ILookup` stands in.

(deftype LookupHtml [html]
  clojure.lang.ILookup
  (valAt [_ k] (when (= :__html k) html))
  (valAt [_ k not-found] (if (= :__html k) html not-found)))

(deftest dangerously-set-inner-html-reads-any-value-answering-html
  (doseq [[label tree expected]
          [["a value answering :__html renders its markup as the body"
            [:div {:dangerouslySetInnerHTML (->LookupHtml "<i>raw</i>")} "ignored"]
            "<div><i>raw</i></div>"]
           ["a string answers no :__html and gives an empty body"
            [:div {:dangerouslySetInnerHTML "<b>x</b>"} "ignored"]
            "<div></div>"]]]
    (is (= expected (rf.ssr.emit/render-to-string tree {}))
        (str "render-to-string — " label))))

;; ===========================================================================
;; Form-2 components. The walker resolves a component head through
;; `emit/resolve-component-head`.
;; ===========================================================================

(deftest emit-renders-form-2-component
  (testing "A Form-2 component renders its inner hiccup, not the inner fn's
            .toString"
    (let [form2-closed (fn [value] (fn [] [:div value]))
          form2-arg    (fn [_outer-value]
                         (fn [value] [:p (str "v=" value)]))]
      (is (= "<div>hello</div>" (rf.ssr.emit/emit-element [form2-closed "hello"])))
      (is (= "<p>v=7</p>" (rf.ssr.emit/emit-element [form2-arg 7])))))

  (testing "A component still a fn after the Form-2 unwrap fails loud"
    (let [deep (fn [x] (fn [] (fn [] [:div x])))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/ssr-nonrenderable-component"
                            (rf.ssr.emit/emit-element [deep "z"]))))))

;; The arity is selected from the inner fn's DECLARED arities and invoked
;; once. Probing it by catching ArityException would re-run a render whose own
;; body raised one, duplicating its effects and replacing the author's error
;; with the retry's. The doubles COUNT their calls, because the exception is
;; the same either way.

(deftest emit-form-2-inner-body-arity-exception-propagates-once
  (testing "An ArityException raised inside a correctly-invoked inner render
            propagates, and the render runs exactly once"
    (let [calls       (atom 0)
          needs-two   (fn [a b] [:span a b])
          form2-buggy (fn [] (fn []
                               (swap! calls inc)
                               ;; Deliberately wrong arity — this call IS the fixture payload.
                               [:div #_{:clj-kondo/ignore [:invalid-arity]}
                                     (needs-two "x")]))]
      (is (thrown? clojure.lang.ArityException
                   (rf.ssr.emit/emit-element [form2-buggy])))
      (is (= 1 @calls))))

  (testing "The inner body's original failure propagates unchanged; a
            zero-arity retry would report `(0)` about a call never written"
    (let [calls       (atom 0)
          needs-two   (fn [a b] [:span a b])
          form2-buggy (fn [_outer-value] (fn [value]
                                (swap! calls inc)
                                ;; Deliberately wrong arity — this call IS the fixture payload.
                                [:div #_{:clj-kondo/ignore [:invalid-arity]}
                                      (needs-two value)]))]
      (is (thrown-with-msg? clojure.lang.ArityException
                            #"Wrong number of args \(1\)"
                            (rf.ssr.emit/emit-element [form2-buggy "x"])))
      (is (= 1 @calls)))))

(deftest emit-form-2-variadic-inner-body-failure-is-not-swallowed
  (testing "A zero-arity retry would SUCCEED on a variadic inner, silently
            shipping different HTML"
    (let [needs-two (fn [a b] [:span a b])
          form2     (fn [x] (fn [& xs]
                              (if (seq xs)
                                ;; Deliberately wrong arity — this call IS the fixture payload.
                                [:div #_{:clj-kondo/ignore [:invalid-arity]}
                                      (needs-two x)]
                                [:p "zero-arity retry reached this"])))]
      (is (thrown? clojure.lang.ArityException
                   (rf.ssr.emit/emit-element [form2 "x"]))))))

(deftest emit-passes-form-2-variadic-inner-the-whole-arg-seq
  (let [calls (atom 0)
        form2 (fn [_a _b] (fn [& xs]
                            (swap! calls inc)
                            [:p (str/join "," xs)]))]
    (is (= "<p>a,b</p>" (rf.ssr.emit/emit-element [form2 "a" "b"])))
    (is (= 1 @calls))))

(deftest render-to-string-presence-classes-are-preserved
  (testing "A nil on an ordinary attribute is dropped, beside a presence
            attribute that stays a bare name"
    (is (= "<button disabled>Go</button>"
           (rf.ssr.emit/render-to-string [:button {:disabled true :title nil} "Go"] {})))))
