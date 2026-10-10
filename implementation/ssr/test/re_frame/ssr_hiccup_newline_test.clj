(ns re-frame.ssr-hiccup-newline-test
  "Leading-LF compensation on the hiccup emitters. HTML parsing eats the first
  LF after `<pre>` / `<listing>` / `<textarea>`, so react-dom/server 19.2
  prefixes one compensating LF when such an element's body is a SINGLE STRING
  beginning with LF. Without it `[:pre \"\\ncode\"]` parses to `code`: one
  authored character lost, and a text hydration mismatch the render-tree hash
  (computed over the authored data) cannot see.

  The expected bytes are react-dom/server's own, measured; and
  `server-parse-matches-authored-and-client-text` parses react-dom's bytes with
  a conformant HTML5 parser (`nu.validator.htmlparser`, not jsoup, which keeps
  a textarea's LF) to show they round-trip to the authored string."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.ssr.ui-tree :as rf.ssr.ui-tree])
  (:import [java.io StringReader]
           [nu.validator.htmlparser.dom HtmlDocumentBuilder]
           [org.xml.sax InputSource]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- parsed-text-content
  "The `textContent` of the first `tag` element in the HTML5 parse of `html`."
  [tag html]
  (let [doc      (.parse (HtmlDocumentBuilder.)
                         (InputSource. (StringReader. html)))
        elements (.getElementsByTagName doc tag)]
    (assert (pos? (.getLength elements))
            (str "the parse produced no <" tag "> element: " (pr-str html)))
    (.getTextContent (.item elements 0))))

(deftest hiccup-preserves-a-leading-newline
  (testing "every EXTRA authored LF survives (one is eaten, the rest remain)"
    (is (= "<pre>\n\n\ncode</pre>" (rf.ssr.emit/render-to-string [:pre "\n\ncode"]))))
  (testing "text is still escaped alongside the compensation"
    (is (= "<pre>\n\n&lt;a&gt; &amp; b</pre>" (rf.ssr.emit/render-to-string [:pre "\n<a> & b"]))))
  (testing "an attrs map does not disturb the rule"
    (is (= "<pre class=\"c\">\n\ncode</pre>"
           (rf.ssr.emit/render-to-string [:pre {:class "c"} "\ncode"]))))
  (testing "tag-name case is normalised for classification, not for emission"
    (is (= "<PRE>\n\ncode</PRE>" (rf.ssr.emit/render-to-string [:PRE "\ncode"])))))

(deftest hiccup-vacuity-controls
  (testing "no leading LF, or a non-newline-eating element: no compensation"
    (is (= "<pre>code</pre>" (rf.ssr.emit/render-to-string [:pre "code"])))
    (is (= "<div>\ncode</div>" (rf.ssr.emit/render-to-string [:div "\ncode"]))))
  (testing "raw text is never newline-eating and never escaped"
    (is (= "<script>\nvar a = 1 < 2;</script>"
           (rf.ssr.emit/render-to-string [:script "\nvar a = 1 < 2;"]))))
  ;; React parity, not round-trips: react-dom/server compensates neither of
  ;; these, so neither does this emitter.
  (testing "a leading CR is not a trigger: React tests `charAt(0) === '\\n'`"
    (is (= "<pre>\r\ncode</pre>" (rf.ssr.emit/render-to-string [:pre "\r\ncode"]))))
  (testing "a multi-child body is not compensated"
    (is (= "<pre>\nab</pre>" (rf.ssr.emit/render-to-string [:pre "\na" "b"])))
    (is (= "<pre>\n<b>x</b></pre>" (rf.ssr.emit/render-to-string [:pre "\n" [:b "x"]]))
        "an element sibling is a multi-child body")))

(deftest both-ssr-paths-agree
  (doseq [[tag body] [["pre" "\ncode"] ["listing" "\nl"] ["textarea" "\nt"]
                      ["pre" "code"] ["div" "\ncode"]]]
    (let [hiccup   [(keyword tag) body]
          tree     {:rf.ui/tree-version 1
                    :tag                (keyword tag)
                    :children           [body]}
          from-s5  (rf.ssr.ui-tree/emit-ui-tree tree)]
      (is (= from-s5 (rf.ssr.emit/render-to-string hiccup))
          (str "S5 serialiser vs sync hiccup emitter on <" tag ">")))))

(def ^:private react-dom-static-markup
  "react-dom/server 19.2 `renderToStaticMarkup` output, measured, for the same
  content."
  {"pre"      ["\ncode"   "<pre>\n\ncode</pre>"]
   "listing"  ["\nlisted" "<listing>\n\nlisted</listing>"]
   "textarea" ["\ntext"   "<textarea>\n\ntext</textarea>"]})

(deftest server-parse-matches-authored-and-client-text
  ;; React's client sets a lone string child as a DOM text node whose data IS
  ;; the authored string, so server/client text agreement is exactly "the
  ;; server bytes parse back to the authored string".
  (doseq [[tag [authored expected-bytes]] react-dom-static-markup]
    (let [hiccup [(keyword tag) authored]]
      (is (= expected-bytes (rf.ssr.emit/render-to-string hiccup))
          (str "<" tag ">: byte parity with react-dom/server"))
      (is (= authored (parsed-text-content tag expected-bytes))
          (str "<" tag "> react-dom's bytes parse to the authored string")))))
