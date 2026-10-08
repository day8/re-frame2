(ns re-frame.machine-active-path-geometry-test
  "Exact exit / action / entry identities for each TARGET <-> DECLARING-state
  geometry (Spec 005 §Self-transitions). The discriminator is the target's
  relationship to the state the transition is declared on, not whether the
  target lies on the active path:

   1. Targetless: the only internal case; the `:action` fires alone.
   2. Self target (the declaring state), no `:reenter?`: the target survives,
      its active descendants exit and its `:initial` chain re-descends. A
      proper ANCESTOR of the declaring state instead exits and re-enters,
      with or without `:reenter?`.
   3. Proper-descendant target named by the declaring compound, no
      `:reenter?`: the descendant re-enters while the declarer survives, even
      when the target is the already-active leaf. This takes priority over (2).
   4. `:reenter? true`: restarts the target for (2), the declaring compound
      for (3).

  The load-bearing pin is (1) vs (3) at the same leaf: `lca-len` tests
  `target-descendant-of-decl?` ahead of `target-on-active-path?`, and
  reordering those arms must fail `parent-declared-active-leaf-*`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; Registers `rf/reg-machine` when this ns runs alone.
            [re-frame.machines]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- tag
  "An `:actions` entry that appends `k` to `log` when it fires."
  [log k]
  (fn [_] (swap! log conj k) {}))

(defn- drive!
  "Register `machine` under `id`, consume the bootstrap cascade with a benign
  unhandled event, run `setup-events`, then clear `log` and dispatch `event`.
  Returns the exit/action/entry identities `event` alone recorded."
  ([log id machine event] (drive! log id machine [] event))
  ([log id machine setup-events event]
   (rf/reg-machine id machine)
   (rf/dispatch-sync [id [::prime]])
   (doseq [e setup-events]
     (rf/dispatch-sync [id e]))
   (reset! log [])
   (rf/dispatch-sync [id event])
   @log))

;; ---- (1) vs (3): the parent-declared ACTIVE LEAF --------------------------

(defn- parent-declared-leaf-machine [log]
  {:initial :parent
   :actions {:act          (tag log :act)
             :enter-parent (tag log :enter-parent)
             :exit-parent  (tag log :exit-parent)
             :enter-leaf   (tag log :enter-leaf)
             :exit-leaf    (tag log :exit-leaf)}
   :states
   {:parent
    {:initial :leaf
     :entry   :enter-parent
     :exit    :exit-parent
     ;; Declared ON :parent, so [:parent :leaf] is a proper descendant of the declarer.
     :on      {:targetless   {:action :act}
               :target-leaf  {:target [:parent :leaf] :action :act}
               :reenter-leaf {:target [:parent :leaf] :reenter? true :action :act}}
     :states  {:leaf {:entry :enter-leaf :exit :exit-leaf}}}}})

(deftest parent-declared-active-leaf-re-enters-the-leaf
  (let [log (atom [])]
    (is (= [:exit-leaf :act :enter-leaf]
           (drive! log :geo/parent-leaf (parent-declared-leaf-machine log) [:target-leaf])))))

(deftest targetless-control-on-the-same-leaf-runs-the-action-alone
  (let [log (atom [])]
    (is (= [:act]
           (drive! log :geo/parent-leaf-control (parent-declared-leaf-machine log) [:targetless])))))

(deftest parent-declared-leaf-with-reenter-restarts-the-declaring-compound
  (let [log (atom [])]
    (is (= [:exit-leaf :exit-parent :act :enter-parent :enter-leaf]
           (drive! log :geo/parent-leaf-reenter (parent-declared-leaf-machine log) [:reenter-leaf])))))

;; ---- (2) / (4) self and ANCESTOR targets on a spawning compound -----------
;;
;; At [:process :step3]. The `:spawn` child is what the ancestor OWNS, so its
;; incarnation shows whether :process itself restarted.

