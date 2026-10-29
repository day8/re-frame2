(ns day8.re-frame2-xray.panels.epoch-panel-redaction-cljs-test
  "The Epoch panel's record egress, through the REAL `:rf.xray/epoch-pipeline`
  sub: the record the view renders from carries its state snapshots projected
  under the classification each was recorded with, large values elided as the
  emit-time trace snapshot the HANDLER `:db` diff compares against is, and
  undeclared siblings raw (`epoch-panel/redact-record-db`, Spec 015
  §Retained values keep their recorded classification)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.routing]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each (xray-test-support/make-xray-runtime-fixture))

(defn- observe-and-pin!
  "Install Xray, observe `:rf/default` through the picker, optionally seed
  the history, and pin the spine RETRO on `epoch-id`."
  [history epoch-id]
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/reg-event :test/pin-epoch
    (fn [{:keys [db]} [_ id]]
      {:db (update db :focus assoc :mode :retro :epoch-id id)}))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-frame :rf/default])
    (when history
      (rf/dispatch-sync [:rf.xray/sync-epoch-history history]))
    (rf/dispatch-sync [:test/pin-epoch epoch-id])))

(defn- pipeline-record []
  (rf/with-frame :rf/xray
    (:record @(rf/subscribe [:rf.xray/epoch-pipeline]))))

(defn- large-marker? [v]
  (and (map? v) (contains? v :rf.size/large-elided)))

(deftest pipeline-record-takes-the-emit-time-policy-on-both-db-sides
  (testing "a sensitive slot reads :rf/redacted and a large slot is elided on
            BOTH db sides, so the HANDLER `:db` diff compares like with like;
            the undeclared siblings read raw"
    (rf/make-frame {:id :rf/default})
    (rf/with-frame :rf/default
      (rf/reg-classification ::classified {:sensitive [[:auth :token]]
                                           :large     [[:blob]]}))
    (observe-and-pin!
      [{:epoch-id      :e1
        :frame         :rf/default
        :committed-at  0
        :event-id      :blob/bump
        :trigger-event [:blob/bump]
        :db-before     {:auth {:token "before-secret" :user "ada"}
                        :blob (vec (range 50)) :sibling 1}
        :db-after      {:auth {:token "after-secret" :user "ada"}
                        :blob (conj (vec (range 50)) 99) :sibling 2}
        :trace-events  []}]
      :e1)
    (let [{:keys [db-before db-after] :as record} (pipeline-record)]
      (is (some? record) "PRECONDITION: the pipeline resolved the pinned record")
      (doseq [[side db] [[:db-before db-before] [:db-after db-after]]]
        (is (= :rf/redacted (get-in db [:auth :token]))
            (str side " — the sensitive slot must read :rf/redacted: " (pr-str db)))
        (is (large-marker? (:blob db))
            (str side " — the large slot must be elided as the t1 snapshot is: "
                 (pr-str (:blob db))))
        (is (= "ada" (get-in db [:auth :user]))
            (str side " — the undeclared sibling reads raw")))
      (is (= [1 2] [(:sibling db-before) (:sibling db-after)])
          "the unclassified change still diffs")
      (is (not (re-find #"secret" (pr-str [db-before db-after])))
          "a secret reached the record the view renders"))))

(deftest pipeline-record-keeps-a-past-epochs-recorded-classification
  (testing "the epoch that navigated onto a route declaring `[:query :token]`
            sensitive keeps it redacted in the record's frame-state after the
            frame has navigated away"
    (rf/make-frame {:id :rf/default})
    (rf/reg-route ::oauth {:sensitive [[:query :token]]
                           :query     [:map [:token :string] [:tab :string]]}
                  "/epoch-recorded/oauth")
    (rf/reg-route ::home {} "/epoch-recorded/home")
    (doseq [url ["/epoch-recorded/oauth?token=epoch-recorded-secret&tab=posts"
                 "/epoch-recorded/home"]]
      (rf/dispatch-sync [:rf.route/handle-url-change url {:rf.route/cause :link}]
                        {:frame :rf/default}))
    (let [[onto] (rf.epoch/epoch-history :rf/default)]
      (observe-and-pin! nil (:epoch-id onto))
      (let [query (get-in (pipeline-record)
                          [:frame-state-after :rf.db/runtime :rf.runtime/routing
                           :current :query])]
        (is (= :rf/redacted (:token query))
            (str "the recorded-sensitive token must read :rf/redacted: " (pr-str query)))
        (is (= "posts" (:tab query))
            "the unclassified sibling reads raw")))))
