(ns re-frame.frame-construction-transaction-jvm-test
  "rf2-vxgfnd.197 — deterministic JVM admission proofs for per-frame-id
  construction transactions.

  Same-id competitors fail fast, unrelated ids proceed while an adapter
  callback waits for the other constructor, and construction during the
  lifecycle-dead/pre-dissoc destroy window reports a typed loss. CountDownLatch
  and bounded future derefs expose the exact windows; no sleeps are used."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as substrate]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:dynamic *allocation-role* nil)

(defn- outcome [thunk]
  (try
    (thunk)
    (catch clojure.lang.ExceptionInfo e
      (:rf.error/id (ex-data e)))))

(deftest same-id-competing-thread-fails-fast
  (let [entered        (CountDownLatch. 1)
        release        (CountDownLatch. 1)
        original-state substrate/make-state-container]
    (with-redefs [substrate/make-state-container
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
                "the foreign thread gets the typed loss without waiting for setup")
            (finally
              (.countDown release)))
          (is (some? @owner))
          (is (= prompt @competitor)
              "the competitor's terminal outcome was already available before release")
          (is (= #{:owner}
                 (get-in (frame/frame :construction-thread/same-id) [:config :tags]))
              "only the reservation owner commits"))))))

(deftest unrelated-id-construction-proceeds-from-waited-on-adapter-callback
  (testing "A's adapter callback can wait for B without a process-wide create lock"
    (let [a-entered      (CountDownLatch. 1)
          b-done         (CountDownLatch. 1)
          a-observed-b?  (atom nil)
          original-state substrate/make-state-container]
      (with-redefs [substrate/make-state-container
                    (fn [initial]
                      (when (= :a *allocation-role*)
                        (.countDown a-entered)
                        (reset! a-observed-b?
                                (.await b-done 2000 TimeUnit/MILLISECONDS)))
                      (original-state initial))]
        (let [a (binding [*allocation-role* :a]
                  (future (rf/make-frame {:id :construction-disjoint/a})))]
          (is (.await a-entered 10 TimeUnit/SECONDS)
              "A reached its adapter callback")
          (let [b (future
                    (try
                      (rf/make-frame {:id :construction-disjoint/b})
                      (finally
                        (.countDown b-done))))]
            (is (some? @a))
            (is (some? @b))
            (is (true? @a-observed-b?)
                "B completed while A was still inside its callback")))))))

(deftest lifecycle-dead-raw-row-rejects-ordinary-and-exclusive-construction
  (let [id              :construction-destroy/dead-window
        dead-window     (CountDownLatch. 1)
        release-destroy (CountDownLatch. 1)
        hook-key        :elision/clear-warning-cache!
        original-hook   (late-bind/get-fn hook-key)]
    (rf/make-frame {:id id :tags #{:original}})
    (try
      ;; This cleanup hook runs after mark-frame-destroyed! and before the final
      ;; registry dissoc, exposing the exact lifecycle-dead raw-row window.
      (late-bind/set-fn!
        hook-key
        (fn []
          (when original-hook (original-hook))
          (.countDown dead-window)
          (.await release-destroy 10 TimeUnit/SECONDS)))
      (let [destroyer (future (frame/destroy-frame! id))]
        (is (.await dead-window 10 TimeUnit/SECONDS)
            "destroy reached lifecycle-dead before registry dissociation")
        (is (nil? (frame/frame id)) "public lookup is dead")
        (is (true? (get-in @frame/frames [id :lifecycle :destroyed?]))
            "the raw row remains present and lifecycle-dead")
        (is (= :rf.error/frame-construction-in-progress
               (outcome #(rf/make-frame {:id id :tags #{:ordinary}})))
            "ordinary construction cannot surgically mutate the dead row")
        (is (= :rf.error/frame-construction-in-progress
               (outcome #(rf/make-frame
                           {:id id
                            :tags #{:exclusive}
                            :rf.frame/must-create? true})))
            "exclusive construction gets lifecycle contention, not frame-id-taken")
        (is (= #{:original} (get-in @frame/frames [id :config :tags]))
            "neither rejected call changed the dead record")
        (.countDown release-destroy)
        (is (nil? @destroyer))
        (is (nil? (get @frame/frames id)) "destroy completes its exact removal")
        (is (some? (rf/make-frame {:id id :tags #{:post-destroy}}))
            "a clean retry after dissociation succeeds"))
      (finally
        (.countDown release-destroy)
        (late-bind/set-fn! hook-key original-hook)))))
