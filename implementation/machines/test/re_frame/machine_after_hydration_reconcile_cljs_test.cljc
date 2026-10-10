(ns re-frame.machine-after-hydration-reconcile-cljs-test
  "Hydration RECONCILES the host `:after` timer table rather than adding to it.

  `:rf/hydrate` replaces runtime-db wholesale, but the timer table is host
  state and survives, so every declaration the replacement drops must be
  released. The epoch gate would suppress the stale transition but releases no
  host work, so these tests read the timer table, the released handles and
  subscriptions, and the cancellation trace rather than the transitions. Each
  installs two server-produced runtime-dbs into the SAME frame. Runs on both
  hosts."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            ;; Loading `re-frame.machines` installs the artefact's late-bind
            ;; hooks + reserved fxs; under a single-ns run nothing else does.
            [re-frame.machines]
            [re-frame.machines.hydrate :as rf.machines.hydrate]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A frame of `platform` under an unused id. `make-frame` opts are FLAT: a
  nested `{:config {…}}` would leave the platform at the `:client` default."
  [platform]
  (let [fid (keyword "rf.hydrec" (str (name platform) (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform platform})
    fid))

(defn- inner
  "`frame-id`'s `:after` timer table, or `{}`."
  [frame-id]
  (get @rf.machines.timer/after-timers frame-id {}))

(defn- server-runtime-db
  "Run `machine-id` through `events` on a real SERVER frame, assert it armed no
  host timer, and return that frame's runtime-db — a genuine hydration slice."
  [machine-id events]
  (let [sfid (fresh-frame! :server)]
    (doseq [e events]
      (rf/dispatch-sync [machine-id e] {:frame sfid}))
    (is (empty? (inner sfid)) "precondition: the SERVER armed no `:after` host timer")
    (rf.frame/frame-runtime-db-value sfid)))

(defn- install!
  "Replace an EXISTING frame's runtime-db and run the machines hydration seam —
  the timer table surviving that replacement is the subject here."
  [frame-id runtime-db]
  (rf.frame/replace-runtime-db! frame-id runtime-db)
  (rf.machines.hydrate/rearm-after-timers! frame-id))

(defn- cancelled-rows
  "Captured `:rf.machine.timer/cancelled` payloads for `actor-id`."
  [actor-id]
  (into [] (comp (map :tags) (filter #(= actor-id (:actor-id %))))
        (rf.machines.test-support/events-of :rf.machine.timer/cancelled)))

(def ^:private toggling-machine
  "`:waiting` carries a 5s `:after` and an `:on` escape to the `:after`-free
  `:settled`, so a server run can settle on either side."
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:after {5000 {:target :timeout}}
                       :on    {:settle :settled}}
             :settled {}
             :timeout {}}})

(def ^:private dynamic-delay-machine
  "The same shape with a SUBSCRIPTION-vector delay."
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:after {[:hydrec/dyn-delay] {:target :timeout}}
                       :on    {:settle :settled}}
             :settled {}
             :timeout {}}})

(deftest hydration-cancels-a-timer-the-replacement-no-longer-declares
  (rf/reg-machine :hydrec/changed toggling-machine)
  (let [rt-waiting (server-runtime-db :hydrec/changed [[:go]])
        rt-settled (server-runtime-db :hydrec/changed [[:go] [:settle]])
        epoch      (get-in rt-waiting [:rf.runtime/machines :snapshots :hydrec/changed
                                       :data :rf/after-epoch [:waiting]])
        armed      (atom [])
        released   (atom [])
        cfid       (fresh-frame! :client)]
    (with-redefs [rf.interop/schedule-after!   (fn [_thunk _ms]
                                                 (let [h (keyword "handle" (str (count @armed)))]
                                                   (swap! armed conj h)
                                                   h))
                  rf.interop/cancel-scheduled! (fn [h] (swap! released conj h) nil)]
      (install! cfid rt-waiting)
      (rf.machines.test-support/reset-captured!)
      (install! cfid rt-settled))
    (is (empty? (inner cfid))
        "the dropped declaration's entry is gone: the arm phase supersedes only the keys it arms")
    (is (= @armed @released)
        "and its host handle was released, not merely forgotten")
    (is (= [{:reason :on-exit :state :waiting :delay 5000 :epoch epoch :frame cfid}]
           (mapv #(select-keys % [:reason :state :delay :epoch :frame])
                 (cancelled-rows :hydrec/changed)))
        "one ordinary `:on-exit` row: the actor survived, its declaring node left the active configuration")))

