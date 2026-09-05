(ns re-frame.ssr.presence-truthiness-cljs-test
  "rf2-u82a (second pass) — `re-frame.ssr.html-helpers/presence-value-truthy?`
  driven on BOTH runtimes, because the defect this file exists for was a
  HOST ASYMMETRY and every other test of the predicate is JVM-only.

  ## Why this file is `.cljc` and not another `.clj` beside the parity test

  The predicate answers one question — *would react-dom treat this value on a
  presence attribute as present?* — for two serialisers
  (`re-frame.ssr.emit`'s hiccup path and `re-frame.ssr.ui-tree`'s structural
  path) on two runtimes. `ssr_boolean_attr_react_parity_test` pins it against
  react-dom's own measured bytes, which is the right anchor and the reason the
  rosters are trustworthy; but it is a `.clj`, so it speaks about the JVM
  alone. A predicate written to make TWO RUNTIMES emit the same bytes needs at
  least one suite that runs on both, and the bug below is precisely the shape
  that slips through when there is none.

  ## The bug, and why it read as cross-platform

  The predicate's NaN arm shipped as the self-inequality idiom `(not= v v)`,
  documented as *the one NaN check that reads the same on both runtimes*. It
  does not. In ClojureScript it is correct — `=` on numbers reaches
  `identical?`, and `NaN === NaN` is false. On the JVM it is DEAD CODE:
  `clojure.lang.Util/equiv` short-circuits on reference identity before
  comparing numerically, and a predicate parameter hands ONE boxed object to
  both argument positions, so `(= v v)` is `true` for every value a caller can
  supply and `(not= v v)` is `false` for all of them — NaN included. Nothing
  throws, no branch is skipped, and the arm simply never runs.

  So the value fell through to `true`, and BOTH public JVM emitters wrote a
  presence attribute react-dom omits: `[:button {:disabled ##NaN}]` rendered
  `<button disabled>` and the structural path `<button disabled=\"\">`, where
  react-dom 19.2.0 renders `<button>`. Sharing one predicate — which is what
  rf2-u82a's first pass correctly did — made the two serialisers agree on the
  same wrong answer, so no parity test BETWEEN them could see it either.

  That is also why these assertions are not symmetric evidence: on the old
  source they are GREEN in ClojureScript and RED on the JVM. The runtime that
  renders server-side is the JVM, so the half that was broken is the half that
  ships. Keeping both halves in one file is the point — the contract is that
  they agree, not that either is separately plausible.

  ## Why NaN is worth a suite at all

  It is not an exotic input: NaN is what arithmetic on a missing or
  non-numeric app-db value produces, so it arrives at a view attribute by
  accident rather than by authorship — and a wrong answer here is a
  server/client hydration divergence, which Spec 011 records React does not
  guarantee to patch. A server-rendered button is DISABLED where the browser
  believes the attribute absent; nothing errors and nothing repairs it.

  The controls beside it are the values a repair is most likely to take with
  it: the number `0` and `\"\"` must stay absent (JS-falsy, logically TRUE in
  Clojure), the STRING `\"0\"` and a whitespace string must stay present
  (truthy — only the NUMBER 0 is not), and infinity must stay present (JS
  truthiness is not finiteness). The overloaded pair is driven too, because a
  presence-class repair that reaches `download` is the failure mode the class
  split was introduced to stop."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]
            [re-frame.ssr.ui-tree :as rf.ssr.ui-tree]))

;; ---------------------------------------------------------------------------
;; 1. The shared predicate.
;; ---------------------------------------------------------------------------

