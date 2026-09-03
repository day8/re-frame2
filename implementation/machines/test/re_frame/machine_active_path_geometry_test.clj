(ns re-frame.machine-active-path-geometry-test
  "Exact exit / action / entry IDENTITIES for every TARGET ↔ DECLARING-state
  transition geometry (rf2-xkr5r3).

  Per Spec 005 §Self-transitions the discriminator is the target's
  relationship to the state the transition is DECLARED on — NOT merely
  whether the target lies on the active path. Four geometries:

   1. **Targetless** — the ONLY internal case: the `:action` fires alone and
      the configuration, active descendants included, survives untouched.
   2. **Self / proper-ancestor target, no `:reenter?`** — the target NODE
      survives (its `:exit`/`:entry` do not fire) but its active DESCENDANTS
      exit and its `:initial` chain re-descends.
   3. **Proper-descendant target named by the declaring compound, no
      `:reenter?`** — the targeted descendant RE-ENTERS (its `:exit` AND
      `:entry` fire) while the declaring compound survives. This holds EVEN
      WHEN the target is already active and IS A LEAF, and it takes PRIORITY
      over (2) wherever both descriptions fit.
   4. **`:reenter? true`** — restarts the TARGET for (2), but the DECLARING
      COMPOUND (then descending to the named target) for (3).

  The load-bearing pin is the (1)-vs-(3) contrast at the SAME leaf: a
  `:parent`-declared target of the already-active leaf produces
  exit-leaf/action/enter-leaf, while the targetless control on `:parent`
  produces the action ALONE. Stale prose in `transition.cljc` once claimed an
  active-path target was `internal` and that targeting a LEAF necessarily
  equalled targetless; both are false for the declaring-compound descendant
  relationship, and `lca-len`'s `cond` has always ordered
  `target-descendant-of-decl?` AHEAD of `target-on-active-path?` to say so.
  Reordering those two arms must fail `parent-declared-active-leaf-*` below.

  The leaf SELF-target cases pin the genuine coincidence the stale prose
  over-generalised FROM: a transition declared ON the leaf targeting itself
  has no descendants to re-resolve, so it really does collapse to the
  targetless log — the distinction is the DECLARING state, not leaf-ness."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading the machines facade registers `rf/reg-machine` + the
            ;; reserved machine fxs when this ns runs alone.
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- tag
  "An `:actions` entry that appends `k` to `log` when it fires."
  [log k]
  (fn [_] (swap! log conj k) {}))

(defn- drive!
  "Register `machine` under `id`, consume the bootstrap initial-entry cascade
  with a benign unhandled event, run `setup-events`, then clear `log` and
  dispatch `event`. Returns the recorded exit/action/entry identities for
  `event` ALONE."
  ([log id machine event] (drive! log id machine [] event))
  ([log id machine setup-events event]
   (rf/reg-machine id machine)
   ;; The first dispatch fires the bootstrap cascade; a benign unhandled
   ;; event drives it without touching the states under test.
   (rf/dispatch-sync [id [::prime]])
   (doseq [e setup-events]
     (rf/dispatch-sync [id e]))
   (reset! log [])
   (rf/dispatch-sync [id event])
   @log))

;; ===========================================================================
;; (1) vs (3) — the parent-declared ACTIVE LEAF regression.
;; ===========================================================================

(defn- parent-declared-leaf-machine [log]
  {:initial :parent
   :data    {}
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
     ;; Every transition here is DECLARED ON :parent, so `decl-path` is
     ;; [:parent] and `[:parent :leaf]` is a PROPER DESCENDANT of it.
     :on      {:targetless   {:action :act}
               :target-leaf  {:target [:parent :leaf] :action :act}
               :reenter-leaf {:target [:parent :leaf] :reenter? true :action :act}}
     :states  {:leaf {:entry :enter-leaf :exit :exit-leaf}}}}})

(deftest parent-declared-active-leaf-re-enters-the-leaf
  (testing "a :parent-declared target of the ALREADY-ACTIVE LEAF re-enters the
            leaf itself — exit-leaf → action → enter-leaf — because the
            declaring-compound/proper-descendant relationship takes priority
            over the generic active-path case (target-descendant-of-decl?
            is tested ahead of target-on-active-path? in lca-len)"
    (let [log (atom [])]
      (is (= [:exit-leaf :act :enter-leaf]
             (drive! log :geo/parent-leaf (parent-declared-leaf-machine log)
                     [:target-leaf]))
          "the targeted leaf IS re-entered; :parent is neither exited nor entered")
      (is (= [:parent :leaf] (rf.machines.test-support/machine-state :geo/parent-leaf))
          "the configuration is unchanged in VALUE — the churn is the point"))))

(deftest targetless-control-on-the-same-leaf-runs-the-action-alone
  (testing "the TARGETLESS control declared on the same :parent, at the same
            [:parent :leaf] configuration, runs the :action ALONE — no exit,
            no entry. This is the contrast that makes an explicit
            active-path target NOT internal and NOT equal to targetless"
    (let [log (atom [])]
      (is (= [:act]
             (drive! log :geo/parent-leaf-control (parent-declared-leaf-machine log)
                     [:targetless]))
          "targetless is the ONLY true internal no-op — the action fires alone"))))

