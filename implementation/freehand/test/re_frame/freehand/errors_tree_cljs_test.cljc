(ns re-frame.freehand.errors-tree-cljs-test
  "FH-ERROR-001 (the containment half) — `v/error-boundary` on the structural
  host.

  On the JVM structural walk (and identically in ClojureScript, which is what
  makes this a cross-host claim) a boundary IS a try around the child's walk:
  a render-class throw below the boundary is CONTAINED — the boundary node
  holds the fallback subtree — while every SIBLING node the surrounding walk
  built keeps its place. The browser realises the same law through a React
  class boundary; here it is proven as a pure structural value."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.tree :as tree]))

(def error-001 (conf/fixture :FH-ERROR-001))

;; Two test-local declared children: one that renders, one that throws when
;; its body runs — the render-class failure a boundary exists to contain.
(def ^:private ok-child
  (descriptor/declare-view
    {:view-id :app/ok :lowering :interpreted :children-policy :optional
     :render (fn [_] [:span "ok"])}))

(def ^:private throwing-child
  (descriptor/declare-view
    {:view-id :app/throwing :lowering :interpreted :children-policy :optional
     :render (fn [_] (throw (ex-info "a child render threw" {})))}))

(defn- boundary-node
  "The single error-boundary node under the rendered `:div` root."
  [tree]
  (first (:children tree)))

(defn- sibling-node
  [tree]
  (second (:children tree)))

(deftest fh-error-001-a-boundary-passes-a-healthy-child-through
  (testing "Per FH-ERROR-001: with no throw, the boundary is transparent —
            its node holds the child's own subtree, and the sibling renders
            beside it as usual."
    (let [{:keys [boundary-view-id sibling-tag sibling-text]} (:contained error-001)
          tree (tree/render
                 [:div
                  [v/error-boundary {:fallback (:fallback error-001)} [ok-child {}]]
                  (:sibling error-001)])
          bnode (boundary-node tree)]
      (is (= boundary-view-id (:view-id bnode)))
      (is (= [{:tag :span :children ["ok"]}]
             (:children (first (:children bnode))))
          "the child rendered inside the boundary node")
      (is (= sibling-tag (:tag (sibling-node tree))))
      (is (= [sibling-text] (:children (sibling-node tree)))))))

(deftest fh-error-001-a-throwing-child-is-contained-and-siblings-keep-rendering
  (testing "Per FH-ERROR-001: a child that throws is CONTAINED — the boundary
            node holds the FALLBACK subtree instead of the child's — and the
            SIBLING keeps rendering. The throw never reaches the surrounding
            `:div`, so the whole tree still renders."
    (let [{:keys [boundary-view-id fallback-tag fallback-text sibling-tag sibling-text]}
          (:contained error-001)
          tree (tree/render
                 [:div
                  [v/error-boundary {:fallback (:fallback error-001)} [throwing-child {}]]
                  (:sibling error-001)])
          bnode (boundary-node tree)]
      (is (= boundary-view-id (:view-id bnode))
          "the boundary node is present")
      (is (= [{:tag fallback-tag :attrs {:class "fallback"} :children [fallback-text]}]
             (:children bnode))
          "and it holds the fallback subtree, not the child's")
      (is (= sibling-tag (:tag (sibling-node tree)))
          "the sibling kept rendering")
      (is (= [sibling-text] (:children (sibling-node tree)))))))

(deftest fh-error-001-a-changed-reset-key-re-mounts-and-retries-the-child
  (testing "Per FH-ERROR-001: a fresh structural render with the child no
            longer throwing renders the child again — the structural analogue
            of a reset re-mounting and retrying the child. (The stateful
            reset gate itself is proven by the error-boundary law suite.)"
    (let [failing (tree/render
                    [v/error-boundary {:fallback (:fallback error-001) :reset-key :r1}
                     [throwing-child {}]])
          healthy (tree/render
                    [v/error-boundary {:fallback (:fallback error-001) :reset-key :r2}
                     [ok-child {}]])]
      (is (= :p (:tag (first (:children failing)))) "first the fallback")
      (is (= [{:tag :span :children ["ok"]}]
             (:children (first (:children healthy))))
          "then, retried, the child"))))

;; ===========================================================================
;; Non-vacuity probe — the containment is doing REAL work.
;; ===========================================================================

(deftest non-vacuity-without-a-boundary-the-throw-propagates
  (testing "Non-vacuity: the throwing child, rendered WITHOUT a boundary
            above it, propagates its throw — so the contained result above is
            real containment, not a child that quietly rendered nothing. And
            the FH-ERROR-001 fixture carries real expectations."
    (is (thrown? #?(:clj Throwable :cljs :default)
                 (tree/render [:div [throwing-child {}]]))
        "the throw escapes when nothing contains it")
    (is (map? (:contained error-001)) "the FH-ERROR-001 fixture is non-empty")
    ;; And the boundary really is a declared boundary, not an ordinary view.
    (is (true? (descriptor/error-boundary? v/error-boundary))
        "v/error-boundary carries the error-boundary marker")))
