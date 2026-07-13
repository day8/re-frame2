(ns re-frame.frame-destroy-incarnation-jvm-test
  "Deterministic JVM barriers for incarnation-owned frame teardown."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest expected-incarnation-token-controls-destroy-authority
  (rf/make-frame {:id :destroy/token})
  (let [live-token (frame/frame-incarnation-token :destroy/token)]
    (is (nil? (frame/destroy-frame! :destroy/token (atom false)))
        "a mismatched token is a silent no-op")
    (is (identical? live-token
                    (frame/frame-incarnation-token :destroy/token))
        "a mismatched token leaves the live incarnation unchanged")
    (is (nil? (frame/destroy-frame! :destroy/token live-token))
        "the matching token keeps destroy-frame!'s nil return contract")
    (is (nil? (frame/frame :destroy/token))
        "the matching token destroys its incarnation")))

(deftest stale-destroy-revalidates-after-candidate-capture
  ;; Pause the expected-token destroy AFTER it captures incarnation A. Another
  ;; actor replaces the id with B before the destroy enters drain serialization.
  ;; On resume, core must revalidate the token under the lifecycle gate and no-op
  ;; rather than applying A's stale authority to B.
  (rf/make-frame {:id :destroy/race})
  (let [token-a   (frame/frame-incarnation-token :destroy/race)
        captured  (CountDownLatch. 1)
        release   (CountDownLatch. 1)
        probe-var (ns-resolve 're-frame.frame '*destroy-claim-probe*)
        stale     (with-bindings
                    {probe-var
                     (fn [id token]
                       (when (and (= :destroy/race id)
                                  (identical? token-a token))
                         (.countDown captured)
                         (.await release 10 TimeUnit/SECONDS)))}
                    (future
                      (frame/destroy-frame! :destroy/race token-a)))]
    (is (.await captured 10 TimeUnit/SECONDS)
        "the stale destroy captured incarnation A before the replacement")
    (frame/destroy-frame! :destroy/race)
    (rf/make-frame {:id :destroy/race})
    (let [token-b (frame/frame-incarnation-token :destroy/race)]
      (is (and (some? token-b) (not (identical? token-a token-b)))
          "the actor installed a distinct incarnation B")
      (.countDown release)
      (is (nil? @stale) "the stale expected-token destroy returns a silent no-op")
      (is (identical? token-b
                      (frame/frame-incarnation-token :destroy/race))
          "incarnation B survives stale teardown unchanged"))))

(deftest concurrent-duplicate-destroy-is-a-non-blocking-no-op
  (rf/make-frame {:id :destroy/duplicate
                  :on-destroy [:destroy/duplicate-cleanup]})
  (let [cleanup-runs (atom 0)
        claimed      (CountDownLatch. 1)
        release      (CountDownLatch. 1)
        original     (late-bind/get-fn :ui/on-frame-destroyed!)]
    (rf/reg-event :destroy/duplicate-cleanup
      (fn [_ _]
        (swap! cleanup-runs inc)
        {}))
    (try
      ;; The UI hook is after claim publication and before lifecycle-dead
      ;; publication. Hold the winning destroy there while a second thread
      ;; attempts the same incarnation's destroy.
      (late-bind/set-fn!
        :ui/on-frame-destroyed!
        (fn [id]
          (when original (original id))
          (when (= :destroy/duplicate id)
            (.countDown claimed)
            (.await release 10 TimeUnit/SECONDS))))
      (let [winner (future (frame/destroy-frame! :destroy/duplicate))]
        (is (.await claimed 10 TimeUnit/SECONDS)
            "the winning destroy published its claim")
        (let [duplicate (future (frame/destroy-frame! :destroy/duplicate))]
          (is (nil? (deref duplicate 5000 ::timeout))
              "the duplicate observes the claim and returns without waiting for teardown"))
        (.countDown release)
        (is (nil? (deref winner 5000 ::timeout))
            "the winning destroy preserves the nil return contract"))
      (finally
        (.countDown release)
        (late-bind/set-fn! :ui/on-frame-destroyed! original)))
    (is (= 1 @cleanup-runs)
        "the user cleanup event runs exactly once")
    (is (nil? (frame/frame :destroy/duplicate))
        "the claimed incarnation is fully destroyed")))

