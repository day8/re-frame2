(ns re-frame.frame-destroy-cascade-test
  "Frame destroy tears every active machine down in reverse-creation order,
  read off the durable `[:rf.runtime/machines :spawn-order]` vector (Spec 005
  §Cross-Spec Interactions §1)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private plain-child {:initial :running :data {} :states {:running {}}})

(defn- spawn-fx [t] [:rf.machine/spawn {:machine-id t :id-prefix t}])

(defn- boot-machine
  "A singleton whose `event` emits `fx`."
  [event fx]
  {:initial :idle :data {} :states {:idle {:on {event {:action (fn [_] {:fx fx})}}}}})

(defn- exiting-child
  "A child machine whose `:exit` appends its own actor-id to `exit-log`."
  [exit-log]
  {:initial :running
   :data    {}
   :states  {:running {:exit (fn [{data :data}]
                               (swap! exit-log conj (:rf/self-id data))
                               {})}}})

(defn- durable-spawn-order [frame-id]
  (get-in (rf.machines.test-support/runtime-db frame-id) [:rf.runtime/machines :spawn-order]))

(deftest spawn-order-records-each-spawn-and-forgets-on-destroy
  (rf/reg-machine :spo/child plain-child)
  (rf/reg-machine :spo/parent
                  {:initial :running
                   :data    {}
                   :states  {:running {:on {:spawn-it   {:action (fn [_] {:fx [(spawn-fx :spo/child)
                                                                               (spawn-fx :spo/child)]})}
                                            :drop-first {:action (fn [_] {:fx [[:rf.machine/destroy :spo/child#1]]})}}}}})
  (rf/dispatch-sync [:spo/parent [:spawn-it]])
  (let [after-spawn (rf.machines.spawn-order/frame-order :rf/default)]
    (rf/dispatch-sync [:spo/parent [:drop-first]])
    (is (= [[:spo/child#1 :spo/child#2] [:spo/child#2]]
           [after-spawn (rf.machines.spawn-order/frame-order :rf/default)]))))

(deftest frame-destroy-traces-and-aborts-every-active-actor
  (rf/make-frame {:id :lt/auth})
  (let [aborted (atom [])]
    ;; The cascade calls the hook's frame-bearing arity.
    (rf.late-bind/set-fn! :http/abort-on-actor-destroy
                          (fn [frame-id actor-id] (swap! aborted conj [frame-id actor-id])))
    (rf/reg-machine :lt/child plain-child)
    (rf/reg-machine :lt/boot (boot-machine :start [(spawn-fx :lt/child) (spawn-fx :lt/child)]))
    (rf/dispatch-sync [:lt/boot [:start]] {:frame :lt/auth})
    (rf.machines.test-support/with-trace-capture traces
      (rf/destroy-frame! :lt/auth)
      ;; the two spawned actors and the singleton :lt/boot
      (is (= {[:lt/auth :lt/child#1 :parent-frame-destroyed] 1
              [:lt/auth :lt/child#2 :parent-frame-destroyed] 1
              [:lt/auth :lt/boot :parent-frame-destroyed]    1}
             (->> @traces
                  (filter #(= :rf.machine.lifecycle/destroyed (:operation %)))
                  (map (comp (juxt :frame :actor-id :reason) :tags))
                  frequencies)))
      (is (= #{[:lt/auth :lt/child#1] [:lt/auth :lt/child#2] [:lt/auth :lt/boot]}
             (set @aborted))))))

(deftest destroy-of-one-frame-does-not-disturb-anothers-machines
  (rf/make-frame {:id :iso/frame-a})
  (rf/make-frame {:id :iso/frame-b})
  ;; A registration is shared across frames, so each frame spawns its own
  ;; child type to keep the two frames' actors distinguishable.
  (let [exit-log (atom [])]
    (rf/reg-machine :iso/child-a (exiting-child exit-log))
    (rf/reg-machine :iso/child-b (exiting-child exit-log))
    (rf/reg-machine :iso/boot-a (boot-machine :go [(spawn-fx :iso/child-a)]))
    (rf/reg-machine :iso/boot-b (boot-machine :go [(spawn-fx :iso/child-b)]))
    (rf/dispatch-sync [:iso/boot-a [:go]] {:frame :iso/frame-a})
    (rf/dispatch-sync [:iso/boot-b [:go]] {:frame :iso/frame-b})
    (rf/destroy-frame! :iso/frame-a)
    (is (= [[:iso/child-a#1] true]
           [@exit-log (some? (rf.machines.test-support/snapshot :iso/frame-b :iso/child-b#1))]))))

;; Two machine types: `:probe/a#1`, `:probe/a#2`, `:probe/b#1` are created in
;; that order, which no sort on the per-prefix `#<n>` suffix can recover —
;; descending suffix puts `:probe/a#2` ahead of the newest actor `:probe/b#1`.
(defn- reg-probe-machines! [exit-log]
  (rf/reg-machine :probe/a (exiting-child exit-log))
  (rf/reg-machine :probe/b (exiting-child exit-log))
  (rf/reg-machine :probe/boot
                  (boot-machine :spawn-mixed [(spawn-fx :probe/a) (spawn-fx :probe/a) (spawn-fx :probe/b)])))

(deftest explicit-destroy-prunes-durable-spawn-order
  (rf/make-frame {:id :probedd/auth})
  (let [exit-log (atom [])]
    (reg-probe-machines! exit-log)
    (rf/reg-machine :probedd/boot (boot-machine :drop-middle [[:rf.machine/destroy :probe/a#2]]))
    (rf/dispatch-sync [:probe/boot [:spawn-mixed]] {:frame :probedd/auth})
    (rf/dispatch-sync [:probedd/boot [:drop-middle]] {:frame :probedd/auth})
    (is (= [:probe/a#1 :probe/b#1] (durable-spawn-order :probedd/auth)))
    (rf/destroy-frame! :probedd/auth)
    (is (= [:probe/a#2 :probe/b#1 :probe/a#1] @exit-log)
        "a#2 exits once, at its destroy; frame destroy exits only the survivors, newest first")))

(deftest mixed-prefix-order-survives-a-real-frame-state-reinstall
  (rf/make-frame {:id :probere/auth})
  (let [exit-log (atom [])]
    (reg-probe-machines! exit-log)
    (rf/reg-machine :probere/boot
                    (boot-machine :drop-all [[:rf.machine/destroy :probe/a#1]
                                             [:rf.machine/destroy :probe/a#2]
                                             [:rf.machine/destroy :probe/b#1]]))
    (rf/dispatch-sync [:probe/boot [:spawn-mixed]] {:frame :probere/auth})
    (let [captured (rf/frame-state-value :probere/auth)]
      ;; The destroys also empty the transient spawn-order cache, so after the
      ;; reinstall only the durable vector can order the walk.
      (rf/dispatch-sync [:probere/boot [:drop-all]] {:frame :probere/auth})
      (is (nil? (durable-spawn-order :probere/auth)) "the durable slot is pruned once it empties")
      (reset! exit-log [])
      (rf.frame/replace-frame-state! :probere/auth captured)
      (rf/destroy-frame! :probere/auth)
      (is (= [:probe/b#1 :probe/a#2 :probe/a#1] @exit-log)))))

;; An in-process install (`replace-frame-state!`, the fn `restore-epoch!`
;; calls) that rewinds past a spawn leaves the discarded actor in the
;; transient spawn-order cache; both of the cache's readers must defer to
;; durable state.
(deftest restore-past-a-spawn-does-not-reap-the-discarded-actor
  (rf/make-frame {:id :stage/auth})
  (rf/reg-machine :stage/a plain-child)
  (rf/reg-machine :stage/b plain-child)
  (rf/reg-machine :stage/boot
                  {:initial :idle
                   :data    {}
                   :states  {:idle {:on {:spawn-a {:action (fn [_] {:fx [(spawn-fx :stage/a)]})}
                                         :spawn-b {:action (fn [_] {:fx [(spawn-fx :stage/b)]})}
                                         :drop-b  {:action (fn [_] {:fx [[:rf.machine/destroy :stage/b#1]]})}}}}})
  (rf/dispatch-sync [:stage/boot [:spawn-a]] {:frame :stage/auth})
  (let [captured (rf/frame-state-value :stage/auth)]
    (rf/dispatch-sync [:stage/boot [:spawn-b]] {:frame :stage/auth})
    (rf.frame/replace-frame-state! :stage/auth captured)
    (rf.machines.test-support/with-trace-capture traces
      (rf/dispatch-sync [:stage/boot [:drop-b]] {:frame :stage/auth})
      (rf/destroy-frame! :stage/auth)
      ;; Destroying b#1 is the silent no-op of Spec 005 §Destroy is
      ;; silent-idempotent, and frame destroy reaps a#1 alone.
      (is (= {:rf.machine/destroyed           []
              :rf.machine.lifecycle/destroyed [:stage/a#1]}
             (into {}
                   (for [op [:rf.machine/destroyed :rf.machine.lifecycle/destroyed]]
                     [op (->> @traces
                              (filter #(= op (:operation %)))
                              (map #(:actor-id (:tags %)))
                              (filterv #{:stage/a#1 :stage/b#1}))])))))))
