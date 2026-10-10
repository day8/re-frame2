(ns re-frame.machine-lifecycle-tail-exact-handoff-cljs-test
  "Each lifecycle tail rechecks frame ownership after every callback-bearing
  stage, so a callback that destroys frame A and publishes a same-id B mid-tail
  leaves B's registrar, spawn-order and subscription state untouched. Each
  fixture seeds B's state and drives the loss on the callback's own stack."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines]
            [re-frame.machines.classification :as rf.machines.classification]
            [re-frame.machines.lifecycle-fx.destroy :as rf.machines.lifecycle-fx.destroy]
            [re-frame.machines.lifecycle-fx.finalize :as rf.machines.lifecycle-fx.finalize]
            [re-frame.machines.lifecycle-fx.spawn :as rf.machines.lifecycle-fx.spawn]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private actor-type :rf2-rbxdxa/child)

(defn- snapshot-path [id] [:rf.runtime/machines :snapshots id])

(defn- actor-spec
  "A spawned-actor spec whose `:running` `:exit` calls `on-exit`."
  [on-exit]
  {:initial :running
   :states  {:running {:exit (fn [ctx] (when on-exit (on-exit)) (:data ctx))
                       :on   {:go :done}}
             :done    {:final? true}}})

(defn- seed-actor!
  "Seed a live spawned actor `actor-id` (snapshot + spawn-order entry) into
  `frame-id`, registering its TYPE so the spec resolves off the snapshot."
  [frame-id actor-id on-exit]
  (rf/reg-machine actor-type (actor-spec on-exit))
  (rf.frame/swap-runtime-db!
    frame-id
    (fn [rt] (assoc-in rt (snapshot-path actor-id)
                       {:state           :running
                        :data            {:rf/self-id actor-id}
                        :rf/machine-type actor-type})))
  (rf.machines.spawn-order/record! frame-id actor-id))

(defn- destroy-and-republish!
  "Destroy frame A and publish a same-id B, once."
  [fired? frame-id]
  (when (compare-and-set! fired? false true)
    (rf.frame/destroy-frame! frame-id)
    (rf/make-frame {:id frame-id})
    true))

(deftest ordinary-destroy-preserves-successor-registrar-handoff
  ;; A `:rf.machine/destroyed` listener publishes B and registers B's handler
  ;; at `actor-id`; A's unregister, after the trace, must not clear it.
  (let [frame-a  :rf2-rbxdxa/released-frame
        actor-id (keyword "rf2-rbxdxa" "released#1")
        b-meta   {:fn (fn [db _] db) :rf/provenance :successor-B}
        fired?   (atom false)]
    (rf/make-frame {:id frame-a})
    (seed-actor! frame-a actor-id nil)
    (rf.trace.tooling/register-listener!
      ::released-handoff
      (fn [ev]
        (when (and (= :rf.machine/destroyed (:operation ev))
                   (destroy-and-republish! fired? frame-a))
          (rf.registrar/register! :event actor-id b-meta))))
    (try
      (let [token-a (rf.frame/frame-incarnation-token frame-a)]
        (rf.frame/call-with-event-owner-token frame-a token-a
          (fn [] (rf.machines.lifecycle-fx.destroy/destroy-machine-fx {:frame frame-a} actor-id))))
      (is (= b-meta (rf.registrar/lookup :event actor-id)))
      (finally
        (rf.trace.tooling/unregister-listener! ::released-handoff)
        (rf.registrar/unregister! :event actor-id)))))

(deftest spawn-classification-lowering-loss-fences-spawn-order-record
  ;; A loss during `lower-at-spawn!`: the spawn rechecks ownership after it, so
  ;; the bare-id spawn-order record never lands A's ghost child in B's order.
  (rf/reg-machine actor-type (actor-spec nil))
  (let [frame-a        :rf2-rbxdxa/spawn-class-frame
        fired?         (atom false)
        orig-dispatch! (rf.late-bind/get-fn :router/dispatch!)]
    (rf/make-frame {:id frame-a})
    (try
      ;; An inert bootstrap dispatch, so no async drain races the assertion.
      (rf.late-bind/set-fn! :router/dispatch! (fn [_ev _opts] nil))
      ;; Multi-arity like the real fn: CLJS compiles the 4-arity call to a direct
      ;; `arity$4` invoke, which only a multi-arity fn exposes.
      (with-redefs [rf.machines.classification/lower-at-spawn!
                    (fn ([_frame _actor _spec] nil)
                        ([_frame _actor _spec _token]
                         (destroy-and-republish! fired? frame-a)
                         nil))]
        (let [token-a (rf.frame/frame-incarnation-token frame-a)]
          (rf.frame/call-with-event-owner-token frame-a token-a
            (fn [] (rf.machines.lifecycle-fx.spawn/spawn-fx {:frame frame-a}
                                                           {:machine-id actor-type
                                                            :start      [:go]})))))
      (is (empty? (rf.machines.spawn-order/frame-order frame-a)))
      (finally
        (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!)))))

(defn- finishing-machine [frame-id]
  {:initial   :done
   :rf/frame  frame-id
   :rf/cofx   {:rf/time-ms 0}
   :data      {:result 42}
   :states    {:done {:final? true :output-key :result}}})