(deftest fresh-same-id-destroy-replaces-stale-marker-token-safely
  ;; Pause A after its registry dissoc but before its terminal finally. Install
  ;; B under the reused id, claim B's destroy, then let A's finally run while B
  ;; is paused pre-liveness-flip. B must be destroyable despite A's stale marker,
  ;; and A's finally must not erase B's replacement marker.
  (let [id              :destroy/marker-overlap
        a-after-dissoc  (CountDownLatch. 1)
        release-a       (CountDownLatch. 1)
        b-claimed       (CountDownLatch. 1)
        release-b       (CountDownLatch. 1)
        first-epoch?    (atom true)
        b-installed?    (atom false)
        cleanup-runs    (atom 0)
        original-epoch  (late-bind/get-fn :epoch/on-frame-destroyed)
        original-ui     (late-bind/get-fn :ui/on-frame-destroyed!)]
    (rf/make-frame {:id id})
    (try
      (late-bind/set-fn!
        :epoch/on-frame-destroyed
        (fn [& args]
          ;; The epoch hook is after dissoc-frame!. Hold only A's first call.
          (when (and (= id (first args))
                     (compare-and-set! first-epoch? true false))
            (.countDown a-after-dissoc)
            (.await release-a 10 TimeUnit/SECONDS))
          (when original-epoch
            (apply original-epoch args))))
      (late-bind/set-fn!
        :ui/on-frame-destroyed!
        (fn [frame-id]
          (when original-ui (original-ui frame-id))
          ;; The UI hook is after claim publication and before B's lifecycle
          ;; flip. Hold every B teardown attempt here: an erroneously-authorised
          ;; duplicate will block, making A-finally marker erasure observable.
          (when (and (= id frame-id) @b-installed?)
            (.countDown b-claimed)
            (.await release-b 10 TimeUnit/SECONDS))))

      (let [destroy-a (future (frame/destroy-frame! id))]
        (is (.await a-after-dissoc 10 TimeUnit/SECONDS)
            "incarnation A is paused post-dissoc with its claim marker live")
        (rf/reg-event :destroy/marker-overlap-cleanup
          (fn [_ _]
            (swap! cleanup-runs inc)
            {}))
        (rf/make-frame {:id id
                        :on-destroy [:destroy/marker-overlap-cleanup]})
        (let [token-b (frame/frame-incarnation-token id)]
          (reset! b-installed? true)
          (let [destroy-b (future (frame/destroy-frame! id token-b))]
            (is (.await b-claimed 10 TimeUnit/SECONDS)
                "fresh B replaces A's stale marker and claims its own destroy")

            ;; A now reaches its terminal cleanup while B's distinct claim is
            ;; active. Correct compare-remove leaves B's marker untouched.
            (.countDown release-a)
            (is (nil? (deref destroy-a 5000 ::timeout))
                "A finishes while B remains paused under its own claim")
            (let [duplicate-b (future (frame/destroy-frame! id token-b))]
              (is (nil? (deref duplicate-b 2000 ::timeout))
                  "A's finally did not erase B's marker; duplicate B is a prompt no-op"))

            (.countDown release-b)
            (is (nil? (deref destroy-b 5000 ::timeout))
                "B's owning destroy completes")
            (is (= 1 @cleanup-runs)
                "B cleanup runs once; no duplicate teardown acquired authority")
            (is (nil? (frame/frame id)) "the reused id is fully destroyed"))))
      (finally
        (.countDown release-a)
        (.countDown release-b)
        (late-bind/set-fn! :epoch/on-frame-destroyed original-epoch)
        (late-bind/set-fn! :ui/on-frame-destroyed! original-ui)))))