(deftest hydration-cancels-timers-for-an-actor-the-replacement-drops
  (rf/reg-machine :hydrec/dropped toggling-machine)
  (let [rt   (server-runtime-db :hydrec/dropped [[:go]])
        cfid (fresh-frame! :client)]
    (with-redefs [rf.interop/schedule-after!   (fn [_thunk _ms] ::handle)
                  rf.interop/cancel-scheduled! (fn [_h] nil)]
      (install! cfid rt)
      (install! cfid (update-in rt [:rf.runtime/machines :snapshots] dissoc :hydrec/dropped)))
    (is (= [:on-destroy] (mapv :reason (cancelled-rows :hydrec/dropped)))
        "the actor itself is gone, which is the `:on-destroy` reading, not `:on-exit`")))

(deftest hydration-releases-a-dropped-dynamic-delays-watcher-and-subscription
  ;; A lingering subscription-delay entry would hold its reaction, watcher and
  ;; shared `(frame, query-v)` ref-count for the life of the frame.
  (let [reaction (atom 2500)
        unsubs   (atom [])
        cfid     (fresh-frame! :client)]
    (rf/reg-sub :hydrec/dyn-delay (fn [_db _] @reaction))
    (rf/reg-machine :hydrec/dyn dynamic-delay-machine)
    (with-redefs [rf.subs/subscribe            (fn ([_q] reaction) ([_q _o] reaction))
                  rf.subs/unsubscribe-if-reaction (fn [_frame q _r] (swap! unsubs conj q) nil)
                  rf.interop/schedule-after!   (fn [_thunk _ms] ::handle)
                  rf.interop/cancel-scheduled! (fn [_h] nil)]
      (let [rt-waiting (server-runtime-db :hydrec/dyn [[:go]])
            rt-settled (server-runtime-db :hydrec/dyn [[:go] [:settle]])]
        (install! cfid rt-waiting)
        (let [entry (val (first (inner cfid)))]
          (is (= [:sub reaction true]
                 [(:delay-source entry) (:reaction entry) (some? (:sub-watcher-key entry))])
              "hydration armed the subscription delay, holding its reaction and change-watcher"))
        (install! cfid rt-settled)
        (is (= [[:hydrec/dyn-delay]] @unsubs)
            "the dropped entry's shared subscription ref-count was released exactly once")))))

(deftest the-reconcile-is-frame-scoped
  (rf/reg-machine :hydrec/sib toggling-machine)
  (let [rt-waiting (server-runtime-db :hydrec/sib [[:go]])
        rt-settled (server-runtime-db :hydrec/sib [[:go] [:settle]])
        keeper     (fresh-frame! :client)
        mover      (fresh-frame! :client)]
    (with-redefs [rf.interop/schedule-after!   (fn [_thunk _ms] ::handle)
                  rf.interop/cancel-scheduled! (fn [_h] nil)]
      (install! keeper rt-waiting)
      (install! mover rt-waiting)
      (install! mover rt-settled))
    (is (= [1 0] (mapv (comp count inner) [keeper mover]))
        "the hydrated frame reconciled; its sibling's identical declaration is untouched")))

(deftest a-server-side-hydrate-reconciles-nothing
  ;; A server frame cannot arm, so the entry a cancel phase would release is seeded.
  (rf/reg-machine :hydrec/srv toggling-machine)
  (let [rt-settled (server-runtime-db :hydrec/srv [[:go] [:settle]])
        sfid       (fresh-frame! :server)
        k          {:parent :hydrec/srv :spawn [:waiting] :delay 5000}]
    (swap! rf.machines.timer/after-timers assoc-in [sfid k]
           {:handle ::handle :resolved-ms 5000 :epoch 1 :state :waiting
            :delay-source :literal :token -1})
    (try
      (install! sfid rt-settled)
      (is (= #{k} (set (keys (inner sfid))))
          "the platform refusal covers the cancel phase as well as the arm")
      (is (empty? (cancelled-rows :hydrec/srv))
          "and no cancellation trace was emitted")
      (finally
        (swap! rf.machines.timer/after-timers dissoc sfid)))))
