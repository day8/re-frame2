(ns re-frame.frame-destroy-incarnation-jvm-test
  "Deterministic JVM barriers for incarnation-owned frame teardown."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
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
