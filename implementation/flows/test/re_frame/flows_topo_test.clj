(ns re-frame.flows-topo-test
  "JVM coverage for the pure-data topo module (flows/topo.cljc) —
  algorithm-level invariants that the registration / drain tests
  exercise only indirectly.

  Per rf2-oozsq: pin the defensive throw in `extract-cycle-path` (the
  closing-repeat cycle-path extractor). The branch is unreachable by
  construction — by Kahn's algorithm every stuck node has at least one
  stuck dep, so the next-dep lookup never returns nil — but the throw
  is load-bearing defence: a closing-repeat vector built from a dead
  end would lie to tools (Causa flow panel, re-frame-10x cycle
  visualisation) about the offending chain. Pin the throw + ex-data
  shape so a future refactor cannot silently break the invariant.

  Per audit `ai/findings/flows-slice-audit-2026-05-15.md` §T2 (prior
  round) + audit-r2 carried forward."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.flows.topo :as topo]))

;; ---------------------------------------------------------------------------
;; extract-cycle-path defensive throw (rf2-oozsq)
;;
;; Construct a graph + remaining-set whose entries violate Kahn's stuck-
;; node invariant: a stuck node whose graph entry has no edges into the
;; stuck-set. With `:a` in `remaining` and `(graph :a) = #{}`, the
;; `(filter remaining (graph node))` cull yields `()` → next-dep is nil
;; → the defensive throw fires.
;;
;; The throw site is private; reach it via `#'` resolution. The branch
;; is unreachable from `topo-sort`'s public surface (Kahn's algorithm
;; would never produce this stuck-set shape) — this test exercises the
;; defence directly so a regression that silently dropped the throw and
;; returned a malformed cycle path surfaces here.
;; ---------------------------------------------------------------------------

(deftest extract-cycle-path-defends-against-dead-end
  (testing "extract-cycle-path throws :rf.error/flow-cycle-extract-invariant on a stuck node with no stuck dep"
    ;; `:a` is in `remaining` but `(graph :a)` is empty — no edge to
    ;; follow into the stuck-set. By Kahn's algorithm this combination
    ;; is impossible; the defence catches a regression that allowed
    ;; the impossible state to reach extract-cycle-path.
    (let [graph     {:a #{}}
          remaining #{:a}
          thrown    (try
                      (#'topo/extract-cycle-path graph remaining)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown)
          "extract-cycle-path throws when a stuck node has no stuck dep to follow")
      (is (= ":rf.error/flow-cycle-extract-invariant"
             (.getMessage ^Throwable thrown))
          "the documented invariant id appears in the message")
      (let [data (ex-data thrown)]
        (is (= ":rf.error/flow-cycle-extract-invariant" (:reason data))
            "ex-data carries :reason naming the invariant")
        (is (= :no-recovery (:recovery data))
            "ex-data carries :recovery :no-recovery — the dead-end means topo state is internally inconsistent")
        (is (= :a (:node data))
            "ex-data names the offending node")
        (is (vector? (:stack data))
            "ex-data carries the DFS stack at the moment of the throw")
        (is (set? (:seen data))
            "ex-data carries the seen-set at the moment of the throw")
        (is (= remaining (:remaining data))
            "ex-data carries the remaining stuck-set at the moment of the throw")))))

;; ---------------------------------------------------------------------------
;; topo-sort smoke (positive cases) — kept tiny; the registration and
;; drain tests cover the integrated behaviour. This file is the topo
;; module's own algorithm-level gate.
;; ---------------------------------------------------------------------------

(deftest topo-sort-empty-and-singleton
  (testing "empty flow-map yields empty order"
    (is (= [] (topo/topo-sort {}))))
  (testing "single flow yields itself in order"
    (is (= [:a]
           (topo/topo-sort {:a {:id :a :inputs [[:n]] :output identity :path [:a]}})))))

(deftest topo-sort-detects-cycle
  (testing "two flows forming a cycle raise :rf.error/flow-cycle with :cycle in ex-data"
    (let [flow-map {:a {:id :a :inputs [[:b]] :output identity :path [:a]}
                    :b {:id :b :inputs [[:a]] :output identity :path [:b]}}
          thrown   (try (topo/topo-sort flow-map)
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown) "cycle raises")
      (let [data (ex-data thrown)]
        (is (vector? (:cycle data))
            "ex-data carries :cycle as a vector")
        (is (>= (count (:cycle data)) 2)
            "the cycle vector closes (at least two entries including the closing repeat)")))))
