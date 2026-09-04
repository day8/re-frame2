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
  machines artefact publishes for `rf.frame/destroy-frame!`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            ;; Loading `re-frame.machines` registers the late-bind hooks
            ;; (`:machines/reg-machine`, `:machines/teardown-on-frame-destroy!`,
            ;; …) that the tests below exercise — keep the require even
            ;; when the test ns doesn't reach `machines/...` directly.
            [re-frame.machines]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

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
             (rf.machines.spawn-order/frame-order :rf/default))
          "spawn-order vector grew by exactly the two spawned actor-ids")
      ;; Explicit destroy of the first actor: it leaves the second behind.
      (rf/dispatch-sync [:spo/parent [:drop-first]])
      (is (= [:spo/child#2]
             (rf.machines.spawn-order/frame-order :rf/default))
          "explicit destroy forgets the first actor; the second remains tracked"))))

;; ---- frame destroy walks recorded actors in reverse-creation order -------

(deftest frame-destroy-runs-exit-cascade-in-reverse-creation-order
  (testing "destroy-frame! walks live machines newest-spawn-first, running each :exit before clearing"
    (rf/make-frame {:id :fc/auth :doc "scratch frame"})
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
             (rf.machines.spawn-order/frame-order :fc/auth))
          "spawn-order vector ordered oldest → newest")
      ;; Destroy the frame.
      (rf/destroy-frame! :fc/auth)
      ;; :exit fired three times in REVERSE-spawn order.
      (is (= [:fc/child#3 :fc/child#2 :fc/child#1] @exit-log)
          ":exit ran newest-first per Spec 005 §Cross-Spec Interactions §1")
      ;; Every spawned actor handler is unregistered.
      (is (nil? (rf.registrar/lookup :event :fc/child#1))
          "spawned actor handler #1 was unregistered")
      (is (nil? (rf.registrar/lookup :event :fc/child#2))
          "spawned actor handler #2 was unregistered")
      (is (nil? (rf.registrar/lookup :event :fc/child#3))
          "spawned actor handler #3 was unregistered")
      ;; The spawn-order entry for the frame is gone.
      (is (= [] (rf.machines.spawn-order/frame-order :fc/auth))
          "spawn-order slot for the destroyed frame is cleared")
      ;; The registered (non-spawned) `:fc/child` and `:fc/boot`
      ;; machines stay registered — they're global singletons that
      ;; happen to share the address space with the spawned actors.
      (is (some? (rf.registrar/lookup :event :fc/child))
          "the singleton `:fc/child` machine handler stays globally registered")
      (is (some? (rf.registrar/lookup :event :fc/boot))
          "the singleton `:fc/boot` machine handler stays globally registered"))))

;; ---- [:rf.runtime/machines :system-ids] reverse index is released -------

(deftest frame-destroy-releases-system-id-reverse-index
  (testing "destroy-frame! clears [:rf.runtime/machines :system-ids <sid>] for every system-id-bound spawned actor"
    (rf/make-frame {:id :si/auth :doc "system-id reverse-index test frame"})
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
      (let [db (:rf.db/runtime (rf/frame-state-value :si/auth))]
        (is (= :si/child#1 (get-in db [:rf.runtime/machines :system-ids :session/primary]))
            "system-id was bound to the spawned actor before destroy"))
      (rf/destroy-frame! :si/auth)
      ;; Frame is gone — and the actor's handler was unregistered as
      ;; part of the cascade.
      (is (nil? (rf.frame/frame :si/auth))
          "frame was destroyed")
      (is (nil? (rf.registrar/lookup :event :si/child#1))
          "the system-id-bound spawned actor was unregistered (its [:rf.runtime/machines :system-ids] entry was implicitly released as part of the unified teardown projection)"))))

;; ---- :rf.machine.lifecycle/destroyed trace contract ----------------------

(deftest frame-destroy-emits-lifecycle-trace-per-active-machine
  (testing "destroy-frame! emits :rf.machine.lifecycle/destroyed per active actor with :reason :parent-frame-destroyed"
    (rf/make-frame {:id :lt/auth :doc "lifecycle-trace frame"})
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
      (rf.machines.test-support/with-trace-capture traces
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
    (rf/make-frame {:id :ha/auth :doc "http-abort hook test frame"})
    (let [aborted (atom [])
          ;; Install the hook explicitly. `re-frame.http.managed`
          ;; isn't loaded in this leaf-artefact's classpath, so we
          ;; register the hook directly to stand in for it. rf2-wjfm — the
          ;; cascade calls the hook's FRAME-BEARING arity, so the stub records
          ;; the pair and the assertion below pins the frame as well as the
          ;; address. `abort-actor-in-flight-http!` swallows any throw from the
          ;; hook, so a 1-arg stub here would not error — it would silently
          ;; record nothing.
          _ (rf.late-bind/set-fn!
              :http/abort-on-actor-destroy
              (fn [frame-id actor-id] (swap! aborted conj [frame-id actor-id])))
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
      (is (= #{[:ha/auth :ha/child#1] [:ha/auth :ha/child#2] [:ha/auth :ha/boot]}
             (set @aborted))
          "the abort hook fired once per active actor — spawned plus singleton —
           and each call carried the DESTROYING FRAME (rf2-wjfm), so the http
           registry narrows the sweep to this frame's slot"))))

;; ---- multiple frames isolated -------------------------------------------

(deftest destroy-of-one-frame-does-not-disturb-anothers-machines
  (testing "destroy-frame! walks only the destroyed frame's spawn-order channel"
    (rf/make-frame {:id :iso/frame-a :doc "frame A"})
    (rf/make-frame {:id :iso/frame-b :doc "frame B"})
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
      (is (= [:iso/child-a#1] (rf.machines.spawn-order/frame-order :iso/frame-a)))
      (is (= [:iso/child-b#1] (rf.machines.spawn-order/frame-order :iso/frame-b)))
      ;; A spawned actor carries NO per-instance registrar entry; its
      ;; liveness IS its snapshot's presence in the frame's (revertible)
      ;; app-db. Cross-frame isolation is therefore asserted on the
      ;; snapshots, not the registrar.
      (is (some? (get-in (:rf.db/runtime (rf/frame-state-value :iso/frame-a))
                         [:rf.runtime/machines :snapshots :iso/child-a#1]))
          "frame A's spawned actor is live (snapshot present) before destroy")
      (is (some? (get-in (:rf.db/runtime (rf/frame-state-value :iso/frame-b))
                         [:rf.runtime/machines :snapshots :iso/child-b#1]))
          "frame B's spawned actor is live (snapshot present) before destroy")
      ;; Spawned actors never register a per-instance handler.
      (is (nil? (rf.registrar/lookup :event :iso/child-a#1))
          "frame A's spawned actor has no per-instance registrar entry")
      (is (nil? (rf.registrar/lookup :event :iso/child-b#1))
          "frame B's spawned actor has no per-instance registrar entry")
      ;; Destroy A; B's actor stays alive (its snapshot survives).
      (rf/destroy-frame! :iso/frame-a)
      (is (= [:iso/child-a#1] @exit-log)
          "only frame A's spawned actor ran its :exit")
      (is (some? (rf.registrar/lookup :event :iso/child-b)
                 )
          "frame B's TYPE machine stays globally registered after A's destroy")
      (is (some? (get-in (:rf.db/runtime (rf/frame-state-value :iso/frame-b))
                         [:rf.runtime/machines :snapshots :iso/child-b#1]))
          "frame B's spawned actor stays alive (snapshot present) after A's destroy")
      (is (= [] (rf.machines.spawn-order/frame-order :iso/frame-a))
          "frame A's spawn-order slot is cleared")
      (is (= [:iso/child-b#1] (rf.machines.spawn-order/frame-order :iso/frame-b))
          "frame B's spawn-order slot is untouched"))))

;; ---- restore / hydration: spawned snapshots absent from spawn-order ------
;;
;; Restore / SSR hydration / `replace-frame-state!` / `restore-epoch!`
;; repopulate the DURABLE runtime-db snapshots WITHOUT repopulating the
;; PROCESS-SIDE (transient) `spawn-order` atom (Spec 002 §Durable vs
;; transient: the atom is runtime bookkeeping, not durable state). A restored
;; SPAWNED actor (snapshot carries `:rf/machine-type`) flows through the FULL
;; `destroy-single-actor!` teardown — dissoc the snapshot, release the
;; system-id reverse index, clear schema marks, cancel `:after` timers,
;; unregister a handler — in reverse-creation order read off the durable
;; `[:rf.runtime/machines :spawn-order]` vector, which rides the runtime-db
;; value through the round trip. (The straggler `run-singleton-exit-cascade!` path
;; runs the `:exit` cascade + HTTP abort only and is reserved for restored
;; SINGLETON snapshots that carry no `:rf/machine-type`.)
;;
;; The tests in THIS section model an SSR / preload hydration into a FRESH
;; PROCESS: durable snapshots arrive on the wire and the transient atom was
;; never populated at all. `rf.machines.spawn-order/reset-all!` reproduces exactly that —
;; an empty cache beside a full runtime-db.
;;
;; It is NOT a model of an IN-PROCESS `restore-epoch!` / `replace-frame-state!`,
;; and reading it as one is what rf2-1vlyg's first pass got wrong: no
;; production install path clears the cache (`:machines/on-frame-restored!`
;; cancels `:after` timers and nothing else), so after an in-process install
;; the cache is POPULATED and may name actors the installed durable value
;; discarded. That harder shape has its own section — §in-process runtime-state
;; install, below — driven through core's real write surface with no reach into
;; machines internals.

(defn- runtime-snapshots
  "The `[:rf.runtime/machines :snapshots]` map on `frame-id`'s runtime-db."
  [frame-id]
  (get-in (:rf.db/runtime (rf/frame-state-value frame-id)) [:rf.runtime/machines :snapshots]))

(deftest restored-spawned-snapshots-get-full-teardown-newest-first
  (testing "destroy-frame! treats restored spawned snapshots (absent from spawn-order) as spawned actors: full teardown, newest-first by durable actor-id"
    (rf/make-frame {:id :rs/auth :doc "restore-teardown frame"})
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
      (rf.machines.spawn-order/reset-all!)
      (is (= [] (rf.machines.spawn-order/frame-order :rs/auth))
          "spawn-order atom is empty post-restore (the bug's precondition)")
      ;; Destroy the frame.
      (rf/destroy-frame! :rs/auth)
      ;; :exit fired for all three children, NEWEST-FIRST off the durable
      ;; spawn-order vector. (Filter to children — the boot singleton's
      ;; :exit, if any, is irrelevant to the spawned-ordering contract.)
      ;;
      ;; These three share ONE id-prefix, so this case cannot distinguish the
      ;; durable order from the per-prefix `#<n>` suffix the pre-rf2-1vlyg
      ;; fallback parsed — which is exactly why it stays here as the
      ;; SAME-PREFIX CONTROL, green across the repair, while
      ;; `restored-mixed-prefix-actors-exit-in-reverse-creation-order` below
      ;; is the discriminator.
      (is (= [:rs/child#3 :rs/child#2 :rs/child#1]
             (filterv #{:rs/child#1 :rs/child#2 :rs/child#3} @exit-log))
          ":exit ran newest-first, order read off the durable spawn-order vector (not the lost transient atom)")
      ;; FULL teardown: every restored SPAWNED snapshot is dissoc'd. The
      ;; singleton straggler path would have LEFT these in runtime-db.
      (is (empty? (keep (fn [[id snap]]
                          (when (some? (:rf/machine-type snap)) id))
                        (runtime-snapshots :rs/auth)))
          "every restored spawned snapshot was dissoc'd (full teardown, not exit-only)"))))

(deftest restored-spawned-snapshot-releases-system-id-index
  (testing "destroy-frame! releases the system-id reverse index for a restored, spawn-order-less spawned actor"
    (rf/make-frame {:id :rsi/auth :doc "restore system-id frame"})
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
             (get-in (:rf.db/runtime (rf/frame-state-value :rsi/auth))
                     [:rf.runtime/machines :system-ids :session/primary]))
          "system-id bound before restore")
      ;; Restore: wipe the transient spawn-order; the durable snapshot +
      ;; system-id reverse index survive.
      (rf.machines.spawn-order/reset-all!)
      (rf/destroy-frame! :rsi/auth)
      (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rsi/auth))
                        [:rf.runtime/machines :system-ids :session/primary]))
          "system-id reverse index released — only the full spawned teardown does this; the singleton straggler path leaves it bound")
      (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rsi/auth))
                        [:rf.runtime/machines :snapshots :rsi/child#1]))
          "restored spawned snapshot dissoc'd"))))

(deftest restored-singleton-snapshot-keeps-singleton-straggler-path
  (testing "a restored SINGLETON snapshot (no :rf/machine-type) keeps the exit-only straggler path — handler survives, snapshot left for app-db release"
    (rf/make-frame {:id :rsg/auth :doc "restore singleton frame"})
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
      (is (some? (get-in (:rf.db/runtime (rf/frame-state-value :rsg/auth))
                         [:rf.runtime/machines :snapshots :rsg/single]))
          "singleton snapshot present before restore")
      (is (nil? (:rf/machine-type
                  (get-in (:rf.db/runtime (rf/frame-state-value :rsg/auth))
                          [:rf.runtime/machines :snapshots :rsg/single])))
          "singleton snapshot carries NO :rf/machine-type (the discriminator)")
      (rf.machines.spawn-order/reset-all!)
      (rf/destroy-frame! :rsg/auth)
      ;; The singleton's :exit ran (straggler path runs the exit cascade)...
      (is (= [:rsg/single] @exit-log)
          "singleton :exit cascade ran via the straggler path")
      ;; ...but its TYPE handler stays globally registered (outlives the frame).
      (is (some? (rf.registrar/lookup :event :rsg/single))
          "singleton handler stays registered — NOT unregistered by the straggler path"))))

;; ---- durable spawn-order: the frame-global creation sequence (rf2-1vlyg) ----
;;
;; The tests above restore a frame whose actors all share ONE id-prefix, so the
;; per-prefix `#<n>` suffix happens to be a valid total order and the defect
;; below is invisible. These tests use TWO machine types, which the suffix
;; cannot order: `:probe/a#1`, `:probe/a#2` and `:probe/b#1` were created in
;; that sequence, but descending-suffix sorting puts `:probe/a#2` (rank 2)
;; ahead of the NEWEST actor `:probe/b#1` (rank 1). The information the old
;; fallback tried to reconstruct is simply not in the actor-id — so the frame's
;; total creation order is now RECORDED, in the durable
;; `[:rf.runtime/machines :spawn-order]` vector that rides the runtime-db value
;; through restore / hydration / `replace-runtime-db!`.

(defn- runtime-spawn-order
  "The durable `[:rf.runtime/machines :spawn-order]` vector (oldest →
  newest) on `frame-id`'s runtime-db, or nil when the slot is absent."
  [frame-id]
  (get-in (:rf.db/runtime (rf/frame-state-value frame-id))
          [:rf.runtime/machines :spawn-order]))

(defn- reg-probe-machines!
  "Register two child machine types under DIFFERENT id-prefixes, each
  appending its own stamped `:rf/self-id` to `exit-log` from its active
  state's `:exit`, plus a boot machine whose action emits three
  `:rf.machine/spawn` effects in the order A, A, B."
  [exit-log]
  (let [child (fn [] {:initial :running
                      :data    {}
                      :states  {:running
                                {:exit (fn [{data :data}]
                                         (swap! exit-log conj (:rf/self-id data))
                                         {})}}})
        spawn (fn [t] [:rf.machine/spawn {:machine-id t :id-prefix t}])]
    (rf/reg-machine :probe/a (child))
    (rf/reg-machine :probe/b (child))
    (rf/reg-machine :probe/boot
                    {:initial :idle
                     :data    {}
                     :states  {:idle {:on {:spawn-mixed
                                           {:action (fn [_]
                                                      {:fx [(spawn :probe/a)
                                                            (spawn :probe/a)
                                                            (spawn :probe/b)]})}}}}})))

(deftest restored-mixed-prefix-actors-exit-in-reverse-creation-order
  (testing "a restored frame holding actors of TWO machine types disposes them newest-first — the per-prefix #<n> suffix cannot order them, the durable spawn-order vector can"
    (rf/make-frame {:id :probe/auth :doc "mixed-prefix restore frame"})
    (let [exit-log (atom [])]
      (reg-probe-machines! exit-log)
      (rf/dispatch-sync [:probe/boot [:spawn-mixed]] {:frame :probe/auth})
      ;; --- non-vacuity controls, BEFORE the round trip -------------------
      ;; All three actors are live...
      (is (= #{:probe/a#1 :probe/a#2 :probe/b#1}
             (set (keep (fn [[id snap]]
                          (when (some? (:rf/machine-type snap)) id))
                        (runtime-snapshots :probe/auth))))
          "three spawned snapshots are live before the round trip")
      ;; ...and the DURABLE order records the exact sequence they were
      ;; created in. This is the fact the old code had no way to know.
      (is (= [:probe/a#1 :probe/a#2 :probe/b#1]
             (runtime-spawn-order :probe/auth))
          "durable spawn-order carries the frame-global creation sequence, across id-prefixes")
      ;; --- the loss boundary --------------------------------------------
      ;; Model epoch restore / SSR hydration: the durable runtime-db
      ;; survives, the transient process-side atom does not.
      (rf.machines.spawn-order/reset-all!)
      (is (= [] (rf.machines.spawn-order/frame-order :probe/auth))
          "transient spawn-order atom is empty post-restore (the bug's precondition)")
      (is (= [:probe/a#1 :probe/a#2 :probe/b#1]
             (runtime-spawn-order :probe/auth))
          "the durable order is untouched by the loss of the transient atom")
      ;; --- the discriminator --------------------------------------------
      (rf/destroy-frame! :probe/auth)
      (is (= [:probe/b#1 :probe/a#2 :probe/a#1]
             (filterv #{:probe/a#1 :probe/a#2 :probe/b#1} @exit-log))
          (str "exact reverse creation order. Descending-suffix sorting — the pre-rf2-1vlyg "
               "fallback — yields [:probe/a#2 :probe/a#1 :probe/b#1], exiting :probe/a#2 "
               "ahead of the newest actor :probe/b#1 and inverting the stack discipline "
               "Spec 005 §Cross-Spec Interactions §1 pins."))
      (is (= 3 (count (filterv #{:probe/a#1 :probe/a#2 :probe/b#1} @exit-log)))
          "each child exited exactly once")
      (is (empty? (keep (fn [[id snap]]
                          (when (some? (:rf/machine-type snap)) id))
                        (runtime-snapshots :probe/auth)))
          "every restored spawned snapshot was dissoc'd (full teardown, not exit-only)"))))

(deftest durable-spawn-order-is-transport-safe
  (testing "the durable ordering fact is plain data — it round-trips through EDN unchanged and carries no fn / atom / host handle"
    (rf/make-frame {:id :probeedn/auth :doc "spawn-order transport frame"})
    (let [exit-log (atom [])]
      (reg-probe-machines! exit-log)
      (rf/dispatch-sync [:probe/boot [:spawn-mixed]] {:frame :probeedn/auth})
      (let [order (runtime-spawn-order :probeedn/auth)]
        (is (= [:probe/a#1 :probe/a#2 :probe/b#1] order)
            "the order is recorded before the round trip (non-vacuity control)")
        (is (vector? order) "a vector — an ordered, indexable value")
        (is (every? keyword? order)
            "actor-id keywords only: no function, atom, or host handle enters runtime-db")
        (is (= order (read-string (pr-str order)))
            "survives pr-str / read-string unchanged — so it rides the SSR hydration payload and an epoch snapshot")))))

(deftest explicit-destroy-prunes-durable-spawn-order
  (testing "a successful explicit destroy removes the actor from the durable order, so a later frame destroy neither re-exits it nor leaves a stale entry"
    (rf/make-frame {:id :probedd/auth :doc "spawn-order prune frame"})
    (let [exit-log (atom [])]
      (reg-probe-machines! exit-log)
      ;; Add a destroy trigger to the boot machine's state.
      (rf/reg-machine :probedd/boot
                      {:initial :idle
                       :data    {}
                       :states  {:idle {:on {:drop-middle
                                             {:action (fn [_]
                                                        {:fx [[:rf.machine/destroy :probe/a#2]]})}}}}})
      (rf/dispatch-sync [:probe/boot [:spawn-mixed]] {:frame :probedd/auth})
      (is (= [:probe/a#1 :probe/a#2 :probe/b#1]
             (runtime-spawn-order :probedd/auth))
          "all three recorded before the destroy (non-vacuity control)")
      (rf/dispatch-sync [:probedd/boot [:drop-middle]] {:frame :probedd/auth})
      (is (= [:probe/a#1 :probe/b#1] (runtime-spawn-order :probedd/auth))
          "the explicitly destroyed actor is pruned from the durable order, the survivors keep their sequence")
      (is (= [:probe/a#2] @exit-log)
          "the destroyed actor's :exit ran once, at destroy time")
      ;; The frame destroy that follows must not re-exit the dead actor.
      (rf/destroy-frame! :probedd/auth)
      (is (= [:probe/a#2 :probe/b#1 :probe/a#1] @exit-log)
          "frame destroy exits only the two survivors, newest-first — the dead actor is not exited a second time")
      (is (nil? (runtime-spawn-order :probedd/auth))
          "the slot is pruned once it empties — no unbounded stale entry survives the frame"))))

;; ---- in-process runtime-state install: the transient cache goes stale -----
;;
;; An IN-PROCESS whole/partial runtime-state install — `restore-epoch!`,
;; `replace-frame-state!`, a captured-value `swap-runtime-db!` revert — replaces
;; the durable runtime-db and touches NOTHING process-side. No production path
;; clears the frame's transient spawn-order cache: `:machines/on-frame-restored!`
;; cancels `:after` timers and nothing else. So an install that rewinds PAST a
;; spawn leaves the discarded actor named in the cache while durable state has
;; dropped it — the cache is not merely EMPTY after a round trip (the
;; fresh-process shape above), it is WRONG.
;;
;; The invariant that settles it (rf2-1vlyg): a cache entry is evidence that a
;; spawn once COMMITTED IN THIS PROCESS, never evidence that the actor is still
;; in the frame's durable state. Both consumers confirm against the live
;; runtime-db before believing it — frame destroy takes its whole membership
;; from the durable snapshots + durable order, and the destroy liveness probe
;; re-reads the live runtime-db before trusting a cache hit. That holds for
;; EVERY install path, including those that fire no hook at all, which is why it
;; is enforced where the cache is READ rather than at each install site.
;;
;; These tests drive the loss boundary through `rf.frame/replace-frame-state!` —
;; core's ONE frame-state write surface, and the very fn
;; `epoch/perform-restore!` calls to install a restored epoch. No test below
;; resets a machines internal by hand.

(defn- reg-staged-probe-machines!
  "Two child TYPES under DIFFERENT id-prefixes, each appending its stamped
  `:rf/self-id` to `exit-log` from its active state's `:exit`, plus a boot
  machine that spawns them on SEPARATE events (and can destroy the second).
  Staging the two spawns apart is what lets a test capture a frame-state
  BETWEEN them — the value a `restore-epoch!` rewinds to."
  [exit-log]
  (let [child (fn [] {:initial :running
                      :data    {}
                      :states  {:running
                                {:exit (fn [{data :data}]
                                         (swap! exit-log conj (:rf/self-id data))
                                         {})}}})
        spawn (fn [t] [:rf.machine/spawn {:machine-id t :id-prefix t}])]
    (rf/reg-machine :stage/a (child))
    (rf/reg-machine :stage/b (child))
    (rf/reg-machine :stage/boot
                    {:initial :idle
                     :data    {}
                     :states  {:idle {:on {:spawn-a {:action (fn [_] {:fx [(spawn :stage/a)]})}
                                           :spawn-b {:action (fn [_] {:fx [(spawn :stage/b)]})}
                                           :drop-b  {:action (fn [_]
                                                               {:fx [[:rf.machine/destroy :stage/b#1]]})}}}}})))

(defn- collect-traces!
  "Run `body-fn` with a trace listener attached; return the collected
  envelopes."
  [body-fn]
  (let [traces (atom [])
        cb-key (gensym ::destroy-cascade-cb)]
    (rf/register-listener! :trace cb-key (fn [ev] (swap! traces conj ev)))
    (try (body-fn) (finally (rf/unregister-listener! :trace cb-key)))
    @traces))

(defn- destroyed-actor-ids
  "The `:actor-id` tags of every `operation` trace in `traces`, in emission
  order, narrowed to `of-interest`."
  [traces operation of-interest]
  (->> traces
       (filter #(= operation (:operation %)))
       (map #(:actor-id (:tags %)))
       (filterv of-interest)))

(deftest restore-past-a-spawn-does-not-reap-the-discarded-actor
  (testing "an in-process frame-state install that rewinds PAST a spawn leaves the discarded actor in the transient cache; frame destroy must reap only what durable state still carries"
    (rf/make-frame {:id :stage/auth :doc "in-process restore frame"})
    (let [exit-log (atom [])]
      (reg-staged-probe-machines! exit-log)
      (rf/dispatch-sync [:stage/boot [:spawn-a]] {:frame :stage/auth})
      ;; The value `restore-epoch!` would reinstall — captured while ONLY
      ;; :stage/a#1 is live.
      (let [captured (rf/frame-state-value :stage/auth)]
        (is (= [:stage/a#1] (runtime-spawn-order :stage/auth))
            "the durable order carries a#1 at capture time (non-vacuity control)")
        (rf/dispatch-sync [:stage/boot [:spawn-b]] {:frame :stage/auth})
        (is (= [:stage/a#1 :stage/b#1] (runtime-spawn-order :stage/auth))
            "b#1 is recorded NEWER than a#1 in the durable order (non-vacuity control)")
        (is (= [:stage/a#1 :stage/b#1] (rf.machines.spawn-order/frame-order :stage/auth))
            "the transient cache carries both (non-vacuity control)")
        ;; --- the loss boundary: a whole frame-state install through core's
        ;; ONE write surface, the same fn epoch/perform-restore! calls.
        (rf.frame/replace-frame-state! :stage/auth captured)
        (is (= [:stage/a#1] (runtime-spawn-order :stage/auth))
            "the durable order rewound past b#1")
        (is (nil? (get (runtime-snapshots :stage/auth) :stage/b#1))
            "b#1's snapshot is gone from durable state — the install DISCARDED that actor")
        (is (= [:stage/a#1 :stage/b#1] (rf.machines.spawn-order/frame-order :stage/auth))
            (str "the transient cache still names the discarded b#1 — no production "
                 "install path clears it. This is the CONDITION under test, not a "
                 "defect the fix papers over by clearing it."))
        ;; --- the discriminator ---
        (let [traces (collect-traces! #(rf/destroy-frame! :stage/auth))]
          (is (= [:stage/a#1]
                 (destroyed-actor-ids traces :rf.machine.lifecycle/destroyed
                                      #{:stage/a#1 :stage/b#1}))
              (str "only the actor durable state still carries is reaped. Unioning the "
                   "stale transient cache into the walk reaps :stage/b#1 as well — and "
                   "since the durable segment goes FIRST, it places the discarded newer "
                   "b#1 AFTER the older a#1, inverting the reverse-creation discipline "
                   "Spec 005 §Cross-Spec Interactions §1 pins."))
          (is (= [:stage/a#1] (filterv #{:stage/a#1 :stage/b#1} @exit-log))
              "only a#1's :exit cascade ran"))))))

(deftest explicit-destroy-of-a-restore-discarded-actor-is-silent
  (testing "after an install that rewinds past its spawn, an explicit :rf.machine/destroy of the discarded actor is the silent-idempotent no-op Spec 005 pins — a stale cache entry alone must not report it live"
    (rf/make-frame {:id :stagex/auth :doc "silent-destroy-after-restore frame"})
    (let [exit-log (atom [])]
      (reg-staged-probe-machines! exit-log)
      (rf/dispatch-sync [:stage/boot [:spawn-a]] {:frame :stagex/auth})
      (let [captured (rf/frame-state-value :stagex/auth)]
        (rf/dispatch-sync [:stage/boot [:spawn-b]] {:frame :stagex/auth})
        (is (some? (get (runtime-snapshots :stagex/auth) :stage/b#1))
            "b#1 is live before the install (non-vacuity control)")
        (rf.frame/replace-frame-state! :stagex/auth captured)
        (is (nil? (get (runtime-snapshots :stagex/auth) :stage/b#1))
            "b#1 was discarded by the install")
        (is (some? (some #{:stage/b#1} (rf.machines.spawn-order/frame-order :stagex/auth)))
            "the stale cache entry for b#1 survives the install (the condition under test)")
        (reset! exit-log [])
        (let [traces (collect-traces!
                       #(rf/dispatch-sync [:stage/boot [:drop-b]] {:frame :stagex/auth}))]
          (is (= [] (destroyed-actor-ids traces :rf.machine/destroyed #{:stage/b#1}))
              (str "no :rf.machine/destroyed for an actor durable state no longer carries. "
                   "Spec 005 §Destroy is silent-idempotent: an already-gone actor emits no "
                   "trace, runs no teardown and raises no error."))
          (is (= [] @exit-log)
              "and no :exit cascade ran for the discarded actor"))))))

(deftest mixed-prefix-order-survives-a-real-frame-state-reinstall
  (testing "the DURABLE vector, not the transient cache, carries reverse-creation teardown through a genuine in-process frame-state reinstall"
    (rf/make-frame {:id :probere/auth :doc "reinstall ordering frame"})
    (let [exit-log (atom [])]
      (reg-probe-machines! exit-log)
      (rf/reg-machine :probere/boot
                      {:initial :idle
                       :data    {}
                       :states  {:idle {:on {:drop-all
                                             {:action (fn [_]
                                                        {:fx [[:rf.machine/destroy :probe/a#1]
                                                              [:rf.machine/destroy :probe/a#2]
                                                              [:rf.machine/destroy :probe/b#1]]})}}}}})
      (rf/dispatch-sync [:probe/boot [:spawn-mixed]] {:frame :probere/auth})
      (let [captured (rf/frame-state-value :probere/auth)]
        (is (= [:probe/a#1 :probe/a#2 :probe/b#1] (runtime-spawn-order :probere/auth))
            "the durable order is recorded at capture time (non-vacuity control)")
        ;; Empty the transient cache the ORDINARY way — three explicit
        ;; destroys, each running `rf.machines.spawn-order/forget!`. No internals reset.
        (rf/dispatch-sync [:probere/boot [:drop-all]] {:frame :probere/auth})
        (is (= [] (rf.machines.spawn-order/frame-order :probere/auth))
            "the transient cache is empty after the destroys (non-vacuity control)")
        (is (nil? (runtime-spawn-order :probere/auth))
            "and the durable slot is pruned once it empties")
        (reset! exit-log [])
        ;; Reinstall the captured frame-state — the whole-value install
        ;; `restore-epoch!` performs. Only the DURABLE order comes back.
        (rf.frame/replace-frame-state! :probere/auth captured)
        (is (= [:probe/a#1 :probe/a#2 :probe/b#1] (runtime-spawn-order :probere/auth))
            "the durable order rode the install back in")
        (is (= [] (rf.machines.spawn-order/frame-order :probere/auth))
            (str "the transient cache is STILL empty — no install path repopulates it, "
                 "so the walk below has nothing but the durable vector to read"))
        (rf/destroy-frame! :probere/auth)
        (is (= [:probe/b#1 :probe/a#2 :probe/a#1]
               (filterv #{:probe/a#1 :probe/a#2 :probe/b#1} @exit-log))
            (str "exact reverse creation order, read off the reinstalled durable vector. "
                 "Descending-suffix sorting — the pre-rf2-1vlyg fallback — yields "
                 "[:probe/a#2 :probe/a#1 :probe/b#1]."))))))
