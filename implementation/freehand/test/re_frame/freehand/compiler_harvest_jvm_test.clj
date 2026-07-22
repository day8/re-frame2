(ns re-frame.freehand.compiler-harvest-jvm-test
  "Unit tests for the custom-element source harvest (rf2-vxgfnd.141, dimension 2).

  `declarations-in-source` is a PURE syntactic read: it recognizes the exact
  literal `(<v>/custom-element <tag> <opts>)` shape — resolving how the source's
  own `(ns …)` aliases spell `re-frame.freehand/custom-element` — and rejects anything
  the macro would reject rather than seeding it. These tests pin the recognition
  and the rejection so the harvest can never seed a classification the macro
  would not, nor miss one it would."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.compiler.harvest :as harvest]))

(defn- decls [src] (harvest/declarations-in-source src))

(deftest recognizes-aliased-head
  (let [src "(ns app.a (:require [re-frame.freehand :as v]))
             (v/custom-element :x-card {:properties #{:model :help-text}})"]
    (is (= [{:tag :x-card :properties #{:model :help-text}}] (decls src)))))

(deftest recognizes-fully-qualified-head
  (let [src "(ns app.a (:require [re-frame.freehand]))
             (re-frame.freehand/custom-element :x-badge {:properties #{:count}})"]
    (is (= [{:tag :x-badge :properties #{:count}}] (decls src)))))

(deftest recognizes-referred-head
  (let [src "(ns app.a (:require [re-frame.freehand :refer [custom-element]]))
             (custom-element :x-tag {:properties #{:value}})"]
    (is (= [{:tag :x-tag :properties #{:value}}] (decls src)))))

(deftest bare-alias-only-matches-that-spelling
  ;; With only `:as v`, a bare `custom-element` (unreferred) is NOT the macro
  ;; and must not be harvested — nor a foreign `other/custom-element`.
  (let [src "(ns app.a (:require [re-frame.freehand :as v] [other.lib :as other]))
             (v/custom-element :x-real {:properties #{:model}})
             (custom-element :x-bare {:properties #{:nope}})
             (other/custom-element :x-foreign {:properties #{:nope}})"]
    (is (= [{:tag :x-real :properties #{:model}}] (decls src)))))

(deftest empty-properties-declaration-is-recognized
  (let [src "(ns app.a (:require [re-frame.freehand :as v]))
             (v/custom-element :x-plain {:properties #{}})
             (v/custom-element :x-empty {})"]
    (is (= [{:tag :x-plain :properties #{}}
            {:tag :x-empty :properties #{}}]
           (decls src)))))

(deftest finds-declarations-regardless-of-position
  ;; A declaration textually BELOW a view is exactly the dimension-2 defect; the
  ;; harvest must find it wherever it sits.
  (let [src "(ns app.a (:require [re-frame.freehand :as v :refer [defview]]))
             (defview v [] [:x-card {:model \"m\"}])
             (v/custom-element :x-card {:properties #{:model}})"]
    (is (= [{:tag :x-card :properties #{:model}}] (decls src)))))

(deftest rejects-what-the-macro-would-reject
  (testing "non-literal :properties (a symbol) is not seeded"
    (let [src "(ns app.a (:require [re-frame.freehand :as v]))
               (v/custom-element :x-dyn {:properties some-var})"]
      (is (= [] (decls src)))))
  (testing "unknown option is not seeded"
    (let [src "(ns app.a (:require [re-frame.freehand :as v]))
               (v/custom-element :x-opt {:properties #{:m} :events #{:go}})"]
      (is (= [] (decls src)))))
  (testing "a tag without '-' is not a custom element and is not seeded"
    (let [src "(ns app.a (:require [re-frame.freehand :as v]))
               (v/custom-element :plain {:properties #{:m}})"]
      (is (= [] (decls src)))))
  (testing "qualified property keywords are not seeded"
    (let [src "(ns app.a (:require [re-frame.freehand :as v]))
               (v/custom-element :x-q {:properties #{:ns/q}})"]
      (is (= [] (decls src))))))

(deftest tolerates-reader-conditionals-tagged-literals-and-alias-keywords
  ;; A well-formed CLJC source with `#?`, `#js`, and `::alias/kw` in forms
  ;; surrounding the declaration must still yield the declaration — the harvest
  ;; reads under `:cljs`, degrades unknown tags to their value, and resolves
  ;; alias keywords from the ns form.
  (let [src "(ns app.a (:require [re-frame.freehand :as v] [other.lib :as o]))
             (def opts #?(:cljs #js {:a 1} :clj {:a 1}))
             (def k ::o/thing)
             (v/custom-element :x-cond {:properties #{:model}})"]
    (is (= [{:tag :x-cond :properties #{:model}}] (decls src)))))

(deftest source-seed-carries-the-declaring-namespace
  (let [src "(ns app.a (:require [re-frame.freehand :as v]))
             (v/custom-element :x-card {:properties #{:model}})"]
    (is (= [['app.decl :x-card {:properties #{:model}}]]
           (harvest/source-seed 'app.decl src)))))
