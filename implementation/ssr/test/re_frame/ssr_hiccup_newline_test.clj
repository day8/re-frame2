(ns re-frame.ssr-hiccup-newline-test
  "rf2-s7l5 — leading-LF compensation for the TWO HICCUP emitters.

  ANCHOR (rf2-z05di, extended by rf2-s7l5): HTML parsing eats the FIRST LF
  immediately after `<pre>` / `<listing>` / `<textarea>` (the newline-eating
  elements), so react-dom/server 19.2 prefixes ONE compensating LF when such an
  element's body is a SINGLE STRING beginning with LF — making the authored
  content survive the parse round-trip. Multiple / element children are left
  untouched (React's `typeof children === 'string'` guard).

  rf2-z05di implemented that rule for the S5 STRUCTURAL serialiser
  (`re-frame.ssr.ui-tree`) only. Both HICCUP emitters — the non-streaming
  `re-frame.ssr.emit/render-to-string` and the streaming shell walker
  `re-frame.ssr.streaming/render-shell` — emitted the start tag, the children
  and the end tag with no compensation, so an ordinary supported
  `[:pre \"\\ncode\"]` reached the browser as `<pre>\\ncode</pre>` and parsed to
  the DOM text `code`: one authored character silently lost, and a text
  hydration mismatch against the client's Reagent/React rendering of the same
  `.cljc` view. Spec 011 §The render-tree → HTML emitter describes the hiccup
  emitter as text-PRESERVING per-position HTML output.

  WHY THE STRUCTURAL RENDER HASH DOES NOT CATCH IT: `render-tree-hash` is
  computed from the AUTHORED data, not from the parsed HTML, so server and
  client agree on the hash while disagreeing on the text node. Only a
  parse-level assertion sees it — which is what `parsed-text-content` below is
  for.

  THE PARSE MODEL. There is no HTML parser on this artefact's classpath, so
  `parsed-text-content` models the ONE HTML5 tokenizer rule under test — \"a
  newline-eating element's start tag is followed by an ignored LF\" (HTML
  Standard §13.2.6.4.x, `<pre>`/`<listing>`/`<textarea>`) — over emitted markup
  whose only structure is the element under test. It is deliberately not a
  general parser: it exists so the assertion reads on the OBSERVABLE
  `textContent` the acceptance names, rather than only on the emitted bytes.
  Both are asserted; the byte pins are the react-dom/server 19.2 parity half.

  JVM-only, mirroring `re-frame.ssr-emit-test`: the emitters are
  platform-neutral `.cljc` and the shared rule's own unit-level proof runs on
  both platforms via `re-frame.ssr.emit-ui-tree-cljs-test`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]
            [re-frame.ssr.streaming :as rf.ssr.streaming]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.ssr.ui-tree :as rf.ssr.ui-tree]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ---------------------------------------------------------------------------
;; The parse model — see the ns docstring
;; ---------------------------------------------------------------------------

(defn- parsed-text-content
  "The DOM `textContent` a browser yields for `html`, a single element whose
  body is text. Strips the start and end tag, then applies the HTML5
  newline-eating rule: one LF immediately after a `<pre>` / `<listing>` /
  `<textarea>` start tag is DISCARDED by the tokenizer."
  [html]
  (let [[_ tag body] (re-matches #"(?s)<([A-Za-z][A-Za-z0-9-]*)[^>]*>(.*)</[A-Za-z][A-Za-z0-9-]*>"
                                 html)
        _            (assert (some? tag) (str "parse model saw no element: " (pr-str html)))
        newline-eater? (contains? rf.ssr.html-helpers/newline-eating-tags
                                  (str/lower-case tag))
        parsed       (if (and newline-eater? (str/starts-with? body "\n"))
                       (subs body 1)
                       body)]
    ;; Un-escape the entity forms this emitter produces, so the model reports
    ;; the DOM text rather than the wire text.
    (-> parsed
        (str/replace "&lt;" "<")
        (str/replace "&gt;" ">")
        (str/replace "&quot;" "\"")
        (str/replace "&#39;" "'")
        (str/replace "&amp;" "&"))))

(deftest parse-model-is-itself-exercised
  ;; The model underwrites every textContent assertion below, so prove it
  ;; BITES in the direction it is used: it must report the loss on the
  ;; uncompensated markup, and no loss on a non-newline-eating element.
  (testing "an UNCOMPENSATED newline-eating element loses its leading LF"
    (is (= "code" (parsed-text-content "<pre>\ncode</pre>"))
        "this is the defect rf2-s7l5 repairs, seen through the model"))
  (testing "a COMPENSATED one round-trips"
    (is (= "\ncode" (parsed-text-content "<pre>\n\ncode</pre>"))))
  (testing "a non-newline-eating element eats nothing"
    (is (= "\ncode" (parsed-text-content "<div>\ncode</div>"))))
  (testing "entity forms are read back as DOM text"
    (is (= "\n<a> & b" (parsed-text-content "<pre>\n\n&lt;a&gt; &amp; b</pre>")))))

