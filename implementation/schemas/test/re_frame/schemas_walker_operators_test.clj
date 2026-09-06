(ns re-frame.schemas-walker-operators-test
  "JVM tests pinning the per-slot flag walker's behaviour across every
  Malli operator family `re-frame.schemas.walker` claims to support
  (rf2-yv62u).

  Existing slice-local tests pin `:map`, `:vector`, `:maybe`, `:or`,
  `:tuple`, and `:multi` directly. The walker also claims (per its
  docstring) to handle the remaining dispatch-bearing combinators
  `:orn` / `:altn`, the POSITION-bearing combinators `:cat` / `:catn`
  (rf2-4q681i — each element descends at `(conj base i)`), and the
  positional containers `:set` / `:sequential` / `:and` / `:not`. The
  audit (rf2-yv62u) flagged the absence of operator-family pins for these
  — refactor drift could silently break the un-tested branches.

  This file pins one example per claimed operator family for the
  `:sensitive?` flag (the parameterised walker serves both flags so
  pinning one suffices to lock the structural recognition)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.schemas :as rf.schemas]))

;; ---- dispatch-bearing combinators ----------------------------------------
;;
;; :multi / :orn / :altn — children carry dispatch-value branches; the
;; branch's slot-props claim the PARENT path (the op's base-path), not a
;; child path; dispatch values aren't path segments.
;;
;; :catn is NOT dispatch-bearing (rf2-4q681i): although its children carry
;; name slots, Malli reports a :catn failure's :in segment as the integer
;; POSITION, not the name — so :catn is POSITION-bearing alongside :cat /
;; :tuple (see the position-bearing section below).

(deftest orn-branch-slot-claims-parent-path
  (testing ":orn — a branch's :sensitive? on its slot-props claims the
            parent path (mirrors :multi behaviour)"
    (is (= {[:value] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:orn
              [:secret {:sensitive? true} :string]
              [:public :string]]
             [:value])))))

(deftest orn-branch-inner-descends-at-parent-path
  (testing ":orn — a :sensitive? slot inside the branch's inner schema
            descends at the parent path (the dispatch value is not a
            path segment)"
    (is (= {[:value :token] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:orn
              [:authed [:map [:token {:sensitive? true} :string]]]
              [:anon   [:map [:guest :string]]]]
             [:value])))))

(deftest altn-branch-inner-descends-at-parent-path
  (testing ":altn — a :sensitive? slot inside an alt branch descends at
            the parent path"
    (is (= {[:doc :ssn] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:altn
              [:full [:map [:ssn {:sensitive? true} :string]]]
              [:abbr [:map [:initials :string]]]]
             [:doc])))))

;; ---- positional / nameless containers ------------------------------------
;;
;; :vector / :set / :sequential / :maybe / :and / :or / :not — children
;; descend at the SAME base-path; these ops are homogeneous (one shared
;; element schema) or their index is not a declarable app-db slot, so they
;; don't introduce a new path segment.
;;
;; :tuple / :cat / :catn are POSITION-bearing (:tuple rf2-ss06u.4;
;; :cat/:catn rf2-4q681i) — element `i` descends at `(conj base i)`; see
;; the position-bearing section below.

(deftest set-descends-at-parent-path
  (testing ":set — inner :sensitive? slot claims the :set's path"
    (is (= {[:tokens] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:set [:string {:sensitive? true}]]
             [:tokens])))))

(deftest sequential-descends-at-parent-path
  (testing ":sequential — inner :sensitive? slot claims the :sequential's
            path"
    (is (= {[:audit-log] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:sequential [:string {:sensitive? true}]]
             [:audit-log])))))

;; ---- position-bearing combinators ----------------------------------------
;;
;; :tuple / :cat / :catn — each element has its OWN schema; element `i`
;; descends at `(conj base i)`, the integer index being the discriminating
;; segment. Malli reports the integer POSITION in :in for all three.
;; (:tuple already pinned in the slice-local tests; :cat/:catn added by
;; rf2-4q681i.)

(deftest cat-element-is-position-bearing
  (testing ":cat — POSITION-bearing (rf2-4q681i); element `i`'s inner
            sensitive claims `(conj base i)`, NOT the shared base-path"
    ;; element 1 is the sensitive :string → claims [:ev 1], not [:ev].
    (is (= {[:ev 1] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:cat :string [:string {:sensitive? true}]]
             [:ev])))))

(deftest catn-element-is-position-bearing
  (testing ":catn — POSITION-bearing (rf2-4q681i); the entry name is
            decorative (Malli reports the integer position in :in), so an
            entry-level sensitive claims `(conj base i)`, not base"
    ;; entry 0 (:head) is sensitive → claims [:row 0], not [:row].
    (is (= {[:row 0] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:catn
              [:head {:sensitive? true} :string]
              [:tail :string]]
             [:row])))))

(deftest and-descends-at-parent-path
  (testing ":and — every child descends at the parent path"
    (is (= {[:slot] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:and :string [:string {:sensitive? true}]]
             [:slot])))))

(deftest not-descends-at-parent-path
  (testing ":not — single-child positional; inner sensitive descends at
            the parent path"
    (is (= {[:slot] {:sensitive? true :source :schema}}
           (rf.schemas/extract-sensitive-paths-from-schema
             [:not [:string {:sensitive? true}]]
             [:slot])))))

;; ---- opaque / malformed forms --------------------------------------------
;;
;; Per walker.cljc — non-vector, non-keyword forms are opaque leaves;
;; the walker treats them as "not introspectable" and skips. This is
;; the defensive contract that protects the walker from blowing up on
;; registry refs / schema objects / fn schemas.

(deftest opaque-leaf-schema-skipped
  (testing "an opaque schema value (not a vector form) yields no
            declarations — the walker doesn't try to peer inside"
    ;; A symbol — not a Malli vector form, not a keyword.
    (is (= {} (rf.schemas/extract-sensitive-paths-from-schema 'malli/AnyMap [])))
    ;; A fn — also opaque.
    (is (= {} (rf.schemas/extract-sensitive-paths-from-schema (fn [_] true) [])))))

(deftest empty-vector-form-tolerated
  (testing "an empty vector (degenerate schema form) does not blow up;
            it yields no declarations"
    (is (= {} (rf.schemas/extract-sensitive-paths-from-schema [] [])))))

(deftest single-element-vector-form-tolerated
  (testing "a single-element vector form (no props, no children) does
            not blow up; it yields no declarations"
    (is (= {} (rf.schemas/extract-sensitive-paths-from-schema [:string] [])))))
