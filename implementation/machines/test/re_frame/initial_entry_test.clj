(ns re-frame.initial-entry-test
  "Per rf2-0z73. Verifies whether a machine's **initial-state `:entry`
  actions** fire when the machine first comes into existence — both for
  top-level singleton machines (registered via `reg-machine`) and for
  spawned actors (via declarative `:spawn` or imperative
  `[:rf.machine/spawn ...]`).

  Surfaced from rf2-yf97 (the websocket example) and the
  `:rf.http/managed` machine-shape wrapper: both work around an apparent
  gap by declaring `:on :rf.machine/spawned :action ...` on the initial
  state instead of relying on the natural `:entry` slot. If `:entry`
  doesn't fire on initial state, every Pattern doc and worked example
  that assumes the canonical `:entry` shape is misleading.

  Three scenarios under test, mirroring the three ways a machine first
  comes into existence:

   1. **Top-level singleton machine** — registered via `reg-machine`,
      first dispatched event arrives. The initial state's `:entry`
      action should fire as part of bringing the machine to life.

   2. **Spawned actor via declarative `:spawn`** — parent enters an
      `:spawn`-bearing state; the spawn fx allocates the child, seeds
      its snapshot at `[:rf/machines <spawned-id>]`. The child's initial
      state's `:entry` action should fire as the spawn cascade runs.

   3. **Compound initial cascade** — initial state is itself a compound
      whose `:initial` chain descends through nested states. EVERY
      state along the initial cascade should fire its `:entry` action
      shallowest-first (per Spec 005 §Initial cascading)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- snapshot
  [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

;; ---- (1) singleton-machine initial :entry firing -------------------------

(deftest singleton-initial-entry-fires-on-first-dispatch
  (testing "a singleton machine's initial-state :entry action fires when the machine first runs"
    (let [calls (atom [])
          spec  {:initial :idle
                 :data    {}
                 :actions
                 {:on-enter-idle (fn [{data :data}]
                                   (swap! calls conj :idle-entry)
                                   {:data (assoc data :idle-entered? true)})}
                 :states
                 {:idle  {:entry :on-enter-idle
                          :on    {:go {:target :next}}}
                  :next  {}}}]
      (rf/reg-machine :rf2-0z73/singleton spec)
      ;; First dispatch — the machine snapshot is synthesised here, and
      ;; the inner event `[:go]` triggers the :idle→:next transition.
      ;; If initial :entry fires, :on-enter-idle should have been
      ;; invoked exactly once before the transition out of :idle.
      (rf/dispatch-sync [:rf2-0z73/singleton [:go]])
      (is (= [:idle-entry] @calls)
          "the initial state's :entry action fired exactly once on machine first-event")
      (is (true? (get-in (snapshot :rf2-0z73/singleton) [:data :idle-entered?]))
          ":entry action's :data write is visible in the committed snapshot")
      (is (= :next (:state (snapshot :rf2-0z73/singleton)))
          "the transition out of :idle still happens — :entry runs before, not in place of"))))

;; ---- (2) spawned-actor initial :entry firing ------------------------------

(deftest spawned-initial-entry-fires-on-spawn
  (testing "a spawned actor's initial-state :entry action fires during the spawn cascade"
    (let [calls (atom [])
          ;; Register a side-effecting fx the child's :entry will emit
          ;; via :fx. The fx records its argument so the test can
          ;; observe it. Per Spec 002 §Effect handler signature the
          ;; handler is `(fn [ctx args] ...)` — the ctx slot carries
          ;; `:frame` and (when supplied) `:event`.
          _     (rf/reg-fx :rf2-0z73/record
                           (fn [_ctx arg] (swap! calls conj arg)))
          child {:initial :requesting
                 :data    {}
                 :actions
                 {:fire-request (fn [_]
                                  {:fx [[:rf2-0z73/record :child-entry-fired]]})}
                 :states
                 {:requesting {:entry :fire-request}}}
          parent {:initial :idle
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:spawn {:machine-id :rf2-0z73/child}
                             :on    {:done :idle}}}}]
      (rf/reg-machine :rf2-0z73/child  child)
      (rf/reg-machine :rf2-0z73/parent parent)
      (rf/dispatch-sync [:rf2-0z73/parent [:start]])
      ;; Outcome verification: the child's :entry should have fired
      ;; exactly once on spawn.
      (is (= [:child-entry-fired] @calls)
          "spawned child's initial-state :entry action fired exactly once during spawn"))))

