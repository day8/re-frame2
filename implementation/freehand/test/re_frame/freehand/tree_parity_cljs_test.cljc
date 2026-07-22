(ns re-frame.freehand.tree-parity-cljs-test
  "FH-STRUCT-006 — one declaration, one value, on both hosts.

  The rows in `tree-cljs-test` walk raw Hiccup. These render DECLARED
  views: the boundary call is normalized, the body runs, and the expansion
  is recorded inside a real view-boundary node. That is the whole claim of
  the slice — that a declaration means the same thing on the JVM and in
  ClojureScript — so the fixture pins whole trees literally and each host
  proves it produces them.

  Two hosts producing one pinned value is what \"equal on both hosts\"
  means operationally. Asserting the two runs against each other directly
  is not available (they are different processes) and would be weaker
  anyway: two walks can agree with each other while both disagreeing with
  the contract."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.tree :as tree]
            [re-frame.freehand.tree-views :as views]))

(def struct-006 (conf/fixture :FH-STRUCT-006))

(defn- realize [form]
  (walk/postwalk #(if (= :fh/fn %) (fn [_] nil) %) form))

(deftest fh-struct-006-one-declaration-one-tree
  (testing "Per FH-STRUCT-006: the same declaration yields the same
            structural tree on either host. View boundaries are real
            nodes, recording the props the body received, the `:key` the
            call carried, and the expansion the body built — so a view
            stays addressable whether it renders one element, several, or
            nothing at all."
    (is (seq (:cases struct-006)) "the fixture's case table loaded")
    (doseq [{:keys [note view args tree]} (:cases struct-006)]
      (let [declared (get views/by-name view)]
        (is (some? declared) (str "the fixture names a declared view: " view))
        (is (= tree (tree/render (into [declared] (realize args)))) note)))))

(deftest fh-struct-006-the-tree-round-trips-as-edn
  (testing "Per FH-STRUCT-006: the tree is plain, serialisable data —
            plain maps and strings, no wrapper types, no metadata-carried
            contract. A boundary records its view-id and never the
            descriptor value, because a tree carrying a `deftype` would
            print and then fail to read back, and every consumer
            downstream assumes the round-trip holds."
    (let [t (tree/render [views/page {:items [1 2]}])]
      (is (< 1 (count (tree-seq map? :children t))) "the tree has interior nodes to lose")
      (is (= t (edn/read-string (pr-str t)))
          "the tree round-trips through print and read, losslessly"))))
