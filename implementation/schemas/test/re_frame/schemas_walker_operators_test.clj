(ns re-frame.schemas-walker-operators-test
  "JVM tests pinning the per-slot flag walker's behaviour across every
  Malli operator family `re-frame.schemas.walker` claims to support.

  Other tests pin `:map`, `:vector`, `:maybe`, `:or`,
  `:tuple`, and `:multi` directly. The walker also claims (per its
  docstring) to handle the remaining dispatch-bearing combinators
  `:orn` / `:altn`, the POSITION-bearing combinators `:cat` / `:catn`
  (each element descends at `(conj base i)`), and the
  positional containers `:set` / `:sequential` / `:and` / `:not`. Without
  operator-family pins for these, refactor drift could silently break the
  untested branches.

  This file pins one example per claimed operator family for the
  `:sensitive?` flag (the parameterised walker serves both flags so
  pinning one suffices to lock the structural recognition)."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.schemas :as rf.schemas]))

;; ---- dispatch-bearing combinators ----------------------------------------
;;
;; :multi / :orn / :altn — children carry dispatch-value branches; the
;; branch's slot-props claim the PARENT path (the op's base-path), not a
;; child path; dispatch values aren't path segments.
;;
;; :catn is NOT dispatch-bearing: although its children carry
;; name slots, Malli reports a :catn failure's :in segment as the integer
;; POSITION, not the name — so :catn is POSITION-bearing alongside :cat /
;; :tuple (see the position-bearing section below).

(deftest dispatch-bearing-branches-claim-the-parent-path
  (testing "a branch's flag, on its slot-props or inside its inner schema,
            claims the op's base-path: the dispatch value is not a path
            segment"
    (are [schema base-path expected]
         (= expected (rf.schemas/extract-sensitive-paths-from-schema schema base-path))
      ;; :orn — a branch's slot-props claim the parent path (as :multi does)
      [:orn [:secret {:sensitive? true} :string] [:public :string]]
      [:value]
      {[:value] {:sensitive? true :source :schema}}
      ;; :orn — a flagged slot inside the branch's inner schema
      [:orn [:authed [:map [:token {:sensitive? true} :string]]] [:anon [:map [:guest :string]]]]
      [:value]
      {[:value :token] {:sensitive? true :source :schema}}
      ;; :altn — a flagged slot inside an alt branch
      [:altn [:full [:map [:ssn {:sensitive? true} :string]]] [:abbr [:map [:initials :string]]]]
      [:doc]
      {[:doc :ssn] {:sensitive? true :source :schema}})))

;; ---- positional / nameless containers ------------------------------------
;;
;; :vector / :set / :sequential / :maybe / :and / :or / :not — children
;; descend at the SAME base-path; these ops are homogeneous (one shared
;; element schema) or their index is not a declarable app-db slot, so they
;; don't introduce a new path segment.
;;
;; :tuple / :cat / :catn are POSITION-bearing — element `i` descends at
;; `(conj base i)`; see
;; the position-bearing section below.

(deftest nameless-containers-descend-at-the-parent-path
  (testing "an inner flag under a homogeneous or nameless container claims
            the container's own path"
    (are [schema base-path expected]
         (= expected (rf.schemas/extract-sensitive-paths-from-schema schema base-path))
      [:set [:string {:sensitive? true}]]        [:tokens]    {[:tokens] {:sensitive? true :source :schema}}
      [:sequential [:string {:sensitive? true}]] [:audit-log] {[:audit-log] {:sensitive? true :source :schema}}
      ;; :and — every child descends at the parent path
      [:and :string [:string {:sensitive? true}]] [:slot]     {[:slot] {:sensitive? true :source :schema}}
      ;; :not — single-child positional
      [:not [:string {:sensitive? true}]]        [:slot]      {[:slot] {:sensitive? true :source :schema}})))

;; ---- position-bearing combinators ----------------------------------------
;;
;; :tuple / :cat / :catn — each element has its OWN schema; element `i`
;; descends at `(conj base i)`, the integer index being the discriminating
;; segment. Malli reports the integer POSITION in :in for all three.
;; The fixed-width positions are pinned by the `extract-*` tests in
;; `schemas_sensitive_test`; this section pins the variable-width case.

(deftest variable-width-sequence-descends-index-free
  (testing "a :cat / :catn whose width is not fixed cannot pin a
            flag to a position; its elements descend at the SHARED base-path,
            like :sequential"
    (is (= {[:ev :tok] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:cat [:* :int] [:map [:tok {:sensitive? true} :string]]]
             [:ev])))
    (is (= {[:row] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:catn [:head [:? :int]] [:tail {:sensitive? true} :string]]
             [:row]))
        "a :catn entry flag claims the base-path")
    (is (= {[:ev] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:* [:cat :int [:string {:sensitive? true}]]]
             [:ev]))
        "a fixed-width :cat spliced into a regex op owns no positions")))

;; ---- opaque / malformed forms --------------------------------------------
;;
;; Per walker.cljc — non-vector, non-keyword forms are opaque leaves;
;; the walker treats them as "not introspectable" and skips. This is
;; the defensive contract that protects the walker from blowing up on
;; registry refs / schema objects / fn schemas.

(deftest opaque-and-degenerate-forms-yield-no-declarations
  (testing "the walker does not peer inside an opaque value and does not
            blow up on a degenerate vector form"
    (are [schema] (= {} (rf.schemas/extract-sensitive-paths-from-schema schema []))
      'malli/AnyMap      ;; a symbol — not a Malli vector form, not a keyword
      (fn [_] true)      ;; a fn — also opaque
      []                 ;; an empty vector form
      [:string])))       ;; a single-element form: no props, no children
