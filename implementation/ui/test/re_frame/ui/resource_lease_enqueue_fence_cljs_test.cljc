(ns re-frame.ui.resource-lease-enqueue-fence-cljs-test
  "rf2-vxgfnd.150 — real-router proof that a reentrant resource release cannot
  enter the frame FIFO before the ensure envelope that triggered it.

  The rf2-vxgfnd.124 stand-in suite (resource-lease-reconcile-cljs-test) replaces
  `router/dispatch!` with a recorder that appends the event at FUNCTION ENTRY, so
  its `ensure, release` order proves only handler-ENTRY order. The production
  router does the opposite at the queue boundary: `router/dispatch!` fires its
  synchronous `:rf.event/dispatched` trace listeners BEFORE it inserts the
  envelope and schedules the drain. A listener that tears the cell down (or
  supersedes its capture) on the first ensure therefore dispatches a cleanup
  `release-owner` that reaches FIFO AHEAD of the still-mid-dispatch ensure —
  draining `release, ensure` re-attaches an owner nothing will release (a
  permanent leak on the dead cell) or resurrects a superseded owner.

  This suite drives the REAL router with real registrar-registered ensure/release
  handlers modelling an owner registry (ensure attaches, release drops; a release
  for an unattached owner is a no-op — exactly the leak seam), a real public
  synchronous `:trace` listener, and a captured `interop/next-tick`. It asserts
  the actual drained handler order and net owner state. Red before the enqueue
  fence; green after. Runs on node and JVM without the optional Resources
  artefact — the observing handlers stand in for its ensure/release owner
  accounting through the same real router queue."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.features :as features]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.resource-lease-owner :as lease-owner]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui.reactive :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(def ^:private fid :rf/default)

(defn- register-feature! [& resources]
  (doseq [resource resources]
    (registrar/register! :resource resource {:rf/resource {}})))

(defn- capture-leases [cell sites]
  (reactive/enable-resource-lifecycle! cell)
  (rf/with-frame fid
    (reactive/with-capture
     cell
     (fn []
       (doseq [[sid descriptor] sites]
         (reactive/lease-site sid descriptor))
       :element))))

(defn- commit-leases! [cell sites]
  (let [[_ capture] (capture-leases cell sites)]
    (reactive/commit-resources! cell capture)
    capture))

(defn- install-owner-registry!
  "Real ensure/release handlers on the REAL router: ensure attaches an owner,
  release drops it. A release for an unattached owner is a natural no-op — which
  is precisely why a cleanup release draining ahead of its ensure leaves the
  owner attached with nothing to release it. Records drained handler order and
  the live owner set into `order` / `active`."
  [order active]
  (rf/reg-event
   :rf.resource/ensure
   (fn [{:keys [db]} [_ {:keys [owner]}]]
     (swap! order conj [:ensure owner])
     (swap! active conj owner)
     {:db db}))
  (rf/reg-event
   :rf.resource/release-owner
   (fn [{:keys [db]} [_ {:keys [owner]}]]
     (swap! order conj [:release owner])
     (swap! active disj owner)
     {:db db})))

(defn- drain-ticks!
  "Run every captured `interop/next-tick` drain callback to quiescence (one
  same-frame drain). Bounded so a scheduler regression cannot hang the suite."
  [ticks]
  (loop [guard 0]
    (when (and (< guard 1000) (seq @ticks))
      (let [f (first @ticks)]
        (swap! ticks #(vec (rest %)))
        (f)
        (recur (inc guard))))))

(deftest reentrant-teardown-release-cannot-jump-ahead-of-its-ensure
  ;; The first ensure's synchronous trace listener tears the cell down. Teardown
  ;; marks the cell dead, discards its cleanup record, and dispatches
  ;; release-owner(A). Pre-fix that release reaches FIFO before ensure(A), so the
  ;; drain runs release-A (no-op — A is not attached yet) then ensure-A (attaches
  ;; A), leaking A on the dead cell. The enqueue fence defers the reentrant
  ;; release until ensure(A) is actually queued, so the drain runs ensure-A then
  ;; release-A and A is clean.
  (register-feature! :feed/items)
  (let [cell   (reactive/make-cell ::teardown)
        order  (atom [])
        active (atom #{})
        ticks  (atom [])
        fired? (atom false)
        cap    (commit-leases! cell [[::a {:resource :feed/items}]])]
    (install-owner-registry! order active)
    (rf/register-listener!
     :trace ::teardown-on-first-ensure
     (fn [ev]
       (when (and (= :rf.event/dispatched (:operation ev))
                  (= :rf.resource/ensure (first (get-in ev [:tags :rf.event/v])))
                  (compare-and-set! fired? false true))
         (reactive/teardown! cell))))
    (try
      (with-redefs [features/require-feature! (constantly true)
                    interop/next-tick (fn [f] (swap! ticks conj f))]
        (reactive/reconcile-resource-leases! cell cap)
        (drain-ticks! ticks))
      (finally
        (rf/unregister-listener! :trace ::teardown-on-first-ensure)))
    (is (= :dead (reactive/lifecycle cell))
        "the reentrant teardown left the cell dead")
    (is (= [:ensure :release] (mapv first @order))
        "ensure(A) enters FIFO before its reentrant teardown release — drained
         handler order is ensure then release, not release then ensure")
    (is (empty? @active)
        "owner A is attached then released — it does not leak on the dead cell")
    (is (empty? (reactive/resource-held cell))
        "the terminated reconcile publishes no held state onto the dead cell")
    (is (empty? (reactive/resource-reservations cell))
        "the terminated reconcile publishes no reservation onto the dead cell")))

(deftest reentrant-supersession-releases-cannot-jump-ahead-of-ensures
  ;; The first ensure's synchronous trace listener commits a NEW capture and
  ;; reconciles it, queuing nested ensure(A2) + release(A1) while outer ensure(A1)
  ;; is still mid-dispatch. Pre-fix FIFO is ensure(A2), release(A1), ensure(A1):
  ;; the drain resurrects the superseded A1. The fence defers the reentrant
  ;; release(A1) until every ensure is queued, so FIFO is ensure(A2), ensure(A1),
  ;; release(A1) and only the latest owner A2 survives.
  (register-feature! :feed/items)
  (let [cell   (reactive/make-cell ::supersession)
        order  (atom [])
        active (atom #{})
        ticks  (atom [])
        mints  (atom 0)
        fired? (atom false)
        cap1   (commit-leases! cell [[::a {:resource :feed/items}]])]
    (install-owner-registry! order active)
    (rf/register-listener!
     :trace ::supersede-on-first-ensure
     (fn [ev]
       (when (and (= :rf.event/dispatched (:operation ev))
                  (= :rf.resource/ensure (first (get-in ev [:tags :rf.event/v])))
                  (compare-and-set! fired? false true))
         (let [cap2 (commit-leases! cell [[::a {:resource :feed/items}]])]
           (reactive/reconcile-resource-leases! cell cap2)))))
    (try
      (with-redefs [features/require-feature! (constantly true)
                    lease-owner/mint! (fn [] [:lease (swap! mints inc)])
                    interop/next-tick (fn [f] (swap! ticks conj f))]
        (reactive/reconcile-resource-leases! cell cap1)
        (drain-ticks! ticks))
      (finally
        (rf/unregister-listener! :trace ::supersede-on-first-ensure)))
    (let [kinds          (mapv first @order)
          release-owners (into [] (comp (filter #(= :release (first %)))
                                        (map second))
                               @order)]
      (is (= [:ensure :ensure :release] kinds)
          "the nested (A2) and outer (A1) ensures both enter FIFO before the
           deferred release of the superseded owner")
      (is (= 1 (count release-owners))
          "exactly the one superseded owner is released")
      (is (not (contains? @active (first release-owners)))
          "the superseded owner A1 is not resurrected after A2 wins")
      (is (= 1 (count @active))
          "exactly the latest capture's owner remains active")
      (is (= @active (set (keys (reactive/resource-held cell))))
          "the live owner set matches the cell's held owners"))))
