(ns re-frame.http-reply-lowering-cljs-test
  "Host-symmetric (CLJS + JVM) conformance for the PURE core of the
  managed-HTTP lowering (rf2-zqefg3.2 / rf2-ibksxg): the canonical reply
  map (the ONE public dialect — no compat reshape), the work-id head, and
  the stale-suppression builders in `re-frame.http.reply`. Runs on the
  `npm run test:cljs` node gate (its ns matches the `cljs-test$` regexp)
  so the lowering's pure functor / schema core is exercised on the CLJS
  runtime too — the end-to-end real-transport groups live in the JVM-only
  `http-reply-lowering-test`.

  Canonical contract: `spec/Managed-Effects.md` §The uniform reply
  envelope; EP-0011; rf2-ibksxg (one canonical async-reply envelope)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.http.reply :as http-reply]
            [re-frame.reply :as reply]))

(def ^:private ctx
  {:request-id   :article/by-id
   :origin-event [:article/load {:id 42}]
   :attempt      1
   :frame        :app/main
   :completed-at 1781078400456})

(deftest work-id-and-transport-id
  (testing "HTTP work-id head [:rf.work/http logical-id issuance attempt]"
    (is (= [:rf.work/http :article/by-id 1 1] (http-reply/work-id ctx)))
    (is (= [:rf.work/http :article/load 1 1]
           (http-reply/work-id (dissoc ctx :request-id)))
        "logical-id falls back to origin event-id")
    (is (= [:rf.work/http :article/by-id 1 2]
           (http-reply/work-id (assoc ctx :attempt 2)))
        "attempt slot discriminates retries within one issuance")
    (testing "issuance slot discriminates re-issuances across supersessions (rf2-azcmd3)"
      (is (= [:rf.work/http :article/by-id 2 1]
             (http-reply/work-id (assoc ctx :issuance 2))))
      (is (not= (http-reply/work-id ctx)
                (http-reply/work-id (assoc ctx :issuance 2))))))
  (testing "frame-qualified transport request-id [:rf.req frame-id work-id]"
    (is (= [:rf.req :app/main [:rf.work/http :article/by-id 1 1]]
           (http-reply/transport-request-id :app/main (http-reply/work-id ctx))))))

(deftest suppress-builds-canonical-stale-reply
  (testing "rf2-azcmd3 — http-reply/suppress produces a :status :stale / :work/status :suppressed reply with carried/current work-id correlation, joined to :work/id"
    (let [{:keys [deliver? reply trace]}
          (http-reply/suppress ctx [:rf.work/http :article/by-id 2 1])]
      (is (false? deliver?) "a superseded attempt's app target MUST NOT run")
      (is (= :suppressed (:work/status reply)))
      (is (= :stale (:status reply)))
      (is (= :rf.http/request-id-superseded (:stale/reason reply)))
      (is (not (contains? reply :value)) "a stale reply MUST NOT carry :value")
      (is (reply/valid-reply? reply) (str (reply/validate-reply reply)))
      ;; carried = the superseded attempt's work-id (issuance 1);
      ;; current = the superseding attempt's work-id (issuance 2); =-distinct.
      (is (= [:rf.work/http :article/by-id 1 1] (:work/id (:rf.reply/carried trace))))
      (is (= [:rf.work/http :article/by-id 2 1] (:work/id (:rf.reply/current trace))))
      (is (= [:rf.work/http :article/by-id 1 1] (:work/id trace))))))

(deftest actor-destroy-obsolete-target-suppression
  (testing "rf2-yrrpe2 — actor-destroy obsolete-target predicate + canonical stale suppression (host-symmetric pure core)"
    (testing "the obsolete-target predicate: target == actor-id → obsolete; ordinary target → meaningful"
      (is (true?  (http-reply/actor-destroy-target-obsolete? :worker/proc#1 :worker/proc#1)))
      (is (false? (http-reply/actor-destroy-target-obsolete? :reply/recorder :worker/proc#1)))
      (is (false? (http-reply/actor-destroy-target-obsolete? :worker/proc#1 nil)))
      (is (false? (http-reply/actor-destroy-target-obsolete? nil :worker/proc#1))))
    (testing "actor-destroy-suppress produces a canonical :status :stale / :work/status :suppressed reply with carried work-id, no current successor"
      (let [actor-ctx {:request-id   [:worker/proc#1 :slow]
                       :origin-event [:worker/proc#1 [:rf.http/failed]]
                       :issuance     1
                       :attempt      1
                       :frame        :app/main}
            {:keys [deliver? reply trace]} (http-reply/actor-destroy-suppress actor-ctx)]
        (is (false? deliver?) "the obsolete actor-bound app target MUST NOT run")
        (is (= :suppressed (:work/status reply)))
        (is (= :stale (:status reply)))
        (is (= :rf.http/actor-destroyed-target-obsolete (:stale/reason reply)))
        (is (not (contains? reply :value)) "a stale reply MUST NOT carry :value")
        (is (reply/valid-reply? reply) (str (reply/validate-reply reply)))
        (is (= [:rf.work/http [:worker/proc#1 :slow] 1 1] (:work/id (:rf.reply/carried trace))))
        (is (nil? (:rf.reply/current trace))
            "no live successor — the actor that owned the target is gone")))))

(deftest canonical-replies-validate
  (testing ":status :ok success"
    (let [r (http-reply/success-reply ctx {:title "Welcome"})]
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))
      (is (= :ok (:status r)))
      (is (= :completed (:work/status r)))
      (is (= :http (:work/kind r)))
      (is (= {:request-id :article/by-id} (:correlation r)))
      (is (not (contains? r :request-id))
          ":request-id is correlation metadata, not a second stale key")))
  (testing ":status :error failure"
    (let [r (http-reply/failure-reply ctx {:kind :rf.http/http-5xx :status 503})]
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))
      (is (= :error (:status r)))
      (is (= :failed (:work/status r)))))
  (testing "timeout → :status :error + :work/status :timed-out (not a top-level status)"
    (let [r (http-reply/failure-reply ctx {:kind :rf.http/timeout :limit-ms 30000 :elapsed-ms 30012})]
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))
      (is (= :error (:status r)))
      (is (= :timed-out (:work/status r)))))
  (testing "abort → :status :cancelled with :rf.http/aborted :error"
    (let [r (http-reply/aborted-reply ctx {:kind :rf.http/aborted :reason :user})]
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))
      (is (= :cancelled (:status r)))
      (is (= :cancelled (:work/status r)))
      (is (true? (:cancelled? r)))
      (is (= :user (:cancel/reason r)))
      (is (= :rf.http/aborted (get-in r [:error :kind]))))))

(deftest no-public-payload-reshape
  (testing "rf2-ibksxg — the compat-reply reshape (reply->public-payload) is DELETED; every family delivers the canonical envelope verbatim, no {:kind :success/:failure} dialect"
    (is (not (contains? (ns-publics 're-frame.http.reply) 'reply->public-payload))
        "reply->public-payload must not exist — the canonical reply is the public payload")))

(deftest trace-summary-elides-wire-slots
  (testing "the canonical trace summary keeps identity facts verbatim"
    (let [r       (http-reply/success-reply ctx {:secret "x"})
          summary (http-reply/trace-reply r {:sensitive? true})]
      (is (= :ok (:status summary)))
      (is (= [:rf.work/http :article/by-id 1 1] (:work/id summary)))
      (is (= :http (:work/kind summary)))
      (testing "a sensitive request redacts the wire slots wholesale"
        (is (= :rf/redacted (:value summary)))))))
