(ns re-frame.epoch-silencing-lineage-aba-test
  "A destroyed predecessor A's delayed silence is decided per identity, not by
  whether A won its compare-owned store cleanup: when same-id B claims the
  stores but delivers nothing, A LOSES the comparison yet still owes the only
  silence.

  A is paused at its post-dissoc `:epoch/on-frame-destroyed` hook by a
  late-bind wrapper, so the real destroy recipe runs end to end."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks.
            [re-frame.epoch]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- executor-barrier!
  "Wait until work already submitted through `interop/next-tick` has run."
  []
  (let [latch (CountDownLatch. 1)]
    (rf.interop/next-tick #(.countDown latch))
    (is (.await latch 5 TimeUnit/SECONDS)
        "the executor reached the deterministic barrier")))

(defn- silences-for [silencings cb]
  (count (filter #(= cb (:cb-id (:tags %))) @silencings)))

(defn- record-silences! []
  (let [silencings (atom [])]
    (rf/register-listener! :trace ::silencing
      (fn [ev]
        (when (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
          (swap! silencings conj ev))))
    silencings))

(defn- pause-first-destroy-hook!
  "Wrap `:epoch/on-frame-destroyed` so `id`'s first call parks until `release`
  after counting down `reached`. Returns the original hook for restoring."
  [id reached release]
  (let [original (rf.late-bind/get-fn :epoch/on-frame-destroyed)
        first?   (atom true)]
    (rf.late-bind/set-fn! :epoch/on-frame-destroyed
      (fn [& args]
        (when (and (= id (first args)) (compare-and-set! first? true false))
          (.countDown ^CountDownLatch reached)
          (.await ^CountDownLatch release 10 TimeUnit/SECONDS))
        (when original (apply original args))))
    original))

(deftest predecessor-silences-after-successor-claims-without-delivery
  (let [id             :vxgfnd265/claim-idle
        cb             ::vxgfnd265-claim-idle-cb
        a-after-dissoc (CountDownLatch. 1)
        release-a      (CountDownLatch. 1)]
    (rf/reg-event :vxgfnd265/claim-idle-settle
      (fn [{:keys [db]} _] {:db (assoc db :a true)}))
    (rf/reg-event :vxgfnd265/claim-idle-destroy
      (fn [_ _] (rf.frame/destroy-frame! id) {:db {:owner :a-tail}}))
    (rf/make-frame {:id id})
    (rf/register-listener! :epoch cb (fn [_] nil))
    (rf/dispatch-sync [:vxgfnd265/claim-idle-settle] {:frame id})
    (let [silencings (record-silences!)
          original   (pause-first-destroy-hook! id a-after-dissoc release-a)]
      (try
        (let [dispatch-a (future
                           (rf/dispatch-sync [:vxgfnd265/claim-idle-destroy] {:frame id}))]
          (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
              "A is paused at its post-dissoc epoch hook")
          ;; Same-id B claims the stores (as a pre-first-epoch render/backfill
          ;; does) but settles nothing, so cb never observes B.
          (rf/make-frame {:id id})
          (rf.epoch.state/claim-frame-owner! id (rf.frame/frame-incarnation-token id))
          (.countDown release-a)
          (deref dispatch-a 5000 ::timeout)
          (executor-barrier!)
          (is (= 1 (silences-for silencings cb))
              "A emits its one owed silence for cb — B claimed but never delivered"))
        (finally
          (.countDown release-a)
          (when (rf.frame/frame id) (rf.frame/destroy-frame! id))
          (rf.late-bind/set-fn! :epoch/on-frame-destroyed original))))))
