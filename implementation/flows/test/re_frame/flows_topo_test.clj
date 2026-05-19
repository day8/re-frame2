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
  (testing "two flows forming a cycle raise :rf.error/flow-cycle with the standard error shape (rf2-6mxr2)"
    (let [flow-map {:a {:id :a :inputs [[:b]] :output identity :path [:a]}
                    :b {:id :b :inputs [[:a]] :output identity :path [:b]}}
          thrown   (try (topo/topo-sort flow-map)
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown) "cycle raises")
      (is (= ":rf.error/flow-cycle" (.getMessage ^Throwable thrown))
          "ex-info message is the documented category")
      (let [data (ex-data thrown)]
        (is (= :rf.error/flow-cycle (:error data))
            "ex-data carries :error :rf.error/flow-cycle (standard shape — rf2-6mxr2)")
        (is (= 'rf/reg-flow (:where data))
            "ex-data carries :where 'rf/reg-flow — points at the user-facing call site")
        (is (= :no-recovery (:recovery data))
            "ex-data carries :recovery :no-recovery")
        (is (string? (:reason data))
            "ex-data carries :reason as a string diagnostic")
        (is (vector? (:cycle data))
            "ex-data carries :cycle as a vector")
        (is (>= (count (:cycle data)) 2)
            "the cycle vector closes (at least two entries including the closing repeat)")
        (is (= #{:error :where :recovery :reason :cycle}
               (set (keys data)))
            "ex-data carries exactly the standard slots + :cycle (no extras, no missing)")))))

;; ---------------------------------------------------------------------------
;; rf2-m05md — depends-on? direct function-boundary tests
;; (follow-on from rf2-q1z1u F4)
;;
;; `depends-on?` is the load-bearing helper that `topo-sort` consumes
;; when building the dependency graph (each flow's set of dep ids is
;; the set of OTHER flows it `depends-on?`). It is exercised
;; transitively by the topo-sort tests above and by the integration
;; tests in flows_test.clj — but a regression that flipped a base case
;; (self-edge, missing dep, multi-hop transitive) would cascade into
;; incorrect topo-sort output without a sharp signal at the helper's
;; name.
;;
;; Per Spec 013 §Topological sort: B depends on A iff A's :path and
;; any of B's :inputs share a path prefix in either direction.
;; ---------------------------------------------------------------------------

(deftest depends-on?-direct-prefix-match
  (testing "rf2-m05md — depends-on? is true when one of B's :inputs IS
            A's :path (the canonical case — B reads exactly the slot
            A writes)"
    (let [a {:id :a :path [:foo]   :inputs [[:other]]}
          b {:id :b :path [:bar]   :inputs [[:foo]]}]
      (is (true? (topo/depends-on? b a))
          "B's :inputs include A's :path exactly → B depends on A"))))

(deftest depends-on?-true-when-input-is-prefix-of-output-path
  (testing "rf2-m05md — depends-on? is true when one of B's :inputs is
            a PREFIX of A's :path (B reads a parent slot of what A
            writes — Spec 013 'prefix in either direction')"
    (let [a {:id :a :path [:foo :bar :baz] :inputs []}
          b {:id :b :path [:other]         :inputs [[:foo]]}]
      (is (true? (topo/depends-on? b a))
          "B reads :foo (a prefix of A's :path [:foo :bar :baz])"))))

(deftest depends-on?-true-when-output-path-is-prefix-of-input
  (testing "rf2-m05md — depends-on? is true when A's :path is a PREFIX
            of one of B's :inputs (B reads a CHILD slot of what A
            writes — also covered by 'prefix in either direction')"
    (let [a {:id :a :path [:foo]              :inputs []}
          b {:id :b :path [:other]            :inputs [[:foo :bar :baz]]}]
      (is (true? (topo/depends-on? b a))
          "A writes [:foo] which is a prefix of B's input [:foo :bar :baz]"))))

(deftest depends-on?-self-edge-via-overlapping-path
  (testing "rf2-m05md — depends-on? returns true for a self-edge if a
            flow's own :inputs share a prefix with its own :path. The
            consumer (topo-sort) explicitly filters self-edges out via
            `(not= id %)` so this only matters at the helper boundary
            — pinning the truth of the helper's prefix rule
            independent of its caller's self-edge guard."
    (let [a {:id :a :path [:foo] :inputs [[:foo]]}]
      (is (true? (topo/depends-on? a a))
          "A's own :inputs include A's own :path → depends-on? returns
           true; topo-sort filters this self-edge separately"))))

(deftest depends-on?-false-when-no-overlap
  (testing "rf2-m05md — depends-on? returns false when no input shares
            a prefix with A's :path in either direction"
    (let [a {:id :a :path [:foo :bar] :inputs []}
          b {:id :b :path [:other]    :inputs [[:unrelated] [:other-thing]]}]
      (is (false? (topo/depends-on? b a))
          "B's inputs are disjoint from A's :path tree → no dependency"))))

(deftest depends-on?-false-when-paths-share-no-prefix-but-share-element
  (testing "rf2-m05md — depends-on? is PREFIX-based, not element-
            membership-based: a shared NON-PREFIX element does NOT
            create a dependency edge"
    (let [a {:id :a :path [:foo :bar] :inputs []}
          ;; B's input [:bar :foo] shares both elements with A's :path
          ;; but neither is a prefix of the other → no dependency.
          b {:id :b :path [:other]    :inputs [[:bar :foo]]}]
      (is (false? (topo/depends-on? b a))
          "shared elements without a prefix relationship → false"))))

(deftest depends-on?-empty-inputs
  (testing "rf2-m05md — depends-on? returns false when B has no :inputs
            (cannot depend on anything if it reads nothing)"
    (let [a {:id :a :path [:foo] :inputs []}
          b {:id :b :path [:bar] :inputs []}]
      (is (false? (topo/depends-on? b a))
          "B has no :inputs → cannot depend on A (or anything else)"))))

(deftest depends-on?-multiple-inputs-any-match-wins
  (testing "rf2-m05md — depends-on? is an `or` across B's :inputs: a
            single matching input is enough to establish the
            dependency edge"
    (let [a {:id :a :path [:foo] :inputs []}
          b {:id :b :path [:bar] :inputs [[:unrelated] [:foo] [:other]]}]
      (is (true? (topo/depends-on? b a))
          "B's second input matches A's :path → dependency established
           despite the surrounding non-matching inputs"))))
