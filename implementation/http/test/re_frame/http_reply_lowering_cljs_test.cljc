(ns re-frame.http-reply-lowering-cljs-test
  "The pure core of managed HTTP's reply lowering in `re-frame.http.reply`,
  on both hosts: the canonical reply map for each outcome and failure
  self-identification. The real-transport round trips live in the JVM-only
  `http-reply-lowering-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.http.reply :as rf.http.reply]
            [re-frame.reply :as rf.reply]))

(def ^:private ctx
  {:request-id   :article/by-id
   :origin-event [:article/load {:id 42}]
   :attempt      1
   :frame        :app/main
   :completed-at 1781078400456})

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