;; ---- (3) compound-initial cascade fires every :entry shallowest-first -----

(deftest compound-initial-cascade-fires-every-entry
  (testing "a compound initial cascade fires every state's :entry shallowest-first"
    (let [calls (atom [])
          spec  {:initial :outer
                 :data    {}
                 :actions
                 {:enter-outer (fn [{data :data}]
                                 (swap! calls conj :outer)
                                 {:data (update data :seen (fnil conj []) :outer)})
                  :enter-mid   (fn [{data :data}]
                                 (swap! calls conj :mid)
                                 {:data (update data :seen (fnil conj []) :mid)})
                  :enter-leaf  (fn [{data :data}]
                                 (swap! calls conj :leaf)
                                 {:data (update data :seen (fnil conj []) :leaf)})}
                 :states
                 {:outer {:entry   :enter-outer
                          :initial :mid
                          :states
                          {:mid  {:entry   :enter-mid
                                  :initial :leaf
                                  :states
                                  {:leaf {:entry :enter-leaf
                                          :on    {:go :elsewhere}}}}
                           :elsewhere {}}}}}]
      (rf/reg-machine :rf2-0z73/compound spec)
      (rf/dispatch-sync [:rf2-0z73/compound [:go]])
      (is (= [:outer :mid :leaf] @calls)
          "every state in the initial cascade fired :entry shallowest-first, exactly once"))))

