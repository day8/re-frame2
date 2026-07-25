(ns re-frame.freehand.structural-test-surface-cljs-test
  "F1e — the public structural test surface `re-frame.freehand.test` (`t`)
  and the conformance runner that executes structural laws through it.

  Two claims, both on both hosts. FIRST, the surface itself: a declared
  view renders to the versioned semantic tree headlessly, event intent is
  an equality check on data, and the four projections read what the node
  schema pins. SECOND, the runner: a conformance fixture NAMED in the index
  is executed through the public `t/render`, its status flows back naming
  the `FH` id, the run reports a non-zero test count, and a deliberately
  wrong fixture REDS rather than passing vacuously — the falsifiability
  proof a table-driven gate lives or dies by."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.structural-runner :as runner]
            [re-frame.freehand.test :as t]))

;; ---------------------------------------------------------------------------
;; The declared views the surface tests render
;; ---------------------------------------------------------------------------

(v/defview save-button
  "The codex-design headline: a button carrying its intent as data."
  [{:keys [article-id]}]
  [:button {:on-click [:article/save article-id]} "Save"])

(v/defview toolbar
  "Several buttons — so a finder has more than one node to choose between."
  [{:keys [ids]}]
  [:div.toolbar
   (for [id ids]
     [:button {:key id :on-click [:row/pick id]} (str "row-" id)])])

(v/defview nothing
  "A view that renders nothing — still a boundary node, so it stays
  addressable and its projection is total."
  [_]
  nil)

(v/defview marked
  "A marker-bearing node with a text leaf in FRONT of it — the shape a
  membership predicate meets. `tree-seq` yields the string too, so this is
  the tree that proves `find` never hands one to `pred` (rf2-per51)."
  [_]
  [:div.marked
   "before"
   (v/presence {:timeout-ms 250}
     [:span {:key "x"} "inside"])])

;; ---------------------------------------------------------------------------
;; The surface — render / find / find-all / text / attrs
;; ---------------------------------------------------------------------------

