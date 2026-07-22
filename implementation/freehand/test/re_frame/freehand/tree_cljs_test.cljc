(ns re-frame.freehand.tree-cljs-test
  "FH-STRUCT-001 … FH-STRUCT-005 and FH-STRUCT-008 — the versioned node
  schema, the element node's pinned fields, attribute values in semantic
  space, child normalization, namespace context, and the JavaScript
  number grammar over the double value space.

  Every row is table-driven from its fixture, and every fixture runs on
  the JVM and in ClojureScript from the same bytes. That is not a
  convenience: the two hosts disagree about number formatting, and a walk
  that reached for the host's own `str` would answer two different trees
  for one form. A `.cljc` claim proven on one host would be a gap wearing
  a pass's clothes."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.conversion :as conv]
            [re-frame.freehand.tree :as tree]))

(defn- realize
  "Substitute a real function for the fixture's `:fh/fn` stand-in — EDN
  carries no functions, and a handler site's classification turns on
  whether the value IS one."
  [form]
  (walk/postwalk #(if (= :fh/fn %) (fn [_] nil) %) form))

(defn- check-cases
  [fixture]
  (is (seq (:cases fixture)) "the fixture's case table loaded")
  (doseq [{:keys [note form tree]} (:cases fixture)]
    (is (= tree (tree/render (realize form))) note)))

(defn- check-rejected
  [fixture]
  (doseq [{:keys [note form error-id]} (:rejected fixture)]
    (is (= error-id (conf/caught-id #(tree/render (realize form)))) note)))

;; ---------------------------------------------------------------------------
;; FH-STRUCT-001 — the node schema
;; ---------------------------------------------------------------------------

(def struct-001 (conf/fixture :FH-STRUCT-001))

(deftest fh-struct-001-the-node-schema-and-its-discrimination
  (testing "Per FH-STRUCT-001: five variants, a closed set, discriminated
            in one pinned order. Text is the host string itself and not a
            map, so a text-rooted form roots in a fragment — the variant
            whose whole job is to hold a run of children."
    (check-cases struct-001)))

(deftest fh-struct-001-the-root-carries-the-schema-version
  (testing "Per FH-STRUCT-001: `:rf.ui/tree-version` is the required gate
            every consumer validates FIRST, and it lives on the root
            alone. An interior node carrying one would make the version a
            property of a position rather than of a tree."
    (let [root (tree/render [:ul [:li "a"] [:li [:b "b"]]])]
      (is (= tree/tree-version (:rf.ui/tree-version root)))
      (is (every? #(not (contains? % :rf.ui/tree-version))
                  (filter map? (rest (tree-seq map? :children root))))
          "no interior node carries a version"))))

(deftest fh-struct-001-an-uncarryable-value-fails-loud
  (testing "Per FH-STRUCT-001: a value the closed node set cannot carry
            fails loud rather than rendering something plausible. A
            silently-dropped child is the failure mode a structural test
            can never see."
    (is (seq (:rejected struct-001)) "the fixture's rejected table loaded")
    (check-rejected struct-001)))

;; ---------------------------------------------------------------------------
;; FH-STRUCT-002 — element fields
;; ---------------------------------------------------------------------------

(def struct-002 (conf/fixture :FH-STRUCT-002))

(deftest fh-struct-002-element-fields-are-pinned
  (testing "Per FH-STRUCT-002: the tag survives verbatim, sugar merges
            sugar-first, `:attrs` and `:events` are disjoint by
            construction, `:key` is present iff the site was keyed, and
            every optional field is absent when empty — so one semantic
            element has exactly one representation."
    (check-cases struct-002)))

(deftest fh-struct-002-ambiguous-and-impossible-elements-are-rejected
  (testing "Per FH-STRUCT-002: two id spellings on one element is an
            ambiguity this grammar removes rather than ranks, and a void
            element's children are impossible rather than ignorable."
    (is (seq (:rejected struct-002)) "the fixture's rejected table loaded")
    (check-rejected struct-002)))

;; ---------------------------------------------------------------------------
;; FH-STRUCT-003 — attribute values
;; ---------------------------------------------------------------------------

(def struct-003 (conf/fixture :FH-STRUCT-003))

(deftest fh-struct-003-attr-values-normalize-into-semantic-space
  (testing "Per FH-STRUCT-003: one canonical class string, a canonical
            style map, `name` for keywords, JavaScript `ToString` for
            numbers, booleans intact, nils gone. The number rows are the
            cross-host teeth — they are the ones the JVM gets wrong for
            free."
    (check-cases struct-003)))

(deftest fh-struct-003-values-outside-the-grammar-are-rejected
  (testing "Per FH-STRUCT-003: a collection outside `:class`/`:style`
            would reach the DOM as `[object Object]`, so it is a loud
            reject and not a silent stringification."
    (is (seq (:rejected struct-003)) "the fixture's rejected table loaded")
    (check-rejected struct-003)))

;; ---------------------------------------------------------------------------
;; FH-STRUCT-004 — child normalization
;; ---------------------------------------------------------------------------

(def struct-004 (conf/fixture :FH-STRUCT-004))

(deftest fh-struct-004-children-canonicalize
  (testing "Per FH-STRUCT-004: one document-order vector; nil/false/true
            drop, numbers become text, seqs flatten, adjacent text
            coalesces and empties drop. Canonical uniqueness is what makes
            the tree a legitimate equality input rather than a shape that
            merely tends to compare equal."
    (check-cases struct-004)))

(deftest fh-struct-004-an-unrenderable-child-fails-loud
  (testing "Per FH-STRUCT-004: a value that is not markup, text, a seq of
            children, or nothing has no place in the children vector."
    (is (seq (:rejected struct-004)) "the fixture's rejected table loaded")
    (check-rejected struct-004)))

;; ---------------------------------------------------------------------------
;; FH-STRUCT-005 — namespace context
;; ---------------------------------------------------------------------------

(def struct-005 (conf/fixture :FH-STRUCT-005))

(deftest fh-struct-005-namespace-context-is-derived-at-render
  (testing "Per FH-STRUCT-005: HTML writes no `:ns` at all — the canonical
            form has one representation per node — while `<svg>` and
            `<math>` carry the namespace they open, descendants inherit,
            and the two HTML islands revert. A view cannot know where it
            will be mounted, so the context comes from the position the
            node actually occupies."
    (check-cases struct-005)))

;; ---------------------------------------------------------------------------
;; FH-STRUCT-008 — JavaScript `Number::toString(10)` over the double space
;; ---------------------------------------------------------------------------
;;
;; The expected column is JavaScript's OWN output. In ClojureScript the
;; conversion IS `String(d)` — a number there is a JavaScript number — so
;; the ClojureScript run of these rows is what makes the column
;; trustworthy, and the JVM run is what makes it a contract. Proving this
;; on one host would leave the JVM implementation grading its own
;; homework against expectations it wrote.

(def struct-008 (conf/fixture :FH-STRUCT-008))

(deftest fh-struct-008-numbers-render-with-javascript-tostring
  (testing "Per FH-STRUCT-008: every admitted double takes ECMA
            `Number::toString(10)` — the shortest decimal that
            round-trips, ties broken to even, plain notation inside the
            (-6, 21] decimal-exponent window and `e`-notation outside.
            The classes with teeth are a large integral double, whose
            EXACT decimal is longer than the shortest round-tripping one;
            the smallest subnormals, where the JVM's own
            `Double/toString` is not the shortest decimal it is
            documented to be; and a tie at the shortest length, where
            ECMA rounds to even."
    (is (seq (:numbers struct-008)) "the fixture's number table loaded")
    (doseq [{:keys [note value js]} (:numbers struct-008)]
      (is (= js (conv/js-number-str value)) note))))

(deftest fh-struct-008-the-rule-reaches-every-numeric-position
  (testing "Per FH-STRUCT-008: one conversion, every numeric position — a
            text child, an attribute value and a CSS value all read the
            same rule, so a converter cannot be right where a unit test
            looks and wrong where a view actually puts a number."
    (is (seq (:through struct-008)) "the fixture's position table loaded")
    (doseq [{:keys [note form tree]} (:through struct-008)]
      (is (= tree (tree/render form)) note))))

#?(:clj
   (deftest fh-struct-008-the-jvm-integer-domain-is-wider-and-explicit
     (testing "Per FH-STRUCT-008: a JVM integral type renders its EXACT
               decimal at any magnitude, because it IS an exact integer
               and printing an approximation of one would be a lie.
               Inside JavaScript's safe-integer range that spelling is
               also the double's, so the hosts agree; outside it the
               exact integer keeps a spelling no JavaScript number can
               produce, and the cross-host claim deliberately does not
               reach it — the ClojureScript reader makes a double there,
               which is a different number rather than the same number
               spelled differently."
       (is (seq (:jvm-integers struct-008)) "the fixture's integer table loaded")
       (doseq [{:keys [note value string js-safe]} (:jvm-integers struct-008)]
         (is (= string (conv/js-number-str value)) note)
         (if js-safe
           (is (= (conv/js-number-str value) (conv/js-number-str (double value)))
               (str note " — inside the safe range the integer and its double spell alike"))
           (is (not= (conv/js-number-str value) (conv/js-number-str (double value)))
               (str note " — outside it the exact integer is not the double's spelling")))))))

;; ---------------------------------------------------------------------------
;; FH-STRUCT-009 — author space in the tree; React prop names in the emitter
;; ---------------------------------------------------------------------------

(def struct-009 (conf/fixture :FH-STRUCT-009))

(deftest fh-struct-009-structural-output-stays-in-author-space
  (testing "Per FH-STRUCT-009: React prop names are the React emitter's
            concern and appear nowhere in the structural tree. The tree
            carries the names the author wrote, so a structural test
            asserts the declaration rather than a client-side projection
            of it — and the JVM, which has no React, owes nothing to
            React's vocabulary."
    (is (seq (:structural struct-009)) "the fixture's structural table loaded")
    (doseq [{:keys [note form tree]} (:structural struct-009)]
      (is (= tree (tree/render form)) note))))

(deftest fh-struct-009-reserved-and-rejected-spellings-are-refused
  (testing "Per FH-STRUCT-009: `:children`, `:ref` and the raw-markup
            aliases are refused at the walk, on both hosts, with the same
            diagnostic id the React emitter raises — which is what stops a
            declaration from being structurally fine and behaviourally
            different, and what stops a structurally legal void element
            from throwing in React alone."
    (is (seq (:rejected struct-009)) "the fixture's rejected table loaded")
    (doseq [{:keys [note form error-id]} (:rejected struct-009)]
      (is (= error-id (conf/caught-id #(tree/render form))) note))))