(deftest presence-value-truthy-answers-nan-the-way-javascript-does
  (testing "rf2-u82a — NaN is JS-falsy, so a presence attribute given NaN is
            ABSENT. This is the assertion the `(not= v v)` arm could not
            satisfy on the JVM, where reference identity short-circuits `=`
            before it compares numerically"
    (is (false? (rf.ssr.html-helpers/presence-value-truthy? ##NaN))
        "NaN is falsy in JavaScript: react-dom writes no attribute"))

  (testing "rf2-u82a — and the repair did not widen. Every other number keeps
            the answer it had: zero (in both its integer and its floating
            spellings) is the OTHER value Clojure and JavaScript disagree
            about, and infinity is the reminder that JS truthiness is not
            finiteness"
    (is (false? (rf.ssr.html-helpers/presence-value-truthy? 0))
        "the NUMBER 0 is JS-falsy: absent")
    (is (false? (rf.ssr.html-helpers/presence-value-truthy? 0.0))
        "and so is 0.0 — the same number, and `zero?` sees both")
    (is (true? (rf.ssr.html-helpers/presence-value-truthy? 1))
        "an ordinary number is present")
    (is (true? (rf.ssr.html-helpers/presence-value-truthy? ##Inf))
        "infinity is truthy in JS — falsiness is not the same as unusualness")
    (is (true? (rf.ssr.html-helpers/presence-value-truthy? ##-Inf))
        "including negative infinity"))

  (testing "rf2-u82a — the non-numeric controls, unchanged. The STRING \"0\"
            is the trap in the other direction: it is a non-empty string and
            therefore truthy, where the NUMBER 0 is not"
    (is (true? (rf.ssr.html-helpers/presence-value-truthy? "0"))
        "the STRING \"0\" is truthy — only the number is not")
    (is (true? (rf.ssr.html-helpers/presence-value-truthy? " "))
        "a whitespace string is non-empty, so truthy — not `str/blank?`")
    (is (false? (rf.ssr.html-helpers/presence-value-truthy? ""))
        "the empty string is JS-falsy")
    (is (true? (rf.ssr.html-helpers/presence-value-truthy? "yes"))
        "an ordinary string is truthy")
    (is (false? (rf.ssr.html-helpers/presence-value-truthy? nil))
        "`null` is falsy in JS, and the predicate is total over nil")
    (is (true? (rf.ssr.html-helpers/presence-value-truthy? true)))
    (is (false? (rf.ssr.html-helpers/presence-value-truthy? false)))))

;; ---------------------------------------------------------------------------
;; 2. The two public emitters — the same answer, through the shipped surface.
;;
;; A predicate assertion alone would not have caught the original defect being
;; USER-VISIBLE: what makes it a bug rather than a wart is that both public
;; render paths wrote the attribute. Both are driven here, each against its
;; OWN no-attribute baseline, because the two pipelines spell presence
;; differently by design (bare `disabled` / `disabled=""`, 004B: one table,
;; two pipelines) and a byte comparison between them would fail for the wrong
;; reason.
;; ---------------------------------------------------------------------------

(defn- tree-html [attrs]
  (rf.ssr.ui-tree/emit-ui-tree {:rf.ui/tree-version 1 :tag :button :attrs attrs}))

(defn- hiccup-html [attrs]
  (rf.ssr.emit/render-to-string [:button attrs] {}))

(deftest both-public-emitters-omit-a-presence-attribute-given-nan
  (testing "rf2-u82a — the PUBLIC hiccup path. `[:button {:disabled ##NaN}]`
            must render exactly what the empty attribute map renders; on the
            old source it rendered `<button disabled>`"
    (is (= (hiccup-html {}) (hiccup-html {:disabled ##NaN}))
        "NaN writes no presence attribute at all"))

  (testing "rf2-u82a — the PUBLIC structural-tree path, which reaches the same
            shared predicate. On the old source it rendered `disabled=\"\"`,
            so the two pipelines agreed on the wrong answer and no parity test
            between them could see it"
    (is (= (tree-html {}) (tree-html {:disabled ##NaN}))
        "NaN writes no presence attribute at all"))

  (testing "rf2-u82a — and the emitters still disagree with nothing. The
            controls that a repair could plausibly have taken with it: the
            number 0 stays absent, the string \"0\" stays present"
    (is (= (hiccup-html {}) (hiccup-html {:disabled 0}))
        "the NUMBER 0 is JS-falsy: no attribute")
    (is (= (tree-html {}) (tree-html {:disabled 0}))
        "and the structural path agrees")
    (is (not= (hiccup-html {}) (hiccup-html {:disabled "0"}))
        "the STRING \"0\" is truthy: the attribute is written")
    (is (not= (tree-html {}) (tree-html {:disabled "0"}))
        "and the structural path agrees")))

;; ---------------------------------------------------------------------------
;; 3. The class split survives.
;; ---------------------------------------------------------------------------

(deftest an-overloaded-attribute-never-consults-the-presence-predicate
  (testing "rf2-u82a — `download` and `capture` are `:overloaded`: the two
            booleans behave exactly as `:presence`, but a NON-boolean value is
            KEPT rather than collapsed. They must therefore be unreachable
            from any change to the presence truthiness rule — a repair that
            collapsed NaN here would take `download=\"report.pdf\"` with it,
            which is the whole reason the class was split out of `:presence`
            in the first place"
    (is (not= (rf.ssr.emit/render-to-string [:a {}] {})
              (rf.ssr.emit/render-to-string [:a {:download ##NaN}] {}))
        "an overloaded name keeps a non-boolean value, NaN included")
    (is (not= (rf.ssr.emit/render-to-string [:a {}] {})
              (rf.ssr.emit/render-to-string [:a {:download 0}] {}))
        "and keeps the number 0, which the presence class omits")
    (is (= :overloaded (rf.ssr.html-helpers/boolean-attr-class "download"))
        "the split itself is still in place")
    (is (= :presence (rf.ssr.html-helpers/boolean-attr-class "disabled"))
        "and `disabled` is still the presence class this file drives")))
