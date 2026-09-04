(ns re-frame.ssr-emit-test
  "Spec 011 §XSS at output boundaries — the strip-prop rule (rf2-dwds9)
  driven through the FULL emit composition, not just `attr-string` in
  isolation. Per rf2-usio0 (testcov audit ai/findings/2026-05-21-testcov-ssr.md
  §G1).

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
       emitter, including the void-element branch, the registered-view
       branch, fragments, and the root-attrs (`:emit-hash?`) injection.
    2. `re-frame.ssr.streaming/render-shell` — the streaming shell walk,
       whose `walk-dom-tag` re-derives attrs via `emit/attr-string`.

  so a reorder/bypass regression at EITHER composed callsite is caught.

  JVM-only — the strip rule is platform-neutral .cljc, but the per-attr
  proof already runs on both platforms (`ssr_attr_filter_test`); driving
  the JVM emit composition here is enough to pin the composition order."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.emit :as emit]
            [re-frame.ssr.streaming :as streaming]
            [re-frame.ssr.test-fixture :as tf]
            [re-frame.ssr.ui-tree :as ui-tree]))

(use-fixtures :each tf/reset-runtime)

;; ---------------------------------------------------------------------------
;; rf2-xbvzh — shared raw-text emission across ALL three SSR paths.
;; ---------------------------------------------------------------------------

(defn- v1
  "Wrap a structural node as a version-1 tree for `emit-ui-tree`."
  [node]
  (assoc node :rf.ui/tree-version 1))

(defn- assert-emitters-agree
  "The load-bearing cross-emitter proof (rf2-xbvzh ruling Option (a)): for a
  raw-text `tag` (`:script`/`:style`) carrying a single string `content`, all
  three SSR paths — the sync hiccup emitter, the streaming shell walker, and
  the S5 structural serialiser — emit the SAME `expected-inner` body between
  the tags. `expected-inner` is the raw-text body AFTER the closing-sequence
  rewrite (verbatim but for that rewrite; NO entity escaping)."
  [tag content expected-inner]
  (let [tag-name (name tag)
        expected (str "<" tag-name ">" expected-inner "</" tag-name ">")]
    (is (= expected (emit/render-to-string [tag content] {}))
        (str "sync render-to-string byte-mismatch for <" tag-name ">"))
    (is (str/includes? (:shell-html (streaming/render-shell [:div [tag content]]))
                       expected)
        (str "streaming render-shell byte-mismatch for <" tag-name ">"))
    (is (= expected (ui-tree/emit-ui-tree (v1 {:tag tag :children [content]})))
        (str "emit-ui-tree byte-mismatch for <" tag-name ">"))))

;; ===========================================================================
;; G1 — strip-prop XSS rule through `render-to-string` / `emit-element`
;; ===========================================================================

(deftest render-to-string-strips-event-handler-props
  (testing "rf2-usio0 / rf2-dwds9 — an `on*` event-handler prop fed
            through the FULL `render-to-string` emit composition (not
            `attr-string` in isolation) is dropped at emit time. Pins
            that the strip survives `emit-element`'s attr path."
    (testing ":on-click (kebab) is stripped through render-to-string"
      (is (= "<div id=\"x\"></div>"
             (emit/render-to-string [:div {:on-click "alert(1)" :id "x"}] {}))))

    (testing ":onClick (camelCase) is stripped through render-to-string"
      (is (= "<div id=\"x\"></div>"
             (emit/render-to-string [:div {:onClick "alert(1)" :id "x"}] {}))))

    (testing "the stripped handler value never appears in the output
              string — belt-and-braces against a partial-emit leak"
      (let [html (emit/render-to-string
                   [:div {:onMouseDown "steal()" :id "x"}] {})]
        (is (not (str/includes? html "steal"))
            "the handler body must not survive anywhere in the markup")
        (is (not (str/includes? html "onMouseDown"))
            "the handler attribute name must not survive either")))

    (testing "a div whose ONLY attr is a stripped handler emits a clean
              open tag — no stray space, no bare attr"
      (is (= "<div></div>"
             (emit/render-to-string [:div {:on-click "f"}] {}))))))

