(ns re-frame.after-test
  "State-level `:after` timers at runtime (Spec 005 §Delayed :after
  transitions). The tests dispatch the synthetic
  [:rf.machine.timer/after-elapsed delay-key epoch decl-path] event the host
  clock would, so a firing is deterministic; the decl-path is part of that
  contract (Spec 005 §Hierarchy interaction)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.subs]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private frame-db rf.machines.test-support/runtime-db)
(def ^:private snapshot rf.machines.test-support/snapshot)
(def ^:private events-of rf.machines.test-support/events-of)

(defn- epoch-of [machine-id decl-path]
  (get-in (snapshot machine-id) [:data :rf/after-epoch decl-path]))

;; Spec 005 §Multi-stage interaction with :guard: a guard-suppressed firing is
;; fired and discarded, and its sibling timers stay live.
(deftest after-guard-suppression-leaves-sibling-timers-live
  (rf/reg-machine :a/guard
                  {:initial :idle
                   :data    {:slow? false}
                   :guards  {:slow? (fn [{:keys [data]}] (:slow? data))}
                   :states  {:idle    {:on {:fetch :loading}}
                             :loading {:after {5000  {:guard :slow? :target :warn}
                                               30000 :timeout}}
                             :warn    {}
                             :timeout {}}})
  (rf/dispatch-sync [:a/guard [:fetch]])
  (let [epoch (epoch-of :a/guard [:loading])]
    (rf/dispatch-sync [:a/guard [:rf.machine.timer/after-elapsed 5000 epoch [:loading]]])
    (is (= [:loading epoch] [(:state (snapshot :a/guard)) (epoch-of :a/guard [:loading])])
        "the guard-suppressed :after neither transitions nor advances the epoch")
    (is (= [[false 5000]] (map (comp (juxt :fired? :delay) :tags)
                               (events-of :rf.machine.timer/fired)))
        "one :fired? false trace marks the suppression")
    (rf/dispatch-sync [:a/guard [:rf.machine.timer/after-elapsed 30000 epoch [:loading]]])
    (is (= :timeout (:state (snapshot :a/guard)))
        "the sibling timer, at the same epoch, still fires")))

;; Spec 005 §Hierarchy interaction: epochs are per declaring path, so a
;; child-only sibling transition stales the exited child's timer and leaves the
;; parent's in-flight timer live.
(deftest after-parent-survives-child-sibling-transition
  (rf/reg-machine :a/hier
                  {:initial :p
                   :data    {}
                   :states  {:p {:initial :a
                                 :after   {30000 :timed-out}
                                 :states  {:a {:after {5000 [:a-warn]} :on {:next :b}}
                                           :b {:after {7000 [:b-warn]}}}}
                             :timed-out {}
                             :a-warn    {}
                             :b-warn    {}}})
  (rf/dispatch-sync [:a/hier [:noop]])
  (let [parent-epoch (epoch-of :a/hier [:p])
        a-epoch      (epoch-of :a/hier [:p :a])]
    (rf/dispatch-sync [:a/hier [:next]])
    (is (not= a-epoch (epoch-of :a/hier [:p :a]))
        "exiting :a advanced its own epoch")
    (rf/dispatch-sync [:a/hier [:rf.machine.timer/after-elapsed 5000 a-epoch [:p :a]]])
    (is (= [:p :b] (:state (snapshot :a/hier)))
        "the exited child's timer does not transition")
    (is (= [5000] (map (comp :delay :tags) (events-of :rf.machine.timer/stale-after)))
        "it drops as :stale-after")
    (rf/dispatch-sync [:a/hier [:rf.machine.timer/after-elapsed 30000 parent-epoch [:p]]])
    (is (= [:timed-out] (:state (snapshot :a/hier)))
        "the parent's timer is still live and fires")))

;; ---- :spawn-bearing state with :after — wall-clock guard via :after ----

