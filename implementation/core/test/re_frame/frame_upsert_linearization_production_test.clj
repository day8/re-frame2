(ns re-frame.frame-upsert-linearization-production-test
  "Frame-upsert linearization witnessed through production state, so it runs
  in the prod gate: the registry record, the generation slot, the runtime-db
  partition, and the always-on no-emit policy store. Its dev twin,
  `frame-upsert-linearization-jvm-test`, reads the dev-only retention ring and
  is skipped there. Windows open on the `rf.frame/*upsert-decide-probe*` and
  `*upsert-policy-probe*` seams, conveyed into the racing thread by `future`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.trace :as rf.trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace.tooling/clear-listeners!)
  (rf.trace/clear-frame-no-emit!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- err-id [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- window-probe
  "Trip `reached` when the transaction reaches its window for `target`,
  then block on `release`."
  [target ^CountDownLatch reached ^CountDownLatch release]
  (fn [id]
    (when (= id target)
      (.countDown reached)
      (.await release 10 TimeUnit/SECONDS))))

(deftest exclusive-create-loser-cannot-publish-over-the-owner
  (let [reached (CountDownLatch. 1)
        release (CountDownLatch. 1)
        owner   (binding [rf.frame/*upsert-decide-probe*
                          (window-probe :prod/race reached release)]
                  (future
                    (rf.frame/upsert-frame! :prod/race
                                         {:rf.frame/must-create?    true
                                          :tags                     #{:owner}
                                          :rf.trace/frame-no-emit?  false})))]
    (is (.await reached 10 TimeUnit/SECONDS) "the owner holds the id before publication")
    (is (= [:rf.error/frame-construction-in-progress false]
           [(err-id #(rf.frame/upsert-frame! :prod/race
                                             {:tags                    #{:contender}
                                              :rf.trace/frame-no-emit? true}))
            (rf.trace/frame-trace-disabled? :prod/race)])
        "the contender loses at admission and publishes no suppression policy")
    (.countDown release)
    ;; the owner's record, policy and container are what got published
    (is (= [:prod/race #{:owner} false true]
           [@owner
            (get-in (rf.frame/frame :prod/race) [:config :tags])
            (rf.trace/frame-trace-disabled? :prod/race)
            (some? (rf.frame/frame-state-container :prod/race))]))))

(deftest failed-reregistration-rolls-back-to-the-intervening-generation
  ;; A staged re-registration pauses provisional; a reprojection swaps only the
  ;; generation in that window; the registration hook then fails. Rollback
  ;; restores the config, policy authority and revision without erasing the
  ;; intervening generation or the runtime write made during the callback.
  (let [id            :prod/rollback
        hook-key      :routing/on-frame-registered!
        original-hook (rf.late-bind/get-fn hook-key)
        reached       (CountDownLatch. 1)
        release       (CountDownLatch. 1)]
    (rf.frame/upsert-frame! id
                         {:tags                    #{:prior}
                          :rf.frame/generation     :prior-gen
                          :rf.trace/frame-no-emit? true})
    (let [prior-record       (rf.frame/frame id)
          prior-config       (:config prior-record)
          prior-policy-token (:trace-policy-token prior-record)
          prior-revision     (get-in prior-record [:construction :revision])]
      (try
        (rf.late-bind/set-fn!
          hook-key
          (fn [candidate-id]
            (when (= id candidate-id)
              (rf.frame/replace-runtime-db! id {:foreign-runtime true})
              (throw (ex-info "registration hook failed"
                              {:test/outcome :hook-failed})))))
        (let [owner (binding [rf.frame/*upsert-policy-probe*
                              (window-probe id reached release)]
                      (future
                        (try
                          (rf.frame/upsert-frame!
                            id {:tags                    #{:failed}
                                :rf.frame/generation     :failed-gen
                                :rf.trace/frame-no-emit? false})
                          :unexpected-success
                          (catch clojure.lang.ExceptionInfo e
                            (:test/outcome (ex-data e))))))]
          (try
            (is (.await reached 10 TimeUnit/SECONDS)
                "the constructor staged its provisional revision")
            ;; the resolving readers decline a provisional record, so these
            ;; read the production registry atom directly
            (is (= [:provisional :failed-gen]
                   [(get-in @rf.frame/frames [id :construction :state])
                    (get-in @rf.frame/frames [id :generation])]))
            (rf.frame/set-generation! id :foreign-gen)
            (finally
              (.countDown release)))
          (is (= [:hook-failed :foreign-gen {:foreign-runtime true} prior-config true true true]
                 [@owner
                  (rf.frame/frame-generation id)
                  (rf.frame/frame-runtime-db-value id)
                  (:config (rf.frame/frame id))
                  (rf.trace/frame-trace-disabled? id)
                  (identical? prior-policy-token (:trace-policy-token (rf.frame/frame id)))
                  (identical? prior-revision
                              (get-in (rf.frame/frame id) [:construction :revision]))])))
        (finally
          (.countDown release)
          (rf.late-bind/set-fn! hook-key original-hook))))))
