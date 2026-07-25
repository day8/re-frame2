(ns re-frame.freehand.presence-parity-cljs-test
  "FH-PRESENCE-001 and FH-PRESENCE-004 — a presence boundary, and the
  presence-aware child under it, project one structural value each, in both
  modes and on both hosts.

  The retention behaviour is a runtime machine (FH-PRESENCE-002 pins the
  state machine, FH-PRESENCE-003 the real browser). This suite pins the
  STRUCTURAL projection: `FH-PRESENCE-001` the presence NODE — the
  `:rf.ui/presence` marker, the terminal `:timeout-ms`, and the retained
  children rendered `:present` — and `FH-PRESENCE-004` the CHILD's own
  `(v/presence-phase)` read, which a render with no lifecycle answers
  `:present`. It renders the interpreted views in
  `re-frame.freehand.presence-views` AND their `{:compiled true}` twins
  against the SAME fixture rows — same call sites, same pinned trees, the
  view-id namespace the only mechanical substitution — so an interpreted
  `(v/presence …)` and a compiled one are proven to denote the same node,
  and a phase read is proven to be a fact about the RENDERER rather than
  about the compiler.

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
            [re-frame.freehand.test :as t]
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
          ;; The `map?` filter is load-bearing, not decoration: a raw
          ;; `tree-seq` yields text content as host strings too, and
          ;; `contains?` on a String throws on the JVM. Without it this walk
          ;; passes only by the ACCIDENT of laziness reaching the marker
          ;; before the first string (rf2-per51) — `t/find` applies its
          ;; predicate to node maps only, and a hand-rolled walk asserting
          ;; the same reachability has to say so itself.
          presence (->> (tree-seq map? :children t)
                        (filter map?)
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

;; ---------------------------------------------------------------------------
;; FH-PRESENCE-004 — the phase READ under a structural render (rf2-erqin)
;; ---------------------------------------------------------------------------
;;
;; The rows above pin the presence NODE. These pin the presence-aware CHILD:
;; a view that reads its own `(v/presence-phase)` to stamp its exit class and
;; `aria-hidden`, which is the shape Spec 004 §Presence tells an author to
;; write and the shape a `.cljc` structural test is supposed to be able to
;; render. A structural render carries no lifecycle — the presence node it
;; builds says `{:phase :present …}` — so the read answers `:present`, and it
;; answers it on BOTH hosts. It used to answer it only on the JVM: the
;; ClojureScript arm was an unconditional `react/useContext`, and `t/render`
;; opens no React render, so the identical declaration threw
;; `TypeError: Cannot read properties of null (reading 'useContext')`.
;;
;; It is a GOVERNED row rather than two ordinary tests, and the reason is the
;; ledger's own: FH-PRESENCE-001's declarations carry no phase read, so its
;; green never implied this and could not have caught the regression. A law
;; proven only by tests the conformance census cannot see is a law that can be
;; deleted without the index noticing (rf2-nxykb).

(def presence-004 (conf/fixture :FH-PRESENCE-004))

(deftest fh-presence-004-a-phase-reading-child-renders-in-both-modes
  (testing "Per FH-PRESENCE-004: a declaration that reads its own
            (v/presence-phase) renders under t/render — outside a boundary and
            under one — and the tree it produces is one pinned value from the
            interpreted declaration and from its compiled twin. The tree
            equality is the whole assertion: the expected :attrs maps are
            exact, so a phase other than :present, a stray exit class or a
            stray :aria-hidden all fail here."
    (is (seq (:cases presence-004)) "the fixture's case table loaded")
    (doseq [{:keys [note view args tree]} (:cases presence-004)]
      (let [interpreted (get views/by-name view)
            promoted    (get compiled/by-name view)
            call        (into [] args)]
        (is (some? interpreted) (str "the fixture names an interpreted view: " view))
        (is (some? promoted) (str "the fixture names a promoted twin: " view))
        (is (= tree (tree/render (into [interpreted] call)))
            (str note " (interpreted)"))
        (is (= (as-compiled-ids tree) (tree/render (into [promoted] call)))
            (str note " (compiled — the same node, from the same call)"))))))

(deftest fh-presence-004-the-structural-phase-is-present-and-nothing-exits
  (testing "Per FH-PRESENCE-004, said in the fixture's own vocabulary rather
            than left implied by a map equality: the phase the child READ is
            the one a render with no retention machine can truthfully report,
            and the two exit facts FH-PRESENCE-003 asserts are PRESENT while
            :unmounting are asserted ABSENT here. Read through the public
            `t/find` / `t/attrs` a consumer's own structural test calls."
    (let [phase      (:structural-phase presence-004)
          exit-class (:exit-class presence-004)
          exit-aria  (:exit-aria presence-004)
          rendered   (t/render [views/phase-stack {}])
          stack      (t/find rendered #(= :div (:tag %)))
          presence   (t/find rendered :rf.ui/presence)
          card       (t/find presence #(= :div (:tag %)))]
      (is (and (string? phase) (seq phase)) "the fixture states the phase")
      (is (= "stack" (:class (t/attrs stack)))
          "the whole declaration rendered rather than throwing")
      (is (= {:phase (keyword phase) :timeout-ms 300} (:rf.ui/presence presence))
          "the boundary node renders its children in that phase")
      (is (= phase (:data-phase (t/attrs card)))
          "and the child's own read agrees with the boundary")
      (is (not (re-find (re-pattern exit-class) (:class (t/attrs card))))
          "no exit class — a structural render is never :unmounting")
      (is (not (contains? (t/attrs card) exit-aria))
          "nor is the subtree hidden from the accessibility tree")
      (is (= "saved" (t/text presence))
          "with its content in place"))))

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
