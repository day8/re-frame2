(ns re-frame.machine-destroy-at-event-boundary-cljs-test
  "A machine callback that destroys its own frame during the event — an `:exit`
  effect inside an actor's teardown, a spawned actor's `:entry` action on the
  way into a root `:final?` leaf — does not cut the machine's tail short. The destroy waits for
  the event boundary (Spec 002 §Destroy), so the teardown or completion tail
  runs to its end against the incarnation it started in, and the frame is gone
  once the event returns.

  With no destroy, the destroy-time `:exit` writes its `:data` before its
  effects run, in order, and the snapshot is then removed — under an event
  owner and eventless, as the frame-destroy cascade runs it."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.lifecycle-fx.destroy :as rf.machines.lifecycle-fx.destroy]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private actor :mdb/actor)
(def ^:private snapshot-path [:rf.runtime/machines :snapshots actor])

(defn- reg-exiting-actor!
  "Register `actor`, whose `:exit` writes `:exit-marker` and fires two
  `:mdb/effect`s. Each effect records its value and the `:exit-marker` it saw;
  `on-effect` runs after the record."
  [frame-id on-effect]
  (let [effects (atom [])]
    (rf/reg-fx :mdb/effect
      (fn [_ctx value]
        (swap! effects conj [value (get-in (rf.machines.test-support/snapshot frame-id actor)
                                           [:data :exit-marker])])
        (on-effect value)))
    (rf/reg-machine actor
      {:initial :running
       :states  {:running {:exit (fn [_]
                                   {:data {:exit-marker :exited}
                                    :fx   [[:mdb/effect 1] [:mdb/effect 2]]})}}})
    (rf/make-frame {:id frame-id})
    (rf.frame/swap-runtime-db! frame-id assoc-in snapshot-path {:state :running :data {}})
    effects))

(defn- destroyed-ops
  "Every `:rf.machine/destroyed` as `[actor-id reason]`."
  []
  (mapv (comp (juxt :actor-id :reason) :tags)
        (rf.machines.test-support/events-of :rf.machine/destroyed)))

(deftest exit-effect-destroying-the-frame-lets-the-actor-teardown-finish
  (let [frame-id :mdb/exit-frame
        effects  (reg-exiting-actor! frame-id
                                     #(when (= 1 %) (rf/destroy-frame! frame-id)))]
    (rf/reg-event :mdb/destroy-actor (fn [_ _] {:fx [[:rf.machine/destroy actor]]}))
    (rf/dispatch-sync [:mdb/destroy-actor] {:frame frame-id})
    (is (= {:effects   [[1 :exited] [2 :exited]]
            :destroyed [[actor :explicit]]
            :frame     nil}
           {:effects   @effects
            :destroyed (destroyed-ops)
            :frame     (rf.frame/frame frame-id)})
        "both exit effects ran, the actor's own destroyed trace fired once, and the frame is gone after the event")))

(deftest final-entry-destroying-the-frame-lets-the-completion-tail-finish
  (let [frame-id :mdb/final-frame]
    (rf/reg-machine :mdb/finisher
      {:initial :running
       :states  {:running {:on {:finish :done}}
                 :done    {:final? true
                           :entry  (fn [_] (rf/destroy-frame! frame-id) {})}}})
    (rf/reg-machine :mdb/host
      {:initial :hosting
       :states  {:hosting {:spawn {:machine-id :mdb/finisher}}}})
    (rf/make-frame {:id frame-id})
    (rf/dispatch-sync [:mdb/host [:rf.machine/start]] {:frame frame-id})
    (rf/dispatch-sync [:mdb/finisher#1 [:finish]] {:frame frame-id})
    (is (= {:done      [:mdb/finisher#1]
            :destroyed [[:mdb/finisher#1 :rf.machine/finished]]
            :frame     nil}
           {:done      (mapv (comp :actor-id :tags)
                             (rf.machines.test-support/events-of :rf.machine/done))
            :destroyed (filterv #(= :mdb/finisher#1 (first %)) (destroyed-ops))
            :frame     (rf.frame/frame frame-id)})
        "the done trace and the finished teardown both fired, and the frame is gone after the event")))

(deftest owner-intact-exit-writes-then-fires-then-tears-down
  (testing "with no destroy — under the frame's event owner, or eventless as the frame-destroy cascade runs it — the exit write lands before both effects run, in order, and the snapshot is removed"
    (doseq [[frame-id mode] [[:mdb/live-frame :live]
                             [:mdb/eventless-frame :eventless]]]
      (let [effects (reg-exiting-actor! frame-id (constantly nil))]
        (if (= :eventless mode)
          (rf.machines.lifecycle-fx.destroy/destroy-single-actor! frame-id actor)
          (rf.frame/call-with-event-owner-token
            frame-id (rf.frame/frame-incarnation-token frame-id)
            #(rf.machines.lifecycle-fx.destroy/destroy-machine-fx {:frame frame-id} actor)))
        (is (= {:effects [[1 :exited] [2 :exited]] :snapshot nil}
               {:effects  @effects
                :snapshot (rf.machines.test-support/snapshot frame-id actor)})
            (str mode))))))
