(ns re-frame.machine-destroy-tail-incarnation-fence-test
  "The ordinary `:rf.machine/destroy` teardown tail is fenced to the frame
  incarnation A that entered it: a callback that destroys A and publishes a
  same-id B must leave B untouched."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.classification :as rf.machines.classification]
            [re-frame.machines.lifecycle-fx.destroy :as rf.machines.lifecycle-fx.destroy]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private actor-type :rf2-i4aj9c/child)
(def ^:private actor-id   (keyword "rf2-i4aj9c" "child#1"))

;; Declares a `:sensitive` path, so spawn lowers a classification claim.
(def ^:private classified-spec
  {:initial   :running
   :sensitive [[:data :token]]
   :states    {:running {:exit (fn [ctx] (:data ctx))
                         :on   {:go :done}}
               :done    {:final? true}}})

(defn- elision-slot [frame-id] (get-in (rf/frame-state-value frame-id) [:rf.db/runtime :rf.runtime/elision]))

(defn- seed-live-actor!
  "Seed `actor-id` into `frame-id` as a live spawned actor of `actor-type`:
  snapshot, spawn-order entry and lowered classification."
  [frame-id]
  (rf.frame/swap-runtime-db!
    frame-id
    #(assoc-in % [:rf.runtime/machines :snapshots actor-id]
               {:state           :running
                :data            {:rf/self-id actor-id :token "secret"}
                :rf/machine-type actor-type}))
  (rf.machines.spawn-order/record! frame-id actor-id)
  (rf.machines.classification/lower-at-spawn! frame-id actor-id classified-spec))

(deftest destroyed-trace-loss-fences-destroy-tail
  ;; The effect is called directly, so its `:rf.machine/destroyed` listener runs
  ;; on the destroying stack. It destroys A and re-seeds the same actor in a
  ;; same-id B; ownership is rechecked after the trace, so A's spawn-order
  ;; forget and the rest of its tail never reach B.
  (let [frame-a :rf2-i4aj9c/destroyed-frame
        fired?  (atom false)
        b-birth (atom nil)]
    (rf/make-frame {:id frame-a})
    (rf/reg-machine actor-type classified-spec)
    (seed-live-actor! frame-a)
    (rf.trace.tooling/register-listener!
      ::destroy-tail-fence
      (fn [ev]
        (when (and (= :rf.machine/destroyed (:operation ev))
                   (compare-and-set! fired? false true))
          (rf.frame/destroy-frame! frame-a)
          (rf/make-frame {:id frame-a})
          (seed-live-actor! frame-a)
          (reset! b-birth (rf.machines.test-support/runtime-db frame-a)))))
    (try
      (let [token-a (rf.frame/frame-incarnation-token frame-a)]
        (rf.frame/call-with-event-owner-token frame-a token-a
          #(rf.machines.lifecycle-fx.destroy/destroy-machine-fx {:frame frame-a} actor-id)))
      (is (= [@b-birth [actor-id]]
             [(rf.machines.test-support/runtime-db frame-a) (rf.machines.spawn-order/frame-order frame-a)]))
      (finally
        (rf.trace.tooling/unregister-listener! ::destroy-tail-fence)))))

;; Releasing a subscription-vector `:after` timer decrements the shared
;; (frame, query-v) ref-count. Once a cancelled-trace listener has destroyed A,
;; a same-id B may hold the same query, so A must skip the decrement.
(deftest sub-vec-timer-release-decrements-only-for-a-live-owner
  (doseq [[lose-a? expected-unsubscribes] [[true 0] [false 1]]]
    (let [frame-a     (keyword "rf2-i4aj9c" (str "subvec-" lose-a?))
          unsub-count (atom 0)
          fired?      (atom false)]
      (rf/make-frame {:id frame-a})
      (swap! rf.machines.timer/after-timers assoc-in
             [frame-a {:parent actor-id :spawn [] :delay [:i4aj9c/dyn]}]
             {:handle nil :reaction (atom 5000) :sub-watcher-key ::a-watch
              :resolved-ms 5000 :epoch 0 :state :running
              :region nil :delay-source :sub :token ::a-token})
      (with-redefs [rf.subs/unsubscribe (fn ([_] (swap! unsub-count inc) nil)
                                          ([_ _] (swap! unsub-count inc) nil))]
        (rf.trace.tooling/register-listener!
          ::subvec-fence
          (fn [ev]
            (when (and lose-a?
                       (= :rf.machine.timer/cancelled (:operation ev))
                       (compare-and-set! fired? false true))
              (rf.frame/destroy-frame! frame-a)
              (rf/make-frame {:id frame-a}))))
        (try
          (let [token-a     (rf.frame/frame-incarnation-token frame-a)
                owner-gone? #(not (rf.frame/event-continuation-live? frame-a token-a))]
            (rf.frame/call-with-event-owner-token frame-a token-a
              #(rf.machines.timer/cancel-actor-timers! frame-a actor-id owner-gone?)))
          (is (= expected-unsubscribes @unsub-count) (str "lose-a? " lose-a?))
          (finally
            (rf.trace.tooling/unregister-listener! ::subvec-fence)
            (swap! rf.machines.timer/after-timers dissoc frame-a)))))))

(defn- install-watching-adapter!
  "Install a plain-atom adapter whose container write lands, then runs
  `on-write` once while `armed?` holds."
  [armed? on-write]
  (let [base-replace (:replace-container! rf.substrate.plain-atom/adapter)]
    (rf.substrate.adapter/dispose-adapter!)
    (reset! rf.frame/frames {})
    (rf.substrate.adapter/install-adapter!
      (assoc rf.substrate.plain-atom/adapter
             :kind :custom
             :replace-container!
             (fn [container value]
               (base-replace container value)
               (when (compare-and-set! armed? true false)
                 (on-write)))))))

(defn- restore-plain-adapter! []
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter))

(deftest classification-drop-write-watch-loss-fences-removal
  ;; A container watch destroys A and publishes a same-id B, which re-lowers
  ;; the same owner's claim, DURING A's classification-drop write.
  (let [frame-a :rf2-i4aj9c/class-drop-frame
        armed?  (atom false)
        b-state (atom nil)]
    (install-watching-adapter!
      armed?
      (fn []
        (rf.frame/destroy-frame! frame-a)
        (rf/make-frame {:id frame-a})
        (rf.machines.classification/lower-at-spawn! frame-a actor-id classified-spec)
        (reset! b-state [(elision-slot frame-a) (rf.frame/frame-commit-epoch frame-a)])))
    (try
      (rf/reg-machine actor-type classified-spec)
      (rf/make-frame {:id frame-a})
      (let [token-a (rf.frame/frame-incarnation-token frame-a)]
        (reset! armed? true)
        (rf.frame/call-with-event-owner-token frame-a token-a
          #(rf.machines.classification/drop-at-destroy! frame-a actor-id classified-spec token-a)))
      (is (seq (first @b-state)) "B re-lowered its claim")
      (is (= @b-state [(elision-slot frame-a) (rf.frame/frame-commit-epoch frame-a)])
          "A's drop neither removed B's claim nor bumped B's commit epoch")
      (finally
        (restore-plain-adapter!)))))