(deftest render-to-string-strips-lowercase-touch-handlers
  (testing "rf2-cv165 — the lower-case W3C Touch Events L2 GlobalEventHandlers
            (`ontouchstart` / `ontouchmove` / `ontouchend` / `ontouchcancel`)
            are stripped through the FULL `render-to-string` emit composition.
            These have no upper-case tail char and no hyphen, so the structural
            `event-handler-name-re` CANNOT catch them — the allowlist arm is
            the only thing that strips them. Reverting the four allowlist
            entries makes this go RED (the JVM-side counterpart of the
            security tier's render-to-string assertion)."
    (doseq [k [:ontouchstart :ontouchmove :ontouchend :ontouchcancel]]
      (testing (str k " is stripped through render-to-string")
        (let [html (emit/render-to-string
                     [:div {k "alert(document.cookie)" :id "x"} [:p "body"]] {})]
          (is (str/includes? html "id=\"x\"")
              (str "the legit attr should survive for " k))
          (is (str/includes? html "<p>body</p>")
              (str "the child should render for " k))
          (is (not (str/includes? (str/lower-case html) (name k)))
              (str "touch handler name " k " leaked into wire HTML: " html))
          (is (not (str/includes? html "alert(document.cookie)"))
              (str "touch handler payload reached wire HTML for " k ": "
                   html)))))))

(deftest render-to-string-strips-function-valued-props
  (testing "rf2-usio0 / rf2-dwds9 — a function-valued prop has no HTML
            serialisation and is dropped through the full emit
            composition."
    (is (= "<div id=\"x\"></div>"
           (emit/render-to-string [:div {:title (fn [_] :handler) :id "x"}] {})))

    (testing "fn value is stripped even on an innocuous key name"
      (is (= "<span></span>"
             (emit/render-to-string [:span {:data-cb (fn [] nil)}] {}))))))

(deftest render-to-string-drops-prototype-pollution-keys
  (testing "rf2-usio0 / rf2-dwds9 — reserved prototype-pollution keys
            (`__proto__` / `constructor` / `prototype`) are dropped
            through `render-to-string` before they reach the host
            createElement-equivalent on hydration."
    (doseq [k ["__proto__" "constructor" "prototype"]]
      (testing (str "`" k "` is dropped through render-to-string")
        (is (= "<div id=\"x\"></div>"
               (emit/render-to-string
                 [:div {(keyword k) "polluted" :id "x"}] {}))
            (str k " must not survive to wire output via the emit path"))))

    (testing "the match is case-insensitive at the composed callsite too"
      (is (= "<div id=\"x\"></div>"
             (emit/render-to-string
               [:div {(keyword "Constructor") "polluted" :id "x"}] {}))))))

(deftest render-to-string-strips-props-on-void-element
  (testing "rf2-usio0 — the strip composes with the VOID-element branch
            of `emit-element` (emit.cljc:322-323), not just the
            open/close branch. An `on*` handler on an <input> is dropped
            and the void tag self-closes cleanly."
    (is (= "<input id=\"x\">"
           (emit/render-to-string
             [:input {:on-change "x()" :id "x"}] {})))
    (let [html (emit/render-to-string
                 [:img {:onError "alert(1)" :src "/a.png"}] {})]
      (is (str/includes? html "src=\"/a.png\"") "the legit attr survives")
      (is (not (str/includes? html "onError")) "the handler is stripped")
      (is (not (str/includes? html "alert")) "the handler body is gone"))))

(deftest render-to-string-strips-props-through-registered-view-root
  (testing "rf2-usio0 — the strip composes with the CALLABLE-head
            resolution branch. A view whose ROOT DOM element carries a
            hostile handler must still emit stripped — the callable-head
            indirection must not bypass `attr-string`."
    (rf/reg-view ^{:rf/id :test/hostile-root} hostile-root-view []
      [:div {:on-click "alert(document.cookie)" :id "v"}
       [:p "safe body"]])
    (let [html (emit/render-to-string [(rf/view :test/hostile-root)] {})]
      (is (str/includes? html "<p>safe body</p>")
          "the view body still renders")
      (is (str/includes? html "id=\"v\"")
          "the legit root attr survives")
      (is (not (str/includes? html "on-click"))
          "the root handler is stripped through the callable-head branch")
      (is (not (str/includes? html "alert(document.cookie)"))
          "the handler body never reaches the wire"))))

(deftest render-to-string-strips-props-when-root-attrs-injected
  (testing "rf2-usio0 — the strip survives the rf2-lxwse root-attrs
            (`:emit-hash?`) injection path. The injected
            `data-rf-render-hash` lands while the user's hostile handler
            on the SAME root element is dropped — `merge-root-attrs`
            feeds into the same `attr-string` strip."
    (let [html (emit/render-to-string
                 [:div {:onClick "alert(1)" :id "root"} [:p "x"]]
                 {:emit-hash? true})]
      (is (str/includes? html "data-rf-render-hash=")
          "the render-hash root attr was injected")
      (is (str/includes? html "id=\"root\"")
          "the legit user attr survives alongside the injected hash")
      (is (not (str/includes? html "onClick"))
          "the user's handler on the hash-bearing root is still stripped")
      (is (not (str/includes? html "alert"))
          "no handler body leaks through the injection composition"))))

(deftest render-to-string-strips-deep-nested-handler
  (testing "rf2-usio0 — the strip runs at EVERY emit-element descent, not
            only the root. A handler buried several levels deep is
            dropped — `emit-children` re-enters `emit-element` per child."
    (let [html (emit/render-to-string
                 [:div
                  [:section
                   [:ul
                    [:li {:onClick "deep()" :class "item"} "deep"]]]]
                 {})]
      (is (str/includes? html "class=\"item\"") "the deep legit attr survives")
      (is (str/includes? html ">deep</li>") "the deep text survives")
      (is (not (str/includes? html "onClick")) "the deep handler is stripped")
      (is (not (str/includes? html "deep()")) "the deep handler body is gone"))))

;; ===========================================================================
;; G1 — strip-prop XSS rule through `streaming/render-shell`'s walk
;; ===========================================================================

(deftest render-shell-strips-event-handler-props
  (testing "rf2-usio0 / rf2-dwds9 — the streaming shell walker
            (`walk-dom-tag`, streaming.cljc:225-227) re-derives attrs via
            `emit/attr-string`, so an `on*` handler on a shell DOM
            element must be stripped through the shell walk too — not
            just through the non-streaming `render-to-string`."
    (let [tree [:div {:on-click "alert(1)" :id "x"}
                [:p "shell body"]]
          {:keys [shell-html]} (streaming/render-shell tree)]
      (is (str/includes? shell-html "<p>shell body</p>")
          "the shell body renders")
      (is (str/includes? shell-html "id=\"x\"")
          "the legit attr survives the walk")
      (is (not (str/includes? shell-html "on-click"))
          "the handler is stripped through walk-dom-tag")
      (is (not (str/includes? shell-html "alert(1)"))
          "the handler body never reaches the shell HTML"))))

(deftest render-shell-strips-function-and-proto-props
  (testing "rf2-usio0 / rf2-dwds9 — fn-valued + prototype-pollution
            props are also stripped through the streaming walk."
    (let [tree [:div {:title (fn [] nil)
                      :__proto__ "polluted"
                      :id "x"}
                [:span "ok"]]
          {:keys [shell-html]} (streaming/render-shell tree)]
      (is (str/includes? shell-html "id=\"x\"") "the legit attr survives")
      (is (str/includes? shell-html "<span>ok</span>") "body renders")
      (is (not (str/includes? shell-html "__proto__"))
          "the prototype-pollution key is stripped through the walk")
      (is (not (str/includes? shell-html "polluted"))
          "the prototype-pollution value never reaches the shell"))))

(deftest render-shell-strips-handler-on-void-element
  (testing "rf2-usio0 — the streaming walk's void-element branch
            (streaming.cljc:224) also runs the strip. An `on*` handler on
            an <input> in the shell is dropped."
    (let [tree [:form
                [:input {:onChange "steal()" :name "q"}]]
          {:keys [shell-html]} (streaming/render-shell tree)]
      (is (str/includes? shell-html "name=\"q\"") "the legit attr survives")
      (is (not (str/includes? shell-html "onChange")) "the handler is stripped")
      (is (not (str/includes? shell-html "steal")) "the handler body is gone"))))

(deftest render-shell-strips-handler-buried-near-suspense-boundary
  (testing "rf2-usio0 — a hostile handler on a shell element that SITS
            ALONGSIDE a :rf/suspense-boundary is stripped through the
            walk, while the boundary still registers its continuation.
            Pins that the strip composes with the suspense-walk path,
            not only plain DOM descent."
    (let [tree [:div {:onClick "alert(1)" :id "outer"}
                [:rf/suspense-boundary
                 {:id :sb :fallback [:p "loading"]}
                 [:p "body"]]]
          {:keys [shell-html continuations]} (streaming/render-shell tree)]
      (is (= 1 (count continuations))
          "the boundary still registered its continuation")
      (is (str/includes? shell-html "id=\"outer\"")
          "the legit attr on the boundary-bearing element survives")
      (is (not (str/includes? shell-html "onClick"))
          "the handler on the boundary-bearing element is stripped")
      (is (not (str/includes? shell-html "alert(1)"))
          "the handler body never reaches the shell"))))

;; ===========================================================================
;; rf2-xbvzh (supersedes the rf2-ee38b.10 refusal) — ordinary inline
;; <script>/<style> STRING content is AUTHOR content: emitted VERBATIM with
;; only React's context-safe closing-sequence rewrite, NO entity escaping and
;; NO refusal. The refusal (`:rf.error/ssr-raw-text-in-body`) was pushing real
;; content into the genuinely-unguarded trusted shell opts, which is strictly
;; LESS safe than a guarded render-tree element. ONE raw-text semantics now
;; holds across all three SSR paths (sync hiccup, streaming hiccup, S5
;; serialiser); the DATA-payload channels keep their stricter escapes.
;; ===========================================================================

(deftest raw-text-body-content-is-emitted-verbatim-not-escaped
  (testing "rf2-xbvzh — literal JS/CSS operators and `&` are NOT entity-escaped
            (escape-html would corrupt them), and all three SSR paths agree."
    ;; escape-html would have produced `if (a &lt; b)` / `a &gt; .b` — a
    ;; corrupted script/style body. Raw text emits them literally.
    (assert-emitters-agree :script "if (a < b) { x() }" "if (a < b) { x() }")
    (assert-emitters-agree :script "a & b && c" "a & b && c")
    (assert-emitters-agree :style "a > .b { color: red }" "a > .b { color: red }")
    (assert-emitters-agree :style "x & y" "x & y")))

(deftest raw-text-body-closing-sequence-is-rewritten-case-insensitively
  (testing "rf2-xbvzh — an embedded `(<|</)script`/`style` closing sequence is
            rewritten to a context-safe spelling so the raw-text parser cannot
            terminate the element early, matching react-dom/server's
            scriptRegex/styleRegex byte-for-byte (case-insensitive), and all
            three SSR paths agree."
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
  (testing "rf2-xbvzh + rf2-hzttr finding 3 — an UPPER/MIXED-case <SCRIPT> /
            <Style> tag is still classified as raw text (author case preserved
            in the emitted markup), so its literal `<` is NOT entity-escaped."
    (doseq [tag [:SCRIPT :Script :sCrIpT]]
      (is (= (str "<" (name tag) ">if (a < b)</" (name tag) ">")
             (emit/render-to-string [tag "if (a < b)"] {}))
          (str tag " body emitted as raw text, not escaped")))
    (doseq [tag [:STYLE :Style]]
      (is (= (str "<" (name tag) ">a > .b { }</" (name tag) ">")
             (emit/render-to-string [tag "a > .b { }"] {}))
          (str tag " body emitted as raw text, not escaped")))))

(deftest raw-text-json-island-round-trips-through-json-parse
  (testing "rf2-xbvzh — a JSON data island written as ordinary <script> string
            content is emitted raw with the closing-sequence rewrite; the
            embedded `</script>` cannot terminate the element, and because
            `\\u0073` is ALSO a valid JSON string escape the payload round-trips
            back through JSON.parse."
    (let [json "{\"@type\":\"WebSite\",\"u\":\"a</script>b\"}"
          out  (emit/render-to-string
                 [:script {:type "application/ld+json"} json] {})]
      (is (= (str "<script type=\"application/ld+json\">"
                  "{\"@type\":\"WebSite\",\"u\":\"a</\\u0073cript>b\"}"
                  "</script>")
             out)
          "the embedded </script> is rewritten to </\\u0073cript>, element not terminated")
      ;; Reversing `s` -> `s` (exactly what a JS engine / JSON.parse does
      ;; when decoding the escape) restores the author's JSON verbatim — the
      ;; round-trip the ruling requires.
      (let [body    (-> out
                        (str/replace-first "<script type=\"application/ld+json\">" "")
                        (str/replace-first "</script>" ""))
            decoded (str/replace body "\\u0073" "s")]
        (is (= json decoded)
            "decoding \\u0073 -> s restores the original JSON island (JSON.parse round-trip)")))))

(deftest data-payload-json-ld-channel-uses-stricter-escape-unchanged
  (testing "rf2-xbvzh — the DATA-payload channel (reg-head JSON-LD) is
            UNCHANGED: the SAME `</script>` payload keeps the stricter
            data-aware `\\u003c` escape, DISTINCT from the author-content
            raw-text closing-sequence rewrite. Removing the body refusal did
            NOT touch the data-payload escape path."
    (let [html (rf/head-model->html
                 {:json-ld [{"@type"    "Article"
                             "headline" "</script><script>alert(1)</script>"}]})]
      (is (str/includes? html "\\u003c/script>\\u003cscript>")
          "JSON-LD data payload still escapes every `<` as the JSON `\\u003c` escape")
      (is (not (str/includes? html "</\\u0073cript>"))
          "the data channel does NOT use the author-content raw-text rewrite")
      (is (not (str/includes? html "</script><script>alert"))
          "the hostile breakout literal does not survive on the data channel"))))

(deftest render-to-string-allows-empty-or-element-only-raw-text-tags
  (testing "rf2-xbvzh — a raw-text tag with NO string child is inert and emits
            unchanged; the raw-text emission only applies to string content."
    (is (= "<script></script>"
           (emit/render-to-string [:script] {}))
        "empty <script> is fine")
    (is (= "<style></style>"
           (emit/render-to-string [:style {}] {}))
        "empty <style> with an attrs map is fine")
    (is (= "<script src=\"/main.js\"></script>"
           (emit/render-to-string [:script {:src "/main.js"}] {}))
        "an attribute-only <script> (the common external-script shape) is fine")))

;; ===========================================================================
;; rf2-hzttr finding 3 — void classification is CASE-INSENSITIVE.
;; `validate-tag-name!` admits upper/mixed-case names, but the void element
;; SET is keyed lower-case. Without normalisation, `[:BR]` was emitted as a
;; non-void <BR></BR> pair. Same void issue in the streaming walker. (The
;; case-insensitive RAW-TEXT classification is exercised by the rf2-xbvzh
;; emission tests above — `raw-text-classification-is-case-insensitive-on-the-tag`
;; and `render-shell-raw-text-is-case-insensitive`.)
;; ===========================================================================

(deftest render-to-string-void-classification-is-case-insensitive
  (testing "rf2-hzttr finding 3 — an UPPER/MIXED-case void tag is recognised
            as void and self-closes; it must NOT emit a spurious closing tag.
            HTML5 tag names are case-insensitive."
    (testing "[:BR] self-closes (no </BR>)"
      (let [html (emit/render-to-string [:BR] {})]
        (is (= "<BR>" html)
            "void classification is case-insensitive; author case preserved")
        (is (not (str/includes? html "</BR>"))
            "no spurious closing tag for an upper-case void element")))
    (testing "[:Img …] self-closes with its attrs"
      (let [html (emit/render-to-string [:Img {:src "/a.png"}] {})]
        (is (= "<Img src=\"/a.png\">" html))
        (is (not (str/includes? html "</Img>")))))
    (testing "[:INPUT …] self-closes"
      (is (= "<INPUT name=\"q\">"
             (emit/render-to-string [:INPUT {:name "q"}] {}))))
    (testing "a non-void upper-case tag still emits an open+close pair"
      (is (= "<DIV>x</DIV>"
             (emit/render-to-string [:DIV "x"] {}))
          "case-folding only affects the void/raw-text classification, not
           which tags are void"))))

(deftest render-shell-void-classification-is-case-insensitive
  (testing "rf2-hzttr finding 3 — the streaming walker mirrors the
            case-insensitive void classification (streaming.cljc:284). A
            `[:BR]` in the shell must self-close, not emit <BR></BR>."
    (let [{:keys [shell-html]} (streaming/render-shell
                                 [:div [:BR] [:Img {:src "/a.png"}]])]
      (is (str/includes? shell-html "<BR>"))
      (is (not (str/includes? shell-html "</BR>"))
          "upper-case void element self-closes in the streaming shell")
      (is (str/includes? shell-html "<Img src=\"/a.png\">"))
      (is (not (str/includes? shell-html "</Img>"))))))

(deftest render-shell-raw-text-is-case-insensitive
  (testing "rf2-xbvzh — the streaming walk classifies raw-text tags
            case-insensitively too: an UPPER/MIXED-case <STYLE>/<SCRIPT> body
            is emitted as raw text (author case preserved), NOT entity-escaped
            and NOT refused."
    (let [{:keys [shell-html]} (streaming/render-shell
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
;; rf2-ee38b.10 — Reagent-native interop head `:>` cannot be statically
;; rendered server-side (no React on the JVM); fail loud rather than dump
;; the component+props as raw text.
;; ===========================================================================

(deftest render-to-string-rejects-reagent-native-head
  (testing "rf2-ee38b.10 — `[:> Component {props} child]` throws
            :rf.error/ssr-reagent-native-head instead of stringifying the
            component ref + dumping the props map as raw EDN into markup."
    (let [some-component (fn [_props] [:div "react"])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/ssr-reagent-native-head"
                            (emit/render-to-string
                              [:> some-component {:prop "v"} [:span "child"]] {})))
      (testing "the props map is never emitted as raw text on the wire"
        (let [thrown (try (emit/render-to-string
                            [:> some-component {:secret "leak-me"}] {})
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (instance? clojure.lang.ExceptionInfo thrown))
          (is (= :rf.error/ssr-reagent-native-head
                 (:rf.error/id (ex-data thrown)))))))))

(deftest render-shell-rejects-reagent-native-head
  (testing "rf2-ee38b.10 — the streaming walk routes `:>` through the same
            single throw (no raw component+props splice)."
    (let [some-component (fn [_props] [:div "react"])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/ssr-reagent-native-head"
                            (streaming/render-shell
                              [:div [:> some-component {:prop "v"}]]))))))

(deftest render-to-string-still-handles-fragment-head
  (testing "rf2-ee38b.10 — splitting `:>` out of the `:<>` branch leaves
            the fragment head working: children splice with no wrapper."
    (is (= "<p>a</p><p>b</p>"
           (emit/render-to-string [:<> [:p "a"] [:p "b"]] {})))))

(deftest render-hash-threads-through-fragment-root
  (testing "rf2-58zvy1 finding 2 — root-attrs (the render-hash marker)
            thread through a `:<>` fragment ROOT onto the first DOM-tag
            child exactly once. The prior plain `emit-children` on the
            fragment branch dropped root-attrs, so a fragment-rooted SSR
            tree lost the `data-rf-render-hash` the emitter docstring
            promises (and that the emitter/Ring hash contract depends on)."
    (testing ":emit-hash? lands data-rf-render-hash on the fragment's first child"
      (is (re-matches #"<div data-rf-render-hash=\"[0-9a-f]+\">x</div>"
                      (emit/render-to-string [:<> [:div "x"]] {:emit-hash? true}))
          "single-child fragment root gets the marker on the div"))
    (testing "the marker lands on the FIRST DOM child only, not every child"
      (let [html (emit/render-to-string [:<> [:div "a"] [:div "b"]] {:emit-hash? true})]
        (is (re-matches #"<div data-rf-render-hash=\"[0-9a-f]+\">a</div><div>b</div>"
                        html)
            (str "exactly one data-rf-render-hash, on the first div; got: " html))
        (is (= 1 (count (re-seq #"data-rf-render-hash=" html)))
            "marker appears exactly once across the fragment's children")))
    (testing "an explicit :render-hash threads through the fragment root"
      (is (= "<div data-rf-render-hash=\"deadbeef\">x</div>"
             (emit/render-to-string [:<> [:div "x"]] {:render-hash "deadbeef"}))
          "the supplied hash drives the root-attr marker through the fragment"))
    (testing "nested fragments keep threading the marker down to the first DOM tag"
      (is (re-matches #"<div data-rf-render-hash=\"[0-9a-f]+\">y</div>"
                      (emit/render-to-string [:<> [:<> [:div "y"]]] {:emit-hash? true}))
          "a fragment whose first child is a fragment still places the marker"))
    (testing "no opts → no marker (fragment branch unchanged when root-attrs nil)"
      (is (= "<div>x</div>"
             (emit/render-to-string [:<> [:div "x"]] {}))
          "without :emit-hash?/:render-hash the fragment root emits no marker"))))

(deftest render-hash-threads-through-lazy-seq-root
  (testing "rf2-a73idu — root-attrs (the render-hash marker) thread through a
            `lazy-seq` / list ROOT onto the first DOM-tag element, per Spec 011
            §Source-coord annotation / §Hydration-mismatch detection (a
            lazy-seq root is 'passed through the injection'). The prior
            `(sequential? el)` branch used plain `emit-children`, DROPPING
            root-attrs, so a lazy-seq-rooted tree silently lost its
            data-rf-render-hash marker."
    (testing ":emit-hash? lands the marker on a (list …) root's first DOM child"
      (is (re-matches #"<div data-rf-render-hash=\"[0-9a-f]+\">x</div>"
                      (emit/render-to-string (list [:div "x"]) {:emit-hash? true}))
          "single-child list root gets the marker on the div"))
    (testing ":emit-hash? lands the marker on a (map …) lazy-seq root"
      (is (re-matches #"<div data-rf-render-hash=\"[0-9a-f]+\">1</div>"
                      (emit/render-to-string (map (fn [i] [:div i]) [1]) {:emit-hash? true}))
          "a lazy-seq produced by map gets the marker on its first DOM child"))
    (testing "the marker lands on the FIRST DOM child only across a multi-child seq"
      (let [html (emit/render-to-string (for [i [1 2]] [:p i]) {:emit-hash? true})]
        (is (re-matches #"<p data-rf-render-hash=\"[0-9a-f]+\">1</p><p>2</p>" html)
            (str "exactly one marker, on the first <p>; got: " html))
        (is (= 1 (count (re-seq #"data-rf-render-hash=" html)))
            "marker appears exactly once across the seq's children")))
    (testing "an explicit :render-hash threads through the lazy-seq root"
      (is (= "<div data-rf-render-hash=\"deadbeef\">x</div>"
             (emit/render-to-string (list [:div "x"]) {:render-hash "deadbeef"}))
          "the supplied hash drives the marker through the seq root"))
    (testing "no opts → no marker (seq branch unchanged when root-attrs nil)"
      (is (= "<div>x</div>"
             (emit/render-to-string (list [:div "x"]) {}))
          "without :emit-hash?/:render-hash the lazy-seq root emits no marker"))))

;; ===========================================================================
;; rf2-bee5i — :rf/suspense-boundary is a streaming-only marker. The standard
;; emitter must REJECT it (fail loud, parallel to :>) rather than emit a
;; phantom <suspense-boundary> DOM element — its name passes the tag grammar,
;; so without the guard it would serialise the {:id … :fallback …} attrs as
;; bogus attributes and the subtree as bogus children.
;; ===========================================================================

(deftest render-to-string-rejects-suspense-boundary-outside-stream
  (testing "rf2-bee5i — `[:rf/suspense-boundary {:id … :fallback …} child]`
            reaching render-to-string outside a stream throws
            :rf.error/ssr-suspense-boundary-outside-stream instead of
            emitting a phantom <suspense-boundary> DOM element."
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf.error/ssr-suspense-boundary-outside-stream"
                          (emit/render-to-string
                            [:rf/suspense-boundary
                             {:id :b1 :fallback [:span "loading"]}
                             [:div "resolved"]]
                            {})))
    (testing "the marker's id/fallback/subtree never reach the wire as
              a bogus <suspense-boundary> element"
      (let [thrown (try (emit/render-to-string
                          [:rf/suspense-boundary
                           {:id :secret-boundary :fallback [:span "spin"]}
                           [:div "leak-me"]]
                          {})
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo thrown))
        (is (= :rf.error/ssr-suspense-boundary-outside-stream
               (:rf.error/id (ex-data thrown))))))
    (testing "a marker NESTED inside a normal DOM tree also fails loud
              (emit-children recurses into it)"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/ssr-suspense-boundary-outside-stream"
                            (emit/render-to-string
                              [:div [:section
                                     [:rf/suspense-boundary
                                      {:id :b :fallback [:span "…"]}
                                      [:p "x"]]]]
                              {}))))))

(deftest streaming-walker-still-handles-suspense-boundary
  (testing "rf2-bee5i — the streaming shell walker still recognises
            :rf/suspense-boundary (the emit-element guard only fires on
            the NON-streaming path); render-shell materialises the
            fallback as a <template> and does NOT throw."
    (let [{:keys [shell-html continuations]}
          (streaming/render-shell
            [:div
             [:rf/suspense-boundary
              {:id :card-1 :fallback [:span "loading…"]}
              [:div "resolved card"]]])]
      (is (str/includes? shell-html "<template")
          "the fallback materialises as a <template> placeholder")
      (is (str/includes? shell-html "loading")
          "the fallback content is rendered inline in the shell")
      (is (not (str/includes? shell-html "<suspense-boundary"))
          "no phantom <suspense-boundary> DOM element is emitted")
      (is (= 1 (count continuations))
          "the boundary registers exactly one continuation"))))

;; ===========================================================================
;; rf2-ynjts.13 — emit-element scalar-child branches (emit.cljc:319-323).
;; The strip-prop / raw-text / tag-name security gates are well covered, but
;; the load-bearing scalar emission rules — number stringifies, boolean is
;; DROPPED, nil is dropped, string is escaped — had no direct behaviour
;; assertion. These are the leaf rules every render bottoms out at; a
;; regression (e.g. booleans accidentally stringifying to "true") would
;; corrupt every server-rendered page and slip past the existing tests.
;; ===========================================================================

(deftest render-to-string-number-child-stringifies
  (testing "rf2-ynjts.13 — a number child renders as its `str` form, not
            dropped, not escaped (emit-element number? branch)."
    (is (= "<span>42</span>"
           (emit/render-to-string [:span 42] {}))
        "integer child stringifies")
    (is (= "<span>3.14</span>"
           (emit/render-to-string [:span 3.14] {}))
        "double child stringifies")
    (is (= "<p>count=7</p>"
           (emit/render-to-string [:p "count=" 7] {}))
        "a number sits inline alongside a string child")))

(deftest render-to-string-boolean-child-is-dropped
  (testing "rf2-ynjts.13 — a boolean CHILD emits nothing (emit-element
            boolean? branch → \"\"). The ubiquitous
            `[:div (when cond? [:p ...])]` shape yields `false`/`nil` for
            the false arm; both must vanish, not render the word `true`/
            `false`. Distinct from a boolean ATTR VALUE (which `attr-string`
            renders as a bare attr name) — this is the child position."
    (is (= "<div></div>"
           (emit/render-to-string [:div true] {}))
        "a lone `true` child is dropped")
    (is (= "<div></div>"
           (emit/render-to-string [:div false] {}))
        "a lone `false` child is dropped")
    (is (= "<div><p>shown</p></div>"
           (emit/render-to-string [:div (when true [:p "shown"]) (when false [:p "hidden"])] {}))
        "the false arm of a (when …) yields nil → dropped; the true arm renders")
    (is (= "<p>ab</p>"
           (emit/render-to-string [:p "a" true "b" false nil] {}))
        "booleans + nil interleaved with strings drop, strings survive")))

(deftest render-to-string-fn-headed-component
  (testing "rf2-ynjts.13 — a fn-headed component `[component-fn & args]`
            (emit-element fn? branch) is invoked with its args and its
            returned hiccup is emitted. The streaming walker's fn-head path
            is exercised indirectly elsewhere, but the non-streaming
            emitter's fn-head branch had no direct assertion."
    (let [greeting (fn [name] [:h1 "Hello, " name])]
      (is (= "<h1>Hello, world</h1>"
             (emit/render-to-string [greeting "world"] {}))
          "the component fn is called with the trailing args; its hiccup emits"))
    (testing "a fn-headed component nested as a child is resolved too"
      (let [item (fn [label] [:li label])]
        (is (= "<ul><li>a</li><li>b</li></ul>"
               (emit/render-to-string [:ul [item "a"] [item "b"]] {}))
            "fn-heads in child position resolve via emit-children → emit-element")))
    (testing "a fn-headed component returning a string renders the string escaped"
      (let [raw (fn [] "a < b")]
        (is (= "<p>a &lt; b</p>"
               (emit/render-to-string [:p [raw]] {}))
            "the fn's string output flows back through escape-html")))))

;; ===========================================================================
;; rf2-ynjts.13 — escape-attr / escape-html asymmetry (html_helpers.cljc).
;; A deliberate, security-relevant correctness invariant: text-node content
;; escapes `< > & " '` (no raw-tag injection), but attribute VALUES escape
;; ONLY `& "` because `<`/`>` are legal inside a double-quoted attribute
;; value per the HTML5 parser. A regression that over-escaped attrs would
;; corrupt legit values (e.g. a `content` meta carrying `a<b`); one that
;; under-escaped text nodes would open an XSS. Pinned through the full
;; `render-to-string` composition, not the helper in isolation.
;; ===========================================================================

(deftest render-to-string-text-node-escapes-all-five-entities
  (testing "rf2-ynjts.13 — a text-node child escapes `& < > \" '` so no
            raw markup or quote can break out of text position."
    (is (= "<p>&amp;&lt;&gt;&quot;&#39;</p>"
           (emit/render-to-string [:p "&<>\"'"] {}))
        "all five escapable entities are rewritten in text position")
    (is (= "<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>"
           (emit/render-to-string [:p "<script>alert(1)</script>"] {}))
        "a literal <script> in text cannot inject a real tag")))

(deftest render-to-string-attr-value-escapes-only-amp-and-quote
  (testing "rf2-ynjts.13 — an attribute VALUE escapes only `&` and `\"`;
            `<` / `>` / `'` are LEGAL inside a double-quoted attr value and
            are emitted verbatim (HTML5 parser rule). This asymmetry with
            text-node escaping is deliberate — over-escaping attrs corrupts
            legit values."
    (is (= "<div data-x=\"a&amp;b\"></div>"
           (emit/render-to-string [:div {:data-x "a&b"}] {}))
        "`&` in an attr value is escaped to &amp;")
    (is (= "<div data-x=\"&quot;q&quot;\"></div>"
           (emit/render-to-string [:div {:data-x "\"q\""}] {}))
        "a double-quote in an attr value is escaped to &quot; (else it would
         close the value)")
    (is (= "<div data-x=\"a<b>c\"></div>"
           (emit/render-to-string [:div {:data-x "a<b>c"}] {}))
        "`<` and `>` are NOT escaped in an attr value — legal per HTML5")
    (is (= "<div data-x=\"it's\"></div>"
           (emit/render-to-string [:div {:data-x "it's"}] {}))
        "a single-quote is NOT escaped — the value is double-quoted")))

;; ===========================================================================
;; rf2-ynjts.13 — boolean ATTR-VALUE branch through the full emitter
;; (attr-string true → bare name; false/nil → omitted). The head emitter
;; test pins async/defer, and ssr_attr_filter_test pins the helper in
;; isolation, but the non-streaming body emitter's boolean-attr composition
;; (the common `[:input {:disabled true :required false}]` shape) had no
;; direct render-to-string assertion.
;; ===========================================================================

(deftest render-to-string-boolean-attr-values
  (testing "rf2-ynjts.13 — `true` attr value → bare attribute name; `false`
            and `nil` attr values → omitted entirely, through the full
            render-to-string composition."
    (is (= "<input disabled required>"
           (emit/render-to-string [:input {:disabled true :required true}] {}))
        "`true` boolean attrs emit as bare names on a void element")
    (is (= "<input>"
           (emit/render-to-string [:input {:disabled false :hidden nil}] {}))
        "`false` and `nil` attrs are omitted — no bare name, no empty value")
    (is (= "<button disabled>Go</button>"
           (emit/render-to-string [:button {:disabled true :title nil} "Go"] {}))
        "boolean + nil attrs compose on a non-void element alongside text")))

;; ===========================================================================
;; rf2-wtd8z finding 2 — Var-headed component resolution (emit.cljc +
;; streaming.cljc). On the JVM a Var (`#'component`) is `ifn?` but NOT
;; `fn?`, so the prior `(fn? head)` test let a Var-headed component fall
;; through to the scalar/`:else` arm and emit the EDN text
;; `[#'re-frame.ssr-emit-test/var-component "ok"]` instead of resolving it
;; to `<span>ok</span>`. The emitter / streaming walker now test `ifn?`,
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
  (testing "rf2-wtd8z finding 2 — a Var-headed component `[#'component & args]`
            is invoked and its hiccup emitted (NOT stringified as EDN)"
    (is (= "<span>ok</span>"
           (emit/render-to-string [#'var-component "ok"] {}))
        "the Var head resolves: invoked with its args, returned hiccup emits")
    (testing "a Var-headed component nested as a child resolves too"
      (is (= "<ul><span>a</span><span>b</span></ul>"
             (emit/render-to-string [:ul [#'var-component "a"] [#'var-component "b"]] {}))
          "Var-heads in child position resolve via emit-children → emit-element"))
    (testing "root render-hash threads through the Var head onto the resolved DOM root"
      (let [html (emit/render-to-string [#'var-component "ok"] {:emit-hash? true})]
        (is (re-find #"<span [^>]*data-rf-render-hash=\"[^\"]+\">ok</span>" html)
            (str "the root data-rf-render-hash lands on the Var head's resolved"
                 " <span> root; got: " html))))))

(deftest render-to-string-var-headed-registered-view
  (testing "rf2-wtd8z finding 2 — a registered view whose body is a
            Var-headed component resolves (no EDN leak), while the
            render-hash root-attr DOES thread through the Var head onto
            the resolved DOM root. Two levels of callable indirection:
            `(rf/view :id)` reaches the view, whose body is itself
            Var-headed."
    (rf/reg-view ^{:rf/id :rf.ssr-emit-test/var-view} var-view []
      [#'var-component "v"])
    (let [html (emit/render-to-string [(rf/view :rf.ssr-emit-test/var-view)] {})]
      (is (= "<span>v</span>" html)
          (str "the Var head resolved to <span>v</span> (NOT EDN text); "
               "got: " html)))
    (testing "render-hash threads through the Var-headed view root onto the resolved DOM root"
      (let [html (emit/render-to-string [(rf/view :rf.ssr-emit-test/var-view)] {:emit-hash? true})]
        (is (re-find #"<span [^>]*data-rf-render-hash=\"[^\"]+\">v</span>" html)
            (str "the root data-rf-render-hash threads through the view-ref AND "
                 "the Var head onto the resolved <span> root; got: " html))))))

(deftest render-shell-var-headed-component
  (testing "rf2-wtd8z finding 2 — the streaming shell walker resolves a
            Var-headed component just like the non-streaming emitter
            (ifn?, not fn?), recursing on its returned hiccup"
    (let [{:keys [shell-html]} (streaming/render-shell [#'var-component "streamed"])]
      (is (= "<span>streamed</span>" shell-html)
          (str "the streaming walker resolved the Var head + recursed on its"
               " body; got: " shell-html)))
    (testing "a Var head nested in a DOM tree streams resolved too"
      (let [{:keys [shell-html]} (streaming/render-shell
                                   [:section [#'var-component "x"]])]
        (is (= "<section><span>x</span></section>" shell-html)
            (str "nested Var head resolved in the shell walk; got: "
                 shell-html))))))

;; ===========================================================================
;; rf2-y1jbaq — malformed-head hiccup vector fails loud, never emits raw
;; unescaped output (XSS-class escape bypass)
;;
;; A hiccup vector whose head is a string / nil / number / boolean (not a
;; keyword and not a callable) previously hit `:else (str el)` and shipped its
;; WHOLE EDN form RAW — a `[nil "<script>…"]` put a live `<script>` on the
;; wire. Both the sync emitter and the streaming shell walker MUST reject it
;; with `:rf.error/invalid-hiccup-head`; no raw angle-brackets may reach output.
;; ===========================================================================

(deftest emit-rejects-malformed-hiccup-head
  (testing "rf2-y1jbaq — a nil / string / number / boolean head throws
            :rf.error/invalid-hiccup-head through render-to-string"
    (doseq [el [[nil "<script>alert(1)</script>"]
                ["x" "<img src=x onerror=alert(1)>"]
                [42 "<script>x</script>"]
                [true "<script>y</script>"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/invalid-hiccup-head"
                            (emit/render-to-string el {}))
          (str "malformed-head vector must fail loud, not emit raw: " (pr-str el)))))

  (testing "rf2-y1jbaq — no raw `<script>` / `<img onerror>` survives to output
            for a malformed-head vector (the throw prevents any emission)"
    (doseq [el [[nil "<script>alert(1)</script>"]
                ["x" "<img src=x onerror=alert(1)>"]]]
      (let [out (try (emit/render-to-string el {}) (catch Throwable _ ::threw))]
        (is (= ::threw out)
            (str "no wire output produced for malformed head: " (pr-str el)))))))

(deftest streaming-rejects-malformed-hiccup-head
  (testing "rf2-y1jbaq — the streaming shell walker rejects a malformed-head
            vector identically to the sync emitter (it must NOT splice it as a
            child-seq and ride the raw child strings)"
    (doseq [el [[nil "<script>alert(1)</script>"]
                ["x" "<img src=x onerror=alert(1)>"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/invalid-hiccup-head"
                            (streaming/render-shell el))
          (str "streaming path must fail loud on malformed head: " (pr-str el))))))

;; ===========================================================================
;; rf2-dtza9a — Form-2 raw-fn component renders (never leaks the inner fn's
;; .toString as page text)
;;
;; A Form-2 component (an outer fn returning an inner render fn) previously
;; resolved to a fn VALUE that fell through to `escape-html`, stringifying the
;; fn's `.toString` (`user$…fn__…@…`) as visible page text. Both the sync
;; emitter and the streaming shell walker MUST invoke the inner render fn
;; (Form-2 semantics) and render its hiccup; a result that is STILL a fn after
;; the single unwrap fails loud with `:rf.error/ssr-nonrenderable-component`.
;; ===========================================================================

(deftest emit-renders-form-2-component
  (testing "rf2-dtza9a — a Form-2 component renders its inner hiccup, not the
            inner fn's .toString, through BOTH the sync emit and streaming paths"
    ;; The idiomatic Reagent/UIx Form-2 shapes: a 0-arity inner closing
    ;; over the outer's args, AND a same-arity inner taking the args.
    (let [form2-closed (fn [value] (fn [] [:div value]))
          form2-arg    (fn [_outer-value]
                         (fn [value] [:p (str "v=" value)]))]
      (is (= "<div>hello</div>" (emit/emit-element [form2-closed "hello"]))
          "sync emit renders the 0-arity-inner Form-2 output")
      (is (= "<div>hello</div>"
             (:shell-html (streaming/render-shell [form2-closed "hello"])))
          "streaming renders the 0-arity-inner Form-2 output")
      (is (= "<p>v=7</p>" (emit/emit-element [form2-arg 7]))
          "sync emit renders the same-arity-inner Form-2 output")
      (is (= "<p>v=7</p>"
             (:shell-html (streaming/render-shell [form2-arg 7])))
          "streaming renders the same-arity-inner Form-2 output")
      ;; Belt-and-braces: no fn-identity toString leaks into the output.
      (doseq [out [(emit/emit-element [form2-closed "hello"])
                   (:shell-html (streaming/render-shell [form2-closed "hello"]))]]
        (is (not (str/includes? out "fn__"))
            "no inner-fn .toString (`…fn__…@…`) leaks as page text")
        (is (not (str/includes? out "@"))
            "no object-identity `@hash` leaks as page text"))))

  (testing "rf2-dtza9a — a component that resolves to a fn even after the
            Form-2 unwrap (deeper than Form-2) fails loud, never leaking a fn"
    (let [deep (fn [x] (fn [] (fn [] [:div x])))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/ssr-nonrenderable-component"
                            (emit/emit-element [deep "z"]))
          "sync emit fails loud on a deeper-than-Form-2 component")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":rf.error/ssr-nonrenderable-component"
                            (streaming/render-shell [deep "z"]))
          "streaming fails loud on a deeper-than-Form-2 component"))))

;; ===========================================================================
;; rf2-mocn3 — Form-2 arity adaptation is a DECISION taken before the render
;; runs, never an exception caught after it
;;
;; `invoke-form-2-render-fn` used to probe arity BY EXCEPTION:
;;
;;     (try (apply inner args) (catch ArityException _ (inner)))
;;
;; That catch encloses execution of PROGRAMMER code, which is two independent
;; defects:
;;
;;   1. An `ArityException` raised INSIDE a correctly-invoked inner render —
;;      an ordinary wrong-arity bug in a helper that render calls — is
;;      indistinguishable from an invocation-arity mismatch. The renderer ran
;;      the render body a SECOND time and reported the retry's outcome, so a
;;      non-pure render duplicated its effects and the user's own failure was
;;      replaced by the retry's.
;;   2. The fallback tried only arity ZERO, so an inner accepting a non-zero
;;      PREFIX of the outer's props — valid under the CLJS/JS extra-argument
;;      semantics this helper exists to emulate — was rejected on the JVM.
;;      The same `.cljc` app rendered in the browser and failed SSR.
;;
;; The helper now selects a compatible call shape from the compiled fn's
;; DECLARED arities and invokes exactly once. Both public server paths share
;; the one resolver (`emit/resolve-component-head`), so every row below
;; asserts through BOTH `emit/emit-element` and `streaming/render-shell`;
;; a fix proven through one of them is proven for half the surface.
;;
;; NOTE ON NON-VACUITY: the inners below are FIXED arity where prefix
;; selection is the thing under test (a variadic inner accepts the full arg
;; list and would pass without exercising selection at all), and the
;; double-invocation rows COUNT their calls — an idempotent test double is
;; green against the unrepaired helper and proves nothing.
;; ===========================================================================

(deftest emit-renders-form-2-partial-arity-inner
  (testing "rf2-mocn3 — an inner render declaring a non-zero PREFIX of the
            outer's args renders on the JVM exactly as it does on CLJS,
            through BOTH the sync emitter and the streaming shell walker"
    ;; Fixed arity 1, taking the first of the outer's two props — the
    ;; failure scenario on the bead: the client drops the extra JS argument,
    ;; the JVM used to try 2 args, catch, retry at 0, and throw.
    (let [form2-partial (fn [_outer-value _ignored]
                          (fn [kept] [:p kept]))]
      (is (= "<p>kept</p>" (emit/emit-element [form2-partial "kept" "ignored"]))
          "sync emit passes the inner the longest prefix it accepts")
      (is (= "<p>kept</p>"
             (:shell-html (streaming/render-shell [form2-partial "kept" "ignored"])))
          "streaming passes the inner the longest prefix it accepts"))))

(deftest emit-form-2-inner-body-arity-exception-propagates-once
  (testing "rf2-mocn3 — a zero-arity inner invoked with zero args REACHES its
            body, and an ArityException raised THERE is not an invocation
            mismatch: the render runs exactly ONCE, on BOTH server paths"
    ;; The shape the old catch-and-retry could re-enter successfully. The
    ;; first call already matches, so the body runs, throws, is caught, and
    ;; the retry runs the body a SECOND time. ONLY a counting inner detects
    ;; that — the exception is the same either way, so an idempotent test
    ;; double is green against the unrepaired helper and proves nothing.
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
                   (emit/emit-element [form2-buggy]))
          "sync emit propagates the inner body's own ArityException")
      (is (= 1 @calls)
          "sync emit invoked the inner render EXACTLY once (the old
           catch-and-retry reached 2)")
      (reset! calls 0)
      (is (thrown? clojure.lang.ArityException
                   (streaming/render-shell [form2-buggy]))
          "streaming propagates the inner body's own ArityException")
      (is (= 1 @calls)
          "streaming invoked the inner render EXACTLY once (the old
           catch-and-retry reached 2)")))

  (testing "rf2-mocn3 — the inner body's ORIGINAL failure propagates
            unchanged rather than being replaced by the retry's"
    ;; A SAME-arity inner. The old helper caught the body's
    ;; `Wrong number of args (1)` and re-invoked at arity ZERO, which this
    ;; arity-1 inner rejects — so the programmer was shown
    ;; `Wrong number of args (0)` about a call they never wrote and their
    ;; real bug vanished. The arg COUNT in the message is the discriminator
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
                            (emit/emit-element [form2-buggy "x"]))
          "sync emit surfaces the render's own failing call, not a
           fabricated zero-arity retry")
      (is (= 1 @calls) "sync emit invoked the inner render exactly once")
      (reset! calls 0)
      (is (thrown-with-msg? clojure.lang.ArityException
                            #"Wrong number of args \(1\)"
                            (streaming/render-shell [form2-buggy "x"]))
          "streaming surfaces the render's own failing call, not a
           fabricated zero-arity retry")
      (is (= 1 @calls) "streaming invoked the inner render exactly once"))))

(deftest emit-form-2-variadic-inner-body-failure-is-not-swallowed
  (testing "rf2-mocn3 — the old zero-arity retry SUCCEEDED on a variadic
            inner, silently replacing a failing render with different HTML.
            The programmer's failure must surface on BOTH paths instead"
    ;; With args the render is reached and its helper bug throws; with NO
    ;; args it returns a different tree. Under the old catch-and-retry the
    ;; no-args branch is what shipped — a silent wrong render, no exception
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
                   (emit/emit-element [form2 "x"]))
          "sync emit surfaces the render's own failure rather than
           re-entering the variadic inner with no args")
      (is (thrown? clojure.lang.ArityException
                   (streaming/render-shell [form2 "x"]))
          "streaming surfaces the render's own failure rather than
           re-entering the variadic inner with no args"))))

(deftest emit-passes-form-2-variadic-inner-the-whole-arg-seq
  (testing "rf2-mocn3 — a VARIADIC inner receives the complete original
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
      (is (= "<p>a,b</p>" (emit/emit-element [form2 "a" "b"]))
          "sync emit renders the variadic inner's output")
      (is (= ["a" "b"] @seen)
          "sync emit passed the variadic inner the complete arg sequence")
      (is (= 1 @calls)
          "sync emit invoked the variadic inner exactly once")
      (reset! calls 0)
      (reset! seen nil)
      (is (= "<p>a,b</p>" (:shell-html (streaming/render-shell [form2 "a" "b"])))
          "streaming renders the variadic inner's output")
      (is (= ["a" "b"] @seen)
          "streaming passed the variadic inner the complete arg sequence")
      (is (= 1 @calls)
          "streaming invoked the variadic inner exactly once"))))

;; ===========================================================================
;; rf2-mocn3 (audit) — the selection must not be MORE permissive than CLJS
;;
;; The first repair replaced exception-driven probing with a walk down the
;; inner's declared arities, taking the longest accepted PREFIX. That is not
;; what a compiled ClojureScript fn does. Only a fn with a SINGLE fixed arity
;; and no variadic tail compiles to a bare JavaScript function, and only a
;; bare JavaScript function drops extra arguments; anything with more than one
;; arm compiles to a dispatcher that switches on `arguments.length` and throws
;; `Invalid arity: n`. Measured on node (see the cross-host table in
;; `re-frame.ssr.form2-arity-cljs-test`):
;;
;;   (fn [x] …)               at 3 args → returns
;;   (fn ([x] …) ([x y] …))   at 3 args → throws `Invalid arity: 3`
;;   (fn ([] …) ([x] …))      at 2 args → throws `Invalid arity: 2`
;;
;; The prefix walk selected arity 2 and arity 1 for those last two and
;; rendered, so a shared `.cljc` Form-2 component rendered on the server and
;; failed on hydration — the exact parity the repair exists to hold.
;;
;; The cross-host table runs on both hosts through the sync emitter: it pins
;; the AGREEMENT above, and — since rf2-mocn3's mayor ruling of 2026-09-01 —
;; also the one place agreement STOPS. Where an inner is handed FEWER args
;; than its shortest arm requires, CLJS binds the missing parameters to
;; `undefined` and renders while the JVM raises; the JVM is stricter there on
;; purpose (`emit/invoke-form-2-render-fn`, THE SUPPORTED CONTRACT). What is
;; here is the second public consumer: streaming shares one resolver with
;; sync, so every row asserts through BOTH.
;; ===========================================================================

(deftest emit-form-2-multi-arity-inner-refuses-what-cljs-refuses
  (testing "rf2-mocn3 — a multi-arity inner handed a count no arm declares is
            REFUSED on both server paths, as the client refuses it, rather
            than silently rendering some shorter arm's output"
    ;; The audit's two shapes verbatim. Under the prefix walk the first
    ;; rendered `<p>m2|a|b</p>` and the second `<p>m1|a</p>`.
    (let [multi-1-2 (fn [& _] (fn ([x]   [:p (str "m1|" x)])
                                  ([x y] [:p (str "m2|" x "|" y)])))
          multi-0-1 (fn [& _] (fn ([]  [:p "m0"])
                                  ([x] [:p (str "m1|" x)])))]
      (is (thrown? clojure.lang.ArityException
                   (emit/emit-element [multi-1-2 "a" "b" "c"]))
          "sync emit refuses a 1-or-2-arity inner handed 3 args")
      (is (thrown? clojure.lang.ArityException
                   (streaming/render-shell [multi-1-2 "a" "b" "c"]))
          "streaming refuses a 1-or-2-arity inner handed 3 args")
      (is (thrown? clojure.lang.ArityException
                   (emit/emit-element [multi-0-1 "a" "b"]))
          "sync emit refuses a 0-or-1-arity inner handed 2 args")
      (is (thrown? clojure.lang.ArityException
                   (streaming/render-shell [multi-0-1 "a" "b"]))
          "streaming refuses a 0-or-1-arity inner handed 2 args")

      ;; NON-VACUITY. The same two inners must still render through every arm
      ;; they DO declare — otherwise the rows above would be satisfied by a
      ;; blanket refusal of multi-arity inners, which is a different (and
      ;; worse) behaviour wearing the same green.
      (is (= "<p>m2|a|b</p>" (emit/emit-element [multi-1-2 "a" "b"]))
          "sync emit selects the exact 2-arity arm")
      (is (= "<p>m2|a|b</p>"
             (:shell-html (streaming/render-shell [multi-1-2 "a" "b"])))
          "streaming selects the exact 2-arity arm")
      (is (= "<p>m1|a</p>" (emit/emit-element [multi-1-2 "a"]))
          "sync emit selects the exact 1-arity arm")
      (is (= "<p>m1|a</p>"
             (:shell-html (streaming/render-shell [multi-1-2 "a"])))
          "streaming selects the exact 1-arity arm")
      (is (= "<p>m0</p>" (emit/emit-element [multi-0-1]))
          "sync emit selects the exact 0-arity arm")
      (is (= "<p>m0</p>" (:shell-html (streaming/render-shell [multi-0-1])))
          "streaming selects the exact 0-arity arm")))

  (testing "rf2-mocn3 — a fixed+variadic inner routes by the same rules: the
            exact fixed arm when one matches, otherwise the variadic arm with
            the WHOLE arg list, on both server paths"
    ;; `(fn ([a] …) ([a b & r] …))` — fixed arity 1 plus a variadic arm
    ;; requiring 2. The compiled CLJS dispatcher sends 1 arg to the fixed arm
    ;; and 3 to the variadic one; so must the JVM. Note the prefix walk would
    ;; have sent 3 args to the ARITY-1 arm, dropping two props.
    (let [mixed (fn [& _] (fn ([a] [:p (str "mx1|" a)])
                              ([a b & r] [:p (str "mxv|" a "|" b "|"
                                                  (str/join "," r))])))]
      (is (= "<p>mxv|a|b|c</p>" (emit/emit-element [mixed "a" "b" "c"]))
          "sync emit hands the satisfied variadic arm every arg")
      (is (= "<p>mxv|a|b|c</p>"
             (:shell-html (streaming/render-shell [mixed "a" "b" "c"])))
          "streaming hands the satisfied variadic arm every arg")
      (is (= "<p>mx1|a</p>" (emit/emit-element [mixed "a"]))
          "sync emit prefers the exact fixed arm")
      (is (= "<p>mx1|a</p>" (:shell-html (streaming/render-shell [mixed "a"])))
          "streaming prefers the exact fixed arm"))))

;; ===========================================================================
;; rf2-r9kf — BOOLEAN ATTRIBUTE-VALUE CLASSES through both hiccup SSR modes.
;;
;; `html-helpers/attr-string` used to branch on the VALUE alone — `true` → a
;; bare attribute name, `false`/`nil` → omitted — one rule applied to
;; attributes that do not share one. HTML/React carries three classes, pinned
;; by Spec 004B §Booleans and their neighbours from a row-by-row react-dom
;; 19.2.0 probe:
;;
;;   stringify  `aria-*`, `data-*`, and the booleanish family
;;              (`contentEditable` / `draggable` / `spellCheck`) — `true` AND
;;              `false` both reach markup as `="true"` / `="false"`. ARIA is
;;              not boolean HTML: `aria-expanded="false"` is a DIFFERENT state
;;              from the attribute being absent, so dropping the `false` made
;;              server markup assert the OPPOSITE of what the author wrote,
;;              and assistive technology read a different UI than the client
;;              render shows.
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
;; These drive the classes through BOTH hiccup SSR modes — `emit/
;; render-to-string` and `streaming/render-shell` — because both call the one
;; shared `attr-string`, so neither mode can drift on its own.
;; ===========================================================================

(deftest render-to-string-aria-and-data-booleans-stringify
  (testing "rf2-r9kf — an `aria-*` boolean stringifies in BOTH directions;
            `false` is a state, never an omission"
    (is (= "<button aria-expanded=\"true\">x</button>"
           (emit/render-to-string [:button {:aria-expanded true} "x"] {}))
        "aria-expanded true → aria-expanded=\"true\", never a bare name")
    (is (= "<button aria-expanded=\"false\">x</button>"
           (emit/render-to-string [:button {:aria-expanded false} "x"] {}))
        "aria-expanded false → aria-expanded=\"false\", never absent")
    (is (= "<div aria-hidden=\"false\"></div>"
           (emit/render-to-string [:div {:aria-hidden false}] {}))
        "aria-hidden false survives — absent would mean the opposite")
    (is (= "<div aria-checked=\"false\"></div>"
           (emit/render-to-string [:div {:aria-checked false}] {}))
        "aria-checked false survives")
    (is (= "<div aria-disabled=\"false\"></div>"
           (emit/render-to-string [:div {:aria-disabled false}] {}))
        "aria-disabled false survives"))

  (testing "rf2-r9kf — `data-*` booleans stringify the same way"
    (is (= "<div data-open=\"true\"></div>"
           (emit/render-to-string [:div {:data-open true}] {}))
        "data-* true → data-open=\"true\"")
    (is (= "<div data-open=\"false\"></div>"
           (emit/render-to-string [:div {:data-open false}] {}))
        "data-* false → data-open=\"false\"")))

(deftest render-to-string-booleanish-attrs-stringify-both-ways
  (testing "rf2-r9kf — the nested editable-parent regression: an explicit
            `false` on a child is how it opts OUT of an editable ancestor, so
            dropping it silently makes the child editable"
    (is (= (str "<div contentEditable=\"true\">"
                "<section contentEditable=\"false\">locked</section>"
                "</div>")
           (emit/render-to-string
            [:div {:contentEditable true}
             [:section {:contentEditable false} "locked"]]
            {}))
        "the child keeps its explicit contentEditable=\"false\" marker"))

  (testing "rf2-r9kf — every booleanish family member stringifies true AND
            false (the whole roster, table-driven)"
    (doseq [attribute-key [:contentEditable :draggable :spellCheck]
            [value expected] [[true "true"] [false "false"]]]
      (is (= (str "<div " (name attribute-key) "=\"" expected "\"></div>")
             (emit/render-to-string [:div {attribute-key value}] {}))
          (str "booleanish " attribute-key " " value
               " → " (name attribute-key) "=\"" expected "\"")))))

(deftest render-to-string-presence-classes-are-preserved
  (testing "rf2-r9kf CONTROL — true boolean attributes keep PRESENCE
            semantics. `disabled=\"false\"` is truthy to a browser, so
            emitting the false value here would disable the control"
    (is (= "<input disabled required>"
           (emit/render-to-string [:input {:disabled true :required true}] {}))
        "true boolean attrs stay bare names")
    (is (= "<input>"
           (emit/render-to-string [:input {:disabled false :hidden nil}] {}))
        "a false boolean attr stays OMITTED — never disabled=\"false\"")
    (is (= "<input>"
           (emit/render-to-string [:input {:checked false :readonly false}] {}))
        "checked/readonly false stay omitted"))

  (testing "rf2-r9kf CONTROL — overloaded booleans keep their own shape:
            true → presence, false → omitted, any other value stringifies"
    (is (= "<a download>d</a>"
           (emit/render-to-string [:a {:download true} "d"] {}))
        "download true → bare presence")
    (is (= "<a>d</a>"
           (emit/render-to-string [:a {:download false} "d"] {}))
        "download false → omitted")
    (is (= "<a download=\"report.pdf\">d</a>"
           (emit/render-to-string [:a {:download "report.pdf"} "d"] {}))
        "a string download stringifies"))

  (testing "rf2-r9kf CONTROL — a boolean on an ORDINARY attribute never
            becomes a bare attribute (react-dom drops it)"
    (is (= "<div>x</div>"
           (emit/render-to-string [:div {:title true} "x"] {}))
        "true on an ordinary attribute is dropped, not emitted bare")
    (is (= "<div>x</div>"
           (emit/render-to-string [:div {:role false} "x"] {}))
        "false on an ordinary attribute is dropped")))

(deftest render-shell-applies-the-same-boolean-classes
  (testing "rf2-r9kf — the streaming shell walker re-derives attrs through the
            SAME `attr-string`, so the classes must hold there too; a fix
            landing on one hiccup mode only is the drift this pins"
    (let [tree [:div {:aria-expanded false :contentEditable false}
                [:button {:disabled true :aria-disabled false} "go"]]
          {:keys [shell-html]} (streaming/render-shell tree)]
      (is (str/includes? shell-html "aria-expanded=\"false\"")
          "streaming keeps a false aria-* value")
      (is (str/includes? shell-html "contentEditable=\"false\"")
          "streaming keeps a false booleanish value")
      (is (str/includes? shell-html "aria-disabled=\"false\"")
          "streaming keeps a false aria-* value on a nested element")
      (is (str/includes? shell-html "<button disabled aria-disabled=\"false\">")
          "streaming still emits a true boolean attr as a bare presence name"))))

(defn- boolean-attr-class-signature
  "Reduce an emitted element string to WHICH boolean class the serialiser
  applied to `attribute-name` — the comparable across the two SSR
  serialisers, whose presence SPELLINGS differ by design (the hiccup emitter
  writes a bare `disabled`, the structural-tree serialiser `disabled=\"\"`;
  011 §Hash-based mismatch detection tolerates exactly that difference)."
  [attribute-name html]
  (let [lower (str/lower-case html)
        nm    (str/lower-case attribute-name)]
    (cond
      (str/includes? lower (str nm "=\"true\""))  :stringified-true
      (str/includes? lower (str nm "=\"false\"")) :stringified-false
      (str/includes? lower (str nm "=\"\""))      :presence
      (str/includes? lower (str " " nm ">"))      :presence
      (str/includes? lower (str " " nm " "))      :presence
      :else                                       :absent)))

(deftest boolean-classes-agree-with-the-structural-tree-serialiser
  (testing "rf2-r9kf — the hiccup emitter and `emit-ui-tree` reach the SAME
            class verdict for every row of 004B §Booleans and their
            neighbours. Compared as CLASSES, not bytes: the two pipelines stay
            separate (004B) and differ in presence spelling and name mapping;
            what must not differ is which class an attribute is in"
    (doseq [[attribute-key value] [[:aria-hidden true]     [:aria-hidden false]
                                   [:aria-expanded false]
                                   [:data-open true]       [:data-open false]
                                   [:contentEditable true] [:contentEditable false]
                                   [:draggable false]
                                   [:spellCheck false]
                                   [:disabled true]        [:disabled false]
                                   [:checked false]        [:hidden true]
                                   [:download true]        [:download false]
                                   [:title true]           [:role false]]]
      (let [attribute-name (name attribute-key)
            hiccup-html    (emit/render-to-string [:div {attribute-key value}] {})
            tree-html      (ui-tree/emit-ui-tree
                            (v1 {:tag :div :attrs {attribute-key value}}))]
        (is (= (boolean-attr-class-signature attribute-name tree-html)
               (boolean-attr-class-signature attribute-name hiccup-html))
            (str attribute-key " " value
                 " — hiccup emitted " (pr-str hiccup-html)
                 ", the structural-tree serialiser " (pr-str tree-html)))))))
