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

;; ---------------------------------------------------------------------------
;; The round-trip at depth — the opaque marker occupies a SITE
;; ---------------------------------------------------------------------------

(deftest fh-struct-006-a-nested-non-data-value-is-refused
  (testing "Per FH-STRUCT-006: a prop that IS a callback records as the
            opaque marker, because the grammar names that site. A callback
            NESTED inside a recorded prop has no site to occupy, so it is
            refused at the boundary rather than marked — a marker written
            below the prop key would claim a site the grammar does not
            have and would silently replace the value the author passed."
    (is (seq (:rejected struct-006)) "the fixture's rejection table loaded")
    (doseq [{:keys [note view args error-id]} (:rejected struct-006)]
      (let [declared (get views/by-name view)]
        (is (some? declared) (str "the fixture names a declared view: " view))
        (is (= error-id
               (conf/caught-id #(tree/render (into [declared] (realize args)))))
            note)))))

(deftest fh-struct-006-every-accepted-prop-shape-round-trips
  (testing "Per FH-STRUCT-006: the round-trip is a claim about the WHOLE
            recorded value. A prop carrying ordinary EDN at depth — and
            EDN's own tagged literals — survives print/read unchanged, and
            so does a prop that is a callback, because the marker it
            records as IS data. This is the assertion the nested-fn bug
            broke: the tree printed `#object[…]` and read back nowhere."
    (doseq [[note props] [["ordinary EDN at depth"
                           {:title "Details"
                            :config {:a [1 2 #{:c}] :b 'sym :c nil :d 1.5}}]
                          ["EDN's own tagged literals"
                           {:title "Details"
                            :config {:id #uuid "00000000-0000-0000-0000-000000000001"
                                     :when #inst "1970-01-01T00:00:00.000-00:00"}}]
                          ["a callback AT the prop site records as the marker"
                           {:title "Details" :on-pick (fn [_] nil)}]]]
      (let [t (tree/render [views/panel props])]
        (is (= t (edn/read-string (pr-str t)))
            (str note " — the tree prints and reads back losslessly"))))))

(deftest fh-struct-006-the-round-trip-assertion-can-fail
  (testing "Per FH-STRUCT-006: the round-trip assertion is not vacuous.
            The KNOWN-BAD control is the pre-fix tree — the value the
            boundary used to record when a callback was nested inside a
            prop — asserted directly, because no boundary will build one
            any more. Print/read must NOT reproduce it; an assertion that
            cannot fail proves nothing about the ones that pass."
    (let [pre-fix {:view-id ::pre-fix
                   :props   {:config {:callback (fn [_] nil)}}
                   :rf.ui/tree-version 1}]
      (is (not= pre-fix
                (try (edn/read-string (pr-str pre-fix))
                     (catch #?(:clj Throwable :cljs :default) _ ::unreadable)))
          "a host value nested in :props does not survive print and read"))))
