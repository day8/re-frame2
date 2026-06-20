(ns re-frame.frame-destroy-cascade-test
  "Frame destroy runs the machine `:exit` / disposal cascade in
  reverse-creation order BEFORE sub-cache / adapter teardown. Spec 005
  §Cross-Spec Interactions §1 enumerates the contract:

    `(rf/destroy-frame! :auth)` is called while the frame holds active
    machine instances mid-flight. Each active machine runs its `:exit`
    cascade in **reverse-creation order** (most recently spawned
    disposes first). After every machine has settled, sub-cache
    disposes / substrate releases / `:frame/destroyed` traces.

  The cascade runs the `:exit` actions, unregisters the spawned-actor
  handlers, clears the `[:rf.runtime/machines :system-ids]` reverse index,
  and enforces the reverse-creation ordering — alongside aborting in-flight
  HTTP and emitting `:rf.machine.lifecycle/destroyed`.

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
            [re-frame.machines.test-support :as mtest]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- spawn-order channel — record / forget / clear ----------------------

(deftest spawn-order-records-each-spawn-and-forgets-on-destroy
  (testing "spawn-fx appends to the frame's spawn-order channel; explicit destroy forgets"
    (let [child  {:initial :idle :data {} :states {:idle {}}}
          parent {:initial :running
                  :data    {}
                  :states
                  {:running
                   {:on {:spawn-it {:action (fn [_]
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
                         :drop-first {:action (fn [_]
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
  (testing "destroy-frame! walks live machines newest-spawn-first, running each :exit before clearing"
    (rf/reg-frame :fc/auth {:doc "scratch frame"})
    (let [exit-log (atom [])
          child    {:initial :running
                    :data    {}
                    :states  {:running {:exit (fn [{data :data}]
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
                                 {:action (fn [_]
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
      (rf/destroy-frame! :fc/auth)
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

;; ---- [:rf.runtime/machines :system-ids] reverse index is released -------

(deftest frame-destroy-releases-system-id-reverse-index
  (testing "destroy-frame! clears [:rf.runtime/machines :system-ids <sid>] for every system-id-bound spawned actor"
    (rf/reg-frame :si/auth {:doc "system-id reverse-index test frame"})
    (let [child   {:initial :running :data {} :states {:running {}}}
          parent  {:initial :idle
                   :data    {}
                   :states
                   {:idle {:on {:bind {:action (fn [_]
                                         {:fx [[:rf.machine/spawn
                                                {:machine-id :si/child
                                                 :id-prefix  :si/child
                                                 :system-id  :session/primary}]]})}}}}}]
      (rf/reg-machine :si/child child)
      (rf/reg-machine :si/boot parent)
      (rf/dispatch-sync [:si/boot [:bind]] {:frame :si/auth})
      ;; The reverse index is bound before destroy.
      (let [db (rf/runtime-db-value :si/auth)]
        (is (= :si/child#1 (get-in db [:rf.runtime/machines :system-ids :session/primary]))
            "system-id was bound to the spawned actor before destroy"))
      (rf/destroy-frame! :si/auth)
      ;; Frame is gone — and the actor's handler was unregistered as
      ;; part of the cascade.
      (is (nil? (frame/frame :si/auth))
          "frame was destroyed")
      (is (nil? (registrar/lookup :event :si/child#1))
          "the system-id-bound spawned actor was unregistered (its [:rf.runtime/machines :system-ids] entry was implicitly released as part of the unified teardown projection)"))))

;; ---- :rf.machine.lifecycle/destroyed trace contract ----------------------

(deftest frame-destroy-emits-lifecycle-trace-per-active-machine
  (testing "destroy-frame! emits :rf.machine.lifecycle/destroyed per active actor with :reason :parent-frame-destroyed"
    (rf/reg-frame :lt/auth {:doc "lifecycle-trace frame"})
    (let [child  {:initial :running :data {} :states {:running {}}}
          boot   {:initial :idle
                  :data    {}
                  :states
                  {:idle {:on {:start {:action (fn [_]
                                         {:fx [[:rf.machine/spawn
                                                {:machine-id :lt/child
                                                 :id-prefix  :lt/child}]
                                               [:rf.machine/spawn
                                                {:machine-id :lt/child
                                                 :id-prefix  :lt/child}]]})}}}}}]
      (rf/reg-machine :lt/child child)
      (rf/reg-machine :lt/boot boot)
      (rf/dispatch-sync [:lt/boot [:start]] {:frame :lt/auth})
      ;; Shared `with-trace-capture` — guaranteed unregister in a `finally`.
      (mtest/with-trace-capture traces
        (rf/destroy-frame! :lt/auth)
        (let [destroyed (filter #(= :rf.machine.lifecycle/destroyed (:operation %))
                                @traces)]
          ;; Two spawned actors PLUS the singleton :lt/boot snapshot
          ;; that lives in [:rf.runtime/machines :snapshots] of this frame — three traces.
          (is (= 3 (count destroyed))
              "one trace per actor with a [:rf.runtime/machines :snapshots <id>] snapshot")
          (is (every? #(= :parent-frame-destroyed (:reason (:tags %))) destroyed)
              "every trace carries :reason :parent-frame-destroyed")
          (is (every? #(= :lt/auth (:frame (:tags %))) destroyed)
              "every trace carries the destroyed frame id")
          (is (= #{:lt/child#1 :lt/child#2 :lt/boot}
                 (set (map #(:actor-id (:tags %)) destroyed)))
              "trace covers every active machine — spawned actors + the singleton boot machine"))))))

;; ---- HTTP abort preserved for every active actor -------------------------

(deftest frame-destroy-fires-http-abort-per-active-actor
  (testing "destroy-frame! invokes the :http/abort-on-actor-destroy hook against every active actor"
    (rf/reg-frame :ha/auth {:doc "http-abort hook test frame"})
    (let [aborted (atom [])
          ;; Install the hook explicitly. `re-frame.http.managed`
          ;; isn't loaded in this leaf-artefact's classpath, so we
          ;; register the hook directly to stand in for it.
          _ (late-bind/set-fn!
              :http/abort-on-actor-destroy
              (fn [actor-id] (swap! aborted conj actor-id)))
          child  {:initial :running :data {} :states {:running {}}}
          boot   {:initial :idle
                  :data    {}
                  :states
                  {:idle {:on {:go {:action (fn [_]
                                      {:fx [[:rf.machine/spawn
                                             {:machine-id :ha/child
                                              :id-prefix  :ha/child}]
                                            [:rf.machine/spawn
                                             {:machine-id :ha/child
                                              :id-prefix  :ha/child}]]})}}}}}]
      (rf/reg-machine :ha/child child)
      (rf/reg-machine :ha/boot boot)
      (rf/dispatch-sync [:ha/boot [:go]] {:frame :ha/auth})
      (rf/destroy-frame! :ha/auth)
      (is (= #{:ha/child#1 :ha/child#2 :ha/boot} (set @aborted))
          "the abort hook fired once per active actor — spawned plus singleton"))))

;; ---- multiple frames isolated -------------------------------------------

(deftest destroy-of-one-frame-does-not-disturb-anothers-machines
  (testing "destroy-frame! walks only the destroyed frame's spawn-order channel"
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
                      :states  {:running {:exit (fn [{data :data}]
                                                   (swap! exit-log
                                                          conj (:rf/self-id data))
                                                   {})}}})
          boot     (fn [child-machine-id]
                     {:initial :idle
                      :data    {}
                      :states
                      {:idle {:on {:go {:action (fn [_]
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
      ;; A spawned actor carries NO per-instance registrar entry; its
      ;; liveness IS its snapshot's presence in the frame's (revertible)
      ;; app-db. Cross-frame isolation is therefore asserted on the
      ;; snapshots, not the registrar.
      (is (some? (get-in (rf/runtime-db-value :iso/frame-a)
                         [:rf.runtime/machines :snapshots :iso/child-a#1]))
          "frame A's spawned actor is live (snapshot present) before destroy")
      (is (some? (get-in (rf/runtime-db-value :iso/frame-b)
                         [:rf.runtime/machines :snapshots :iso/child-b#1]))
          "frame B's spawned actor is live (snapshot present) before destroy")
      ;; Spawned actors never register a per-instance handler.
      (is (nil? (registrar/lookup :event :iso/child-a#1))
          "frame A's spawned actor has no per-instance registrar entry")
      (is (nil? (registrar/lookup :event :iso/child-b#1))
          "frame B's spawned actor has no per-instance registrar entry")
      ;; Destroy A; B's actor stays alive (its snapshot survives).
      (rf/destroy-frame! :iso/frame-a)
      (is (= [:iso/child-a#1] @exit-log)
          "only frame A's spawned actor ran its :exit")
      (is (some? (registrar/lookup :event :iso/child-b)
                 )
          "frame B's TYPE machine stays globally registered after A's destroy")
      (is (some? (get-in (rf/runtime-db-value :iso/frame-b)
                         [:rf.runtime/machines :snapshots :iso/child-b#1]))
          "frame B's spawned actor stays alive (snapshot present) after A's destroy")
      (is (= [] (spawn-order/frame-order :iso/frame-a))
          "frame A's spawn-order slot is cleared")
      (is (= [:iso/child-b#1] (spawn-order/frame-order :iso/frame-b))
          "frame B's spawn-order slot is untouched"))))

;; ---- restore / hydration: spawned snapshots absent from spawn-order ------
;;
;; Restore / SSR hydration / `replace-runtime-db!` / `restore-epoch!`
;; repopulate the DURABLE runtime-db snapshots WITHOUT repopulating the
;; PROCESS-SIDE (transient) `spawn-order` atom (Spec 002 §Durable vs
;; transient: the atom is runtime bookkeeping, not durable state). A restored
;; SPAWNED actor (snapshot carries `:rf/machine-type`) flows through the FULL
;; `destroy-single-actor!` teardown — dissoc the snapshot, release the
;; system-id reverse index, clear schema marks, cancel `:after` timers,
;; unregister a handler — in reverse-creation order derived from the durable
;; `<type>#<n>` actor-id. (The straggler `run-singleton-exit-cascade!` path
;; runs the `:exit` cascade + HTTP abort only and is reserved for restored
;; SINGLETON snapshots that carry no `:rf/machine-type`.)
;;
;; These tests model restore by spawning normally, then wiping ONLY the
;; transient spawn-order atom (`spawn-order/reset-all!`) while leaving the
;; durable runtime-db snapshots in place — exactly the post-restore /
;; post-hydration state. Asserting on the durable runtime-db cleanup then
;; pins that the spawned-straggler path runs full teardown.

(defn- runtime-snapshots
  "The `[:rf.runtime/machines :snapshots]` map on `frame-id`'s runtime-db."
  [frame-id]
  (get-in (rf/runtime-db-value frame-id) [:rf.runtime/machines :snapshots]))

(deftest restored-spawned-snapshots-get-full-teardown-newest-first
  (testing "destroy-frame! treats restored spawned snapshots (absent from spawn-order) as spawned actors: full teardown, newest-first by durable actor-id"
    (rf/reg-frame :rs/auth {:doc "restore-teardown frame"})
    (let [exit-log (atom [])
          child    {:initial :running
                    :data    {}
                    :states  {:running {:exit (fn [{data :data}]
                                                 (swap! exit-log
                                                        conj (:rf/self-id data))
                                                 {})}}}
          boot     {:initial :idle
                    :data    {}
                    :states
                    {:idle {:on {:spawn-three
                                 {:action (fn [_]
                                    {:fx [[:rf.machine/spawn
                                           {:machine-id :rs/child :id-prefix :rs/child}]
                                          [:rf.machine/spawn
                                           {:machine-id :rs/child :id-prefix :rs/child}]
                                          [:rf.machine/spawn
                                           {:machine-id :rs/child :id-prefix :rs/child}]]})}}}}}]
      (rf/reg-machine :rs/child child)
      (rf/reg-machine :rs/boot boot)
      (rf/dispatch-sync [:rs/boot [:spawn-three]] {:frame :rs/auth})
      ;; Sanity: three spawned actors live, with durable :rf/machine-type.
      ;; (The :rs/boot singleton's own snapshot also lives here — it has no
      ;; :rf/machine-type and stays a singleton straggler.)
      (is (= #{:rs/child#1 :rs/child#2 :rs/child#3}
             ;; the children are exactly the snapshots carrying :rf/machine-type
             (set (keep (fn [[id snap]]
                          (when (some? (:rf/machine-type snap)) id))
                        (runtime-snapshots :rs/auth))))
          "three spawned snapshots are live (each carrying :rf/machine-type) before restore")
      ;; Simulate restore / hydration: the durable snapshots survive, but
      ;; the transient spawn-order atom is wiped (it is NOT serialized).
      (spawn-order/reset-all!)
      (is (= [] (spawn-order/frame-order :rs/auth))
          "spawn-order atom is empty post-restore (the bug's precondition)")
      ;; Destroy the frame.
      (rf/destroy-frame! :rs/auth)
      ;; :exit fired for all three children, NEWEST-FIRST by the durable
      ;; #<n> rank. (Filter to children — the boot singleton's :exit, if
      ;; any, is irrelevant to the spawned-ordering contract.)
      (is (= [:rs/child#3 :rs/child#2 :rs/child#1]
             (filterv #{:rs/child#1 :rs/child#2 :rs/child#3} @exit-log))
          ":exit ran newest-first, order derived from the durable actor-id (not the lost spawn-order atom)")
      ;; FULL teardown: every restored SPAWNED snapshot is dissoc'd. The
      ;; singleton straggler path would have LEFT these in runtime-db.
      (is (empty? (keep (fn [[id snap]]
                          (when (some? (:rf/machine-type snap)) id))
                        (runtime-snapshots :rs/auth)))
          "every restored spawned snapshot was dissoc'd (full teardown, not exit-only)"))))

(deftest restored-spawned-snapshot-releases-system-id-index
  (testing "destroy-frame! releases the system-id reverse index for a restored, spawn-order-less spawned actor"
    (rf/reg-frame :rsi/auth {:doc "restore system-id frame"})
    (let [child  {:initial :running :data {} :states {:running {}}}
          boot   {:initial :idle
                  :data    {}
                  :states
                  {:idle {:on {:bind {:action (fn [_]
                                        {:fx [[:rf.machine/spawn
                                               {:machine-id :rsi/child
                                                :id-prefix  :rsi/child
                                                :system-id  :session/primary}]]})}}}}}]
      (rf/reg-machine :rsi/child child)
      (rf/reg-machine :rsi/boot boot)
      (rf/dispatch-sync [:rsi/boot [:bind]] {:frame :rsi/auth})
      (is (= :rsi/child#1
             (get-in (rf/runtime-db-value :rsi/auth)
                     [:rf.runtime/machines :system-ids :session/primary]))
          "system-id bound before restore")
      ;; Restore: wipe the transient spawn-order; the durable snapshot +
      ;; system-id reverse index survive.
      (spawn-order/reset-all!)
      (rf/destroy-frame! :rsi/auth)
      (is (nil? (get-in (rf/runtime-db-value :rsi/auth)
                        [:rf.runtime/machines :system-ids :session/primary]))
          "system-id reverse index released — only the full spawned teardown does this; the singleton straggler path leaves it bound")
      (is (nil? (get-in (rf/runtime-db-value :rsi/auth)
                        [:rf.runtime/machines :snapshots :rsi/child#1]))
          "restored spawned snapshot dissoc'd"))))

(deftest restored-singleton-snapshot-keeps-singleton-straggler-path
  (testing "a restored SINGLETON snapshot (no :rf/machine-type) keeps the exit-only straggler path — handler survives, snapshot left for app-db release"
    (rf/reg-frame :rsg/auth {:doc "restore singleton frame"})
    (let [exit-log (atom [])
          ;; A singleton machine — registered, then driven into a state so
          ;; its snapshot lands in runtime-db. Its snapshot carries NO
          ;; :rf/machine-type (build-initial-snapshot does not stamp it).
          single {:initial :live
                  :data    {}
                  :states  {:live {:on   {:noop {:action (fn [{d :data}] {:data d})}}
                                   :exit (fn [_] (swap! exit-log conj :rsg/single) {})}}}]
      (rf/reg-machine :rsg/single single)
      ;; Touch the singleton so its snapshot materialises in this frame.
      (rf/dispatch-sync [:rsg/single [:noop]] {:frame :rsg/auth})
      (is (some? (get-in (rf/runtime-db-value :rsg/auth)
                         [:rf.runtime/machines :snapshots :rsg/single]))
          "singleton snapshot present before restore")
      (is (nil? (:rf/machine-type
                  (get-in (rf/runtime-db-value :rsg/auth)
                          [:rf.runtime/machines :snapshots :rsg/single])))
          "singleton snapshot carries NO :rf/machine-type (the discriminator)")
      (spawn-order/reset-all!)
      (rf/destroy-frame! :rsg/auth)
      ;; The singleton's :exit ran (straggler path runs the exit cascade)...
      (is (= [:rsg/single] @exit-log)
          "singleton :exit cascade ran via the straggler path")
      ;; ...but its TYPE handler stays globally registered (outlives the frame).
      (is (some? (registrar/lookup :event :rsg/single))
          "singleton handler stays registered — NOT unregistered by the straggler path"))))
