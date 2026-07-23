(ns re-frame.freehand.presence-parity-cljs-test
  "FH-PRESENCE-001 — a presence boundary projects one structural value, in
  both modes and on both hosts.

  The retention behaviour is a runtime machine (FH-PRESENCE-002 pins the
  state machine, FH-PRESENCE-003 the real browser). This suite pins the
  STRUCTURAL projection: the `:rf.ui/presence` marker, the terminal
  `:timeout-ms`, and the retained children rendered `:present`. It renders
  the interpreted views in `re-frame.freehand.presence-views` AND their
  `{:compiled true}` twins against the SAME `FH-PRESENCE-001` fixture rows —
  same call sites, same pinned trees, the view-id namespace the only
  mechanical substitution — so an interpreted `(v/presence …)` and a
  compiled one are proven to denote the same node.

  Running on the JVM and in ClojureScript is the cross-host arm: the
  lowering is `.cljc` and its output is asserted against one fixture value
  in two runtimes. The JVM structural render is where a compiled presence
  view is renderable today, so this is also the cross-host / cross-mode
  parity the acceptance names."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.presence-views :as views]
            [re-frame.freehand.presence-views-compiled :as compiled]
            [re-frame.freehand.tree :as tree]))

(def presence-001 (conf/fixture :FH-PRESENCE-001))

(def ^:private interpreted-ns "re-frame.freehand.presence-views")
(def ^:private compiled-ns "re-frame.freehand.presence-views-compiled")

(defn- as-compiled-ids
  "Rewrite the fixture's expected `:view-id`s onto the compiled twins'
  namespace — the ONLY difference promotion is allowed to make, and it is
  not a difference promotion makes at all: it is a difference LIVING IN
  ANOTHER FILE makes."
  [tree]
  (walk/postwalk
    (fn [x]
      (if (and (keyword? x) (= interpreted-ns (namespace x)))
        (keyword compiled-ns (name x))
        x))
    tree))

(deftest fh-presence-001-one-boundary-one-node-both-modes
  (testing "Per FH-PRESENCE-001: a presence boundary projects the
            `:rf.ui/presence` marker, the terminal `:timeout-ms`, and its
            children `:present` — the same structural value from the
            interpreted view and its compiled twin, rendered against the
            same fixture rows on this host."
    (is (seq (:cases presence-001)) "the fixture's case table loaded")
    (doseq [{:keys [note view args tree]} (:cases presence-001)]
      (let [interpreted (get views/by-name view)
            promoted    (get compiled/by-name view)
            call        (into [] args)]
        (is (some? interpreted) (str "the fixture names an interpreted view: " view))
        (is (some? promoted) (str "the fixture names a promoted twin: " view))
        (is (= tree (tree/render (into [interpreted] call)))
            (str note " (interpreted)"))
        (is (= (as-compiled-ids tree) (tree/render (into [promoted] call)))
            (str note " (compiled — the same node, from the same call)"))))))

(deftest the-proof-is-not-vacuous
  (testing "A parity proof where both sides are interpreted proves nothing,
            so the lowering each descriptor reports is asserted before its
            output is trusted — one side really is the interpreted walk, the
            other really is the compiled emitter."
    (doseq [[view-name interpreted] views/by-name]
      (let [promoted (get compiled/by-name view-name)]
        (is (= :interpreted (:lowering (v/describe interpreted)))
            (str view-name " is declared interpreted"))
        (is (= :compiled (:lowering (v/describe promoted)))
            (str view-name " is declared compiled"))))))

(deftest presence-survives-as-a-marker-node
  (testing "The presence marker is a load-bearing node the tree exposes, not
            metadata: it is discoverable by a plain map/`:children` walk, and
            it round-trips as EDN. A consumer (SSR, a tool) reads
            `:rf.ui/presence` off the tree the same way on either host."
    (let [t        (tree/render [views/rooted {}])
          presence (->> (tree-seq map? :children t)
                        (filter #(contains? % :rf.ui/presence))
                        first)]
      (is (some? presence) "the presence node is reachable by an ordinary tree walk")
      (is (= {:phase :present :timeout-ms 250} (:rf.ui/presence presence))
          "and carries the marker the JVM structural host renders")
      (is (= 2 (count (:children presence)))
          "with its retained children present in the tree")
      ;; presence at a view root is NOT flattened into the boundary — the
      ;; boundary keeps it as its own child (guarded in node/plain-fragment?).
      (is (= :re-frame.freehand.presence-views/rooted (:view-id t))
          "the root is the view boundary, wrapping the presence node")
      (is (contains? (first (:children t)) :rf.ui/presence)
          "the presence node is the boundary's child, not adopted away"))))

(deftest the-timeout-must-be-legal
  (testing "The interpreted `v/presence` enforces the same terminal-bound
            contract the compiled analyzer does: `:timeout-ms` is mandatory
            and a positive number, and there is at least one child."
    (is (= descriptor/presence-tag (first (v/presence {:timeout-ms 10} [:div {:key 1}])))
        "a well-formed call returns the reserved-head presence vector")
    (is (thrown? #?(:clj Throwable :cljs :default) (v/presence {} [:div {:key 1}]))
        "a missing :timeout-ms is refused")
    (is (thrown? #?(:clj Throwable :cljs :default) (v/presence {:timeout-ms 0} [:div {:key 1}]))
        "a non-positive :timeout-ms is refused")
    (is (thrown? #?(:clj Throwable :cljs :default) (v/presence {:timeout-ms 10 :easing :ease} [:div {:key 1}]))
        "an unknown option is refused")
    (is (thrown? #?(:clj Throwable :cljs :default) (v/presence {:timeout-ms 10}))
        "a childless presence boundary is refused")))
