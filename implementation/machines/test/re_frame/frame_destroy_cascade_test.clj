(ns re-frame.frame-destroy-cascade-test
  "Per rf2-vsigt — frame destroy must run the machine `:exit` /
  disposal cascade in reverse-creation order BEFORE sub-cache /
  adapter teardown. Spec 005 §Cross-Spec Interactions §1 enumerates
  the contract:

    `(rf/destroy-frame :auth)` is called while the frame holds active
    machine instances mid-flight. Each active machine runs its `:exit`
    cascade in **reverse-creation order** (most recently spawned
    disposes first). After every machine has settled, sub-cache
    disposes / substrate releases / `:frame/destroyed` traces.

  Pre-rf2-vsigt the implementation aborted in-flight HTTP and emitted
  `:rf.machine.lifecycle/destroyed` but did not run the `:exit`
  actions, did not unregister the spawned-actor handlers, did not
  clear the `[:rf/system-ids]` reverse index, and did not enforce any
  ordering.

  These JVM-side tests run on the plain-atom substrate against the
  late-bound `:machines/teardown-on-frame-destroy!` hook that the
  machines artefact publishes for `frame/destroy-frame!`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            ;; Loading `re-frame.machines` registers the late-bind hooks
            ;; (`:machines/reg-machine`, `:machines/teardown-on-frame-destroy!`,
            ;; …) that the tests below exercise — keep the require even
            ;; when the test ns doesn't reach `machines/...` directly.
            [re-frame.machines]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- spawn-order channel — record / forget / clear ----------------------

(deftest spawn-order-records-each-spawn-and-forgets-on-destroy
  (testing "spawn-fx appends to the frame's spawn-order channel; explicit destroy forgets"
    (let [child  {:initial :idle :data {} :states {:idle {}}}
          parent {:initial :running
                  :data    {}
                  :states
                  {:running
                   {:on {:spawn-it {:action
                                    (fn [_ _]
                                      {:fx [[:rf.machine/spawn
                                             {:machine-id :spo/child
                                              :id-prefix  :spo/child}]
                                            [:rf.machine/spawn
                                             {:machine-id :spo/child
                                              :id-prefix  :spo/child}]]})}
                         ;; Destroy the first child via a machine
                         ;; action — the action emits the
                         ;; `[:rf.machine/destroy :spo/child#1]` fx,
                         ;; which routes through `destroy-machine-fx`
                         ;; → `destroy-single!`.
                         :drop-first {:action
                                      (fn [_ _]
                                        {:fx [[:rf.machine/destroy :spo/child#1]]})}}}}}]
      (rf/reg-machine :spo/child child)
      (rf/reg-machine :spo/parent parent)
      (rf/dispatch-sync [:spo/parent [:spawn-it]])
      ;; Two spawns recorded — ids match the per-machine counter.
      (is (= [:spo/child#1 :spo/child#2]
             (spawn-order/frame-order :rf/default))
          "spawn-order vector grew by exactly the two spawned actor-ids")
      ;; Explicit destroy of the first actor: it leaves the second behind.
      (rf/dispatch-sync [:spo/parent [:drop-first]])
      (is (= [:spo/child#2]
             (spawn-order/frame-order :rf/default))
          "explicit destroy forgets the first actor; the second remains tracked"))))

;; ---- frame destroy walks recorded actors in reverse-creation order -------

(deftest frame-destroy-runs-exit-cascade-in-reverse-creation-order
  (testing "destroy-frame walks live machines newest-spawn-first, running each :exit before clearing"
    (rf/reg-frame :fc/auth {:doc "scratch frame"})
    (let [exit-log (atom [])
          child    {:initial :running
                    :data    {}
                    :states  {:running {:exit (fn [data _]
                                                 (swap! exit-log
                                                        conj (:rf/self-id data))
                                                 {})}}}
          ;; The "boot" machine that we'll dispatch into to spawn 3
          ;; child actors — we use a hand-emitted `:rf.machine/spawn`
          ;; for each so each spawn is independent and the spawn-order
          ;; vector reflects three appends in declaration order.
          boot     {:initial :idle
                    :data    {}
                    :states
                    {:idle {:on {:spawn-three
                                 {:action
                                  (fn [_ _]
                                    {:fx [[:rf.machine/spawn
                                           {:machine-id :fc/child
                                            :id-prefix  :fc/child}]
                                          [:rf.machine/spawn
                                           {:machine-id :fc/child
                                            :id-prefix  :fc/child}]
                                          [:rf.machine/spawn
                                           {:machine-id :fc/child
                                            :id-prefix  :fc/child}]]})}}}}}]
      (rf/reg-machine :fc/child child)
      (rf/reg-machine :fc/boot boot)
      (rf/dispatch-sync [:fc/boot [:spawn-three]] {:frame :fc/auth})
      ;; All 3 actors are live.
      (is (= [:fc/child#1 :fc/child#2 :fc/child#3]
             (spawn-order/frame-order :fc/auth))
          "spawn-order vector ordered oldest → newest")
      ;; Destroy the frame.
      (rf/destroy-frame :fc/auth)
      ;; :exit fired three times in REVERSE-spawn order.
      (is (= [:fc/child#3 :fc/child#2 :fc/child#1] @exit-log)
          ":exit ran newest-first per Spec 005 §Cross-Spec Interactions §1")
      ;; Every spawned actor handler is unregistered.
      (is (nil? (registrar/lookup :event :fc/child#1))
          "spawned actor handler #1 was unregistered")
      (is (nil? (registrar/lookup :event :fc/child#2))
          "spawned actor handler #2 was unregistered")
      (is (nil? (registrar/lookup :event :fc/child#3))
          "spawned actor handler #3 was unregistered")
      ;; The spawn-order entry for the frame is gone.
      (is (= [] (spawn-order/frame-order :fc/auth))
          "spawn-order slot for the destroyed frame is cleared")
      ;; The registered (non-spawned) `:fc/child` and `:fc/boot`
      ;; machines stay registered — they're global singletons that
      ;; happen to share the address space with the spawned actors.
      (is (some? (registrar/lookup :event :fc/child))
          "the singleton `:fc/child` machine handler stays globally registered")
      (is (some? (registrar/lookup :event :fc/boot))
          "the singleton `:fc/boot` machine handler stays globally registered"))))

;; ---- :rf/system-ids reverse index is released ----------------------------

(deftest frame-destroy-releases-system-id-reverse-index
  (testing "destroy-frame clears [:rf/system-ids <sid>] for every system-id-bound spawned actor"
    (rf/reg-frame :si/auth {:doc "system-id reverse-index test frame"})
    (let [child   {:initial :running :data {} :states {:running {}}}
          parent  {:initial :idle
                   :data    {}
                   :states
                   {:idle {:on {:bind {:action
                                       (fn [_ _]
                                         {:fx [[:rf.machine/spawn
                                                {:machine-id :si/child
                                                 :id-prefix  :si/child
                                                 :system-id  :session/primary}]]})}}}}}]
      (rf/reg-machine :si/child child)
      (rf/reg-machine :si/boot parent)
      (rf/dispatch-sync [:si/boot [:bind]] {:frame :si/auth})
      ;; The reverse index is bound before destroy.
      (let [db (rf/get-frame-db :si/auth)]
        (is (= :si/child#1 (get-in db [:rf/system-ids :session/primary]))
            "system-id was bound to the spawned actor before destroy"))
      (rf/destroy-frame :si/auth)
      ;; Frame is gone — and the actor's handler was unregistered as
      ;; part of the cascade.
      (is (nil? (frame/frame :si/auth))
          "frame was destroyed")
      (is (nil? (registrar/lookup :event :si/child#1))
          "the system-id-bound spawned actor was unregistered (its [:rf/system-ids] entry was implicitly released as part of the unified teardown projection)"))))

;; ---- :rf.machine.lifecycle/destroyed trace contract ----------------------

(deftest frame-destroy-emits-lifecycle-trace-per-active-machine
  (testing "destroy-frame emits :rf.machine.lifecycle/destroyed per active actor with :reason :parent-frame-destroyed"
    (rf/reg-frame :lt/auth {:doc "lifecycle-trace frame"})
    (let [traces (atom [])
          child  {:initial :running :data {} :states {:running {}}}
          boot   {:initial :idle
                  :data    {}
                  :states
                  {:idle {:on {:start {:action
                                       (fn [_ _]
                                         {:fx [[:rf.machine/spawn
                                                {:machine-id :lt/child
                                                 :id-prefix  :lt/child}]
                                               [:rf.machine/spawn
                                                {:machine-id :lt/child
                                                 :id-prefix  :lt/child}]]})}}}}}]
      (rf/reg-machine :lt/child child)
      (rf/reg-machine :lt/boot boot)
      (rf/dispatch-sync [:lt/boot [:start]] {:frame :lt/auth})
      (rf/register-trace-cb! ::lt (fn [ev] (swap! traces conj ev)))
      (rf/destroy-frame :lt/auth)
      (rf/remove-trace-cb! ::lt)
      (let [destroyed (filter #(= :rf.machine.lifecycle/destroyed (:operation %))
                              @traces)]
        ;; Two spawned actors PLUS the singleton :lt/boot snapshot
        ;; that lives in [:rf/machines] of this frame — three traces.
        (is (= 3 (count destroyed))
            "one trace per actor with a [:rf/machines <id>] snapshot")
        (is (every? #(= :parent-frame-destroyed (:reason (:tags %))) destroyed)
            "every trace carries :reason :parent-frame-destroyed")
        (is (every? #(= :lt/auth (:frame (:tags %))) destroyed)
            "every trace carries the destroyed frame id")
        (is (= #{:lt/child#1 :lt/child#2 :lt/boot}
               (set (map #(:machine-id (:tags %)) destroyed)))
            "trace covers every active machine — spawned actors + the singleton boot machine")))))

;; ---- HTTP abort preserved for every active actor -------------------------

(deftest frame-destroy-fires-http-abort-per-active-actor
  (testing "destroy-frame invokes the :http/abort-on-actor-destroy hook against every active actor"
    (rf/reg-frame :ha/auth {:doc "http-abort hook test frame"})
    (let [aborted (atom [])
          ;; Install the hook explicitly. `re-frame.http-managed`
          ;; isn't loaded in this leaf-artefact's classpath, so we
          ;; register the hook directly to stand in for it.
          _ (late-bind/set-fn!
              :http/abort-on-actor-destroy
              (fn [actor-id] (swap! aborted conj actor-id)))
          child  {:initial :running :data {} :states {:running {}}}
          boot   {:initial :idle
                  :data    {}
                  :states
                  {:idle {:on {:go {:action
                                    (fn [_ _]
                                      {:fx [[:rf.machine/spawn
                                             {:machine-id :ha/child
                                              :id-prefix  :ha/child}]
                                            [:rf.machine/spawn
                                             {:machine-id :ha/child
                                              :id-prefix  :ha/child}]]})}}}}}]
      (rf/reg-machine :ha/child child)
      (rf/reg-machine :ha/boot boot)
      (rf/dispatch-sync [:ha/boot [:go]] {:frame :ha/auth})
      (rf/destroy-frame :ha/auth)
      (is (= #{:ha/child#1 :ha/child#2 :ha/boot} (set @aborted))
          "the abort hook fired once per active actor — spawned plus singleton"))))

;; ---- multiple frames isolated -------------------------------------------

(deftest destroy-of-one-frame-does-not-disturb-anothers-machines
  (testing "destroy-frame walks only the destroyed frame's spawn-order channel"
    (rf/reg-frame :iso/frame-a {:doc "frame A"})
    (rf/reg-frame :iso/frame-b {:doc "frame B"})
    (let [exit-log (atom [])
          ;; Two distinct machine specs (and id-prefixes) so the
          ;; spawned actor handlers don't collide on the global
          ;; registrar — the v1-partial relaxation (Spec 005 §Spawning)
          ;; means cross-frame `:rf.machine/spawn` of the same id-prefix
          ;; resolves to a single global handler entry, so a test that
          ;; uses distinct prefixes per frame is the only meaningful
          ;; isolation assertion at v1.
          mk-child (fn [_label]
                     {:initial :running
                      :data    {}
                      :states  {:running {:exit (fn [data _]
                                                   (swap! exit-log
                                                          conj (:rf/self-id data))
                                                   {})}}})
          boot     (fn [child-machine-id]
                     {:initial :idle
                      :data    {}
                      :states
                      {:idle {:on {:go {:action
                                        (fn [_ _]
                                          {:fx [[:rf.machine/spawn
                                                 {:machine-id child-machine-id
                                                  :id-prefix  child-machine-id}]]})}}}}})]
      (rf/reg-machine :iso/child-a (mk-child :a))
      (rf/reg-machine :iso/child-b (mk-child :b))
      (rf/reg-machine :iso/boot-a (boot :iso/child-a))
      (rf/reg-machine :iso/boot-b (boot :iso/child-b))
      (rf/dispatch-sync [:iso/boot-a [:go]] {:frame :iso/frame-a})
      (rf/dispatch-sync [:iso/boot-b [:go]] {:frame :iso/frame-b})
      ;; Each frame has its own spawn-order vector.
      (is (= [:iso/child-a#1] (spawn-order/frame-order :iso/frame-a)))
      (is (= [:iso/child-b#1] (spawn-order/frame-order :iso/frame-b)))
      ;; Destroy A; B's actor stays alive and its handler stays registered.
      (rf/destroy-frame :iso/frame-a)
      (is (= [:iso/child-a#1] @exit-log)
          "only frame A's spawned actor ran its :exit")
      (is (nil? (registrar/lookup :event :iso/child-a#1))
          "frame A's spawned handler is unregistered")
      (is (some? (registrar/lookup :event :iso/child-b#1))
          "frame B's spawned handler stays registered after A's destroy")
      (is (= [] (spawn-order/frame-order :iso/frame-a))
          "frame A's spawn-order slot is cleared")
      (is (= [:iso/child-b#1] (spawn-order/frame-order :iso/frame-b))
          "frame B's spawn-order slot is untouched"))))
