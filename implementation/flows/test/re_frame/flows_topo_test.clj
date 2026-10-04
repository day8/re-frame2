(ns re-frame.flows-topo-test
  "JVM coverage for the pure-data topo module (flows/topo.cljc) —
  algorithm-level invariants that the registration / drain tests
  exercise only indirectly.

  Pins the defensive throw in `extract-cycle-path` (the closing-repeat
  cycle-path extractor). The branch is unreachable by
  construction — by Kahn's algorithm every stuck node has at least one
  stuck dependency, so the `next-dependency-id` lookup never returns nil —
  but the throw
  is load-bearing defence: a closing-repeat vector built from a dead
  end would lie to tools (Xray flow panel, re-frame-10x cycle
  visualisation) about the offending chain. Pin the throw + ex-data
  shape so a future refactor cannot silently break the invariant."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.flows.topo :as rf.flows.topo]))

;; ---------------------------------------------------------------------------
;; extract-cycle-path defensive throw
;;
;; Construct a graph + remaining-set whose entries violate Kahn's stuck-
;; node invariant: a stuck node whose graph entry has no edges into the
;; stuck-set. With `:a` in `remaining` and `(graph :a) = #{}`, the
;; `(filter remaining (graph node-id))` cull yields `()` →
;; `next-dependency-id` is nil
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
                      (#'rf.flows.topo/extract-cycle-path graph remaining)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown)
          "extract-cycle-path throws when a stuck node has no stuck dep to follow")
      (is (re-find #"\[:rf\.error/flow-cycle-extract-invariant\]"
                   (.getMessage ^Throwable thrown))
          "the [:rf.error/<id>] greppability token appears in the message (Spec 009 §The thrown-error shape, rule 4 — the message LEADS with the human sentence, not the bare keyword)")
      (let [data (ex-data thrown)]
        (is (= :rf.error/flow-cycle-extract-invariant (:rf.error/id data))
            "ex-data carries the canonical :rf.error/id discriminator (per Spec 009 §The thrown-error shape)")
        (is (string? (:reason data))
            "ex-data carries :reason as a human-readable sentence")
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
;; topo-sort — the topo module's own algorithm-level gate; the registration
;; and drain tests cover the integrated behaviour.
;; ---------------------------------------------------------------------------

(deftest topo-sort-detects-cycle
  (testing "two flows forming a cycle raise :rf.error/flow-cycle with the canonical thrown-error shape (per Spec 009 §The thrown-error shape)"
    (let [flow-map {:a {:id :a :inputs [[:b]] :derive identity :output-path [:a]}
                    :b {:id :b :inputs [[:a]] :derive identity :output-path [:b]}}
          thrown   (try (rf.flows.topo/topo-sort flow-map)
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown) "cycle raises")
      (is (re-find #"\[:rf\.error/flow-cycle\]" (.getMessage ^Throwable thrown))
          "the [:rf.error/<id>] greppability token appears in the message (Spec 009 §The thrown-error shape, rule 4)")
      (let [data (ex-data thrown)]
        (is (= :rf.error/flow-cycle (:rf.error/id data))
            "ex-data carries the canonical :rf.error/id discriminator")
        (is (= 'rf/reg-flow (:where data))
            "ex-data carries :where 'rf/reg-flow — points at the user-facing call site")
        (is (= :fix-registration (:recovery data))
            "ex-data carries :recovery :fix-registration — a cycle is caller-fixable
             (detected at reg-flow time on a prospective map; prior state preserved),
             so it reads as user-fixable like the sibling validate-flow rejections")
        (is (string? (:reason data))
            "ex-data carries :reason as a string diagnostic")
        (is (vector? (:cycle data))
            "ex-data carries :cycle as a vector")
        (is (>= (count (:cycle data)) 2)
            "the cycle vector closes (at least two entries including the closing repeat)")
        (is (= #{:rf.error/id :where :recovery :reason :cycle}
               (set (keys data)))
            "ex-data carries exactly the canonical slots + :cycle (no extras, no missing)")))))

;; ---------------------------------------------------------------------------
;; depends-on? direct function-boundary tests
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
;; Per Spec 013 §Topological sort: B depends on A iff A's :output-path and
;; any of B's :inputs share a path prefix in either direction.
;; ---------------------------------------------------------------------------

(deftest depends-on?-is-a-prefix-overlap-in-either-direction
  (testing "B depends on A iff one of B's :inputs and A's :output-path is a
            prefix of the other"
    (are [a-output b-inputs expected]
         (= expected (rf.flows.topo/depends-on? {:id :b :output-path [:b-out] :inputs b-inputs}
                                                {:id :a :output-path a-output :inputs []}))
      [:foo]           [[:foo]]                       true   ; B reads exactly A's slot
      [:foo :bar :baz] [[:foo]]                       true   ; B reads a parent of A's slot
      [:foo]           [[:foo :bar :baz]]             true   ; B reads a child of A's slot
      [:foo]           [[:unrelated] [:foo] [:other]] true   ; one matching input is enough
      [:foo :bar]      [[:unrelated] [:other-thing]]  false  ; disjoint
      [:foo :bar]      [[:bar :foo]]                  false  ; shared elements, no prefix
      [:foo]           []                             false)) ; reads nothing
  (testing "a flow whose own :inputs overlap its own :output-path depends on
            itself; topo-sort retains that self-edge and rejects the flow as a
            single-node cycle (Spec 013 §Dependency rule)"
    (let [a {:id :a :output-path [:foo] :inputs [[:foo]]}]
      (is (true? (rf.flows.topo/depends-on? a a))))))