(deftest parent-declared-leaf-with-reenter-restarts-the-declaring-compound
  (testing ":reenter? on a declaring-compound DESCENDANT target restarts the
            DECLARING COMPOUND (:parent) and then descends to the NAMED
            target — not :parent's :initial by coincidence"
    (let [log (atom [])]
      (is (= [:exit-leaf :exit-parent :act :enter-parent :enter-leaf]
             (drive! log :geo/parent-leaf-reenter (parent-declared-leaf-machine log)
                     [:reenter-leaf]))
          ":parent exits + re-enters (boundary pulls up to decl-path's parent)"))))

;; ===========================================================================
;; (2) — leaf SELF target: the genuine targetless coincidence.
;; ===========================================================================

(defn- leaf-self-machine [log]
  {:initial :parent
   :data    {}
   :actions {:act        (tag log :act)
             :enter-leaf (tag log :enter-leaf)
             :exit-leaf  (tag log :exit-leaf)}
   :states
   {:parent
    {:initial :leaf
     ;; DECLARED ON :leaf — `decl-path` is [:parent :leaf], so `:same-state`
     ;; resolves to the declaring state ITSELF: a SELF target, never a
     ;; descendant of the declaring state.
     :states {:leaf {:entry :enter-leaf
                     :exit  :exit-leaf
                     :on    {:self         {:target :same-state :action :act}
                             :self-reenter {:target :same-state :reenter? true :action :act}}}}}}})

(deftest leaf-self-target-without-reenter-coincides-with-targetless
  (testing "a SELF target declared ON the leaf has no descendants to
            re-resolve, so it genuinely collapses to the targetless log. This
            is the real coincidence the stale prose over-generalised from —
            it is licensed by the DECLARING-state relationship (self, not
            descendant), NOT by leaf-ness alone"
    (let [log (atom [])]
      (is (= [:act]
             (drive! log :geo/leaf-self (leaf-self-machine log) [:self]))
          "self target on a leaf: the target survives and has no descendants"))))

(deftest leaf-self-target-with-reenter-restarts-the-leaf
  (testing ":reenter? on a leaf SELF target restarts the TARGET itself"
    (let [log (atom [])]
      (is (= [:exit-leaf :act :enter-leaf]
             (drive! log :geo/leaf-self-reenter (leaf-self-machine log)
                     [:self-reenter]))
          "the leaf is exited + re-entered under the external opt-in"))))

;; ===========================================================================
;; (2) — proper-ANCESTOR target: node survives, descendants re-resolve.
;; ===========================================================================

(defn- ancestor-target-machine [log]
  {:initial :process
   :data    {}
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
     ;; Declared ON :process targeting :process — `decl-path` == `target-base`,
     ;; so this is a SELF target of the declaring compound (NOT a descendant).
     :on      {:restart         {:target :process :action :act}
               :restart-reenter {:target :process :reenter? true :action :act}}
     :states  {:step1 {:entry :enter-step1 :exit :exit-step1
                       :on    {:next :step3}}
               :step3 {:entry :enter-step3 :exit :exit-step3}}}}})

(deftest ancestor-target-without-reenter-re-resolves-descendants-only
  (testing "at [:process :step3], :target :process declared on :process exits
            :step3 and re-descends :process's :initial (:step1) — :process
            itself is NEITHER exited NOR entered. NOT a no-op (the targetless
            control would have preserved :step3)"
    (let [log (atom [])]
      (is (= [:exit-step3 :act :enter-step1]
             (drive! log :geo/ancestor (ancestor-target-machine log)
                     [[:next]] [:restart]))
          "descendants re-resolve; the target node survives")
      (is (= [:process :step1] (rf.machines.test-support/machine-state :geo/ancestor))
          "the active descendant really was reset to :initial"))))

(deftest ancestor-target-with-reenter-restarts-the-target
  (testing ":reenter? on a self/ancestor target restarts the TARGET: it exits
            and re-enters, then re-descends its own :initial"
    (let [log (atom [])]
      (is (= [:exit-step3 :exit-process :act :enter-process :enter-step1]
             (drive! log :geo/ancestor-reenter (ancestor-target-machine log)
                     [[:next]] [:restart-reenter]))
          ":process exits + re-enters, then re-descends :initial"))))

;; ===========================================================================
;; (3) — parent-declared COMPOUND descendant.
;; ===========================================================================

(defn- compound-descendant-machine [log]
  {:initial :parent
   :data    {}
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
  (testing "at [:parent :child :a], a :parent-declared target of the COMPOUND
            descendant [:parent :child] re-enters :child (exit a + child,
            then re-enter child and re-descend its :initial) while :parent
            survives — the boundary is computed against target-BASE, so a
            descendant whose :initial re-descends to the active leaf still
            re-enters rather than collapsing to a silent no-op"
    (let [log (atom [])]
      (is (= [:exit-a :exit-child :act :enter-child :enter-a]
             (drive! log :geo/compound-descendant (compound-descendant-machine log)
                     [:target-child]))
          "the targeted compound descendant re-enters; :parent untouched"))))