;; ---- (4) initial :entry cascade error path (rf2-dd3b / PR #330 gap) -------
;;
;; Per Spec 005 §Errors and machines.cljc:2161/2174 — if an action in
;; the bootstrap cascade throws, the runtime:
;;   - emits `:rf.error/machine-action-exception` with `:recovery
;;     :no-recovery`
;;   - DOES NOT commit the snapshot (no `:db` effect)
;;   - DOES NOT flow any fx the cascade accumulated
;;
;; PR #330 added the cascade itself; the happy paths above cover it.
;; rf2-dd3b adds the throw-path coverage so a regression that silently
;; committed a partial cascade or swallowed the trace would be caught.

(deftest initial-entry-throw-halts-bootstrap-atomically
  (testing "a throw in initial :entry leaves the snapshot uncommitted and
            fires :rf.error/machine-action-exception"
    (let [calls  (atom [])
          traces (atom [])
          spec   {:initial :a
                  :data    {}
                  :actions
                  {:throws (fn [_]
                             (swap! calls conj :throws-entered)
                             (throw (ex-info "boom in :entry" {:why :test})))}
                  :states
                  {:a {:entry :throws
                       :on    {:go :b}}
                   :b {}}}]
      (rf/reg-machine :rf2-dd3b/throws spec)
      (rf/register-listener! ::dd3b-1 (fn [ev] (swap! traces conj ev)))
      ;; Drive the first dispatch — this is where bootstrap runs.
      (rf/dispatch-sync [:rf2-dd3b/throws [:noop]])
      (rf/unregister-listener! ::dd3b-1)

      ;; The action body executed (so we know the cascade reached :a).
      (is (= [:throws-entered] @calls)
          "the throwing action ran (cascade reached the state) — necessary precondition")

      ;; (a) Snapshot NOT committed: no entry at [:rf/machines ...].
      (is (nil? (get-in (rf/get-frame-db :rf/default)
                        [:rf/machines :rf2-dd3b/throws]))
          "the bootstrap snapshot was NOT committed — cascade halt is atomic")

      ;; (b) :rf.error/machine-action-exception trace fired with diagnostic detail.
      (let [errs (filterv #(= :rf.error/machine-action-exception (:operation %))
                          @traces)]
        (is (= 1 (count errs))
            "exactly one :rf.error/machine-action-exception fired")
        (let [t (first errs)]
          (is (= :error (:op-type t)))
          (is (= :no-recovery (:recovery t)))
          (is (= :rf2-dd3b/throws (-> t :tags :machine-id))
              ":machine-id identifies the bootstrapping machine")
          (is (= [:rf.machine/bootstrap] (-> t :tags :event))
              ":event tag identifies the synthetic bootstrap event")
          (is (some? (-> t :tags :exception))
              ":exception slot is populated"))))))

(deftest initial-entry-throw-skips-invoke-and-after-on-failing-state
  (testing "when :entry throws on the initial state, its sibling :spawn and
            :after declarations do NOT fire — the cascade halt is total"
    (let [spawn-fired? (atom false)
          calls        (atom [])
          ;; Spawn fx — would fire if :spawn ran. We register our own
          ;; observer to detect it without depending on a real child.
          child-spec   {:initial :idle
                        :states  {:idle {}}}]
      (rf/reg-machine :rf2-dd3b/spy-child child-spec)
      ;; Hook the :rf.machine/spawn fx so we can observe if it fires.
      ;; Per Spec 005 §spawn-fx the runtime emits this when a :spawn
      ;; slot is declared on the entering state.
      (let [orig (registrar/lookup :fx :rf.machine/spawn)]
        (rf/reg-fx :rf.machine/spawn
                   {:platforms #{:client :server}}
                   (fn [_ _]
                     (reset! spawn-fired? true)))
        (let [spec {:initial :a
                    :data    {}
                    :actions {:throws (fn [_]
                                        (swap! calls conj :throws-entered)
                                        (throw (ex-info "boom" {})))}
                    :states
                    {:a {:entry  :throws
                         :spawn {:machine-id :rf2-dd3b/spy-child}
                         :after  {1000 :b}}
                     :b {}}}]
          (rf/reg-machine :rf2-dd3b/skip spec)
          (rf/dispatch-sync [:rf2-dd3b/skip [:noop]])
          ;; The action ran (cascade reached :a).
          (is (= [:throws-entered] @calls))
          ;; The spawn fx was NOT fired — the cascade halted before fx flowed.
          (is (false? @spawn-fired?)
              ":spawn's spawn fx did not fire when :entry threw on the same state")
          ;; And the snapshot was not committed — there is no machine record.
          (is (nil? (get-in (rf/get-frame-db :rf/default)
                            [:rf/machines :rf2-dd3b/skip]))
              "the snapshot was not committed; no machine record exists"))
        ;; Restore.
        (when orig
          (rf/reg-fx :rf.machine/spawn orig (fn [_ _] nil)))))))

(deftest initial-entry-throw-in-compound-cascade
  (testing "with a compound :initial cascade, a throw at the inner :entry
            still halts atomically — neither inner nor outer snapshot commits"
    ;; Per the bead's Variant 1: a throw on the second cascade level.
    ;; The contract is the same as a flat throw: the entire bootstrap is
    ;; atomic; nothing commits when any node in the cascade throws.
    (let [calls  (atom [])
          traces (atom [])
          spec   {:initial :outer
                  :data    {}
                  :actions
                  {:outer-ok  (fn [{data :data}]
                                (swap! calls conj :outer)
                                {:data (assoc data :outer-ran? true)})
                   :inner-bad (fn [_]
                                (swap! calls conj :inner-throws)
                                (throw (ex-info "inner boom" {})))}
                  :states
                  {:outer {:entry   :outer-ok
                           :initial :inner
                           :states
                           {:inner {:entry :inner-bad}}}}}]
      (rf/reg-machine :rf2-dd3b/compound spec)
      (rf/register-listener! ::dd3b-3 (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:rf2-dd3b/compound [:noop]])
      (rf/unregister-listener! ::dd3b-3)
      ;; Both actions executed (we want to verify outer ran before inner threw,
      ;; even though nothing committed). The cascade reached both nodes.
      (is (= [:outer :inner-throws] @calls)
          "the cascade ran outer's :entry, then inner's :entry where it threw")
      ;; But the snapshot is NOT committed — the whole bootstrap is atomic.
      (is (nil? (get-in (rf/get-frame-db :rf/default)
                        [:rf/machines :rf2-dd3b/compound]))
          "neither outer nor inner :entry's :data writes committed — bootstrap halted atomically")
      ;; The exception trace fired.
      (is (some #(= :rf.error/machine-action-exception (:operation %))
                @traces)
          ":rf.error/machine-action-exception was emitted"))))
