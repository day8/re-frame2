(ns re-frame.http-reply-lowering-cljs-test
  "The pure core of managed HTTP's reply lowering in `re-frame.http.reply`,
  on both hosts: the work-id head, the canonical reply map for each outcome,
  actor-destroy suppression, failure self-identification and the trace
  summary. The real-transport round trips live in the JVM-only
  `http-reply-lowering-test`."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.http.reply :as rf.http.reply]
            [re-frame.reply :as rf.reply]))

(def ^:private ctx
  {:request-id   :article/by-id
   :origin-event [:article/load {:id 42}]
   :attempt      1
   :frame        :app/main
   :completed-at 1781078400456})

(deftest work-id-head
  (are [c id] (= id (rf.http.reply/work-id c))
    ctx                              [:rf.work/http :article/by-id 1 1]
    (dissoc ctx :request-id)         [:rf.work/http [:rf.http/anonymous :article/load] 1 1]
    (assoc ctx :issuance 2 :attempt 3) [:rf.work/http :article/by-id 2 3]))

(deftest actor-destroy-obsolete-target-suppression
  (are [target actor-id obsolete?] (= obsolete? (rf.http.reply/actor-destroy-target-obsolete? target actor-id))
    :worker/proc#1  :worker/proc#1 true
    :reply/recorder :worker/proc#1 false
    :worker/proc#1  nil            false)
  (let [{:keys [deliver? reply trace]}
        (rf.http.reply/actor-destroy-suppress {:request-id   [:worker/proc#1 :slow]
                                               :origin-event [:worker/proc#1 [:rf.http/failed]]
                                               :issuance     1
                                               :attempt      1
                                               :frame        :app/main})]
    (is (false? deliver?))
    (is (= {:status :stale :rf.reply/work-status :suppressed
            :rf.reply/stale-reason :rf.http/actor-destroyed-target-obsolete}
           (select-keys reply [:status :rf.reply/work-status :rf.reply/stale-reason :value])))
    (is (= [[:rf.work/http [:worker/proc#1 :slow] 1 1] nil]
           [(get-in trace [:rf.reply/carried :work/id]) (:rf.reply/current trace)])
        "carried work-id, and no live successor")))

(deftest canonical-replies-validate
  (testing ":status :ok success, with the response's wire facts riding :meta verbatim"
    (let [meta* {:status 200 :status-text "OK"
                 :headers {"content-type" "application/json" "set-cookie" ["a=1; Path=/" "b=2; Path=/"]}}
          r     (rf.http.reply/success-reply ctx {:title "Welcome"} meta*)]
      (is (rf.reply/valid-reply? r) (str (rf.reply/validate-reply r)))
      (is (= {:status :ok :value {:title "Welcome"} :meta meta*
              :rf.reply/work-status :completed :rf.reply/work-kind :http
              :rf.reply/work-id [:rf.work/http :article/by-id 1 1] :rf.frame/id :app/main
              :completed-at 1781078400456 :correlation {:request-id :article/by-id}}
             (select-keys r [:status :value :meta :rf.reply/work-status :rf.reply/work-kind
                             :rf.reply/work-id :rf.frame/id :completed-at :correlation :request-id])))
      (is (not-any? #(contains? % :meta) [(rf.http.reply/success-reply ctx {:v 1})
                                          (rf.http.reply/success-reply ctx {:v 1} nil)])
          "absent metadata is omitted, never fabricated")))
  (testing ":status :error failure"
    (let [r (rf.http.reply/failure-reply ctx {:kind :rf.http/http-5xx :status 503})]
      (is (rf.reply/valid-reply? r) (str (rf.reply/validate-reply r)))
      (is (= {:status :error :rf.reply/work-status :failed :error {:kind :rf.http/http-5xx :status 503}}
             (select-keys r [:status :rf.reply/work-status :error])))))
  (testing "timeout is :status :error with :rf.reply/work-status :timed-out"
    (let [r (rf.http.reply/failure-reply ctx {:kind :rf.http/timeout :limit-ms 30000 :elapsed-ms 30012})]
      (is (rf.reply/valid-reply? r) (str (rf.reply/validate-reply r)))
      (is (= [:error :timed-out] ((juxt :status :rf.reply/work-status) r)))))
  (testing "abort is :status :cancelled with an :rf.http/aborted :error"
    (let [r (rf.http.reply/failure-reply ctx {:kind :rf.http/aborted :reason :user})]
      (is (rf.reply/valid-reply? r) (str (rf.reply/validate-reply r)))
      (is (= [:cancelled :cancelled true :user :rf.http/aborted]
             ((juxt :status :rf.reply/work-status :cancelled? :rf.reply/cancel-reason (comp :kind :error)) r))))))

(deftest self-identify-failure-stamps-request-identity
  ;; The retry-exhausted, 4xx and aborted stamps are pinned end to end in
  ;; http-reply-lowering-test; this is the anonymous, no-retry shape.
  (is (= {:kind :rf.http/transport :message "boom" :request {:method :post :url "/x"}
          :request-id nil :attempt 1 :work/id [:rf.work/http [:rf.http/anonymous :e] 1 1]}
         (select-keys (rf.http.reply/self-identify-failure
                        {:kind :rf.http/transport :message "boom"}
                        {:method :post :url "/x" :request-id nil :origin-event [:e] :attempt 1})
                      [:kind :message :request :request-id :attempt :max-attempts :work/id]))))

(deftest trace-summary-elides-wire-slots
  (is (= {:status :ok :rf.reply/work-id [:rf.work/http :article/by-id 1 1]
          :rf.reply/work-kind :http :value :rf/redacted}
         (-> (rf.http.reply/success-reply ctx {:secret "x"})
             (rf.http.reply/trace-reply {:sensitive? true})
             (select-keys [:status :rf.reply/work-id :rf.reply/work-kind :value])))
      "a sensitive request's summary keeps identity facts and redacts the wire slots"))
