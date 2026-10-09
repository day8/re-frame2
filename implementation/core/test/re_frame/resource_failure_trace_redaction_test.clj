(ns re-frame.resource-failure-trace-redaction-test
  "The on-box trace chokepoint (`re-frame.classification/project-trace-event`)
  redacts the `:rf.http/*` failure envelope — a raw server response that can
  echo submitted form fields — on the resource / mutation failure traces. The
  off-box leg is `re-frame.epoch-egress-resource-trace-test`."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.classification :as rf.classification]
            [re-frame.privacy :as rf.privacy]))

(def ^:private http-failure-envelope
  {:status    422
   :body-text "{\"auth-token\":\"on-box-secret-PII\"}"
   :detail    :rf.http/http-4xx})

(deftest failure-envelope-redacted-on-box
  ;; Only the envelope slot is summarized; every structural tag rides verbatim.
  (doseq [[operation slot structural]
          [[:rf.resource/failed      :error      {:status-before :loading :status-after :error}]
           [:rf.resource/page-failed :page-error {}]
           [:rf.mutation/failed      :error      {:mutation :m/save :instance 7}]]]
    (let [tags (merge {:rf.frame/id :rf/default
                       :frame       :rf/default
                       slot         http-failure-envelope}
                      structural)]
      (is (= (assoc tags slot rf.privacy/redacted-sentinel)
             (:tags (rf.classification/project-trace-event
                      {:operation operation :op-type :rf.event :tags tags})))
          (str operation)))))
