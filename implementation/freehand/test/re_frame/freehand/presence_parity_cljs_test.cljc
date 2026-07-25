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

;; ---------------------------------------------------------------------------
;; The phase READ under a structural render — rf2-erqin
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

(v/defview toast-card
  "The presence-aware child Spec 004 §Presence teaches: it owns its exit
  styling and accessibility by reading its OWN phase. Nothing here is
  host-bearing — the read is the whole point."
  [{:keys [label]}]
  (let [phase    (v/presence-phase)
        exiting? (= :unmounting phase)]
    [:div.toast {:class       (when exiting? "toast--exit")
                 :aria-hidden (when exiting? "true")
                 :data-phase  (name phase)}
     label]))

(v/defview toast-stack
  "The same child under a real boundary — the whole declaration a consumer
  writes, rendered structurally in one call."
  [_]
  [:div.stack
   (v/presence {:timeout-ms 300}
     [toast-card {:key "a" :label "saved"}])])

(deftest a-presence-phase-reading-child-renders-structurally
  (testing "A view that reads its own (v/presence-phase) renders under
            t/render on both hosts, and reads :present — the one phase a
            render with no retention machine can truthfully report."
    (let [t    (t/render [toast-card {:label "saved"}])
          card (t/find t #(= :div (:tag %)))]
      (is (= "present" (:data-phase (t/attrs card)))
          "the child stamped the phase it read")
      (is (= "toast" (:class (t/attrs card)))
          "and no exit class — a structural render is never :unmounting")
      (is (not (contains? (t/attrs card) :aria-hidden))
          "nor is the exiting subtree hidden from the a11y tree")
      (is (= "saved" (t/text t))
          "the child rendered its content rather than throwing"))))

(deftest a-presence-phase-read-under-a-boundary-renders-structurally
  (testing "The same child UNDER a (v/presence …) boundary renders too, and
            agrees with the boundary's own marker: the node says :present and
            so does every child that asks."
    (let [t        (t/render [toast-stack {}])
          stack    (t/find t #(= :div (:tag %)))
          presence (t/find t :rf.ui/presence)
          card     (t/find presence #(= :div (:tag %)))]
      (is (= "stack" (:class (t/attrs stack)))
          "the whole declaration rendered rather than throwing")
      (is (= {:phase :present :timeout-ms 300} (:rf.ui/presence presence))
          "the boundary node renders its children :present")
      (is (= "present" (:data-phase (t/attrs card)))
          "and the child's own read agrees with it")
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
