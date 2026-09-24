(ns re-frame.machine-decl-path-default-test
  "`compute-transition-geometry`'s default `:decl-path` is ROOT (`[]`), not a
  depth-1 guess.

  `compute-transition-geometry` (reached via the public `apply-transition-once`)
  reads `(:decl-path transition <default>)` — the absolute path of the state
  node whose `:on` / `:always` / `:after` table the transition was declared
  in. It anchors the LCA / exit / entry geometry: a transition's DOMAIN is its
  declaring node (XState `getTransitionDomain`), so for a proper-descendant
  target every active state below the declaring node exits while the
  declaring node survives. The machine ROOT is such a node.

  Every in-tree caller stamps `:decl-path` (via `pick-transition` then
  `machine-transition-single`'s `(assoc :decl-path …)`), so the default is
  reached only by a pure-fn / hand-built / future caller of the public
  `apply-transition-once`. For such a caller the ROOT default keeps a
  ROOT-declared transition (decl-path `[]`) on a deep machine correctly
  located at root: a depth-1 default (`(vec (take 1 src-path))`, the first
  element of the active leaf's path) would mis-locate it at `[<top>]`, moving
  the domain down a level — the top-level compound would then survive a
  root transition that must restart it (the wrong exit / entry set).

  This is a pure-engine geometry test — it calls `apply-transition-once`
  directly with a transition map carrying NO `:decl-path`, so it exercises the
  default. JVM-runnable from arguments alone."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines.transition :as rf.machines.transition]))

;; A deep compound machine. `:p` is a top-level compound (initial :q); `:q` and
;; `:r` are its children; `:r` is itself compound (initial :s). Each state
;; records its `:exit` / `:entry` firing into `:data :log` so the cascade is
;; observable. The active configuration is the leaf [:p :q]. `:p` carries an
;; `:exit` so the log can tell a root domain (`:p` restarts) from a depth-1
;; `[:p]` domain (`:p` survives).
(def ^:private deep-machine
  {:initial :p
   :data    {:log []}
   :actions {:exit-q  (fn [{:keys [data]}] {:data (update data :log conj :exit-q)})
             :exit-p  (fn [{:keys [data]}] {:data (update data :log conj :exit-p)})
             :enter-r (fn [{:keys [data]}] {:data (update data :log conj :enter-r)})
             :enter-s (fn [{:keys [data]}] {:data (update data :log conj :enter-s)})}
   :states
   {:p {:initial :q
        :exit    :exit-p
        :states  {:q {:exit :exit-q}
                  :r {:initial :s
                      :entry   :enter-r
                      :states  {:s {:entry :enter-s}}}}}}})

(deftest decl-path-default-is-root-not-depth-1
  (testing "a ROOT-declared transition with NO `:decl-path` (a pure-fn /
   hand-built caller) targeting a deep descendant of a top-level compound
   resolves its domain at ROOT — NOT the depth-1 guess that would move the
   domain down to `:p` and let `:p` survive"
    ;; Active leaf [:p :q]; transition (no :decl-path) targets [:p :r :s]
    ;; (a deeper descendant of :p, NOT on the active branch).
    ;;
    ;; With the ROOT default (decl-path []): the domain is the root, so every
    ;; active state below it exits — :q then :p — and the path from the root
    ;; to the target enters: :p (no :entry), :r, :s. lca-len = 0.
    ;;
    ;; With a depth-1 default (decl-path [:p]): the domain would be :p, so
    ;; only :q exits and :p survives (lca-len = 1) — the third `testing`
    ;; below pins that contrast, so this test can tell the two apart.
    (let [snapshot {:state [:p :q] :data {:log []}}
          ;; NB: no :decl-path key — exercises the default.
          r        (rf.machines.transition/apply-transition-once
                     deep-machine snapshot [:go] {:target [:p :r :s]})]
      (is (= :ok (:status r)) "the transition applies cleanly")
      (let [snap (:snapshot r)]
        (is (= [:p :r :s] (:state snap))
            "the machine lands at the targeted deep leaf [:p :r :s]")
        (is (= [:exit-q :exit-p :enter-r :enter-s] (get-in snap [:data :log]))
            "ROOT domain: :q and :p exit (deepest-first), then :r and :s enter
             — :p restarts. The depth-1 guess would keep :p alive."))))

  (testing "control — the SAME transition WITH an explicit root `:decl-path []`
   produces the identical cascade (the default and the explicit root agree)"
    (let [snapshot {:state [:p :q] :data {:log []}}
          r        (rf.machines.transition/apply-transition-once
                     deep-machine snapshot [:go] {:target [:p :r :s] :decl-path []})]
      (is (= :ok (:status r)))
      (is (= [:exit-q :exit-p :enter-r :enter-s] (get-in (:snapshot r) [:data :log]))
          "explicit root decl-path matches the defaulted-root geometry")))

  (testing "contrast — an explicit depth-1 `:decl-path [:p]` gives a DIFFERENT
   cascade (:p survives), so the default above is observably the root"
    (let [snapshot {:state [:p :q] :data {:log []}}
          r        (rf.machines.transition/apply-transition-once
                     deep-machine snapshot [:go] {:target [:p :r :s] :decl-path [:p]})]
      (is (= :ok (:status r)))
      (is (= [:exit-q :enter-r :enter-s] (get-in (:snapshot r) [:data :log]))
          "a :p-declared transition's domain is :p — :q exits, :p survives"))))