;; ---------------------------------------------------------------------------
;; The shared rule — one roster, one implementation (acceptance: "share the
;; compensation rather than creating divergent rosters")
;; ---------------------------------------------------------------------------

(deftest one-roster-one-rule
  (testing "the newline-eating roster lives ONCE, in the shared helpers ns"
    (is (= #{"pre" "listing" "textarea"} rf.ssr.html-helpers/newline-eating-tags))
    (is (identical? rf.ssr.html-helpers/newline-eating-tags
                    rf.ssr.ui-tree/newline-eating-tags)
        "the S5 serialiser reads the SHARED roster, not a copy of it"))
  (testing "the shared rule itself"
    (is (= "\n" (rf.ssr.html-helpers/leading-newline-compensation "pre" "\nx")))
    (is (= "\n" (rf.ssr.html-helpers/leading-newline-compensation "textarea" "\nx")))
    (is (= ""   (rf.ssr.html-helpers/leading-newline-compensation "pre" "x")))
    (is (= ""   (rf.ssr.html-helpers/leading-newline-compensation "div" "\nx")))
    (is (= ""   (rf.ssr.html-helpers/leading-newline-compensation "pre" nil))
        "a nil body (no sole string) is never compensated"))
  (testing "the hiccup sole-string-body lever"
    (is (= "\nx" (rf.ssr.html-helpers/sole-string-child ["\nx"])))
    (is (nil? (rf.ssr.html-helpers/sole-string-child ["\na" "b"])))
    (is (nil? (rf.ssr.html-helpers/sole-string-child [[:b "x"]])))
    (is (nil? (rf.ssr.html-helpers/sole-string-child [])))))

;; ---------------------------------------------------------------------------
;; The non-streaming hiccup emitter — render-to-string
;; ---------------------------------------------------------------------------

(deftest non-streaming-hiccup-preserves-a-leading-newline
  (testing "[:pre \"\\ncode\"] — the bead's ordinary supported input"
    ;; RED-BEFORE lever: emitted "<pre>\ncode</pre>", parsing to "code".
    (let [html (rf.ssr.emit/render-to-string [:pre "\ncode"])]
      (is (= "<pre>\n\ncode</pre>" html)
          "byte parity with react-dom/server 19.2 renderToStaticMarkup")
      (is (= "\ncode" (parsed-text-content html))
          "textContent matches the authored string and the client rendering")))
  (testing "[:textarea \"\\ntext\"] — the same content loss"
    (let [html (rf.ssr.emit/render-to-string [:textarea "\ntext"])]
      (is (= "<textarea>\n\ntext</textarea>" html))
      (is (= "\ntext" (parsed-text-content html)))))
  (testing "<listing> is a newline-eating element too"
    (let [html (rf.ssr.emit/render-to-string [:listing "\nlisted"])]
      (is (= "<listing>\n\nlisted</listing>" html))
      (is (= "\nlisted" (parsed-text-content html)))))
  (testing "every EXTRA authored LF survives (one is eaten, the rest remain)"
    (let [html (rf.ssr.emit/render-to-string [:pre "\n\ncode"])]
      (is (= "<pre>\n\n\ncode</pre>" html))
      (is (= "\n\ncode" (parsed-text-content html)))))
  (testing "text is still escaped alongside the compensation"
    (let [html (rf.ssr.emit/render-to-string [:pre "\n<a> & b"])]
      (is (= "<pre>\n\n&lt;a&gt; &amp; b</pre>" html))
      (is (= "\n<a> & b" (parsed-text-content html))
          "the authored string, escaped on the wire and read back off the DOM")))
  (testing "an attrs map does not disturb the rule"
    (is (= "<pre class=\"c\">\n\ncode</pre>"
           (rf.ssr.emit/render-to-string [:pre {:class "c"} "\ncode"]))))
  (testing "tag-name case is normalised for classification, not for emission"
    (is (= "<PRE>\n\ncode</PRE>" (rf.ssr.emit/render-to-string [:PRE "\ncode"])))))

(deftest non-streaming-hiccup-vacuity-controls
  (testing "CONTROL: no leading LF ⇒ no compensation"
    (is (= "<pre>code</pre>" (rf.ssr.emit/render-to-string [:pre "code"])))
    (is (= "<textarea>text</textarea>" (rf.ssr.emit/render-to-string [:textarea "text"]))))
  (testing "CONTROL: a leading CR is not a newline-eating trigger"
    (is (= "<pre>\r\ncode</pre>" (rf.ssr.emit/render-to-string [:pre "\r\ncode"]))))
  (testing "CONTROL: a non-newline-eating element is never compensated"
    (is (= "<div>\ncode</div>" (rf.ssr.emit/render-to-string [:div "\ncode"]))))
  (testing "CONTROL: the existing MULTI-CHILD rule is preserved"
    (is (= "<pre>\nab</pre>" (rf.ssr.emit/render-to-string [:pre "\na" "b"]))
        "React leaves a multi-child body untouched; so do we")
    (is (= "<pre>\n<b>x</b></pre>" (rf.ssr.emit/render-to-string [:pre "\n" [:b "x"]]))
        "an element sibling is a multi-child body"))
  (testing "CONTROL: RAW-TEXT handling is untouched"
    (is (= "<script>\nvar a = 1 < 2;</script>"
           (rf.ssr.emit/render-to-string [:script "\nvar a = 1 < 2;"]))
        "script/style are raw text, never newline-eating, never escaped")
    (is (= "<style>\n.a { color: red }</style>"
           (rf.ssr.emit/render-to-string [:style "\n.a { color: red }"])))))

;; ---------------------------------------------------------------------------
;; The streaming hiccup emitter — render-shell's walk-dom-tag
;; ---------------------------------------------------------------------------

(defn- shell [hiccup]
  (:shell-html (rf.ssr.streaming/render-shell hiccup)))

(deftest streaming-hiccup-preserves-a-leading-newline
  (testing "the shell walker compensates exactly as the sync emitter does"
    ;; RED-BEFORE lever: walk-dom-tag emitted "<pre>\ncode</pre>".
    (let [html (shell [:pre "\ncode"])]
      (is (= "<pre>\n\ncode</pre>" html))
      (is (= "\ncode" (parsed-text-content html)))))
  (testing "textarea and listing through the shell walk"
    (is (= "<textarea>\n\ntext</textarea>" (shell [:textarea "\ntext"])))
    (is (= "<listing>\n\nlisted</listing>" (shell [:listing "\nlisted"]))))
  (testing "BYTE PARITY between the two hiccup emitters"
    (doseq [tree [[:pre "\ncode"] [:textarea "\ntext"] [:listing "\nl"]
                  [:pre "code"] [:pre "\r\ncode"] [:div "\ncode"]
                  [:pre "\na" "b"] [:pre {:class "c"} "\ncode"]
                  [:script "\nvar a = 1 < 2;"]]]
      (is (= (rf.ssr.emit/render-to-string tree) (shell tree))
          (str "the streaming shell and the sync emitter must agree byte-for-"
               "byte on " (pr-str tree)))))
  (testing "compensation survives NESTING inside the shell walk"
    (let [html (shell [:div [:pre "\ncode"]])]
      (is (= "<div><pre>\n\ncode</pre></div>" html)))))

(deftest streaming-hiccup-vacuity-controls
  (testing "CONTROL: no leading LF ⇒ no compensation"
    (is (= "<pre>code</pre>" (shell [:pre "code"]))))
  (testing "CONTROL: a non-newline-eating element is never compensated"
    (is (= "<div>\ncode</div>" (shell [:div "\ncode"]))))
  (testing "CONTROL: the multi-child rule is preserved in the shell walk too"
    (is (= "<pre>\nab</pre>" (shell [:pre "\na" "b"]))))
  (testing "CONTROL: raw-text handling is untouched in the shell walk"
    (is (= "<script>\nvar a = 1 < 2;</script>" (shell [:script "\nvar a = 1 < 2;"])))))

;; ---------------------------------------------------------------------------
;; Three-path agreement — the S5 serialiser already had the rule; the point of
;; the repair is that all three now emit the same bytes for the same content.
;; ---------------------------------------------------------------------------

(deftest all-three-ssr-paths-agree
  (doseq [[tag body] [["pre" "\ncode"] ["listing" "\nl"] ["textarea" "\nt"]
                      ["pre" "code"] ["div" "\ncode"]]]
    (let [hiccup   [(keyword tag) body]
          tree     {:rf.ui/tree-version 1
                    :tag                (keyword tag)
                    :children           [body]}
          from-s5  (rf.ssr.ui-tree/emit-ui-tree tree)]
      (is (= from-s5 (rf.ssr.emit/render-to-string hiccup))
          (str "S5 serialiser vs sync hiccup emitter on <" tag ">"))
      (is (= from-s5 (shell hiccup))
          (str "S5 serialiser vs streaming hiccup emitter on <" tag ">")))))