(defn- spawning-ancestor-machine [log]
  {:initial :process
   :actions {:act           (tag log :act)
             :enter-process (tag log :enter-process)
             :exit-process  (tag log :exit-process)
             :enter-step1   (tag log :enter-step1)
             :exit-step1    (tag log :exit-step1)
             :enter-step3   (tag log :enter-step3)
             :exit-step3    (tag log :exit-step3)}
   :states
   {:process
    {:initial :step1
     :entry   :enter-process
     :exit    :exit-process
     :spawn   {:machine-id :geo/worker}
     ;; Declared ON :process, targeting :process: a SELF target.
     :on      {:restart         {:target :process :action :act}
               :restart-reenter {:target :process :reenter? true :action :act}}
     :states  {:step1 {:entry :enter-step1 :exit :exit-step1
                       :on    {:next :step3}}
               :step3 {:entry :enter-step3 :exit :exit-step3
                       ;; Declared on :step3, targeting its proper ancestor.
                       :on    {:restart-from-child {:target [:process] :action :act}}}}}}})

(defn- drive-spawning-ancestor!
  "At [:process :step3], dispatch `event`. Returns its recorded identities and
  whether `:process`'s spawned child was `:kept` or `:respawned`."
  [id event]
  (let [log (atom [])]
    (rf/reg-machine :geo/worker {:initial :idle :states {:idle {}}})
    (rf/reg-machine id (spawning-ancestor-machine log))
    (rf/dispatch-sync [id [::prime]])
    (rf/dispatch-sync [id [:next]])
    (let [kid    #(get-in (rf.machines.test-support/runtime-db)
                          (rf.machines.paths/spawned-path id [:process]))
          before (kid)]
      (reset! log [])
      (rf/dispatch-sync [id event])
      {:steps @log
       :kid   (cond (nil? before)       :never-spawned
                    (= before (kid))    :kept
                    :else               :respawned)})))

(deftest compound-self-target-with-reenter-restarts-the-target
  (is (= [:exit-step3 :exit-process :act :enter-process :enter-step1]
         (:steps (drive-spawning-ancestor! :geo/self-reenter [:restart-reenter])))))

(deftest child-declared-ancestor-target-restarts-the-ancestor-and-its-child
  (is (= {:steps [:exit-step3 :exit-process :act :enter-process :enter-step1] :kid :respawned}
         (drive-spawning-ancestor! :geo/child-ancestor [:restart-from-child]))))

(deftest ancestor-declared-self-target-keeps-the-ancestor-and-its-child
  (is (= {:steps [:exit-step3 :act :enter-step1] :kid :kept}
         (drive-spawning-ancestor! :geo/self-ancestor [:restart]))))

;; ---- (3) the parent-declared COMPOUND descendant --------------------------

(defn- compound-descendant-machine [log]
  {:initial :parent
   :actions {:act          (tag log :act)
             :enter-parent (tag log :enter-parent)
             :exit-parent  (tag log :exit-parent)
             :enter-child  (tag log :enter-child)
             :exit-child   (tag log :exit-child)
             :enter-a      (tag log :enter-a)
             :exit-a       (tag log :exit-a)}
   :states
   {:parent
    {:initial :child
     :entry   :enter-parent
     :exit    :exit-parent
     :on      {:target-child {:target [:parent :child] :action :act}}
     :states  {:child {:initial :a
                       :entry   :enter-child
                       :exit    :exit-child
                       :states  {:a {:entry :enter-a :exit :exit-a}}}}}}})

(deftest parent-declared-compound-descendant-re-enters-the-descendant
  ;; The boundary is computed against the target BASE, so a descendant whose
  ;; :initial re-descends to the active leaf still re-enters, never a no-op.
  (let [log (atom [])]
    (is (= [:exit-a :exit-child :act :enter-child :enter-a]
           (drive! log :geo/compound-descendant (compound-descendant-machine log) [:target-child])))))