(deftest after-on-spawn-bearing-state-tears-down-child
  (testing ":after on a :spawn-bearing state — firing tears down the spawned child"
    (let [child {:initial :running
                 :states  {:running {:on {:never-fires :done}}
                           :done    {}}}
          parent {:initial :idle
                  :data    {}
                  :states
                  {:idle {:on {:go :authenticating}}
                   :authenticating
                   {:spawn {:machine-id :child/auth}
                    :after  {30000 :timed-out}
                    :on    {:auth/succeeded :authenticated}}
                   :authenticated {}
                   :timed-out     {}}}]
      (rf/reg-machine :child/auth child)
      (rf/reg-machine :sup/atimeout parent)
      (rf/dispatch-sync [:sup/atimeout [:go]])
      (let [child-id (get-in (frame-db) [:rf.runtime/machines :spawned :sup/atimeout [:authenticating]])
            epoch    (get-in (snapshot :sup/atimeout) [:data :rf/after-epoch [:authenticating]])]
        (is (some? child-id) "spawn slot bound")
        (is (some? (get-in (frame-db) [:rf.runtime/machines :snapshots child-id]))
            "child snapshot exists")
        (rf/dispatch-sync [:sup/atimeout [:rf.machine.timer/after-elapsed 30000 epoch [:authenticating]]])
        (is (= :timed-out (:state (snapshot :sup/atimeout)))
            "parent transitioned via :after firing")
        (is (nil? (get-in (frame-db) [:rf.runtime/machines :snapshots child-id]))
            "child machine destroyed via standard exit cascade")))))

;; ---- delay-resolution error arms ------------------------------------------
;;
;; Each arm surfaces its failure as its own error trace instead of letting it
;; show up only as a downstream :rf.error/machine-bad-after-delay.

(defn- error-reports
  "Each `op` error trace as its envelope `:op-type` and `:recovery`, whether it
  carries an `:exception`, and its `tag-keys`."
  [op tag-keys]
  (for [ev (events-of op)]
    (merge {:op-type    (:op-type ev)
            :recovery   (:recovery ev)
            :exception? (some? (-> ev :tags :exception))}
           (select-keys (:tags ev) tag-keys))))

(deftest after-fn-form-throw-surfaces-trace
  (rf/reg-machine :a/throws
                  {:initial :idle
                   :data    {}
                   :states  {:idle    {:on {:go :running}}
                             :running {:after {(fn [_ctx] (throw (ex-info "fn-form delay blew up" {})))
                                               :timeout}}
                             :timeout {}}})
  (rf/dispatch-sync [:a/throws [:go]])
  (is (= [{:op-type :error :recovery :skipped :exception? true}]
         (error-reports :rf.error/machine-after-fn-threw []))))

;; A user sub body's throw is caught inside the subscription layer, so this arm
;; is for a reaction whose deref throws directly: `subscribe` is stubbed to
;; return one.
(deftest after-sub-vec-deref-throw-surfaces-trace
  (rf/reg-sub :s/well-formed (fn [_db _] 1000))
  (rf/reg-machine :s/throws-machine
                  {:initial :idle
                   :data    {}
                   :states  {:idle    {:on {:go :running}}
                             :running {:after {[:s/well-formed] :timeout}}
                             :timeout {}}})
  (let [throwing-reaction (reify clojure.lang.IDeref
                            (deref [_] (throw (ex-info "reaction deref blew up" {}))))]
    (with-redefs [re-frame.subs/subscribe (fn
                                            ([_query-v] throwing-reaction)
                                            ([_query-v _opts] throwing-reaction))]
      (rf/dispatch-sync [:s/throws-machine [:go]])))
  (is (= [{:op-type        :error
           :recovery       :skipped
           :exception?     true
           :rf.sub/id      :s/well-formed
           :rf.sub/query-v [:s/well-formed]}]
         (error-reports :rf.error/machine-after-sub-threw [:rf.sub/id :rf.sub/query-v]))))

;; A failed watch would otherwise stop a dynamic delay re-resolving with no
;; signal; the timer still arms at its first resolution (:recovery :static-delay).
(deftest after-sub-vec-watch-failure-surfaces-trace
  (rf/reg-sub :s/well-behaved (fn [_db _] 1000))
  (rf/reg-machine :w/throws-machine
                  {:initial :idle
                   :data    {}
                   :states  {:idle    {:on {:go :running}}
                             :running {:after {[:s/well-behaved] :timeout}}
                             :timeout {}}})
  (let [real-add-watch add-watch]
    (with-redefs [clojure.core/add-watch
                  (fn [target key f]
                    (if (and (vector? key)
                             (= :re-frame.machines.timer/after-watch (first key)))
                      (throw (ex-info "add-watch blew up on after-watch" {:key key}))
                      (real-add-watch target key f)))]
      (rf/dispatch-sync [:w/throws-machine [:go]])))
  (is (= [{:op-type        :error
           :recovery       :static-delay
           :exception?     true
           :rf.sub/id      :s/well-behaved
           :rf.sub/query-v [:s/well-behaved]
           :actor-id       :w/throws-machine}]
         (error-reports :rf.error/machine-after-watch-failed
                        [:rf.sub/id :rf.sub/query-v :actor-id]))))
