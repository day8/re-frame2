(ns re-frame.ssr-emit-test
  "Spec 011 §XSS at output boundaries — the strip-prop rule
  driven through the FULL emit composition, not just `attr-string` in
  isolation.

  `ssr_attr_filter_test.clj` proves the rule at the per-attribute unit
  level (`html-helpers/attr-string` called directly). That is necessary
  but not sufficient: the emitter COMPOSES `attr-string` inside
  `emit-element` (emit.cljc:323-326) and inside the streaming walker
  (streaming.cljc:225-227), and the strip MUST run AHEAD of the
  attribute-name grammar gate (html_helpers.cljc:179-189) at that
  composed callsite. A regression that reorders the emitter composition,
  or that bypasses `attr-string` for some attr path in `emit-element` /
  `walk-dom-tag`, would pass every per-attribute test while leaking a
  hostile `on*` handler / fn-valued prop / prototype-pollution key onto
  the wire.

  These tests therefore feed hostile props through:

    1. `re-frame.ssr.emit/render-to-string` — the public non-streaming
       emitter, including the void-element branch, the callable-head
       (registered view) branch, fragments, and the root-attrs (`:render-hash`) injection.
    2. `re-frame.ssr.streaming/render-shell` — the streaming shell walk,
       whose `walk-dom-tag` re-derives attrs via `emit/attr-string`.

  so a reorder/bypass regression at EITHER composed callsite is caught.

  JVM-only — the strip rule is platform-neutral .cljc, but the per-attr
  proof already runs on both platforms (`ssr_attr_filter_test`); driving
  the JVM emit composition here is enough to pin the composition order."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.streaming :as rf.ssr.streaming]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.ssr.ui-tree :as rf.ssr.ui-tree]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ---------------------------------------------------------------------------
;; Shared raw-text emission across ALL three SSR paths.
;; ---------------------------------------------------------------------------

(defn- v1
  "Wrap a structural node as a version-1 tree for `emit-ui-tree`."
  [node]
  (assoc node :rf.ui/tree-version 1))

(defn- assert-emitters-agree
  "The load-bearing cross-emitter proof: for a
  raw-text `tag` (`:script`/`:style`) carrying a single string `content`, all
  three SSR paths — the sync hiccup emitter, the streaming shell walker, and
  the S5 structural serialiser — emit the SAME `expected-inner` body between
  the tags. `expected-inner` is the raw-text body AFTER the closing-sequence
  rewrite (verbatim but for that rewrite; NO entity escaping)."
  [tag content expected-inner]
  (let [tag-name (name tag)
        expected (str "<" tag-name ">" expected-inner "</" tag-name ">")]
    (is (= expected (rf.ssr.emit/render-to-string [tag content] {}))
        (str "sync render-to-string byte-mismatch for <" tag-name ">"))
    (is (str/includes? (:shell-html (rf.ssr.streaming/render-shell [:div [tag content]]))
                       expected)
        (str "streaming render-shell byte-mismatch for <" tag-name ">"))
    (is (= expected (rf.ssr.ui-tree/emit-ui-tree (v1 {:tag tag :children [content]})))
        (str "emit-ui-tree byte-mismatch for <" tag-name ">"))))

;; ===========================================================================
;; Strip-prop XSS rule through both hiccup walkers
;; ===========================================================================

(deftest hostile-props-are-stripped-on-both-walkers
  (testing "Every strip class — an `on*` handler in any spelling, a
            function value, a prototype-pollution key in any case — is
            dropped at emit time through the FULL composition of BOTH
            hiccup walkers: `render-to-string` (`emit-element`) and the
            streaming shell walk (`walk-dom-tag`, which re-derives attrs
            via `emit/attr-string`). The rows reach the open/close branch,
            the void-element branch and nested descent, where
            `emit-children` re-enters `emit-element` per child. Each
            expectation is the exact markup, so no handler name or body, no
            stray space and no bare attribute reaches the wire."
    (doseq [[label tree expected]
            [["kebab :on-click"
              [:div {:on-click "alert(1)" :id "x"}]
              "<div id=\"x\"></div>"]
             ["camelCase :onClick"
              [:div {:onClick "alert(1)" :id "x"}]
              "<div id=\"x\"></div>"]
             ["multi-word camelCase :onMouseDown"
              [:div {:onMouseDown "steal()" :id "x"}]
              "<div id=\"x\"></div>"]
             ["a stripped handler as the ONLY attr"
              [:div {:on-click "f"}]
              "<div></div>"]
             ["a handler beside a child"
              [:div {:on-click "alert(1)" :id "x"} [:p "shell body"]]
              "<div id=\"x\"><p>shell body</p></div>"]
             ["a function value"
              [:div {:title (fn [_] :handler) :id "x"}]
              "<div id=\"x\"></div>"]
             ["a function value on an innocuous key"
              [:span {:data-cb (fn [] nil)}]
              "<span></span>"]
             ["__proto__"
              [:div {:__proto__ "polluted" :id "x"}]
              "<div id=\"x\"></div>"]
             ["constructor"
              [:div {:constructor "polluted" :id "x"}]
              "<div id=\"x\"></div>"]
             ["prototype"
              [:div {:prototype "polluted" :id "x"}]
              "<div id=\"x\"></div>"]
             ["a prototype-pollution key matched case-insensitively"
              [:div {:Constructor "polluted" :id "x"}]
              "<div id=\"x\"></div>"]
             ["a function value and __proto__ beside a child"
              [:div {:title (fn [] nil) :__proto__ "polluted" :id "x"} [:span "ok"]]
              "<div id=\"x\"><span>ok</span></div>"]
             ["void <input>, kebab handler"
              [:input {:on-change "x()" :id "x"}]
              "<input id=\"x\">"]
             ["void <img>, camelCase handler"
              [:img {:onError "alert(1)" :src "/a.png"}]
              "<img src=\"/a.png\">"]
             ["void <input> under a <form>"
              [:form [:input {:onChange "steal()" :name "q"}]]
              "<form><input name=\"q\"></form>"]
             ["a handler several levels deep"
              [:div [:section [:ul [:li {:onClick "deep()" :class "item"} "deep"]]]]
              "<div><section><ul><li class=\"item\">deep</li></ul></section></div>"]
             ;; The lower-case Touch Events handlers have no upper-case tail
             ;; char and no hyphen, so the structural matcher cannot see them:
             ;; only the allowlist strips these four.
             ["lower-case touch handler :ontouchstart"
              [:div {:ontouchstart "alert(document.cookie)" :id "x"} [:p "body"]]
              "<div id=\"x\"><p>body</p></div>"]
             ["lower-case touch handler :ontouchmove"
              [:div {:ontouchmove "alert(document.cookie)" :id "x"} [:p "body"]]
              "<div id=\"x\"><p>body</p></div>"]
             ["lower-case touch handler :ontouchend"
              [:div {:ontouchend "alert(document.cookie)" :id "x"} [:p "body"]]
              "<div id=\"x\"><p>body</p></div>"]
             ["lower-case touch handler :ontouchcancel"
              [:div {:ontouchcancel "alert(document.cookie)" :id "x"} [:p "body"]]
              "<div id=\"x\"><p>body</p></div>"]
             ["void <img>, canonical lower-case handler :onerror"
              [:img {:src "x" :onerror "alert(document.cookie)"}]
              "<img src=\"x\">"]
             ["void <img>, upper-case handler :ONLOAD"
              [:img {:src "x" :ONLOAD "steal()"}]
              "<img src=\"x\">"]]]
      (is (= expected (rf.ssr.emit/render-to-string tree {}))
          (str "render-to-string — " label))
      (is (= expected (:shell-html (rf.ssr.streaming/render-shell tree)))
          (str "render-shell — " label)))))

(deftest render-to-string-strips-props-through-registered-view-root
  (testing "The strip composes with the CALLABLE-head
            resolution branch. A view whose ROOT DOM element carries a
            hostile handler must still emit stripped — the callable-head
            indirection must not bypass `attr-string`."
    (rf/reg-view ^{:rf/id :test/hostile-root} hostile-root-view []
      [:div {:on-click "alert(document.cookie)" :id "v"}
       [:p "safe body"]])
    (let [html (rf.ssr.emit/render-to-string [(rf/view :test/hostile-root)] {})]
      (is (str/includes? html "<p>safe body</p>")
          "the view body still renders")
      (is (str/includes? html "id=\"v\"")
          "the legit root attr survives")
      (is (not (str/includes? html "on-click"))
          "the root handler is stripped through the callable-head branch")
      (is (not (str/includes? html "alert(document.cookie)"))
          "the handler body never reaches the wire"))))

(deftest render-to-string-strips-props-when-root-attrs-injected
  (testing "The strip survives the root-attrs
            (`:render-hash`) injection path. The injected
            `data-rf-render-hash` lands while the user's hostile handler
            on the SAME root element is dropped — `merge-root-attrs`
            feeds into the same `attr-string` strip."
    (let [html (rf.ssr.emit/render-to-string
                 [:div {:onClick "alert(1)" :id "root"} [:p "x"]]
                 {:render-hash "deadbeef"})]
      (is (str/includes? html "data-rf-render-hash=")
          "the render-hash root attr was injected")
      (is (str/includes? html "id=\"root\"")
          "the legit user attr survives alongside the injected hash")
      (is (not (str/includes? html "onClick"))
          "the user's handler on the hash-bearing root is still stripped")
      (is (not (str/includes? html "alert"))
          "no handler body leaks through the injection composition"))))

;; ===========================================================================
;; Strip-prop XSS rule through `streaming/render-shell`'s walk
;; ===========================================================================

(deftest render-shell-strips-handler-buried-near-suspense-boundary
  (testing "A hostile handler on a shell element that SITS
            ALONGSIDE a :rf/suspense-boundary is stripped through the
            walk, while the boundary still registers its continuation.
            Pins that the strip composes with the suspense-walk path,
            not only plain DOM descent."
    (let [tree [:div {:onClick "alert(1)" :id "outer"}
                [:rf/suspense-boundary
                 {:id :sb :fallback [:p "loading"]}
                 [:p "body"]]]
          {:keys [shell-html continuations]} (rf.ssr.streaming/render-shell tree)]
      (is (= 1 (count continuations))
          "the boundary still registered its continuation")
      (is (str/includes? shell-html "id=\"outer\"")
          "the legit attr on the boundary-bearing element survives")
      (is (not (str/includes? shell-html "onClick"))
          "the handler on the boundary-bearing element is stripped")
      (is (not (str/includes? shell-html "alert(1)"))
          "the handler body never reaches the shell"))))

;; ===========================================================================
;; Ordinary inline
;; <script>/<style> STRING content is AUTHOR content: emitted VERBATIM with
;; only React's context-safe closing-sequence rewrite, NO entity escaping and
;; NO refusal. A refusal would push real
;; content into the genuinely-unguarded trusted shell opts, which is strictly
;; LESS safe than a guarded render-tree element. ONE raw-text semantics
;; holds across all three SSR paths (sync hiccup, streaming hiccup, S5
;; serialiser); the DATA-payload channels keep their stricter escapes.
;; ===========================================================================

(deftest raw-text-body-content-is-emitted-verbatim-not-escaped
  (testing "Literal JS/CSS operators and `&` are NOT entity-escaped
            (escape-html would corrupt them), and all three SSR paths agree."
    ;; escape-html would produce `if (a &lt; b)` / `a &gt; .b` — a
    ;; corrupted script/style body. Raw text emits them literally.
    (assert-emitters-agree :script "if (a < b) { x() }" "if (a < b) { x() }")
    (assert-emitters-agree :script "a & b && c" "a & b && c")
    (assert-emitters-agree :style "a > .b { color: red }" "a > .b { color: red }")
    (assert-emitters-agree :style "x & y" "x & y")))

(deftest raw-text-body-closing-sequence-is-rewritten-case-insensitively
  (testing "An embedded `(<|</)script`/`style` closing sequence is
            rewritten to a context-safe spelling so the raw-text parser cannot
            terminate the element early, matching react-dom/server's
            scriptRegex/styleRegex byte-for-byte (case-insensitive), and all
            three SSR paths agree."
    ;; <script>: s/S -> \u0073 / \u0053 (a valid JS *and* JSON string escape).
    (assert-emitters-agree :script "var x = '</script>';"
                           "var x = '</\\u0073cript>';")
    (assert-emitters-agree :script "a</ScRiPt>b" "a</\\u0053cRiPt>b")
    (assert-emitters-agree :script "a<script>b" "a<\\u0073cript>b")
    ;; <style>: s/S -> \73 / \53  (trailing space terminates the CSS hex escape).
    (assert-emitters-agree :style "@import '</style>';"
                           "@import '</\\73 tyle>';")
    (assert-emitters-agree :style "x</StYlE>y" "x</\\53 tYlE>y")))

(deftest raw-text-classification-is-case-insensitive-on-the-tag
  (testing "An UPPER/MIXED-case <SCRIPT> /
            <Style> tag is still classified as raw text (author case preserved
            in the emitted markup), so its literal `<` is NOT entity-escaped."
    (doseq [tag [:SCRIPT :Script :sCrIpT]]
      (is (= (str "<" (name tag) ">if (a < b)</" (name tag) ">")
             (rf.ssr.emit/render-to-string [tag "if (a < b)"] {}))
          (str tag " body emitted as raw text, not escaped")))
    (doseq [tag [:STYLE :Style]]
      (is (= (str "<" (name tag) ">a > .b { }</" (name tag) ">")
             (rf.ssr.emit/render-to-string [tag "a > .b { }"] {}))
          (str tag " body emitted as raw text, not escaped")))))

(deftest raw-text-json-island-round-trips-through-json-parse
  (testing "A JSON data island written as ordinary <script> string
            content is emitted raw with the closing-sequence rewrite; the
            embedded `</script>` cannot terminate the element, and because
            `\\u0073` is ALSO a valid JSON string escape the payload round-trips
            back through JSON.parse."
    (let [json "{\"@type\":\"WebSite\",\"u\":\"a</script>b\"}"
          out  (rf.ssr.emit/render-to-string
                 [:script {:type "application/ld+json"} json] {})]
      (is (= (str "<script type=\"application/ld+json\">"
                  "{\"@type\":\"WebSite\",\"u\":\"a</\\u0073cript>b\"}"
                  "</script>")
             out)
          "the embedded </script> is rewritten to </\\u0073cript>, element not terminated"))))

(deftest render-to-string-allows-empty-or-element-only-raw-text-tags
  (testing "A raw-text tag with NO string child is inert; the raw-text
            emission only applies to string content."
    (is (= "<script></script>"
           (rf.ssr.emit/render-to-string [:script] {}))
        "empty <script> is fine")
    (is (= "<style></style>"
           (rf.ssr.emit/render-to-string [:style {}] {}))
        "empty <style> with an attrs map is fine")
    (is (= "<script src=\"/main.js\"></script>"
           (rf.ssr.emit/render-to-string [:script {:src "/main.js"}] {}))
        "an attribute-only <script> (the common external-script shape) is fine")))

;; ===========================================================================
;; Void classification is CASE-INSENSITIVE.
;; `validate-tag-name!` admits upper/mixed-case names, but the void element
;; SET is keyed lower-case. Without normalisation, `[:BR]` would be emitted as
;; a non-void <BR></BR> pair; the streaming walker has the same void rule.
;; (The case-insensitive RAW-TEXT classification is exercised by the raw-text
;; emission tests above — `raw-text-classification-is-case-insensitive-on-the-tag`
;; and `render-shell-raw-text-is-case-insensitive`.)
;; ===========================================================================

(deftest render-to-string-void-classification-is-case-insensitive
  (testing "An UPPER/MIXED-case void tag is recognised
            as void and self-closes; it must NOT emit a spurious closing tag.
            HTML5 tag names are case-insensitive."
    (testing "[:BR] self-closes (no </BR>)"
      (let [html (rf.ssr.emit/render-to-string [:BR] {})]
        (is (= "<BR>" html)
            "void classification is case-insensitive; author case preserved")))
    (testing "[:Img …] self-closes with its attrs"
      (let [html (rf.ssr.emit/render-to-string [:Img {:src "/a.png"}] {})]
        (is (= "<Img src=\"/a.png\">" html))))
    (testing "[:INPUT …] self-closes"
      (is (= "<INPUT name=\"q\">"
             (rf.ssr.emit/render-to-string [:INPUT {:name "q"}] {}))))
    (testing "a non-void upper-case tag still emits an open+close pair"
      (is (= "<DIV>x</DIV>"
             (rf.ssr.emit/render-to-string [:DIV "x"] {}))
          "case-folding only affects the void/raw-text classification, not
           which tags are void"))))

(deftest render-shell-void-classification-is-case-insensitive
  (testing "The streaming walker mirrors the
            case-insensitive void classification (streaming.cljc:284). A
            `[:BR]` in the shell must self-close, not emit <BR></BR>."
    (let [{:keys [shell-html]} (rf.ssr.streaming/render-shell
                                 [:div [:BR] [:Img {:src "/a.png"}]])]
      (is (str/includes? shell-html "<BR>"))
      (is (not (str/includes? shell-html "</BR>"))
          "upper-case void element self-closes in the streaming shell")
      (is (str/includes? shell-html "<Img src=\"/a.png\">"))
      (is (not (str/includes? shell-html "</Img>"))))))

(deftest render-shell-raw-text-is-case-insensitive
  (testing "The streaming walk classifies raw-text tags
            case-insensitively too: an UPPER/MIXED-case <STYLE>/<SCRIPT> body
            is emitted as raw text (author case preserved), NOT entity-escaped
            and NOT refused."
    (let [{:keys [shell-html]} (rf.ssr.streaming/render-shell
                                 [:div
                                  [:STYLE "p > a { margin: 0 }"]
                                  [:Script "if (a < b) { x() }"]])]
      (is (str/includes? shell-html "<STYLE>p > a { margin: 0 }</STYLE>")
          "upper-case <STYLE> body emitted raw in the streaming shell")
      (is (str/includes? shell-html "<Script>if (a < b) { x() }</Script>")
          "mixed-case <Script> body emitted raw in the streaming shell")
      (is (not (str/includes? shell-html "&lt;"))
          "no entity escaping leaked into a raw-text body"))))

;; ===========================================================================
;; Reagent-native interop head `:>` cannot be statically
;; rendered server-side (no React on the JVM); fail loud rather than dump
;; the component+props as raw text.
;; ===========================================================================

(deftest render-to-string-rejects-reagent-native-head
  (testing "`[:> Component {props} child]` throws
            :rf.error/ssr-reagent-native-head instead of stringifying the
            component ref + dumping the props map as raw EDN into markup."
    (let [some-component (fn [_props] [:div "react"])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/ssr-reagent-native-head"
                            (rf.ssr.emit/render-to-string
                              [:> some-component {:prop "v"} [:span "child"]] {})))
      (testing "the props map is never emitted as raw text on the wire"
        (let [thrown (try (rf.ssr.emit/render-to-string
                            [:> some-component {:secret "leak-me"}] {})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (instance? clojure.lang.ExceptionInfo thrown))
          (is (= :rf.error/ssr-reagent-native-head
                 (:rf.error/id (ex-data thrown)))))))))

(deftest render-shell-rejects-reagent-native-head
  (testing "The streaming walk routes `:>` through the same
            single throw (no raw component+props splice)."
    (let [some-component (fn [_props] [:div "react"])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/ssr-reagent-native-head"
                            (rf.ssr.streaming/render-shell
                              [:div [:> some-component {:prop "v"}]]))))))

(deftest render-hash-threads-through-fragment-root
  (testing "Root-attrs (the render-hash marker)
            thread through a `:<>` fragment ROOT onto the first DOM-tag
            child exactly once. A plain `emit-children` on the
            fragment branch would drop root-attrs, so a fragment-rooted SSR
            tree would lose the `data-rf-render-hash` the emitter docstring
            promises (and that the emitter/Ring hash contract depends on)."
    (testing "the marker lands on the FIRST DOM child only, not every child"
      (let [html (rf.ssr.emit/render-to-string [:<> [:div "a"] [:div "b"]] {:render-hash "deadbeef"})]
        (is (re-matches #"<div data-rf-render-hash=\"[0-9a-f]+\">a</div><div>b</div>"
                        html)
            (str "exactly one data-rf-render-hash, on the first div; got: " html))))
    (testing "an explicit :render-hash threads through the fragment root"
      (is (= "<div data-rf-render-hash=\"deadbeef\">x</div>"
             (rf.ssr.emit/render-to-string [:<> [:div "x"]] {:render-hash "deadbeef"}))
          "the supplied hash drives the root-attr marker through the fragment"))
    (testing "nested fragments keep threading the marker down to the first DOM tag"
      (is (re-matches #"<div data-rf-render-hash=\"[0-9a-f]+\">y</div>"
                      (rf.ssr.emit/render-to-string [:<> [:<> [:div "y"]]] {:render-hash "deadbeef"}))
          "a fragment whose first child is a fragment still places the marker"))))

(deftest fragment-props-map-is-not-a-child
  (testing "A `:<>` fragment's PROPS MAP at slot 1 is not a child.
            A plain `(rest el)` would hand it to `emit-element`, which would
            fall through to `escape-html` and put the map's EDN in the response
            bytes — garbage text, and a guaranteed hydration mismatch against
            a client render that emits none of it. `[:<> {:key i} …]` inside a
            `for` is the canonical fragment idiom, so this is reachable from
            ordinary application markup."
    (testing "the three spellings agree"
      ;; Treating slot 1 as a child would give:
      ;;   [:<> {:key "k"} [:div "x"]] => "{:key &quot;k&quot;}<div>x</div>"
      ;;   [:<> {}         [:div "x"]] => "{}<div>x</div>"
      ;;   [:<>            [:div "x"]] => "<div>x</div>"
      ;; Only the third is right; all three emit the same markup.
      (is (= "<div>x</div>"
             (rf.ssr.emit/render-to-string [:<> {:key "k"} [:div "x"]] {}))
          "a keyed fragment emits its children and nothing else")
      (is (= "<div>x</div>"
             (rf.ssr.emit/render-to-string [:<> {} [:div "x"]] {}))
          "an EMPTY props map is still a props map, not a child")
      (is (= "<div>x</div>"
             (rf.ssr.emit/render-to-string [:<> [:div "x"]] {}))
          "the no-props spelling emits the same markup"))
    (testing "no EDN of the props map survives anywhere on the wire"
      (let [html (rf.ssr.emit/render-to-string
                   [:<> {:key "k" :data-x "v"} [:p "body"]] {})]
        (is (= "<p>body</p>" html) (str "got: " html))))
    (testing "a fragment that is ONLY a props map emits nothing"
      (is (= "" (rf.ssr.emit/render-to-string [:<> {:key "k"}] {})))
      (is (= "" (rf.ssr.emit/render-to-string [:<>] {}))))
    (testing "A NON-`:key` fragment attribute is DROPPED, not
              refused. A fragment is not an element, so no attribute on one has
              a wire representation; React treats a stray Fragment prop as a
              development warning rather than an error, and refusing here would
              make the SERVER stricter than the client for markup that renders
              fine in a browser. Matches the React-side codec (`:key` read,
              remainder ignored) and reagent-slim's static `emit-fragment`
              (whole slot skipped)."
      (is (= "<div>x</div>"
             (rf.ssr.emit/render-to-string
               [:<> {:class "nope" :id "nope" :onClick "alert(1)"} [:div "x"]] {}))
          "non-:key fragment attrs render nothing and throw nothing"))
    (testing "ONLY a map at slot 1 is skipped — a string / vector / seq there
              is a genuine first child"
      (is (= "text<div></div>"
             (rf.ssr.emit/render-to-string [:<> "text" [:div]] {}))
          "a string at slot 1 is a child")
      (is (= "<span>a</span><div>b</div>"
             (rf.ssr.emit/render-to-string [:<> [:span "a"] [:div "b"]] {}))
          "a hiccup vector at slot 1 is a child")
      (is (= "<div>a</div><div>b</div>"
             (rf.ssr.emit/render-to-string [:<> (list [:div "a"]) [:div "b"]] {}))
          "a seq at slot 1 is a child, not props"))))

(deftest render-hash-threads-through-a-fragment-that-has-props
  (testing "Skipping the props slot is also what keeps the root-attrs
            threading working for a keyed fragment. With the map as the first
            child, `emit-children-threading-root-attrs` would thread the
            render-hash onto a value that cannot carry an attribute and the
            marker would VANISH — silently, on exactly the keyed fragments
            applications write. This row pins both directions: the marker
            lands, and it lands on the first CHILD (not the second, and not
            twice)."
    (testing "the marker lands on the first DOM child of a PROPS-carrying fragment"
      (let [html (rf.ssr.emit/render-to-string
                   [:<> {:key "k"} [:div "a"] [:div "b"]] {:render-hash "deadbeef"})]
        (is (= "<div data-rf-render-hash=\"deadbeef\">a</div><div>b</div>" html)
            (str "marker on the first div only; got: " html))))
    (testing "a props-carrying fragment agrees byte-for-byte with the bare one"
      (is (= (rf.ssr.emit/render-to-string
               [:<> [:div "a"] [:div "b"]] {:render-hash "deadbeef"})
             (rf.ssr.emit/render-to-string
               [:<> {:key "k"} [:div "a"] [:div "b"]] {:render-hash "deadbeef"}))
          "the props map changes nothing about the emitted markup"))
    (testing "nested props-carrying fragments keep threading down to the first DOM tag"
      (is (= "<div data-rf-render-hash=\"deadbeef\">y</div>"
             (rf.ssr.emit/render-to-string
               [:<> {:key "outer"} [:<> {:key "inner"} [:div "y"]]]
               {:render-hash "deadbeef"}))
          "a fragment whose first child is a keyed fragment still places the marker"))
    (testing "the canonical `for`-over-keyed-fragments shape"
      (let [html (rf.ssr.emit/render-to-string
                   (into [:<>] (for [i [1 2]] [:<> {:key i} [:li i]]))
                   {:render-hash "deadbeef"})]
        (is (= "<li data-rf-render-hash=\"deadbeef\">1</li><li>2</li>" html)
            (str "keyed fragments in a for emit clean markup; got: " html))))))

(deftest render-hash-threads-through-lazy-seq-root
  (testing "Root-attrs (the render-hash marker) thread through a
            `lazy-seq` / list ROOT onto the first DOM-tag element, per Spec 011
            §Source-coord annotation / §Hydration-mismatch detection (a
            lazy-seq root is 'passed through the injection'). A plain
            `emit-children` on the `(sequential? el)` branch would DROP
            root-attrs, so a lazy-seq-rooted tree would silently lose its
            data-rf-render-hash marker."
    (testing "the marker lands on a (map …) lazy-seq root"
      (is (re-matches #"<div data-rf-render-hash=\"[0-9a-f]+\">1</div>"
                      (rf.ssr.emit/render-to-string (map (fn [i] [:div i]) [1]) {:render-hash "deadbeef"}))
          "a lazy-seq produced by map gets the marker on its first DOM child"))
    (testing "the marker lands on the FIRST DOM child only across a multi-child seq"
      (let [html (rf.ssr.emit/render-to-string (for [i [1 2]] [:p i]) {:render-hash "deadbeef"})]
        (is (re-matches #"<p data-rf-render-hash=\"[0-9a-f]+\">1</p><p>2</p>" html)
            (str "exactly one marker, on the first <p>; got: " html))))
    (testing "an explicit :render-hash threads through the lazy-seq root"
      (is (= "<div data-rf-render-hash=\"deadbeef\">x</div>"
             (rf.ssr.emit/render-to-string (list [:div "x"]) {:render-hash "deadbeef"}))
          "the supplied hash drives the marker through the seq root"))
    (testing "no opts → no marker (root-attrs nil on the seq branch)"
      (is (= "<div>x</div>"
             (rf.ssr.emit/render-to-string (list [:div "x"]) {}))
          "without :render-hash the lazy-seq root emits no marker"))))

;; ===========================================================================
;; :rf/suspense-boundary is a streaming-only marker. The standard
;; emitter must REJECT it (fail loud, parallel to :>) rather than emit a
;; phantom <suspense-boundary> DOM element — its name passes the tag grammar,
;; so without the guard it would serialise the {:id … :fallback …} attrs as
;; bogus attributes and the subtree as bogus children.
;; ===========================================================================

(deftest render-to-string-rejects-suspense-boundary-outside-stream
  (testing "`[:rf/suspense-boundary {:id … :fallback …} child]`
            reaching render-to-string outside a stream throws
            :rf.error/ssr-suspense-boundary-outside-stream instead of
            emitting a phantom <suspense-boundary> DOM element."
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf.error/ssr-suspense-boundary-outside-stream"
                          (rf.ssr.emit/render-to-string
                            [:rf/suspense-boundary
                             {:id :b1 :fallback [:span "loading"]}
                             [:div "resolved"]]
                            {})))
    (testing "a marker NESTED inside a normal DOM tree also fails loud
              (emit-children recurses into it)"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/ssr-suspense-boundary-outside-stream"
                            (rf.ssr.emit/render-to-string
                              [:div [:section
                                     [:rf/suspense-boundary
                                      {:id :b :fallback [:span "…"]}
                                      [:p "x"]]]]
                              {}))))))

;; ===========================================================================
;; emit-element scalar-child branches (emit.cljc:319-323).
;; The load-bearing scalar emission rules — number stringifies, boolean is
;; DROPPED, nil is dropped, string is escaped — are the leaf rules every
;; render bottoms out at; a regression (e.g. booleans accidentally
;; stringifying to "true") would corrupt every server-rendered page, and the
;; strip-prop / raw-text / tag-name security-gate tests would not catch it.
;; ===========================================================================

(deftest render-to-string-number-child-stringifies
  (testing "A number child renders as its `str` form, not
            dropped, not escaped (emit-element number? branch)."
    (is (= "<span>42</span>"
           (rf.ssr.emit/render-to-string [:span 42] {}))
        "integer child stringifies")
    (is (= "<span>3.14</span>"
           (rf.ssr.emit/render-to-string [:span 3.14] {}))
        "double child stringifies")
    (is (= "<p>count=7</p>"
           (rf.ssr.emit/render-to-string [:p "count=" 7] {}))
        "a number sits inline alongside a string child")))

(deftest render-to-string-boolean-child-is-dropped
  (testing "A boolean CHILD emits nothing (emit-element
            boolean? branch → \"\"). The ubiquitous
            `[:div (when cond? [:p ...])]` shape yields `false`/`nil` for
            the false arm; both must vanish, not render the word `true`/
            `false`. Distinct from a boolean ATTR VALUE (which `attr-string`
            renders as a bare attr name) — this is the child position."
    (is (= "<p>ab</p>"
           (rf.ssr.emit/render-to-string [:p "a" true "b" false nil] {}))
        "booleans + nil interleaved with strings drop, strings survive")))

;; ===========================================================================
;; escape-attr / escape-html asymmetry (html_helpers.cljc).
;; A deliberate, security-relevant correctness invariant: text-node content
;; escapes `< > & " '` (no raw-tag injection), but attribute VALUES escape
;; ONLY `& "` because `<`/`>` are legal inside a double-quoted attribute
;; value per the HTML5 parser. A regression that over-escaped attrs would
;; corrupt legit values (e.g. a `content` meta carrying `a<b`); one that
;; under-escaped text nodes would open an XSS. Pinned through the full
;; `render-to-string` composition, not the helper in isolation.
;; ===========================================================================

(deftest render-to-string-text-node-escapes-all-five-entities
  (testing "A text-node child escapes `& < > \" '` so no
            raw markup or quote can break out of text position."
    (is (= "<p>&amp;&lt;&gt;&quot;&#39;</p>"
           (rf.ssr.emit/render-to-string [:p "&<>\"'"] {}))
        "all five escapable entities are rewritten in text position")
    (is (= "<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>"
           (rf.ssr.emit/render-to-string [:p "<script>alert(1)</script>"] {}))
        "a literal <script> in text cannot inject a real tag")))

(deftest render-to-string-attr-value-escapes-only-amp-and-quote
  (testing "An attribute VALUE escapes only `&` and `\"`;
            `<` / `>` / `'` are LEGAL inside a double-quoted attr value and
            are emitted verbatim (HTML5 parser rule). This asymmetry with
            text-node escaping is deliberate — over-escaping attrs corrupts
            legit values."
    (is (= "<div data-x=\"a&amp;b\"></div>"
           (rf.ssr.emit/render-to-string [:div {:data-x "a&b"}] {}))
        "`&` in an attr value is escaped to &amp;")
    (is (= "<div data-x=\"&quot;q&quot;\"></div>"
           (rf.ssr.emit/render-to-string [:div {:data-x "\"q\""}] {}))
        "a double-quote in an attr value is escaped to &quot; (else it would
         close the value)")
    (is (= "<div data-x=\"a<b>c\"></div>"
           (rf.ssr.emit/render-to-string [:div {:data-x "a<b>c"}] {}))
        "`<` and `>` are NOT escaped in an attr value — legal per HTML5")
    (is (= "<div data-x=\"it's\"></div>"
           (rf.ssr.emit/render-to-string [:div {:data-x "it's"}] {}))
        "a single-quote is NOT escaped — the value is double-quoted")))

;; ===========================================================================
;; Var-headed component resolution (emit.cljc +
;; streaming.cljc). On the JVM a Var (`#'component`) is `ifn?` but NOT
;; `fn?`, so a `(fn? head)` test would send a Var-headed component past the
;; callable-head arm instead of resolving it to `<span>ok</span>`. The
;; emitter / streaming walker test `ifn?`,
;; which resolves both fns and Var references. These tests pin standard +
;; streaming resolution AND the root-attr threading (render-hash +
;; source-coord) through the Var-head indirection.
;; ===========================================================================

(defn var-component
  "A plain component fn referenced via its Var (`#'var-component`) so the
  emitter's callable-head branch sees an `ifn?`-but-not-`fn?` head on the
  JVM. Returns a DOM-rooted hiccup so root-attr threading has a tag to
  land on."
  [label]
  [:span label])

(deftest render-to-string-var-headed-component
  (testing "A Var-headed component `[#'component & args]`
            is invoked and its hiccup emitted (NOT stringified as EDN)"
    (is (= "<span>ok</span>"
           (rf.ssr.emit/render-to-string [#'var-component "ok"] {}))
        "the Var head resolves: invoked with its args, returned hiccup emits")
    (testing "a Var-headed component nested as a child resolves too"
      (is (= "<ul><span>a</span><span>b</span></ul>"
             (rf.ssr.emit/render-to-string [:ul [#'var-component "a"] [#'var-component "b"]] {}))
          "Var-heads in child position resolve via emit-children → emit-element"))
    (testing "root render-hash threads through the Var head onto the resolved DOM root"
      (let [html (rf.ssr.emit/render-to-string [#'var-component "ok"] {:render-hash "deadbeef"})]
        (is (re-find #"<span [^>]*data-rf-render-hash=\"[^\"]+\">ok</span>" html)
            (str "the root data-rf-render-hash lands on the Var head's resolved"
                 " <span> root; got: " html))))))

(deftest render-to-string-var-headed-registered-view
  (testing "A registered view whose body is a
            Var-headed component resolves (no EDN leak), while the
            render-hash root-attr DOES thread through the Var head onto
            the resolved DOM root. Two levels of callable indirection:
            `(rf/view :id)` reaches the view, whose body is itself
            Var-headed."
    (rf/reg-view ^{:rf/id :rf.ssr-emit-test/var-view} var-view []
      [#'var-component "v"])
    (let [html (rf.ssr.emit/render-to-string [(rf/view :rf.ssr-emit-test/var-view)] {})]
      (is (= "<span>v</span>" html)
          (str "the Var head resolved to <span>v</span> (NOT EDN text); "
               "got: " html)))
    (testing "render-hash threads through the Var-headed view root onto the resolved DOM root"
      (let [html (rf.ssr.emit/render-to-string [(rf/view :rf.ssr-emit-test/var-view)] {:render-hash "deadbeef"})]
        (is (re-find #"<span [^>]*data-rf-render-hash=\"[^\"]+\">v</span>" html)
            (str "the root data-rf-render-hash threads through the view-ref AND "
                 "the Var head onto the resolved <span> root; got: " html))))))

(deftest render-shell-var-headed-component
  (testing "The streaming shell walker resolves a
            Var-headed component just like the non-streaming emitter
            (ifn?, not fn?), recursing on its returned hiccup"
    (let [{:keys [shell-html]} (rf.ssr.streaming/render-shell [#'var-component "streamed"])]
      (is (= "<span>streamed</span>" shell-html)
          (str "the streaming walker resolved the Var head + recursed on its"
               " body; got: " shell-html)))
    (testing "a Var head nested in a DOM tree streams resolved too"
      (let [{:keys [shell-html]} (rf.ssr.streaming/render-shell
                                   [:section [#'var-component "x"]])]
        (is (= "<section><span>x</span></section>" shell-html)
            (str "nested Var head resolved in the shell walk; got: "
                 shell-html))))))

;; ===========================================================================
;; Malformed-head hiccup vector fails loud, never emits raw
;; unescaped output (XSS-class escape bypass)
;;
;; A hiccup vector whose head is a string / nil / number / boolean (not a
;; keyword and not a callable), stringified by an `(str el)` fallthrough,
;; would ship its WHOLE EDN form RAW — a `[nil "<script>…"]` would put a live
;; `<script>` on the wire. Both the sync emitter and the streaming shell walker MUST reject it
;; with `:rf.error/invalid-hiccup-head`; no raw angle-brackets may reach output.
;; ===========================================================================

(deftest emit-rejects-malformed-hiccup-head
  (testing "A nil / string / number / boolean head throws
            :rf.error/invalid-hiccup-head through render-to-string"
    (doseq [el [[nil "<script>alert(1)</script>"]
                ["x" "<img src=x onerror=alert(1)>"]
                [42 "<script>x</script>"]
                [true "<script>y</script>"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/invalid-hiccup-head"
                            (rf.ssr.emit/render-to-string el {}))
          (str "malformed-head vector must fail loud, not emit raw: " (pr-str el))))))

(deftest collection-heads-are-malformed-on-both-paths
  (testing "A vector, map or set head is `ifn?` — a collection looks up its
            argument — but it is not a component. Called as one, each of these
            would emit its lookup result (`div`, `&lt;b&gt;`, `x`) as page
            text. Both emitters raise :rf.error/invalid-hiccup-head instead."
    (doseq [el [[[:div "x"] 0]
                [{:a "<b>"} :a]
                [#{"x"} "x"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/invalid-hiccup-head"
                            (rf.ssr.emit/render-to-string el {}))
          (str "render-to-string refuses a collection head: " (pr-str el)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/invalid-hiccup-head"
                            (rf.ssr.streaming/render-shell el))
          (str "render-shell refuses a collection head: " (pr-str el))))))

;; ===========================================================================
;; `:dangerouslySetInnerHTML` renders as the element's raw body
;;
;; The client hands `{:__html s}` to React, which writes `s` unescaped as the
;; element's body. Both body emitters do the same, in the order the peer
;; serialiser `reagent2.dom.server` uses: a void element ignores the prop, and
;; otherwise the raw body wins over any children.
;; ===========================================================================

(deftest dangerously-set-inner-html-renders-raw-on-both-paths
  (doseq [[label tree expected]
          [["the raw body, unescaped"
            [:div {:dangerouslySetInnerHTML {:__html "<b>x</b>"}}]
            "<div><b>x</b></div>"]
           ["the other attributes stay; the body is not re-escaped"
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
        (str "render-to-string — " label))
    (is (= expected (:shell-html (rf.ssr.streaming/render-shell tree)))
        (str "render-shell — " label))))

;; The body is the markup the prop's value answers for `:__html`, so a value
;; other than the plain map supplies one by answering it through lookup. The
;; Reagent bridge relies on that: stock Reagent 2 keeps the prop only when its
;; value is `reagent.core/unsafe-html`'s tagged value, and
;; `re-frame.adapter.reagent` makes that value answer `:__html`. Reagent is
;; CLJS-only, so the stand-in here is a JVM `ILookup`; the bridge's own value
;; is pinned by `re-frame.adapter-unsafe-html-ssr-cljs-test`.

(deftype LookupHtml [html]
  clojure.lang.ILookup
  (valAt [_ k] (when (= :__html k) html))
  (valAt [_ k not-found] (if (= :__html k) html not-found)))

(deftest dangerously-set-inner-html-reads-any-value-answering-html
  (doseq [[label tree expected]
          [["a value answering :__html renders its markup as the body"
            [:div {:dangerouslySetInnerHTML (->LookupHtml "<b>x</b>")}]
            "<div><b>x</b></div>"]
           ["that body wins over children, as the plain map's does"
            [:div {:dangerouslySetInnerHTML (->LookupHtml "<i>raw</i>")} "ignored"]
            "<div><i>raw</i></div>"]
           ["the plain map renders exactly as before"
            [:div {:dangerouslySetInnerHTML {:__html "<b>x</b>"}}]
            "<div><b>x</b></div>"]
           ["a value answering a nil :__html gives an empty body"
            [:div {:dangerouslySetInnerHTML (->LookupHtml nil)} "ignored"]
            "<div></div>"]
           ["a string answers no :__html and gives an empty body"
            [:div {:dangerouslySetInnerHTML "<b>x</b>"} "ignored"]
            "<div></div>"]
           ["a vector answers no :__html and gives an empty body"
            [:div {:dangerouslySetInnerHTML ["<b>x</b>"]} "ignored"]
            "<div></div>"]]]
    (is (= expected (rf.ssr.emit/render-to-string tree {}))
        (str "render-to-string — " label))
    (is (= expected (:shell-html (rf.ssr.streaming/render-shell tree)))
        (str "render-shell — " label))))

;; ===========================================================================
;; Form-2 raw-fn component renders (never leaks the inner fn's
;; .toString as page text)
;;
;; A Form-2 component (an outer fn returning an inner render fn) resolves to
;; a fn VALUE that, left to fall through to `escape-html`, would stringify the
;; fn's `.toString` (`user$…fn__…@…`) as visible page text. Both the sync
;; emitter and the streaming shell walker MUST invoke the inner render fn
;; (Form-2 semantics) and render its hiccup; a result that is STILL a fn after
;; the single unwrap fails loud with `:rf.error/ssr-nonrenderable-component`.
;; ===========================================================================

(deftest emit-renders-form-2-component
  (testing "A Form-2 component renders its inner hiccup, not the
            inner fn's .toString, through BOTH the sync emit and streaming paths"
    ;; The idiomatic Reagent/UIx Form-2 shapes: a 0-arity inner closing
    ;; over the outer's args, AND a same-arity inner taking the args.
    (let [form2-closed (fn [value] (fn [] [:div value]))
          form2-arg    (fn [_outer-value]
                         (fn [value] [:p (str "v=" value)]))]
      (is (= "<div>hello</div>" (rf.ssr.emit/emit-element [form2-closed "hello"]))
          "sync emit renders the 0-arity-inner Form-2 output")
      (is (= "<div>hello</div>"
             (:shell-html (rf.ssr.streaming/render-shell [form2-closed "hello"])))
          "streaming renders the 0-arity-inner Form-2 output")
      (is (= "<p>v=7</p>" (rf.ssr.emit/emit-element [form2-arg 7]))
          "sync emit renders the same-arity-inner Form-2 output")
      (is (= "<p>v=7</p>"
             (:shell-html (rf.ssr.streaming/render-shell [form2-arg 7])))
          "streaming renders the same-arity-inner Form-2 output")))

  (testing "A component that resolves to a fn even after the
            Form-2 unwrap (deeper than Form-2) fails loud, never leaking a fn"
    (let [deep (fn [x] (fn [] (fn [] [:div x])))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/ssr-nonrenderable-component"
                            (rf.ssr.emit/emit-element [deep "z"]))
          "sync emit fails loud on a deeper-than-Form-2 component")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/ssr-nonrenderable-component"
                            (rf.ssr.streaming/render-shell [deep "z"]))
          "streaming fails loud on a deeper-than-Form-2 component"))))

;; ===========================================================================
;; Form-2 arity adaptation is a DECISION taken before the render
;; runs, never an exception caught after it
;;
;; Probing arity BY EXCEPTION:
;;
;;     (try (apply inner args) (catch ArityException _ (inner)))
;;
;; would enclose execution of PROGRAMMER code, which is two independent
;; defects:
;;
;;   1. An `ArityException` raised INSIDE a correctly-invoked inner render —
;;      an ordinary wrong-arity bug in a helper that render calls — would be
;;      indistinguishable from an invocation-arity mismatch. The renderer
;;      would run the render body a SECOND time and report the retry's
;;      outcome, so a non-pure render would duplicate its effects and the
;;      user's own failure would be replaced by the retry's.
;;   2. A fallback trying only arity ZERO would reject on the JVM an inner
;;      accepting a non-zero PREFIX of the outer's props — valid under the
;;      CLJS/JS extra-argument semantics this helper exists to emulate — so
;;      the same `.cljc` app would render in the browser and fail SSR.
;;
;; The helper selects a compatible call shape from the compiled fn's
;; DECLARED arities and invokes exactly once. Both public server paths share
;; the one resolver (`emit/resolve-component-head`), so every row below
;; asserts through BOTH `emit/emit-element` and `streaming/render-shell`;
;; a behaviour proven through one of them is proven for half the surface.
;;
;; NOTE ON NON-VACUITY: the inners below are FIXED arity where prefix
;; selection is the thing under test (a variadic inner accepts the full arg
;; list and would pass without exercising selection at all), and the
;; double-invocation rows COUNT their calls — an idempotent test double would
;; be green against a catch-and-retry helper and proves nothing.
;; ===========================================================================

(deftest emit-form-2-inner-body-arity-exception-propagates-once
  (testing "A zero-arity inner invoked with zero args REACHES its
            body, and an ArityException raised THERE is not an invocation
            mismatch: the render runs exactly ONCE, on BOTH server paths"
    ;; The shape a catch-and-retry helper could re-enter successfully. The
    ;; first call matches, so the body runs and throws; a catch-and-retry would
    ;; catch that and run the body a SECOND time. ONLY a counting inner detects
    ;; that — the exception is the same either way, so an idempotent test
    ;; double would be green against a catch-and-retry helper and proves
    ;; nothing.
    ;;
    ;; The arity must match on the FIRST call for this to bite: a zero-arity
    ;; inner under a one-arg component throws before entering the body, the
    ;; counter never reaches 2, and the row is vacuous.
    (let [calls       (atom 0)
          needs-two   (fn [a b] [:span a b])
          form2-buggy (fn [] (fn []
                               (swap! calls inc)
                               ;; Deliberately wrong arity — this call IS the fixture payload: the row needs
                               ;; a genuine ArityException raised INSIDE the render body. clj-kondo is right
                               ;; that it is a mismatch and cannot know it is intentional.
                               [:div #_{:clj-kondo/ignore [:invalid-arity]}
                                     (needs-two "x")]))]
      (reset! calls 0)
      (is (thrown? clojure.lang.ArityException
                   (rf.ssr.emit/emit-element [form2-buggy]))
          "sync emit propagates the inner body's own ArityException")
      (is (= 1 @calls)
          "sync emit invoked the inner render EXACTLY once (a
           catch-and-retry would reach 2)")
      (reset! calls 0)
      (is (thrown? clojure.lang.ArityException
                   (rf.ssr.streaming/render-shell [form2-buggy]))
          "streaming propagates the inner body's own ArityException")
      (is (= 1 @calls)
          "streaming invoked the inner render EXACTLY once (a
           catch-and-retry would reach 2)")))

  (testing "The inner body's ORIGINAL failure propagates
            unchanged rather than being replaced by the retry's"
    ;; A SAME-arity inner. A catch-and-retry helper would catch the body's
    ;; `Wrong number of args (1)` and re-invoke at arity ZERO, which this
    ;; arity-1 inner rejects — so the programmer would be shown
    ;; `Wrong number of args (0)` about a call they never wrote and their
    ;; real bug would vanish. The arg COUNT in the message is the discriminator
    ;; here; the invocation counter cannot tell these two apart.
    (let [calls       (atom 0)
          needs-two   (fn [a b] [:span a b])
          form2-buggy (fn [_outer-value] (fn [value]
                                (swap! calls inc)
                                ;; Deliberately wrong arity — this call IS the fixture payload: the row needs
                                ;; a genuine ArityException raised INSIDE the render body. clj-kondo is right
                                ;; that it is a mismatch and cannot know it is intentional.
                                [:div #_{:clj-kondo/ignore [:invalid-arity]}
                                      (needs-two value)]))]
      (reset! calls 0)
      (is (thrown-with-msg? clojure.lang.ArityException
                            #"Wrong number of args \(1\)"
                            (rf.ssr.emit/emit-element [form2-buggy "x"]))
          "sync emit surfaces the render's own failing call, not a
           fabricated zero-arity retry")
      (is (= 1 @calls) "sync emit invoked the inner render exactly once")
      (reset! calls 0)
      (is (thrown-with-msg? clojure.lang.ArityException
                            #"Wrong number of args \(1\)"
                            (rf.ssr.streaming/render-shell [form2-buggy "x"]))
          "streaming surfaces the render's own failing call, not a
           fabricated zero-arity retry")
      (is (= 1 @calls) "streaming invoked the inner render exactly once"))))

(deftest emit-form-2-variadic-inner-body-failure-is-not-swallowed
  (testing "A zero-arity retry would SUCCEED on a variadic
            inner, silently replacing a failing render with different HTML.
            The programmer's failure must surface on BOTH paths instead"
    ;; With args the render is reached and its helper bug throws; with NO
    ;; args it returns a different tree. Under a catch-and-retry the
    ;; no-args branch is what would ship — a silent wrong render, no exception
    ;; anywhere, which is the severest form of defect 1.
    (let [needs-two (fn [a b] [:span a b])
          form2     (fn [x] (fn [& xs]
                              (if (seq xs)
                                ;; Deliberately wrong arity — this call IS the fixture payload: the row needs
                                ;; a genuine ArityException raised INSIDE the render body. clj-kondo is right
                                ;; that it is a mismatch and cannot know it is intentional.
                                [:div #_{:clj-kondo/ignore [:invalid-arity]}
                                      (needs-two x)]
                                [:p "zero-arity retry reached this"])))]
      (is (thrown? clojure.lang.ArityException
                   (rf.ssr.emit/emit-element [form2 "x"]))
          "sync emit surfaces the render's own failure rather than
           re-entering the variadic inner with no args")
      (is (thrown? clojure.lang.ArityException
                   (rf.ssr.streaming/render-shell [form2 "x"]))
          "streaming surfaces the render's own failure rather than
           re-entering the variadic inner with no args"))))

(deftest emit-passes-form-2-variadic-inner-the-whole-arg-seq
  (testing "A VARIADIC inner receives the complete original
            argument sequence, once, preserving ordinary Reagent/CLJS
            semantics on BOTH server paths"
    ;; `(fn [& xs] …)` accepts any arity from zero up; selection must hand it
    ;; the FULL list, not confuse \"accepts any arity\" with \"accepts zero\".
    (let [calls (atom 0)
          seen  (atom nil)
          form2 (fn [_a _b] (fn [& xs]
                              (swap! calls inc)
                              (reset! seen (vec xs))
                              [:p (str/join "," xs)]))]
      (reset! calls 0)
      (reset! seen nil)
      (is (= "<p>a,b</p>" (rf.ssr.emit/emit-element [form2 "a" "b"]))
          "sync emit renders the variadic inner's output")
      (is (= ["a" "b"] @seen)
          "sync emit passed the variadic inner the complete arg sequence")
      (is (= 1 @calls)
          "sync emit invoked the variadic inner exactly once")
      (reset! calls 0)
      (reset! seen nil)
      (is (= "<p>a,b</p>" (:shell-html (rf.ssr.streaming/render-shell [form2 "a" "b"])))
          "streaming renders the variadic inner's output")
      (is (= ["a" "b"] @seen)
          "streaming passed the variadic inner the complete arg sequence")
      (is (= 1 @calls)
          "streaming invoked the variadic inner exactly once"))))

;; ===========================================================================
;; BOOLEAN ATTRIBUTE-VALUE CLASSES.
;;
;; Branching on the VALUE alone — `true` → a bare attribute name,
;; `false`/`nil` → omitted — would apply one rule to attributes that do not
;; share one. HTML/React carries three classes, pinned
;; by Spec 004B §Booleans and their neighbours from a row-by-row react-dom
;; 19.2.0 probe:
;;
;;   stringify  `aria-*`, `data-*`, and the booleanish family
;;              (`contentEditable` / `draggable` / `spellCheck`) — `true` AND
;;              `false` both reach markup as `="true"` / `="false"`. ARIA is
;;              not boolean HTML: `aria-expanded="false"` is a DIFFERENT state
;;              from the attribute being absent, so dropping the `false`
;;              would make server markup assert the OPPOSITE of what the
;;              author wrote, and assistive technology would read a different
;;              UI than the client render shows.
;;   presence   the true boolean attributes (`disabled`, `checked`, …) and the
;;              overloaded booleans (`download`, `capture`) — presence IS
;;              truth and `disabled="false"` is still TRUTHY to a browser, so
;;              `false` MUST stay omitted. Emitting it in the other direction
;;              would disable the control.
;;   ordinary   everything else — a boolean never reaches markup at all,
;;              rather than becoming an arbitrary bare attribute.
;;
;; Neither direction is caught downstream: the render-tree hash is computed
;; over the TREE, so server and client agree on it while the HTML differs, and
;; 011 §What React-native adoption does not catch records that React neither
;; patches nor reports attribute-only hydration mismatches.
;;
;; Every react-dom-evidenced row runs through BOTH hiccup SSR modes —
;; `render-to-string` and `streaming/render-shell`, which share one
;; `attr-string` — and through the structural-tree serialiser, each checked
;; against react-dom's own class, in
;; `re-frame.ssr-boolean-attr-react-parity-test`.
;; ===========================================================================

(deftest render-to-string-presence-classes-are-preserved
  (testing "A nil on an ORDINARY attribute is dropped, beside a presence
            attribute that stays a bare name"
    (is (= "<button disabled>Go</button>"
           (rf.ssr.emit/render-to-string [:button {:disabled true :title nil} "Go"] {}))
        "nil on an ordinary attribute is dropped, beside a presence attr and text")))
