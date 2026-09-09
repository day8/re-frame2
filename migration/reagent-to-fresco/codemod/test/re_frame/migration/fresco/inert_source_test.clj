(ns re-frame.migration.hicasso.inert-source-test
  "**A crossing SITE is source that runs** (rf2-xc11).

  The census half learned this first (merged-PR audit #8140, PR #8151),
  and the repair stopped at the reporter because the audit that found it
  was scoped to the reporter. The fixer's population is a different
  estimand — `[:>]`-family CROSSING sites rather than Reagent CALL sites —
  so it was a separate claim, and it was true: the fixer's walk descended
  into a discard, a quote and a `(comment …)` body and reported every
  crossing it found there as a site.

  ## Why the fixer's stake is not the census's

  A report entry for an inert crossing is noise a migrator can ignore. But
  the fixer has a second walk, and that one WRITES: `--rewrite --write`
  respelled prop keys inside a `(comment …)` body, editing source the
  program never runs. Verified on `main` before the fix —

      (comment [:> Chart {:options {:page-size 10}}])
      #_[:> Chart {:options {:first-name 1}}]

  came back as `:pageSize` and `:firstName`. That is why both passes are
  pinned here and not just the reporting one: pruning `analyse` alone
  would have quietened the report while leaving the write in place, and
  the two passes are required to agree about what the file even contains.

  ## The boundary, and why it is a decision

  A syntax-quote is NOT inert. A macro's template emits a real crossing at
  every expansion, so a site inside one is a site. `census-test` pins that
  boundary for the reporter; [[a-syntax-quote-is-still-a-crossing-site]]
  pins the same boundary here, on the shared predicate both now consult.

  ## The third population (rf2-0xd6)

  `inert?` answers ONE question about the source, and three walks ask it.
  Everything above decides what is a SITE. The last section decides a
  refusal REASON *inside* a site that is live either way: an
  `r/as-element` in a `(comment …)` in a live site's props once cost the
  migrator a hand-port of a form that never runs. Pinned from
  [[an-inert-reagent-call-is-not-a-refusal-reason]] down."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.migration.hicasso.codemod :as rf.migration.hicasso.codemod]))

(def ^:private hdr
  "(ns app.p\n  (:require [reagent.core :as r]))\n")

(defn- classes
  "The report's entry classes for one source string."
  [body]
  (mapv :class (:entries (rf.migration.hicasso.codemod/scan-string (str hdr body) "app/p.cljs"))))

(defn- sites
  "How many crossing sites the walk counted."
  [body]
  (:sites (rf.migration.hicasso.codemod/scan-string (str hdr body) "app/p.cljs")))

(defn- rewritten
  "The fixer's output for one source string."
  [body]
  (:source (rf.migration.hicasso.codemod/rewrite-string (str hdr body) "app/p.cljs")))

;; ---------------------------------------------------------------------------
;; Pass 1 — the report
;; ---------------------------------------------------------------------------

(deftest inert-source-is-not-a-crossing-site
  (testing "the live crossing is the control every case below is measured
            against: without it, a walk that reported nothing at all would
            pass every assertion in this namespace"
    (is (= [:computed-value] (classes "[:> Foo {:on-click f}]\n")))
    (is (= 1 (sites "[:> Foo {:on-click f}]\n"))))

  (testing "rf2-xc11. Each of the three shapes parses into the same nodes a
            live crossing does, and nothing in any of them crosses into
            React. The `[:>]` is identical in all four cases; only its
            surroundings differ."
    (is (= [] (classes "(comment [:> Foo {:on-click f}])\n"))
        "`(comment …)` body — the bead's own probe")
    (is (= [] (classes "#_[:> Foo {:on-click f}]\n"))
        "`#_` discard")
    (is (= [] (classes "(def x '[:> Foo {:on-click f}])\n"))
        "reader quote")
    (is (= [] (classes "(def x (quote [:> Foo {:on-click f}]))\n"))
        "the `quote` special form spells the same thing")
    (is (= [] (classes "(clojure.core/comment [:> Foo {:on-click f}])\n"))
        "a fully-qualified `comment` is the same head"))

  (testing "the site COUNTER moves with the report. `:sites` is what the
            summary divides by, so an inert crossing left in it would
            survive as a denominator even once the entry was gone."
    (is (= 0 (sites "(comment [:> Foo {:on-click f}])\n")))
    (is (= 0 (sites "#_[:> Foo {:on-click f}]\n")))))

(deftest pruning-must-not-swallow-what-follows
  (testing "ADVERSARIAL, the other direction. A prune that took too much
            would pass every assertion above by reporting nothing at all,
            so each case here places a LIVE crossing where an over-eager
            walk would lose it."
    (is (= [:computed-value]
           (classes "(comment [:> A {:on-click f}])\n[:> B {:on-click g}]\n"))
        "a live crossing AFTER an inert form")
    (is (= [:computed-value]
           (classes "#_[:> A {:on-click f}]\n'[:> B {:on-click g}]\n(comment [:> C {:on-click h}])\n[:> D {:on-click i}]\n"))
        "three inert forms in a row, then the live one")
    (is (= [:computed-value]
           (classes "(defn f []\n  (comment [:> A {:on-click g}])\n  [:> B {:on-click h}])\n"))
        "an inert form NESTED inside a live one: the enclosing form keeps
         being walked")
    (is (= [:computed-value]
           (classes "[:> A {:on-click f}]\n(comment [:> B {:on-click g}])\n"))
        "the inert form ENDS the file: `past-subtree` answers nil there and
         the walk must terminate rather than fault or loop"))

  (testing "the file's last form being inert is the case that faults, not
            the case that miscounts — `analyse` guarded only on `z/end?`
            before rf2-xc11, and nil is not an end"
    (is (= 1 (sites "[:> A {:on-click f}]\n(comment [:> B {:on-click g}])\n")))
    (is (= 0 (sites "(comment [:> B {:on-click g}])\n")))))

(deftest a-syntax-quote-is-still-a-crossing-site
  (testing "the boundary, stated as a test so it is a decision and not an
            oversight. `#_`, `'` and `(comment …)` exist to NOT be code. A
            syntax-quote is a macro's template: the crossing it emits is
            real at every expansion, and its `~unquote`s run outright."
    (is (= [:computed-value]
           (classes "(defmacro m [] `[:> Foo {:on-click f}])\n")))))

;; ---------------------------------------------------------------------------
;; Pass 2 — the write, which is the arm with the teeth
;; ---------------------------------------------------------------------------

(deftest the-fixer-does-not-edit-inert-source
  (testing "rf2-xc11's actual stake. `rw-node` is a second walk over the
            same tree, and it is the one that writes: before the fix,
            `--rewrite --write` respelled a prop key inside a `(comment …)`
            body and inside a `#_` discard. The live crossing in the same
            file is the control — it MUST still be repaired, or a walk that
            edited nothing would pass."
    (let [body (str "(comment [:> Chart {:options {:page-size 10}}])\n"
                    "#_[:> Chart {:options {:first-name 1}}]\n"
                    "(def x '[:> Chart {:options {:page-size 10}}])\n"
                    "[:> Chart {:options {:page-size 10}}]\n")
          out  (rewritten body)]
      (is (re-find #"\(comment \[:> Chart \{:options \{:page-size 10\}\}\]\)" out)
          "the `(comment …)` body is returned byte-for-byte")
      (is (re-find #"#_\[:> Chart \{:options \{:first-name 1\}\}\]" out)
          "so is the discard")
      (is (re-find #"'\[:> Chart \{:options \{:page-size 10\}\}\]" out)
          "so is the quote")
      (is (re-find #"\n\[:> Chart \{:options \{:pageSize 10\}\}\]" out)
          "and the LIVE crossing is still repaired — W2 camelCases the
           nested key exactly as it always did")))

  (testing "a file whose only crossings are inert is returned unchanged,
            so `--rewrite` reports it as untouched rather than rewriting a
            comment and calling the file changed"
    (let [body "(comment [:> Chart {:options {:page-size 10}}])\n"]
      (is (= (str hdr body) (rewritten body)))))

  (testing "the two passes agree. `analyse` and `rw-node` walk the same tree
            separately, and the design's whole discipline is that they
            cannot reach different decisions — a file the report says has
            no sites must come back untouched."
    (let [body "#_[:> Chart {:options {:page-size 10}}]\n"]
      (is (= 0 (sites body)))
      (is (= (str hdr body) (rewritten body))))))

;; ---------------------------------------------------------------------------
;; The whole-file class, which is about a def rather than a crossing
;; ---------------------------------------------------------------------------

(deftest an-inert-adapt-def-is-not-a-def-site
  (testing "`:adapt-def-site` is planned from the same walk, so it prunes
            with it. A `(def Foo (r/adapt-react-class …))` inside a
            `(comment …)` defines nothing, and naming it in the report
            would send a migrator to convert a form that does not exist."
    (is (= [] (classes "(comment (def Foo (r/adapt-react-class X)))\n")))
    (is (= [:adapt-def-site] (classes "(def Foo (r/adapt-react-class X))\n"))
        "the live def is the control"))

  (testing "and the same for the CALL sites such an entry lists: a
            `[Foo …]` inside a comment is not a call site, so it must not
            appear in the line list the report tells a migrator to visit"
    (let [entry (first (:entries (rf.migration.hicasso.codemod/scan-string
                                  (str hdr
                                       "(def Foo (r/adapt-react-class X))\n"
                                       "(comment [Foo {:a 1}])\n"
                                       "[Foo {:b 2}]\n")
                                  "app/p.cljs")))]
      (is (= :adapt-def-site (:class entry)))
      (is (= [5] (get-in entry [:detail :call-sites-in-this-file]))
          "line 5 is the live call; line 4 is inside the comment"))))

;; ---------------------------------------------------------------------------
;; A refusal REASON computed inside a live site (rf2-0xd6)
;; ---------------------------------------------------------------------------

(deftest an-inert-reagent-call-is-not-a-refusal-reason
  (testing "rf2-0xd6. The site here is live and was always reported
            correctly; what was wrong is the EXTRA refusal beside it.
            `subtree-has-reagent-call?` recursed through `n/children`
            without consulting `inert?`, so an `r/as-element` inside a
            `(comment …)` in a live site's props read as an as-element
            island and the tool declined a repair it could have made."
    (is (= [:computed-value]
           (classes "[:> Foo {:x (comment (r/as-element [:div]))}]\n"))
        "the bead's own probe. `:computed-value` STAYS — that value really
         is one the tool cannot read — and only `:as-element-island` goes")
    (is (= [:computed-value]
           (classes "[:> Foo {:x #_(r/as-element [:div]) 1}]\n"))
        "`#_` discard")
    (is (= []
           (classes "[:> Foo {:x '(r/as-element [:div])}]\n"))
        "reader quote. A quoted form IS readable off the text, so once the
         island goes there is no `:computed-value` left behind it")
    (is (= [:computed-value]
           (classes "[:> Foo {:x (clojure.core/comment (r/as-element [:div]))}]\n"))
        "a fully-qualified `comment` is the same head"))

  (testing "the guard went INSIDE the shared predicate rather than at either
            call site, so the second caller — the Reagent-API arm, which
            passes a collection of symbols through the same walk — is
            answered by the same one call"
    (is (= [:computed-value]
           (classes "[:> Foo {:x (comment (r/atom 0))}]\n"))
        "an inert `r/atom` is not API residue either")
    (is (= [:computed-value]
           (classes "[:> Foo {:x (comment (r/with-let [a 1] a))}]\n"))
        "nor is an inert form-3 shape")))

(deftest a-live-reagent-call-is-still-a-refusal-reason
  (testing "ADVERSARIAL, and the arm that carries the weight: a fix which
            simply stopped reporting islands would pass every assertion
            above. Each case here puts the same call somewhere it RUNS."
    (is (= [:as-element-island]
           (classes "[:> Foo {:x (fn [] (r/as-element [:div]))}]\n"))
        "the live island, unchanged")
    (is (= [:reagent-api-residue]
           (classes "[:> Foo {:x (fn [] (r/atom 0))}]\n"))
        "and the live API residue, unchanged")
    (is (= [:as-element-island :computed-value]
           (classes (str "[:> Foo {:x (comment (r/as-element [:div]))\n"
                         "         :y (fn [] (r/as-element [:span]))}]\n")))
        "one inert and one LIVE `r/as-element` in the SAME props map. The
         prune is per-node, so it takes the `(comment …)` and nothing
         around it; a guard that skipped the map wholesale would lose the
         live one and report no island at all"))

  (testing "the syntax-quote boundary, restated on the refusal arm. `inert?`
            is one predicate and every population consults it, so a
            boundary that moved would move for all of them at once — and a
            macro's template emits a real `r/as-element` per expansion."
    (is (= [:as-element-island :computed-value]
           (classes "[:> Foo {:x `(r/as-element [:div])}]\n")))))
