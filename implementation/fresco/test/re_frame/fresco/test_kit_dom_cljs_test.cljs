(ns re-frame.fresco.test-kit-dom-cljs-test
  "THE CANONICAL-DOM COMPARATOR'S WITNESSES.

  `re-frame.fresco.test/canonical-dom` takes a DOM node, so its claims
  are taken on a REAL document rather than on a hand-built stand-in — a
  serialiser witnessed against a fake node graph proves the fake, and the
  attribute ordering this gate exists to neutralise is precisely a
  property of the real `NamedNodeMap`.

  ## The two halves, and why one without the other proves nothing

  The comparator's whole value is a pair of claims that pull in opposite
  directions:

  - it must **not** distinguish two pages that differ only in the ORDER
    their attributes were written, because that order is the serialiser's
    and not the page's — the fairness half; and
  - it must distinguish two pages that differ **at all** otherwise, or it
    is a constant and every parity claim ever made through it was
    vacuous — the sensitivity half.

  A comparator asserted on only the first is satisfied by
  `(constantly \"\")`. Both rows are here, on the same pair of nodes.

  This is the browser lane. `:node-test` compiles this namespace too
  (`cljs-test$` matches `-dom-cljs-test`), and every DOM claim degrades
  there to a STATED skip rather than to a false green."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.fresco.test :as rf.fresco.test]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip!
  [why]
  (is true (str "a canonical-DOM claim needs a real document — " why)))

(defn- node!
  "A detached container carrying `html`. Detached on purpose: the
  comparator walks a subtree and needs no layout, so nothing here has to
  reach the document body."
  [html]
  (let [c (js/document.createElement "div")]
    (set! (.-innerHTML c) html)
    c))

(deftest canonical-dom-neutralises-attribute-order-and-nothing-else
  (if-not (browser?)
    (skip! ":node-test has no document")
    (let [a (node! "<p id=\"one\" class=\"row\" data-i=\"3\">milk</p>")
          b (node! "<p data-i=\"3\" class=\"row\" id=\"one\">milk</p>")
          c (node! "<p id=\"one\" class=\"row\" data-i=\"4\">milk</p>")
          d (node! "<p id=\"one\" class=\"row\" data-i=\"3\">bread</p>")]

      (testing "two pages written in different attribute orders are the same
                page, which `innerHTML` alone cannot say"
        (is (= (rf.fresco.test/canonical-dom a) (rf.fresco.test/canonical-dom b)))
        (is (not= (.-innerHTML a) (.-innerHTML b))
            "and the control: the raw serialisation genuinely differs, so the
             equality above is the comparator's answer and not the DOM's"))

      (testing "the names are sorted, so the canonical form is stated rather
                than merely self-consistent"
        (is (= "<p class=\"row\" data-i=\"3\" id=\"one\">milk</p>"
               (rf.fresco.test/canonical-dom a))))

      (testing "a page that differs in an attribute VALUE is a different page"
        (is (not= (rf.fresco.test/canonical-dom a) (rf.fresco.test/canonical-dom c))))

      (testing "and so is one that differs in its text — without this the
                comparator could be a constant and every parity claim made
                through it would be vacuous"
        (is (not= (rf.fresco.test/canonical-dom a) (rf.fresco.test/canonical-dom d))))

      (testing "comments contribute nothing, because a comment is not the page"
        (is (= (rf.fresco.test/canonical-dom a)
               (rf.fresco.test/canonical-dom
                 (node! "<!-- note --><p id=\"one\" class=\"row\" data-i=\"3\">milk</p>")))))

      (testing "adjacent text nodes are one text run, so a split the DOM
                happens to carry is not a difference in the page"
        (let [split (js/document.createElement "div")
              p     (js/document.createElement "p")]
          (set! (.-id p) "one")
          (.setAttribute p "class" "row")
          (.setAttribute p "data-i" "3")
          (.appendChild p (js/document.createTextNode "mi"))
          (.appendChild p (js/document.createTextNode "lk"))
          (.appendChild split p)
          (is (= (rf.fresco.test/canonical-dom a) (rf.fresco.test/canonical-dom split)))))

      (testing "and a value that is not a DOM node refuses rather than
                serialising to something plausible"
        (let [refused (try (rf.fresco.test/canonical-dom {:tag :p}) nil
                           (catch :default e (ex-data e)))]
          (is (= {:rf.error/id :rf.error/fresco-test-not-a-dom-node
                  :where       're-frame.fresco.test}
                 (select-keys refused [:rf.error/id :where]))))))))

(deftest data-cannot-imitate-the-serialisers-own-structure
  ;; rf2-kovp. The sensitivity half above is asserted on ordinary values —
  ;; `3` against `4`, `milk` against `bread` — and every one of those
  ;; differs somewhere the serialiser's alphabet is unambiguous. These rows
  ;; are the case where it is NOT: the page's own data is written in the
  ;; characters the serialiser reserves for structure, so a comparator that
  ;; emits data raw declares two visibly different pages equal, and every
  ;; parity claim taken through it for such a page was vacuous.
  (if-not (browser?)
    (skip! ":node-test has no document")
    (testing "a container whose TEXT reads like markup is not a container
              holding that markup"
      (let [literal (js/document.createElement "div")
            real    (node! "<p>x</p>")]
        (set! (.-textContent literal) "<p>x</p>")
        (is (= "<p>x</p>" (.-textContent literal))
            "the control: the page really is showing those characters to a
             user, which is ordinary rendered content and not malformed DOM")
        (is (not= (rf.fresco.test/canonical-dom literal)
                  (rf.fresco.test/canonical-dom real))
            "two different pages, therefore two different canonical forms")
        (is (= "&lt;p&gt;x&lt;/p&gt;" (rf.fresco.test/canonical-dom literal))
            "and the canonical form is stated, so what the escape produces is
             pinned rather than merely differing from something")))))

(deftest a-quote-in-an-attribute-value-cannot-become-a-second-attribute
  ;; rf2-kovp, the same ambiguity through the attribute door: `"` is the
  ;; serialiser's own value delimiter, so an unescaped one closes the slot
  ;; and opens what reads as another attribute. A page carrying a handler
  ;; would compare equal to a page carrying none.
  (if-not (browser?)
    (skip! ":node-test has no document")
    (let [one (js/document.createElement "div")
          two (js/document.createElement "div")
          p1  (js/document.createElement "p")
          p2  (js/document.createElement "p")]
      (.setAttribute p1 "data-x" "y\" data-z=\"w")
      (.appendChild one p1)
      (.setAttribute p2 "data-x" "y")
      (.setAttribute p2 "data-z" "w")
      (.appendChild two p2)
      (testing "one attribute whose value spells two is still one attribute"
        (is (= 1 (.-length (.-attributes p1))))
        (is (= 2 (.-length (.-attributes p2))))
        (is (not= (rf.fresco.test/canonical-dom one)
                  (rf.fresco.test/canonical-dom two))))
      (testing "and an ampersand a page really shows survives round-distinctly,
                so the escape is not itself a new collision"
        (let [amp (js/document.createElement "div")
              lt  (js/document.createElement "div")]
          (set! (.-textContent amp) "&lt;")
          (set! (.-textContent lt) "<")
          (is (not= (rf.fresco.test/canonical-dom amp)
                    (rf.fresco.test/canonical-dom lt))))))))
