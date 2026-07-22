(ns re-frame.freehand.tree-cljs-test
  "FH-STRUCT-001 … FH-STRUCT-005 — the versioned node schema, the element
  node's pinned fields, attribute values in semantic space, child
  normalization, and namespace context.

  Every row is table-driven from its fixture, and every fixture runs on
  the JVM and in ClojureScript from the same bytes. That is not a
  convenience: the two hosts disagree about number formatting, and a walk
  that reached for the host's own `str` would answer two different trees
  for one form. A `.cljc` claim proven on one host would be a gap wearing
  a pass's clothes."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand.conformance :as conf]
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
