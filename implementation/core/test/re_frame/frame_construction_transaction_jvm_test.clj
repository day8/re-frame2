(ns re-frame.frame-construction-transaction-jvm-test
  "JVM admission proofs for per-frame-id construction transactions: a same-id
  competitor fails fast, an unrelated id proceeds while an adapter callback
  waits on it, a provisional row is invisible to other threads, and
  construction in the lifecycle-dead, pre-dissoc destroy window reports a typed
  loss. Latches and bounded derefs expose each window; there are no sleeps."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:dynamic *allocation-role* nil)

(defn- outcome [thunk]
  (try
    (thunk)
    (catch clojure.lang.ExceptionInfo e
      (:rf.error/id (ex-data e)))))

(deftest same-id-competing-thread-fails-fast
  (let [entered        (CountDownLatch. 1)
        release        (CountDownLatch. 1)
        original-state rf.substrate.adapter/make-state-container]
    (with-redefs [rf.substrate.adapter/make-state-container
                  (fn [initial]
                    (when (= :owner *allocation-role*)
                      (.countDown entered)
                      (.await release 10 TimeUnit/SECONDS))
                    (original-state initial))]
      (let [owner (binding [*allocation-role* :owner]
                    (future (rf/make-frame {:id :construction-thread/same-id
                                            :tags #{:owner}})))]
        (is (.await entered 10 TimeUnit/SECONDS)
            "the owner holds admission inside the adapter callback")
        (let [competitor (future
                           (outcome #(rf/make-frame
                                       {:id :construction-thread/same-id
                                        :tags #{:competitor}})))
              prompt     (deref competitor 2000 ::blocked)]
          (try
            (is (= :rf.error/frame-construction-in-progress prompt)
                "the competitor's typed loss arrives before the owner is released")
            (finally
              (.countDown release)))
          (is (= [true #{:owner}]
                 [(some? @owner)
                  (get-in (rf.frame/frame :construction-thread/same-id) [:config :tags])])
              "only the reservation owner commits"))))))

(deftest unrelated-id-construction-proceeds-from-waited-on-adapter-callback
  ;; A's adapter callback can wait for B: there is no process-wide create lock
  (let [a-entered      (CountDownLatch. 1)
        b-done         (CountDownLatch. 1)
        a-observed-b?  (atom nil)
        original-state rf.substrate.adapter/make-state-container]
    (with-redefs [rf.substrate.adapter/make-state-container
                  (fn [initial]
                    (when (= :a *allocation-role*)
                      (.countDown a-entered)
                      (reset! a-observed-b?
                              (.await b-done 2000 TimeUnit/MILLISECONDS)))
                    (original-state initial))]
      (let [a (binding [*allocation-role* :a]
                (future (rf/make-frame {:id :construction-disjoint/a})))]
        (is (.await a-entered 10 TimeUnit/SECONDS) "A reached its adapter callback")
        (let [b (future
                  (try
                    (rf/make-frame {:id :construction-disjoint/b})
                    (finally
                      (.countDown b-done))))]
          (is (= [true true true] [(some? @a) (some? @b) @a-observed-b?])
              "B completed while A was still inside its callback"))))))

(deftest foreign-thread-cannot-enumerate-a-provisional-frame
  (let [id      :construction-visibility.provisional/x
        reached (CountDownLatch. 1)
        release (CountDownLatch. 1)
        owner   (binding [rf.frame/*upsert-policy-probe*
                          (fn [candidate-id]
                            (when (= id candidate-id)
                              (.countDown reached)
                              (.await release 10 TimeUnit/SECONDS)))]
                  (future
                    (rf.frame/upsert-frame!
                      id {:rf.frame/generation :visibility/gen})))]
    (try
      (is (.await reached 10 TimeUnit/SECONDS))
      (is (= :provisional (get-in @rf.frame/frames [id :construction :state]))
          "the barrier is after provisional publication")
      ;; exact lookup, metadata, whole and prefix enumeration, and image-loaded
      ;; introspection all hide the provisional row
      (is (= [nil nil false false false]
             [(rf.frame/frame id)
              (rf.frame/frame-meta id)
              (contains? (rf.frame/frame-ids) id)
              (contains? (rf.frame/frame-ids "construction-visibility") id)
              (contains? (rf.frame/image-loaded-frame-ids) id)]))
      (finally
        (.countDown release)))
    (is (= [id true true]
           [@owner
            (contains? (rf.frame/frame-ids) id)
            (contains? (rf.frame/image-loaded-frame-ids) id)])
        "the owner finalizes after release and the id becomes enumerable")))

(deftest ensure-default-does-not-adopt-a-foreign-provisional-row
  ;; remove the fixture's :rf/default so the foreign row is a first construction
  (rf.frame/destroy-frame! :rf/default)
  (let [id      :rf/default
        reached (CountDownLatch. 1)
        release (CountDownLatch. 1)
        owner   (binding [rf.frame/*upsert-policy-probe*
                          (fn [candidate-id]
                            (when (= id candidate-id)
                              (.countDown reached)
                              (.await release 10 TimeUnit/SECONDS)))]
                  (future
                    (rf.frame/upsert-frame!
                      id {:doc "replacement default"})))]
    (try
      (is (.await reached 10 TimeUnit/SECONDS))
      (is (= [:provisional :creation :rf.error/frame-construction-in-progress]
             [(get-in @rf.frame/frames [id :construction :state])
              (get-in @rf.frame/frames [id :construction :kind])
              (outcome rf.frame/ensure-default-frame!)])
          "the fixture helper does not treat a foreign provisional row as established")
      (finally
        (.countDown release)))
    (is (= id @owner))))

(deftest lifecycle-dead-raw-row-rejects-ordinary-and-exclusive-construction
  (let [id              :construction-destroy/dead-window
        dead-window     (CountDownLatch. 1)
        release-destroy (CountDownLatch. 1)
        hook-key        :elision/clear-warning-cache!
        original-hook   (rf.late-bind/get-fn hook-key)]
    (rf/make-frame {:id id :tags #{:original}})
    (try
      ;; this cleanup hook runs after mark-frame-destroyed! and before the
      ;; registry dissoc: the lifecycle-dead raw-row window
      (rf.late-bind/set-fn!
        hook-key
        (fn []
          (when original-hook (original-hook))
          (.countDown dead-window)
          (.await release-destroy 10 TimeUnit/SECONDS)))
      (let [destroyer (future (rf.frame/destroy-frame! id))]
        (is (.await dead-window 10 TimeUnit/SECONDS))
        ;; public lookup is dead while the raw row remains; ordinary and
        ;; exclusive construction both get lifecycle contention and change nothing
        (is (= [nil true
                :rf.error/frame-construction-in-progress
                :rf.error/frame-construction-in-progress
                #{:original}]
               [(rf.frame/frame id)
                (get-in @rf.frame/frames [id :lifecycle :destroyed?])
                (outcome #(rf/make-frame {:id id :tags #{:ordinary}}))
                (outcome #(rf/make-frame {:id id
                                          :tags #{:exclusive}
                                          :rf.frame/must-create? true}))
                (get-in @rf.frame/frames [id :config :tags])]))
        (.countDown release-destroy)
        (is (= [nil nil true]
               [@destroyer
                (get @rf.frame/frames id)
                (some? (rf/make-frame {:id id :tags #{:post-destroy}}))])
            "destroy completes its removal and a clean retry succeeds"))
      (finally
        (.countDown release-destroy)
        (rf.late-bind/set-fn! hook-key original-hook)))))
