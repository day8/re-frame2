(ns re-frame.flows-reg-flow-destroy-linearization-test
  "`reg-flow` and a concurrent `destroy-frame!` linearize on the frame's drain
  lock. `reg-flow` pins the incarnation it selected and revalidates that token
  inside its serialized mutation; `destroy-frame!` flips liveness under the
  same lock, and its flows teardown runs before the record is dropped. So a
  destroy that wins makes the registration refuse with
  `:rf.error/flow-frame-not-live` (no ghost row, and no write into a newer
  same-id incarnation), and a registration that wins publishes its row for
  the teardown to remove.

  Each interleaving is forced by parking `reg-flow` at a read of
  `frame-incarnation-token`, which only `reg-flow` calls here; parking on
  `call-serialized-with-drain!` would park the destroy too. JVM-only: CLJS
  has no threads."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.flows :as rf.flows]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- await! [^CountDownLatch latch where]
  (is (.await latch 30 TimeUnit/SECONDS) (str "latch '" where "' did not trip within 30s")))

(defn- reg-flow-error-id
  "Run `thunk`; return the thrown `:rf.error/id`, or `::no-throw`."
  [thunk]
  (try (thunk) ::no-throw
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- parked-reg-flow
  "Start `reg-flow` of `flow-id` on `frame-id` in a future, parked at its
  `n`th incarnation-token read (1 = the pin, before the lock; 2 = the
  revalidation, under it) until `release` trips."
  [frame-id flow-id n ^CountDownLatch parked ^CountDownLatch release]
  (let [orig  rf.frame/frame-incarnation-token
        calls (atom 0)]
    (future
      (with-redefs [rf.frame/frame-incarnation-token
                    (fn [id]
                      (let [token (orig id)]
                        (when (= n (swap! calls inc))
                          (.countDown parked)
                          (await! release "reg-flow release"))
                        token))]
        (reg-flow-error-id
          #(rf/reg-flow flow-id {:frame frame-id :inputs [[:n]] :output-path [:out] :sensitive [[:out]]}
                        (fn [n] (* 2 (or n 0)))))))))

(deftest reg-flow-losing-to-destroy-in-the-mutation-window-refuses-no-ghost
  (let [parked  (CountDownLatch. 1)
        release (CountDownLatch. 1)]
    (rf/make-frame {:id :fc/scratch})
    (let [reg (parked-reg-flow :fc/scratch :ghost 1 parked release)]
      (await! parked "reg-flow parked past its pin")
      (rf.frame/destroy-frame! :fc/scratch)
      (.countDown release)
      (is (= :rf.error/flow-frame-not-live (deref reg 30000 ::timeout))))
    (is (= [{} {} true {} nil]
           [(rf.flows/flows-snapshot) (rf.flows/last-inputs-snapshot)
            (empty? (rf.flows.registry/abandoned-output-paths-snapshot :fc/scratch))
            (rf.elision/sensitive-declarations :fc/scratch) (rf.registrar/lookup :flow :ghost)])
        "no ghost row, dirty-check row, pending vacation, mark or registrar slot")))

(deftest stale-reg-flow-does-not-clobber-a-re-registered-new-incarnation
  ;; The new incarnation is live, so only token identity, not a nil check,
  ;; refuses the stale write.
  (let [parked  (CountDownLatch. 1)
        release (CountDownLatch. 1)]
    (rf/make-frame {:id :fc/reused})
    (rf/reg-event :fc/set-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (let [reg (parked-reg-flow :fc/reused :stale 1 parked release)]
      (await! parked "stale reg-flow parked past its pin")
      (rf.frame/destroy-frame! :fc/reused)
      (rf/make-frame {:id :fc/reused})
      (.countDown release)
      (is (= :rf.error/flow-frame-not-live (deref reg 30000 ::timeout))))
    (rf/dispatch-sync [:fc/set-n 5] {:frame :fc/reused})
    (is (= [{} {:n 5}] [(rf.flows/flows-snapshot) (rf.frame/frame-app-db-value :fc/reused)])
        "the new incarnation inherited no flow, and none ran")))

(deftest reg-flow-holding-the-gate-blocks-destroy-then-teardown-removes-the-row
  (let [parked  (CountDownLatch. 1)
        release (CountDownLatch. 1)]
    (rf/make-frame {:id :fc/winner})
    (let [reg       (parked-reg-flow :fc/winner :won 2 parked release)
          _         (await! parked "reg-flow inside its serialized thunk")
          destroyer (future (rf.frame/destroy-frame! :fc/winner))]
      (is (= ::still-blocked (deref destroyer 1000 ::still-blocked))
          "destroy waits on the lock reg-flow holds")
      (.countDown release)
      (is (= ::no-throw (deref reg 30000 ::timeout)))
      (is (not= ::timeout (deref destroyer 30000 ::timeout))))
    (is (= [nil {}] [(rf.frame/frame :fc/winner) (rf.flows/flows-snapshot)])
        "the frame is destroyed and its teardown removed the committed row")))

(deftest destroy-from-within-a-cold-serialized-write-does-not-deadlock
  ;; A Tool-Pair write holds the lock through `call-serialized-with-drain!`
  ;; and may destroy the same frame from inside it; the nested destroy must
  ;; run reentrantly rather than spin on the lock its own thread holds.
  (rf/make-frame {:id :fc/nested})
  (rf/reg-flow :held {:frame :fc/nested :inputs [[:n]] :output-path [:out]} identity)
  (let [done (future
               (rf.frame/call-serialized-with-drain! :fc/nested
                 (fn [] (rf.frame/destroy-frame! :fc/nested) :ok)))]
    (is (= :ok (deref done 30000 ::timeout))))
  (is (= [nil {}] [(rf.frame/frame :fc/nested) (rf.flows/flows-snapshot)])))
