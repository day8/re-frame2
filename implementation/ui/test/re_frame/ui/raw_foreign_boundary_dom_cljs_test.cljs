(ns re-frame.ui.raw-foreign-boundary-dom-cljs-test
  "S4-D (rf2-yjhrt) — the FOREIGN BOUNDARY corpus, CLIENT half (Spec 004
  §Interop and boundaries; the stage-profile row `§Interop — ui/raw` =
  S1 compile form + opaque marker, COMPLETES S4 with this corpus).

  The JVM half proves the boundary FAILS LOUD where it cannot render
  (raw_foreign_boundary_jvm_test). This half proves what it does where
  it CAN: the foreign value crosses into the real DOM untouched.

    - `ui/raw` splices an already-created React element into child
      position among compiled siblings, in document order. `re-frame.ui`
      never inspects it: the element's props were authored in REACT's
      own spelling (`className`, `tabIndex`) and arrive that way — the
      compiler's kebab conversion table is not applied across the
      boundary, because the compiler never sees the other side of it.

    - `ui/html` bypasses escaping: the string is PARSED as markup (real
      elements appear in the DOM), and nothing in it is sanitised — a
      `javascript:` URL and an inline handler attribute survive
      verbatim. The same characters in an ordinary string child are
      ESCAPED to text. That contrast is the whole XSS boundary, and it
      is the behaviour Spec 004 §Trusted markup documents.

  Rendering these assertions needs a real DOM (`ui/raw` is a host React
  element; `ui/html` lowers to `dangerouslySetInnerHTML`), so the
  browser job runs them and the node host skips."
  (:require ["react" :as React]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.test :as uit]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

;; A foreign React element, authored the way a foreign library would: REACT
;; prop spellings (className / tabIndex), not the compiler's kebab author-space.
(defn- foreign-element []
  (React/createElement "em" #js {:className "foreign" :tabIndex 4} "FOREIGN"))

(defview raw-host []
  [:div.host
   [:span.before "before"]
   (ui/raw (foreign-element))
   [:span.after "after"]])

;; Inert-by-construction hostile markup: the anchor is never clicked and the
;; handler attribute never fires — the point is that both survive UNFILTERED.
(def hostile
  "<a class=\"evil\" href=\"javascript:alert(1)\" onclick=\"window.__rf2_pwned = true\">click</a><b>bold &amp; raw</b>")

(defview trusted-host []
  [:div.trusted (ui/html hostile)])

(defview escaped-host []
  ;; The SAME characters as ordinary template text — the always-escaping path.
  [:div.escaped "<a class=\"evil\">click</a>"])

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true}))

(defn- q [root sel] (.querySelector root sel))

(defn- assert-raw-boundary [root]
  (let [host (q root ".host")
        kids (array-seq (.-children host))]
    (testing "the foreign element reaches the DOM in document order"
      (is (= 3 (count kids)) "compiled sibling, foreign element, compiled sibling")
      (is (= ["SPAN" "EM" "SPAN"] (mapv #(.-tagName %) kids)))
      (is (= ["before" "FOREIGN" "after"] (mapv #(.-textContent %) kids))))
    (testing "re-frame.ui does not touch the far side of the boundary"
      (let [em (q root "em")]
        (is (= "foreign" (.getAttribute em "class"))
            "React's className arrived as the class attribute")
        (is (= "4" (.getAttribute em "tabindex"))
            "React's tabIndex arrived — the compiler's conversion table was never applied")))))

(defn- assert-trusted-boundary [root]
  (testing "the string is PARSED as markup — real elements, not text"
    (is (some? (q root ".trusted a")) "the anchor became a real element")
    (is (some? (q root ".trusted b")) "so did the bold"))
  (testing "NOTHING is filtered — this is the documented XSS boundary"
    (let [a (q root ".trusted a")]
      (is (= "javascript:alert(1)" (.getAttribute a "href"))
          "the javascript: scheme is not gated")
      (is (= "window.__rf2_pwned = true" (.getAttribute a "onclick"))
          "an inline handler attribute is not stripped")))
  (testing "entities in the trusted string decode as markup, not as data"
    (is (= "bold & raw" (.-textContent (q root ".trusted b")))))
  (is (undefined? (.-__rf2_pwned js/window))
      "sanity: the fixture is inert — nothing was executed"))

(defn- assert-escaped-boundary [root]
  (let [el (q root ".escaped")]
    (is (nil? (.querySelector el "a"))
        "an ordinary string child creates NO element — strings always escape")
    (is (= "<a class=\"evil\">click</a>" (.-textContent el))
        "the markup survives as literal TEXT")))

;; `uit/with-root` compiles its template at macro-expansion time, so the view
;; head must be a literal at each call site — only the promise tail is shared.
(defn- finish [p done]
  (-> p
      (.then (fn [_] (done))
             (fn [e]
               (is false (str "mount rejected: " (some-> e ex-message)))
               (done)))))

(deftest raw-splices-a-foreign-element-verbatim-among-compiled-siblings
  (if-not (browser?)
    (is true "ui/raw is a host React element — the browser job runs it")
    (async done
      (finish (uit/with-root [root [raw-host]]
                (-> (uit/flush!)
                    (.then (fn [_] (assert-raw-boundary root)))))
              done))))

(deftest trusted-markup-is-parsed-and-never-sanitised
  (if-not (browser?)
    (is true "ui/html lowers to dangerouslySetInnerHTML — the browser job runs it")
    (async done
      (finish (uit/with-root [root [trusted-host]]
                (-> (uit/flush!)
                    (.then (fn [_] (assert-trusted-boundary root)))))
              done))))

(deftest an-ordinary-string-child-is-escaped-the-bypass-is-the-only-exception
  (if-not (browser?)
    (is true "the escaping contrast needs a DOM — the browser job runs it")
    (async done
      (finish (uit/with-root [root [escaped-host]]
                (-> (uit/flush!)
                    (.then (fn [_] (assert-escaped-boundary root)))))
              done))))