(defn- finishing-snapshot [] {:state :done :data {:result 42}})

(deftest finalize-classification-drop-loss-fences-spawn-order-forget
  ;; A loss during `drop-at-destroy!` that re-seeds B's spawn-order: ownership
  ;; is rechecked after the drop, so A's forget spares B and finalize is inert.
  (let [frame-a    :rf2-rbxdxa/finalize-class-frame
        machine-id :rf2-rbxdxa/finalize-class-machine
        fired?     (atom false)]
    (rf/reg-machine machine-id (finishing-machine frame-a))
    (rf/make-frame {:id frame-a})
    (rf.frame/swap-runtime-db!
      frame-a
      (fn [rt] (assoc-in rt (snapshot-path machine-id) (finishing-snapshot))))
    (rf.machines.spawn-order/record! frame-a machine-id)
    (let [ret (with-redefs
                [rf.machines.classification/drop-at-destroy!
                 (fn ([_frame _mid _machine] nil)
                     ([_frame _mid _machine _token]
                      (when (destroy-and-republish! fired? frame-a)
                        (rf.machines.spawn-order/record! frame-a machine-id))
                      nil))]
                (let [token-a (rf.frame/frame-incarnation-token frame-a)]
                  (rf.frame/call-with-event-owner-token frame-a token-a
                    (fn []
                      (rf.machines.lifecycle-fx.finalize/finalize-machine
                        (finishing-machine frame-a)
                        machine-id frame-a (rf.machines.test-support/runtime-db frame-a)
                        (finishing-snapshot) [:some-completing-event] [])))))]
      (is (= [machine-id] (vec (rf.machines.spawn-order/frame-order frame-a)))
          "B's spawn-order entry survives")
      (is (= [] (:fx ret)) "no A-derived fx are published onto B"))))

(deftest destroy-single-actor-reports-abort-on-owner-loss
  ;; The actor's `:exit` loses the owner, so the teardown aborts and the
  ;; report is falsey: a `:spawn-all` caller emits no phantom destroyed.
  (let [frame-a  :rf2-rbxdxa/single-abort-frame
        actor-id (keyword "rf2-rbxdxa" "single-abort#1")
        fired?   (atom false)]
    (rf/make-frame {:id frame-a})
    (seed-actor! frame-a actor-id #(destroy-and-republish! fired? frame-a))
    (let [token-a (rf.frame/frame-incarnation-token frame-a)
          fence   {:owner-gone? (fn [] (not (rf.frame/event-continuation-live? frame-a token-a)))
                   :owner-token token-a}]
      (is (not (rf.frame/call-with-event-owner-token frame-a token-a
                 (fn [] (rf.machines.lifecycle-fx.destroy/destroy-single-actor! frame-a actor-id fence))))))))

(defn- seed-sub-vec-timer!
  "Seed one armed subscription-vector `:after` timer for `parent-id`, holding a
  real reference on `[:rbxdxa/dyn]` in `frame-id`, so its release reaches the
  sub-cache."
  [frame-id parent-id]
  (swap! rf.machines.timer/after-timers assoc-in
         [frame-id {:parent parent-id :spawn [] :delay [:rbxdxa/dyn]}]
         {:handle          nil
          :reaction        (rf/subscribe [:rbxdxa/dyn] {:frame frame-id})
          :sub-watcher-key ::a-watch
          :resolved-ms     5000
          :epoch           0
          :state           :running
          :region          nil
          :delay-source    :sub
          :token           ::a-token}))

(deftest on-exit-timer-cancel-returns-only-its-own-reaction
  ;; A cancellation listener that publishes same-id B (re-arming the query)
  ;; must not cost B's reaction a ref; an ordinary cancel returns A's one ref.
  (rf/reg-sub :rbxdxa/dyn (fn [_ _] 5000))
  (doseq [[frame-a republish?] [[:rf2-rbxdxa/subvec-exit-frame true]
                                [:rf2-rbxdxa/subvec-exit-live-frame false]]]
    (let [actor-id (keyword "rf2-rbxdxa" "subvec-exit#1")
          fired?   (atom false)
          b-held   (atom nil)
          slot     #(get @(:sub-cache (rf.frame/frame frame-a)) [:rbxdxa/dyn])]
      (rf/make-frame {:id frame-a})
      (seed-sub-vec-timer! frame-a actor-id)
      (when republish?
        (rf.trace.tooling/register-listener!
          ::subvec-exit-fence
          (fn [ev]
            (when (and (= :rf.machine.timer/cancelled (:operation ev))
                       (destroy-and-republish! fired? frame-a))
              (reset! b-held (rf/subscribe [:rbxdxa/dyn] {:frame frame-a}))))))
      (try
        (rf.machines.timer/after-cancel-fx {:frame frame-a}
                                           {:rf/parent-id actor-id :rf/invoke-id []})
        (if republish?
          (is (= [1 true] [(:ref-count (slot)) (identical? @b-held (:reaction (slot)))])
              "A's release leaves B's slot at one reference, holding B's reaction")
          (is (nil? (slot)) "an ordinary cancel returns A's one reference"))
        (finally
          (rf.trace.tooling/unregister-listener! ::subvec-exit-fence)
          (swap! rf.machines.timer/after-timers dissoc frame-a))))))
