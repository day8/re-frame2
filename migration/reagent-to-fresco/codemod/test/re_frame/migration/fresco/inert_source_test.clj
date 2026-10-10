(ns re-frame.migration.fresco.inert-source-test
  "**A crossing SITE is source that runs.**

  The fixer walks a consumer's tree to report and walks it again to WRITE,
  and neither walk may treat a discard, a quote or a `(comment …)` body as
  code: a `--rewrite --write` that respells a prop key inside a
  `(comment …)` edits source the program never runs. Both passes consult
  the one `inert?` predicate the census also uses, and so does the third
  walk, which decides whether a LIVE site's props hold a Reagent call that
  is a reason to refuse its repair.

  A syntax-quote is NOT inert: a macro's template emits a real crossing at
  every expansion, so a site inside one is a site."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.migration.fresco.codemod :as rf.migration.fresco.codemod]))

(def ^:private hdr
  "(ns app.p\n  (:require [reagent.core :as r]))\n")

(defn- scan [body] (rf.migration.fresco.codemod/scan-string (str hdr body) "app/p.cljs"))

(defn- classes
  "The report's entry classes for one source string."
  [body]
  (mapv :class (:entries (scan body))))

(defn- rewritten
  "The fixer's output for one source string."
  [body]
  (:source (rf.migration.fresco.codemod/rewrite-string (str hdr body) "app/p.cljs")))

;; ---------------------------------------------------------------------------
;; Pass 1 — the report
;; ---------------------------------------------------------------------------

(deftest inert-source-is-not-a-crossing-site
  (testing "the live crossing is the control; the same `[:>]` under an inert
            head is no site, and the site COUNTER — the summary's denominator
            — moves with the report"
    (is (= [[[:computed-value] 1] [[] 0] [[] 0] [[] 0]]
           (mapv (juxt classes (comp :sites scan))
                 ["[:> Foo {:on-click f}]\n"
                  "(comment [:> Foo {:on-click f}])\n"
                  "(def x (quote [:> Foo {:on-click f}]))\n"
                  "(clojure.core/comment [:> Foo {:on-click f}])\n"])))))

(deftest pruning-must-not-swallow-what-follows
  (testing "a prune that took too much would pass every assertion above by
            reporting nothing, so each case places a LIVE crossing where an
            over-eager walk would lose it: after three inert forms in a row,
            after an inert form NESTED inside a live one, and before an inert
            form that ENDS the file, where `past-subtree` answers nil and the
            walk must terminate rather than fault or loop"
    (is (= [[:computed-value] [:computed-value] [:computed-value]]
           (mapv classes
                 ["#_[:> A {:on-click f}]\n'[:> B {:on-click g}]\n(comment [:> C {:on-click h}])\n[:> D {:on-click i}]\n"
                  "(defn f []\n  (comment [:> A {:on-click g}])\n  [:> B {:on-click h}])\n"
                  "[:> A {:on-click f}]\n(comment [:> B {:on-click g}])\n"])))))

(deftest a-syntax-quote-is-still-a-crossing-site
  (testing "the boundary, stated as a test so it is a decision and not an
            oversight: the crossing a macro's template emits is real at every
            expansion, and its `~unquote`s run outright"
    (is (= [:computed-value]
           (classes "(defmacro m [] `[:> Foo {:on-click f}])\n")))))

;; ---------------------------------------------------------------------------
;; Pass 2 — the write, which is the arm with the teeth
;; ---------------------------------------------------------------------------

(deftest the-fixer-does-not-edit-inert-source
  (testing "the `(comment …)` body, the discard and the quote come back
            byte-for-byte, and the LIVE crossing beside them is still
            repaired — or a walk that edited nothing would pass"
    (let [inert (str "(comment [:> Chart {:options {:page-size 10}}])\n"
                     "#_[:> Chart {:options {:first-name 1}}]\n"
                     "(def x '[:> Chart {:options {:page-size 10}}])\n")]
      (is (= (str hdr inert "[:> Chart {:options {:pageSize 10}}]\n")
             (rewritten (str inert "[:> Chart {:options {:page-size 10}}]\n")))))))

(deftest an-inert-anonymous-fn-body-is-inert-for-every-walk
  (testing "`#(comment …)` and `#(quote …)` are a `(comment …)` and a quote
            behind an anonymous-fn literal, whose head is its body's head, so
            the report, the write and the refusal walk all skip them. The live
            literal is the control: a walk that skipped every literal would
            pass the rest."
    (let [live  "(def h #(do [:> Chart {:options {:page-size 10}}]))\n"
          inert (str "(def f #(comment [:> Chart {:options {:page-size 10}}]))\n"
                     "(def g #(quote [:> Chart {:options {:page-size 10}}]))\n")]
      (is (= [[:nested-map-keys] [] [] []]
             (mapv classes [live
                            inert
                            "[:> Foo {:x #(comment (r/as-element [:div]))}]\n"
                            "[:> Foo {:x #(quote (r/as-element [:div]))}]\n"])))
      (is (= (str hdr "(def h #(do [:> Chart {:options {:pageSize 10}}]))\n" inert)
             (rewritten (str live inert)))))))

;; ---------------------------------------------------------------------------
;; The whole-file class, which is about a def rather than a crossing
;; ---------------------------------------------------------------------------

(deftest an-inert-adapt-def-is-not-a-def-site
  (testing "`:adapt-def-site` is planned from the same walk, so it prunes
            with it: a `(def Foo (r/adapt-react-class …))` inside a
            `(comment …)` defines nothing, and a `[Foo …]` inside one is not
            a call site the report sends a migrator to — line 5 is the live
            call, line 4 is inside the comment"
    (is (= [] (classes "(comment (def Foo (r/adapt-react-class X)))\n")))
    (is (= [:adapt-def-site [5]]
           ((juxt :class #(get-in % [:detail :call-sites-in-this-file]))
            (first (:entries (scan (str "(def Foo (r/adapt-react-class X))\n"
                                        "(comment [Foo {:a 1}])\n"
                                        "[Foo {:b 2}]\n")))))))))

;; ---------------------------------------------------------------------------
;; A refusal REASON computed inside a live site
;; ---------------------------------------------------------------------------

(deftest an-inert-reagent-call-is-not-a-refusal-reason
  (testing "the site is live and reported either way; an inert `r/as-element`
            in its props must not add a refusal beside it. A value the tool
            cannot read stays `:computed-value`, while a quoted one is
            readable off the text and leaves nothing behind. The Reagent-API
            arm asks through the same walk, so an inert `r/atom` is no API
            residue either."
    (is (= [[:computed-value] [] [:computed-value]]
           (mapv classes ["[:> Foo {:x (comment (r/as-element [:div]))}]\n"
                          "[:> Foo {:x '(r/as-element [:div])}]\n"
                          "[:> Foo {:x (comment (r/atom 0))}]\n"])))))

(deftest a-live-reagent-call-is-still-a-refusal-reason
  (testing "ADVERSARIAL: a fix which simply stopped reporting islands would
            pass every assertion above, so each case puts the call where it
            RUNS — the live island, the live API residue, one inert and one
            live `r/as-element` in the SAME props map (the prune is per-node,
            so it must take the `(comment …)` and nothing around it), and a
            syntax-quote, which every walk treats as live"
    (is (= [[:as-element-island]
            [:reagent-api-residue]
            [:as-element-island :computed-value]
            [:as-element-island :computed-value]]
           (mapv classes ["[:> Foo {:x (fn [] (r/as-element [:div]))}]\n"
                          "[:> Foo {:x (fn [] (r/atom 0))}]\n"
                          (str "[:> Foo {:x (comment (r/as-element [:div]))\n"
                               "         :y (fn [] (r/as-element [:span]))}]\n")
                          "[:> Foo {:x `(r/as-element [:div])}]\n"])))))

(deftest a-reagent-call-at-the-head-of-an-anonymous-fn-is-a-refusal-reason
  (testing "`#(r/as-element [:div])` writes the same call as
            `(fn [] (r/as-element [:div]))`, so the refusal walk reads the
            literal as the call it writes, on the island arm and the API arm
            alike — and a Reagent fn passed as a VALUE inside a literal is no
            call"
    (is (= [[:as-element-island] [:reagent-api-residue] []]
           (mapv classes ["[:> Foo {:x #(r/as-element [:div])}]\n"
                          "[:> Foo {:x #(r/atom 0)}]\n"
                          "[:> Foo {:x #(run-later r/as-element)}]\n"]))))

  (testing "site detection does NOT read through the literal: a
            `#(r/adapt-react-class X)` is a function that adapts a class when
            it is called, so it is neither a W5 head nor an `:adapt-def-site`.
            Each expanded call is the control."
    (is (= [[:adapt-react-class-head] [] [:adapt-def-site] []]
           (mapv classes ["[(r/adapt-react-class X) {:a 1}]\n"
                          "[#(r/adapt-react-class X) {:a 1}]\n"
                          "(def Foo (r/adapt-react-class X))\n"
                          "(def Foo #(r/adapt-react-class X))\n"])))
    (is (= 0 (:sites (scan "[#(r/adapt-react-class X) {:a 1}]\n"))))))
