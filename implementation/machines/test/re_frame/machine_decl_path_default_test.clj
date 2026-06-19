(ns re-frame.machine-decl-path-default-test
  "Per rf2-h16qii — `compute-cascade-paths`' default `:decl-path` is ROOT
  (`[]`), not a depth-1 guess.

  `compute-cascade-paths` (reached via the public `apply-transition-once`)
  reads `(:decl-path transition <default>)` — the absolute path of the state
  node whose `:on` / `:always` / `:after` table the transition was declared
  in. It anchors the LCA / exit / entry geometry: the `target-descendant-of-
  decl?` rule (XState v5 — \"child nodes targeted by a compound transition are
  re-entered\") is GATED on a NON-EMPTY decl-path (`(pos? (count decl-path))`),
  which deliberately excludes the synthetic ROOT.

  The pre-fix default was `(vec (take 1 src-path))` — the FIRST element of the
  active leaf's path, i.e. it assumed the transition was declared at DEPTH 1.
  Every in-tree caller stamps `:decl-path` (via `pick-transition` then
  `machine-transition-single`'s `(assoc :decl-path …)`), so the default is
  reached only by a pure-fn / hand-built / future caller of the public
  `apply-transition-once`. For such a caller a ROOT-declared transition
  (decl-path `[]`) on a deep machine was mis-located at `[<top>]` — a NON-empty
  path that WRONGLY tripped `target-descendant-of-decl?`, diverging the LCA
  geometry (the wrong exit / entry set).

  This is a pure-engine geometry test — it calls `apply-transition-once`
  directly with a transition map carrying NO `:decl-path`, so it exercises the
  default. JVM-runnable from arguments alone."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines.result :as result]
            [re-frame.machines.transition :as transition]))

;; A deep compound machine. `:p` is a top-level compound (initial :q); `:q` and
;; `:r` are its children; `:r` is itself compound (initial :s). Each state
;; records its `:exit` / `:entry` firing into `:data :log` so the cascade is
;; observable. The active configuration is the leaf [:p :q].
(def ^:private deep-machine
  {:initial :p
   :data    {:log []}
   :actions {:exit-q  (fn [{:keys [data]}] {:data (update data :log conj :exit-q)})
             :exit-p  (fn [{:keys [data]}] {:data (update data :log conj :exit-p)})
             :enter-r (fn [{:keys [data]}] {:data (update data :log conj :enter-r)})
             :enter-s (fn [{:keys [data]}] {:data (update data :log conj :enter-s)})}
   :states
   {:p {:initial :q
        :states  {:q {:exit :exit-q}
                  :r {:initial :s
                      :entry   :enter-r
                      :states  {:s {:entry :enter-s}}}}}}})

(deftest decl-path-default-is-root-not-depth-1
  (testing "a ROOT-declared transition with NO `:decl-path` (a pure-fn /
   hand-built caller) targeting a deep descendant of a DIFFERENT top-level
   subtree resolves the LCA geometry from ROOT — NOT the depth-1 guess that
   would mis-trip `target-descendant-of-decl?` and break the exit/entry set"
    ;; Active leaf [:p :q]; transition (no :decl-path) targets [:p :r :s]
    ;; (a deeper descendant of :p, NOT on the active branch). The LCCA of
    ;; [:p :q] and [:p :r :s] is [:p] (common-prefix length 1), so the
    ;; correct geometry exits :q (deepest-first) and enters :r then :s while
    ;; :p survives.
    ;;
    ;; With the ROOT default (decl-path []): `target-descendant-of-decl?` is
    ;; false (empty decl-path), so lca-len = common-prefix = 1 → exit :q,
    ;; enter :r, :s. CORRECT.
    ;;
    ;; With the pre-fix depth-1 default (decl-path [:p]):
    ;; `target-descendant-of-decl?` would be TRUE ([:p] is a prefix of both
    ;; src and target), forcing lca-len = (dec (count [:p :r :s])) = 2 →
    ;; exit set EMPTY (:q not exited) and only :s entered. BROKEN: :q would
    ;; stay on the active path while the machine dives to :s.
    (let [snapshot {:state [:p :q] :data {:log []}}
          ;; NB: no :decl-path key — exercises the default.
          r        (transition/apply-transition-once
                     deep-machine snapshot [:go] {:target [:p :r :s]})]
      (is (result/ok? r) "the transition applies cleanly")
      (let [snap (result/snap r)]
        (is (= [:p :r :s] (:state snap))
            "the machine lands at the targeted deep leaf [:p :r :s]")
        (is (= [:exit-q :enter-r :enter-s] (get-in snap [:data :log]))
            "ROOT-anchored geometry: :q exits (deepest-first), then :r and :s
             enter — :p survives. The depth-1 guess would skip :q's exit and
             :r's entry (lca-len pulled to 2 by a mis-tripped descendant rule)."))))

  (testing "control — the SAME transition WITH an explicit root `:decl-path []`
   produces the identical cascade (the default and the explicit root agree)"
    (let [snapshot {:state [:p :q] :data {:log []}}
          r        (transition/apply-transition-once
                     deep-machine snapshot [:go] {:target [:p :r :s] :decl-path []})]
      (is (result/ok? r))
      (is (= [:exit-q :enter-r :enter-s] (get-in (result/snap r) [:data :log]))
          "explicit root decl-path matches the defaulted-root geometry"))))
