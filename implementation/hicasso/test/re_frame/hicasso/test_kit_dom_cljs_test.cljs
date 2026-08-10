(ns re-frame.hicasso.test-kit-dom-cljs-test
  "THE CANONICAL-DOM COMPARATOR'S WITNESSES (rf2-hic-020).

  `re-frame.hicasso.test/canonical-dom` takes a DOM node, so its claims
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
            [re-frame.hicasso.test :as ht]))

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
        (is (= (ht/canonical-dom a) (ht/canonical-dom b)))
        (is (not= (.-innerHTML a) (.-innerHTML b))
            "and the control: the raw serialisation genuinely differs, so the
             equality above is the comparator's answer and not the DOM's"))

      (testing "the names are sorted, so the canonical form is stated rather
                than merely self-consistent"
        (is (= "<p class=\"row\" data-i=\"3\" id=\"one\">milk</p>"
               (ht/canonical-dom a))))

      (testing "a page that differs in an attribute VALUE is a different page"
        (is (not= (ht/canonical-dom a) (ht/canonical-dom c))))

      (testing "and so is one that differs in its text — without this the
                comparator could be a constant and every parity claim made
                through it would be vacuous"
        (is (not= (ht/canonical-dom a) (ht/canonical-dom d))))

      (testing "comments contribute nothing, because a comment is not the page"
        (is (= (ht/canonical-dom a)
               (ht/canonical-dom
                 (node! "<!-- note --><p id=\"one\" class=\"row\" data-i=\"3\">milk</p>")))))

      (testing "and a value that is not a DOM node refuses rather than
                serialising to something plausible"
        (let [refused (try (ht/canonical-dom {:tag :p}) nil
                           (catch :default e (ex-data e)))]
          (is (= {:rf.error/id :rf.error/hicasso-test-not-a-dom-node
                  :where       're-frame.hicasso.test
                  :recovery    :pass-a-dom-node}
                 (select-keys refused [:rf.error/id :where :recovery]))))))))
