(ns re-frame.http-actor-destroy-stale-test
  "An actor-destroy abort whose reply target addresses the destroyed actor
  itself is obsolete (Managed-Effects §Cancellation): the target MUST NOT run,
  and the outcome lowers to a `:status :stale` / `:suppressed`
  `:rf.http/stale-suppressed` row. Two transport paths reach an aborted reply —
  the abort-fn's own `dispatch-aborted!`, and a completion that wins the
  once-only CAS and reclassifies the flipped abort cell — and both must apply
  the suppression.

  The host transport is replaced at `jvm-fetch`, so each test decides when the
  request completes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.transport-jvm :as rf.http.transport-jvm]
            [re-frame.interop :as rf.interop]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [java.util.concurrent CompletableFuture]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- settle-router!
  "FIFO barrier: a task queued behind a reply dispatch runs only once that
  reply's event has been handled."
  []
  (let [done (promise)]
    (rf.interop/next-tick #(deliver done true))
    (is (true? (deref done 2000 false)) "the router barrier ran")))

(defn- start-self-addressed-worker!
  "Spawn `:worker/proc#1`, whose request's `:on-failure` addresses the actor
  itself, and wait until that request is in flight."
  []
  (rf/reg-machine :worker/proc
    {:initial :idle
     :actions {:fire (fn [_]
                       {:fx [[:rf.http/managed
                              {:request    {:url "http://example.invalid/slow"}
                               :decode     :json
                               :request-id [:worker/proc :slow]
                               :on-failure [:worker/proc#1 [:self/failed]]}]]})}
     :states  {:idle    {:on {:start :running}}
               :running {:entry :fire}}})
  (rf/reg-machine :sup/flow
    {:initial :idle
     :states  {:idle    {:on {:start :working}}
               :working {:spawn {:machine-id :worker/proc :start [:start]}
                         :on    {:cancel :idle}}}})
  (rf/dispatch-sync [:sup/flow [:start]])
  (rf.test-support/poll-until #(seq (rf.http.managed/actor-in-flight-snapshot))
                              {:timeout-ms 5000 :interval-ms 10
                               :label "http-actor-destroy-stale in flight"}))

(defn- self-dispatches [traces]
  (count (filter #(and (= :rf.event/dispatched (:operation %))
                       (= :worker/proc#1 (first (:rf.event/v (:tags %)))))
                 traces)))

(defn- stale-tags [traces ks]
  (-> (filter #(= :rf.http/stale-suppressed (:operation %)) traces)
      first
      :tags
      (select-keys ks)))

(deftest obsolete-actor-bound-target-suppresses-stale-on-destroy
  (testing "destroying the actor suppresses its self-addressed reply as :stale"
    (let [traces (atom [])]
      (with-redefs [rf.http.transport-jvm/jvm-fetch (fn [_] (CompletableFuture.))]
        (try
          (rf.trace.tooling/register-listener! ::direct #(swap! traces conj %))
          (start-self-addressed-worker!)
          (let [before (self-dispatches @traces)]
            (rf/dispatch-sync [:sup/flow [:cancel]])
            (settle-router!)
            (is (= {:rf.reply/status       :stale
                    :rf.reply/work-status  :suppressed
                    :rf.reply/stale-reason :rf.http/actor-destroyed-target-obsolete
                    :rf.reply/work-id      [:rf.work/http [:worker/proc :slow] 1 1]}
                   (stale-tags @traces [:rf.reply/status :rf.reply/work-status
                                        :rf.reply/stale-reason :rf.reply/work-id])))
            (is (= before (self-dispatches @traces))
                "the destroyed actor's reply target was not dispatched"))
          (finally
            (rf.trace.tooling/unregister-listener! ::direct)))))))

(deftest completion-wins-cas-obsolete-target-suppresses-stale-on-destroy
  (testing "a completion that wins the once-only CAS after an actor-destroy flipped
            the abort cell reclassifies to :actor-destroyed and suppresses the
            self-addressed reply the same way"
    (let [traces (atom [])
          cf     (CompletableFuture.)]
      (with-redefs [rf.http.transport-jvm/jvm-fetch (fn [_] cf)]
        (try
          (rf.trace.tooling/register-listener! ::reclassified #(swap! traces conj %))
          (start-self-addressed-worker!)
          (let [before (self-dispatches @traces)
                handle (first (get (rf.http.managed/actor-in-flight-snapshot) :worker/proc#1))]
            ;; Flip the abort cell exactly as `abort-on-actor-destroy` does, but
            ;; without firing the abort-fn, so the completion wins the CAS.
            (reset! (:aborted? handle) {:reason :actor-destroyed :actor-id :worker/proc#1})
            (.complete cf {:ok? true :status 200 :status-text "OK" :headers {} :body-text "{}"})
            (settle-router!)
            (is (= {:rf.reply/status       :stale
                    :rf.reply/work-status  :suppressed
                    :rf.reply/stale-reason :rf.http/actor-destroyed-target-obsolete}
                   (stale-tags @traces [:rf.reply/status :rf.reply/work-status
                                        :rf.reply/stale-reason])))
            (is (= before (self-dispatches @traces))
                "no live :cancelled reply reached the destroyed actor's target"))
          (finally
            (rf.trace.tooling/unregister-listener! ::reclassified)))))))
