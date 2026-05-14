(ns re-frame.schemas-walker-operators-test
  "JVM tests pinning the per-slot flag walker's behaviour across every
  Malli operator family `re-frame.schemas.walker` claims to support
  (rf2-yv62u).

  Existing slice-local tests pin `:map`, `:vector`, `:maybe`, `:or`,
  `:tuple`, and `:multi` directly. The walker also claims (per its
  docstring) to handle the remaining dispatch-bearing combinators
  `:orn` / `:catn` / `:altn` and the positional containers `:set` /
  `:sequential` / `:cat` / `:and` / `:not`. The audit (rf2-yv62u)
  flagged the absence of operator-family pins for these — refactor
  drift could silently break the un-tested branches.

  This file pins one example per claimed operator family for the
  `:sensitive?` flag (the parameterised walker serves both flags so
  pinning one suffices to lock the structural recognition)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.schemas :as schemas]))

;; ---- dispatch-bearing combinators ----------------------------------------
;;
;; :multi / :orn / :catn / :altn — children carry dispatch-value
;; branches; the branch's slot-props claim the PARENT path (the op's
;; base-path), not a child path; dispatch values aren't path segments.

(deftest orn-branch-slot-claims-parent-path
  (testing ":orn — a branch's :sensitive? on its slot-props claims the
            parent path (mirrors :multi behaviour)"
    (is (= {[:value] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:orn
              [:secret {:sensitive? true} :string]
              [:public :string]]
             [:value])))))

(deftest orn-branch-inner-descends-at-parent-path
  (testing ":orn — a :sensitive? slot inside the branch's inner schema
            descends at the parent path (the dispatch value is not a
            path segment)"
    (is (= {[:value :token] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:orn
              [:authed [:map [:token {:sensitive? true} :string]]]
              [:anon   [:map [:guest :string]]]]
             [:value])))))

(deftest catn-branch-slot-claims-parent-path
  (testing ":catn — same dispatch-bearing semantics; the branch's
            slot-props claim the parent path"
    (is (= {[:row] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:catn
              [:head {:sensitive? true} :string]
              [:tail :string]]
             [:row])))))

(deftest altn-branch-inner-descends-at-parent-path
  (testing ":altn — a :sensitive? slot inside an alt branch descends at
            the parent path"
    (is (= {[:doc :ssn] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:altn
              [:full [:map [:ssn {:sensitive? true} :string]]]
              [:abbr [:map [:initials :string]]]]
             [:doc])))))

;; ---- positional / nameless containers ------------------------------------
;;
;; :vector / :set / :sequential / :maybe / :and / :or / :not / :tuple /
;; :cat — children descend at the SAME base-path; these ops don't
;; introduce a new app-db path segment.

(deftest set-descends-at-parent-path
  (testing ":set — inner :sensitive? slot claims the :set's path"
    (is (= {[:tokens] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:set [:string {:sensitive? true}]]
             [:tokens])))))

(deftest sequential-descends-at-parent-path
  (testing ":sequential — inner :sensitive? slot claims the :sequential's
            path"
    (is (= {[:audit-log] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:sequential [:string {:sensitive? true}]]
             [:audit-log])))))

(deftest cat-descends-at-parent-path
  (testing ":cat — positional combinator; inner sensitive descends at
            the parent path"
    (is (= {[:tuple-slot] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:cat :string [:string {:sensitive? true}]]
             [:tuple-slot])))))

(deftest and-descends-at-parent-path
  (testing ":and — every child descends at the parent path"
    (is (= {[:slot] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
             [:and :string [:string {:sensitive? true}]]
             [:slot])))))

(deftest not-descends-at-parent-path
  (testing ":not — single-child positional; inner sensitive descends at
            the parent path"
    (is (= {[:slot] {:sensitive? true :source :schema}}
           (schemas/extract-sensitive-paths-from-schema
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
    (is (= {} (schemas/extract-sensitive-paths-from-schema 'malli/AnyMap [])))
    ;; A fn — also opaque.
    (is (= {} (schemas/extract-sensitive-paths-from-schema (fn [_] true) [])))))

(deftest empty-vector-form-tolerated
  (testing "an empty vector (degenerate schema form) does not blow up;
            it yields no declarations"
    (is (= {} (schemas/extract-sensitive-paths-from-schema [] [])))))

(deftest single-element-vector-form-tolerated
  (testing "a single-element vector form (no props, no children) does
            not blow up; it yields no declarations"
    (is (= {} (schemas/extract-sensitive-paths-from-schema [:string] [])))))
