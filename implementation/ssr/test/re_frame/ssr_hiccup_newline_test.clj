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
  client agree on the hash while disagreeing on the text node. The emitted
  BYTES are where it becomes visible without a browser, and pinning those bytes
  against react-dom's is what this namespace does.

  WHAT THIS WITNESS OBSERVES (rf2-s7l5, merged-PR audits #9377 and #9391). It
  PARSES the emitted markup with a conformant HTML5 parser and reads the
  resulting DOM node's `textContent`. Two things are asserted, and both are
  named honestly at every assertion site:

    1. EMITTED BYTES. `render-to-string` / `render-shell` / `emit-ui-tree`
       produce a specific string, pinned against react-dom/server 19.2's own
       `renderToStaticMarkup` output for the same content. This is a real,
       independent pin: the expected bytes were measured from react-dom, not
       derived from this repository's rule.

    2. PARSED `textContent`. `parsed-text-content` below runs those bytes
       through `nu.validator.htmlparser` — a direct port of the HTML5
       tree-construction algorithm, the parser behind the W3C/WHATWG validator
       — which yields an `org.w3c.dom` tree, and reads `Node.getTextContent()`
       off the element. That is the browser contract's own vocabulary, not a
       re-statement of this repository's rule, and it is what the acceptance
       criterion asks for.

  WHY THAT MATTERS, AND WHAT IT REPLACED. Until rf2-s7l5's second pass this
  namespace carried a hand-written MODEL of the tokenizer rule instead of a
  parse. A model cannot independently establish the rule it models: if the
  production compensation were wrong about WHICH elements eat a newline, or
  about HOW MANY characters are eaten, a model sharing that belief agrees with
  it and every assertion stays green. The parser has no such coupling — it was
  written from the HTML Standard by people who had never seen this repository.

  WHY validator.nu AND NOT jsoup. jsoup is the obvious reach and it is wrong
  for this witness — MEASURED on jsoup 1.22.1: its tree builder consumes the LF
  for `pre` / `listing` but NOT for `textarea`, so a textarea assertion written
  against it would pin jsoup's own conformance gap as the browser's behaviour.
  See `implementation/ssr/deps.edn`.

  THE CLIENT-RENDERING LEG. React's client path never parses HTML for a text
  child: `createElement(:pre, nil, \"\\ncode\")` sets a DOM text node whose data
  IS the authored string, so the client `textContent` and the authored string
  are the same object of comparison. The server leg is therefore the whole
  question, and `server-parse-matches-authored-and-client-text` below closes it
  from both ends — our emitted bytes AND react-dom/server's own measured bytes
  parse to the authored string.

  WHAT IS STILL NOT OBSERVED HERE: a real browser. A conformant parser plus
  byte parity with react-dom/server is the bound; a browser would add the
  rendering engine's own text handling, which no gate in this artefact reaches.

  JVM-only, mirroring `re-frame.ssr-emit-test`: the emitters are
  platform-neutral `.cljc` and the shared rule's own unit-level proof runs on
  both platforms via `re-frame.ssr.emit-ui-tree-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]
            [re-frame.ssr.streaming :as rf.ssr.streaming]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.ssr.ui-tree :as rf.ssr.ui-tree])
  (:import [java.io StringReader]
           [nu.validator.htmlparser.dom HtmlDocumentBuilder]
           [org.xml.sax InputSource]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ---------------------------------------------------------------------------
;; The PARSER — a real HTML5 tree construction, not a model of one.
;; ---------------------------------------------------------------------------

(def ^:private html5-newline-eating-elements
  "The newline-eating elements, written out from the HTML Standard
  (§13.2.6.4.x — the `in body` insertion mode drops one LF immediately after a
  `pre` / `listing` / `textarea` start tag).

  DELIBERATELY A LITERAL, not `rf.ssr.html-helpers/newline-eating-tags`: this
  namespace must not read the production roster it is used to check, or a wrong
  roster would agree with itself. `one-roster-one-rule` pins the production
  roster against this literal from the other side. The PARSER below shares no
  roster with either — it derives the behaviour from the standard itself, which
  is the whole reason it is here."
  #{"pre" "listing" "textarea"})

(defn- parsed-text-content
  "PARSES `html` with `nu.validator.htmlparser` — a port of the HTML5
  tree-construction algorithm — and returns the `textContent` of the first
  element in the resulting `org.w3c.dom` document whose tag name is `tag`.

  This is the browser contract's own vocabulary: `Node.getTextContent()` on a
  real parsed node, not a re-statement of this repository's compensation rule.
  Entity references, the newline-eating rule and raw-text element handling are
  all the parser's, not ours."
  [tag html]
  (let [doc      (.parse (HtmlDocumentBuilder.)
                         (InputSource. (StringReader. html)))
        elements (.getElementsByTagName doc tag)]
    (assert (pos? (.getLength elements))
            (str "the parse produced no <" tag "> element: " (pr-str html)))
    (.getTextContent (.item elements 0))))

(deftest the-parser-is-itself-exercised
  ;; NEGATIVE CONTROL, and the acceptance criterion names it: the parser must
  ;; report the LOSS on UNCOMPENSATED markup — the exact pre-repair emitter
  ;; output — or every green below would be vacuous. A witness that has never
  ;; been red about the defect is not a witness.
  (testing "UNCOMPENSATED markup — the pre-repair bytes — loses its leading LF"
    (is (= "code" (parsed-text-content "pre" "<pre>\ncode</pre>"))
        "this is rf2-s7l5's defect, observed rather than modelled")
    (is (= "listed" (parsed-text-content "listing" "<listing>\nlisted</listing>")))
    (is (= "text" (parsed-text-content "textarea" "<textarea>\ntext</textarea>"))))
  (testing "COMPENSATED markup round-trips"
    (is (= "\ncode" (parsed-text-content "pre" "<pre>\n\ncode</pre>"))))
  (testing "a non-newline-eating element eats nothing, compensated or not"
    (is (= "\ncode" (parsed-text-content "div" "<div>\ncode</div>"))))
  (testing "the parser owns entity decoding"
    (is (= "\n<a> & b" (parsed-text-content "pre" "<pre>\n\n&lt;a&gt; &amp; b</pre>"))))
  (testing "the roster the parser exhibits IS the HTML Standard literal"
    ;; Read off the parser rather than asserted at it: for each element, the
    ;; compensated and uncompensated parses differ exactly where the standard
    ;; says one LF is eaten.
    (is (= html5-newline-eating-elements
           (set (for [tag   ["pre" "listing" "textarea" "div" "span" "p"]
                      :when (not= (parsed-text-content
                                   tag (str "<" tag ">\nx</" tag ">"))
                                  "\nx")]
                  tag)))
        "pre / listing / textarea eat a leading LF; nothing else does")))

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
      (is (= "\ncode" (parsed-text-content "pre" html))
          "the PARSED textContent is the authored string")))
  (testing "[:textarea \"\\ntext\"] — the same content loss"
    (let [html (rf.ssr.emit/render-to-string [:textarea "\ntext"])]
      (is (= "<textarea>\n\ntext</textarea>" html))
      (is (= "\ntext" (parsed-text-content "textarea" html)))))
  (testing "<listing> is a newline-eating element too"
    (let [html (rf.ssr.emit/render-to-string [:listing "\nlisted"])]
      (is (= "<listing>\n\nlisted</listing>" html))
      (is (= "\nlisted" (parsed-text-content "listing" html)))))
  (testing "every EXTRA authored LF survives (one is eaten, the rest remain)"
    (let [html (rf.ssr.emit/render-to-string [:pre "\n\ncode"])]
      (is (= "<pre>\n\n\ncode</pre>" html))
      (is (= "\n\ncode" (parsed-text-content "pre" html)))))
  (testing "text is still escaped alongside the compensation"
    (let [html (rf.ssr.emit/render-to-string [:pre "\n<a> & b"])]
      (is (= "<pre>\n\n&lt;a&gt; &amp; b</pre>" html))
      (is (= "\n<a> & b" (parsed-text-content "pre" html))
          "the authored string, escaped on the wire and decoded by the parser")))
  (testing "an attrs map does not disturb the rule"
    (is (= "<pre class=\"c\">\n\ncode</pre>"
           (rf.ssr.emit/render-to-string [:pre {:class "c"} "\ncode"]))))
  (testing "tag-name case is normalised for classification, not for emission"
    (is (= "<PRE>\n\ncode</PRE>" (rf.ssr.emit/render-to-string [:PRE "\ncode"])))))

(deftest non-streaming-hiccup-vacuity-controls
  (testing "CONTROL: no leading LF ⇒ no compensation"
    (let [html (rf.ssr.emit/render-to-string [:pre "code"])]
      (is (= "<pre>code</pre>" html))
      (is (= "code" (parsed-text-content "pre" html))
          "the acceptance's no-leading-LF control, observed through the parser"))
    (let [html (rf.ssr.emit/render-to-string [:textarea "text"])]
      (is (= "<textarea>text</textarea>" html))
      (is (= "text" (parsed-text-content "textarea" html)))))
  (testing "CONTROL: a non-newline-eating element is never compensated"
    (let [html (rf.ssr.emit/render-to-string [:div "\ncode"])]
      (is (= "<div>\ncode</div>" html))
      (is (= "\ncode" (parsed-text-content "div" html))
          "no compensation is owed, and none is lost")))
  (testing "CONTROL: RAW-TEXT handling is untouched"
    (let [html (rf.ssr.emit/render-to-string [:script "\nvar a = 1 < 2;"])]
      (is (= "<script>\nvar a = 1 < 2;</script>" html)
          "script/style are raw text, never newline-eating, never escaped")
      (is (= "\nvar a = 1 < 2;" (parsed-text-content "script" html))
          "the parser confirms it: raw text, LF intact, `<` not an entity"))
    (is (= "<style>\n.a { color: red }</style>"
           (rf.ssr.emit/render-to-string [:style "\n.a { color: red }"]))))
  ;; The two controls below record REACT-PARITY DIVERGENCES rather than
  ;; round-trips. In each the parser DOES eat a character that no compensation
  ;; replaces — because react-dom/server does not compensate either, and byte
  ;; parity with react-dom is this emitter's contract. Pinning what the parser
  ;; actually reports keeps the witness from overstating the repair's reach.
  (testing "CONTROL: a leading CR is not a newline-eating trigger (React parity)"
    (let [html (rf.ssr.emit/render-to-string [:pre "\r\ncode"])]
      (is (= "<pre>\r\ncode</pre>" html)
          "React's test is `charAt(0) === '\\n'`, so CRLF is not compensated")
      (is (= "code" (parsed-text-content "pre" html))
          "and the parser normalises CRLF→LF before eating it, so BOTH
           characters go — a divergence shared with react-dom/server, not a
           regression this bead introduces")))
  (testing "CONTROL: the existing MULTI-CHILD rule is preserved (React parity)"
    (let [html (rf.ssr.emit/render-to-string [:pre "\na" "b"])]
      (is (= "<pre>\nab</pre>" html)
          "React leaves a multi-child body untouched; so do we")
      (is (= "ab" (parsed-text-content "pre" html))
          "so the parser still eats that LF — React's own documented behaviour
           for `typeof children !== 'string'`"))
    (is (= "<pre>\n<b>x</b></pre>" (rf.ssr.emit/render-to-string [:pre "\n" [:b "x"]]))
        "an element sibling is a multi-child body")))

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
      (is (= "\ncode" (parsed-text-content "pre" html)))))
  (testing "textarea and listing through the shell walk"
    (let [html (shell [:textarea "\ntext"])]
      (is (= "<textarea>\n\ntext</textarea>" html))
      (is (= "\ntext" (parsed-text-content "textarea" html))))
    (let [html (shell [:listing "\nlisted"])]
      (is (= "<listing>\n\nlisted</listing>" html))
      (is (= "\nlisted" (parsed-text-content "listing" html)))))
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
    (let [html (shell [:pre "code"])]
      (is (= "<pre>code</pre>" html))
      (is (= "code" (parsed-text-content "pre" html)))))
  (testing "CONTROL: a non-newline-eating element is never compensated"
    (let [html (shell [:div "\ncode"])]
      (is (= "<div>\ncode</div>" html))
      (is (= "\ncode" (parsed-text-content "div" html)))))
  (testing "CONTROL: the multi-child rule is preserved in the shell walk too"
    (is (= "<pre>\nab</pre>" (shell [:pre "\na" "b"]))))
  (testing "CONTROL: raw-text handling is untouched in the shell walk"
    (let [html (shell [:script "\nvar a = 1 < 2;"])]
      (is (= "<script>\nvar a = 1 < 2;</script>" html))
      (is (= "\nvar a = 1 < 2;" (parsed-text-content "script" html))))))

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

;; ---------------------------------------------------------------------------
;; rf2-s7l5's acceptance criterion, stated once and closed in one place.
;; ---------------------------------------------------------------------------

(def ^:private react-dom-static-markup
  "react-dom/server 19.2 `renderToStaticMarkup` output, MEASURED, for the same
  content. The independent reference: these bytes were read off react-dom, not
  derived from this repository's rule, so parsing them is a check on the
  BROWSER-side claim rather than on our own emitter."
  {"pre"      ["\ncode"   "<pre>\n\ncode</pre>"]
   "listing"  ["\nlisted" "<listing>\n\nlisted</listing>"]
   "textarea" ["\ntext"   "<textarea>\n\ntext</textarea>"]})

(deftest server-parse-matches-authored-and-client-text
  ;; THE ACCEPTANCE: for one LF-leading string under pre / listing / textarea,
  ;; PARSE the emitted non-streaming AND streaming HTML and assert the parsed
  ;; textContent is the authored string — which is also the client rendering,
  ;; because React's client path sets a lone string child as a DOM text node
  ;; whose `data` IS that string (no HTML parse is involved on the client, so
  ;; nothing can eat a newline there). Server-vs-client text agreement is
  ;; therefore exactly "the parsed server text equals the authored string".
  (doseq [[tag [authored expected-bytes]] react-dom-static-markup]
    (let [hiccup      [(keyword tag) authored]
          sync-html   (rf.ssr.emit/render-to-string hiccup)
          stream-html (shell hiccup)]
      (testing (str "<" tag "> — non-streaming")
        (is (= expected-bytes sync-html)
            "byte parity with the measured react-dom/server output")
        (is (= authored (parsed-text-content tag sync-html))
            "parsed textContent == the authored string == the client rendering"))
      (testing (str "<" tag "> — streaming")
        (is (= expected-bytes stream-html))
        (is (= authored (parsed-text-content tag stream-html))
            "parsed textContent == the authored string == the client rendering"))
      (testing (str "<" tag "> — react-dom's own bytes parse the same way")
        (is (= authored (parsed-text-content tag expected-bytes))
            "the browser-side claim, checked against react-dom rather than us"))
      (testing (str "<" tag "> — NEGATIVE CONTROL: the pre-repair bytes")
        (is (not= authored
                  (parsed-text-content tag (str "<" tag ">" authored "</" tag ">")))
            "uncompensated markup — what both hiccup emitters produced before
             rf2-s7l5 — parses to one character SHORT of the authored string")))))