(deftest render-reads-a-declared-view-headlessly
  (testing "render answers the versioned semantic tree for a declared-view
            call, on both hosts, with no browser; the intent is data read
            through attrs, and (:on-click node) is a FIELD miss."
    (let [tree   (t/render [save-button {:article-id 42}])
          button (t/find tree #(= :button (:tag %)))]
      (is (= 1 (:rf.ui/tree-version tree)) "the root carries the schema version")
      (is (some? button) "the button node is found by its tag")
      (is (= "Save" (t/text button)) "text concatenates document-order leaves")
      (is (= [:article/save 42] (:on-click (t/attrs button)))
          "the intent is read through attrs — events merge into the projection")
      (is (nil? (:on-click button))
          "a bare field lookup is a miss: events live under :events, not the node"))))

(deftest find-and-find-all-traverse-the-tree
  (testing "find returns the first match or nil; find-all returns every
            match in document order — conveniences over (tree-seq map?
            :children tree)."
    (let [tree    (t/render [toolbar {:ids [1 2 3]}])
          buttons (t/find-all tree #(= :button (:tag %)))]
      (is (= 3 (count buttons)) "find-all sees every button")
      (is (= ["row-1" "row-2" "row-3"] (mapv t/text buttons)) "document order is preserved")
      (is (= [:row/pick 1] (:on-click (t/attrs (t/find tree #(= :button (:tag %))))))
          "find returns the first match")
      (is (nil? (t/find tree #(= :never (:tag %)))) "no match is nil")
      (is (= [] (t/find-all tree #(= :never (:tag %)))) "no match is the empty vector"))))

(deftest the-predicate-sees-node-maps-only
  (testing "find / find-all apply `pred` to MAP NODES ONLY — the text leaves
            `tree-seq` yields never reach it. So a MEMBERSHIP predicate, the
            shape a marker-node query takes, answers the same on the JVM and
            in ClojureScript instead of throwing on one host and answering on
            the other (rf2-per51: contains? on a String is an
            IllegalArgumentException on the JVM and total in ClojureScript)."
    (let [tree (t/render [marked {}])]
      (is (= "beforeinside" (t/text tree))
          "the tree really does carry text leaves — the case is not vacuous")
      (is (= {:phase :present :timeout-ms 250}
             (:rf.ui/presence (t/find tree #(contains? % :rf.ui/presence))))
          "a membership predicate finds the marker node on either host")
      (is (= 1 (count (t/find-all tree #(contains? % :rf.ui/presence))))
          "and find-all answers the one marker node, never a string leaf")
      (is (nil? (t/find tree #(contains? % :absent)))
          "a membership predicate matching nothing is a miss, not a throw"))))

(deftest attrs-projects-every-node-variant
  (testing "attrs is total over the node schema: element merges :attrs and
            :events, a view boundary reads :props, a fragment/nothing-view
            has no attributes, and nil nil-puns."
    (let [tree (t/render [save-button {:article-id 7}])]
      (is (map? (t/attrs tree)) "the root view boundary projects its props")
      (is (= {:article-id 7} (t/attrs tree)) "a boundary's attrs are its recorded props")
      (is (= {} (t/attrs (t/render [nothing {}])))
          "a nothing-view boundary has no props, so attrs is the empty map")
      (is (nil? (t/attrs nil)) "nil threads through a missed traversal"))))

(deftest text-descends-in-document-order
  (testing "text collects string leaves under a node, descending through
            elements, fragments and boundaries; nil nil-puns."
    (let [tree (t/render [:section [:h2 "Title"] [:p "a" [:b "b"] "c"]])]
      (is (= "Titleabc" (t/text tree)) "every leaf, in order")
      (is (nil? (t/text nil)) "nil nil-puns"))))

(deftest projections-fail-loud-on-a-non-node
  (testing "a projection over something that is not a tree node fails loud
            with :rf.error/ui-tree-malformed rather than reading a plausible
            answer off a broken value — a missing node is almost always a
            test bug, not a passing case."
    (is (= :rf.error/ui-tree-malformed
           (conf/caught-id #(t/attrs "text")))
        "text content is not a node — read it with t/text on the parent")
    (is (= :rf.error/ui-tree-malformed
           (conf/caught-id #(t/text "text")))
        "text content is not a node — it IS the text")
    (is (= :rf.error/ui-tree-malformed
           (conf/caught-id #(t/attrs 42)))
        "a scalar is not a node")
    (is (= :rf.error/ui-tree-malformed
           (conf/caught-id #(t/attrs {:tag :div :view-id :x})))
        "a map carrying two primary discriminators is malformed")))

;; ---------------------------------------------------------------------------
;; The runner — executes conformance rows named in the index
;; ---------------------------------------------------------------------------

(def ^:private index-rows
  "Structural-render laws from the conformance index this runner executes —
  the F1c interpreted-tree rows whose fixtures carry the `:cases` / `:rejected`
  render shape. Each is loaded from the same fixture bytes both hosts read."
  [(conf/fixture :FH-STRUCT-001)
   (conf/fixture :FH-STRUCT-002)
   (conf/fixture :FH-STRUCT-003)
   (conf/fixture :FH-STRUCT-004)
   (conf/fixture :FH-STRUCT-005)])

(deftest the-runner-executes-index-fixtures-and-status-flows-back
  (testing "every named fixture is EXECUTED through the public t/render, the
            run reports a NON-ZERO test count, and every row is green — its
            status flows back per fixture."
    (let [aggregate (runner/run-all index-rows)]
      (is (pos? (:ran aggregate))
          "the runner reports a non-zero test count — Ran 0 is a failure")
      (is (empty? (:failures aggregate))
          (str "every executed row is green; failures: " (pr-str (:failures aggregate)))))
    (doseq [fixture index-rows]
      (let [report (runner/run-structural-fixture fixture)]
        (is (pos? (:ran report)) (str (:fh/id report) " executed at least one case"))
        (is (empty? (:failures report))
            (str (:fh/id report) " is green"))))))

(deftest the-runner-reds-and-names-the-fh-id
  (testing "falsifiability: a deliberately wrong fixture must RED, and each
            failure must NAME its FH id. Proven for both arms — a case whose
            expected tree is wrong, and a rejection that does not raise the
            expected error — so neither arm can pass a broken law silently."
    (let [rigged {:fh/id "FH-STRUCT-999"
                  :cases    [{:note "a div is not a span"
                              :form [:div] :tree {:tag :span :rf.ui/tree-version 1}}]
                  :rejected [{:note "a legal element does not raise"
                              :form [:div] :error-id :rf.error/ui-tree-malformed}]}
          report (runner/run-structural-fixture rigged)]
      (is (= 2 (:ran report)) "both rigged rows ran")
      (is (= 2 (count (:failures report))) "both rigged rows red")
      (is (every? #(= "FH-STRUCT-999" (:fh/id %)) (:failures report))
          "every failure names the FH id its status belongs to")
      (is (some #(= {:tag :span :rf.ui/tree-version 1} (:expected %)) (:failures report))
          "the case failure carries its expected value, so the red is diagnosable")
      (is (some #(= :rf.error/ui-tree-malformed (:expected %)) (:failures report))
          "the rejection failure carries its expected error-id"))))

(deftest an-empty-fixture-is-not-a-vacuous-pass
  (testing "a fixture with no cases and no rejections reports :ran 0 — the
            signal a caller treats as a failure, because a law proven by an
            empty table is proven by nothing."
    (is (= 0 (:ran (runner/run-structural-fixture {:fh/id "FH-STRUCT-000"}))))))
