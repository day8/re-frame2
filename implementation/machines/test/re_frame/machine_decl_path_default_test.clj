(ns re-frame.machine-decl-path-default-test
  "`compute-transition-geometry`'s default `:decl-path` is ROOT (`[]`), not a
  depth-1 guess.

  A transition's DOMAIN is its declaring node (XState `getTransitionDomain`),
  read from `:decl-path`. Every in-tree caller stamps it, so the default is
  reached only by a hand-built caller of the public `apply-transition-once`;
  for such a caller a root-declared transition must restart a top-level
  compound on its way to a deep descendant."
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
  (testing "a ROOT-declared transition with no `:decl-path` targeting a deep
   descendant of a top-level compound restarts that compound (:q and :p exit,
   then :r and :s enter): its domain is the root, not the depth-1 guess"
    (let [snap (:snapshot (rf.machines.transition/apply-transition-once
                            deep-machine {:state [:p :q] :data {:log []}} [:go]
                            {:target [:p :r :s]}))]
      (is (= [[:p :r :s] [:exit-q :exit-p :enter-r :enter-s]]
             [(:state snap) (get-in snap [:data :log])])))))
