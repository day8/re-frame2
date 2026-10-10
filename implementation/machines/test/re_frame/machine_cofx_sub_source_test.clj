(ns re-frame.machine-cofx-sub-source-test
  "A machine named entry's `{:rf/sub query-v :as fact-id}` source is a recorded
  token fact: evaluated live against the committed frame-state, re-presented
  verbatim under `:strict`, and refused when malformed or declared by an event."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.cofx :as rf.cofx]
            [re-frame.machines]
            [re-frame.machines.cofx-attach :as rf.machines.cofx-attach]
            [re-frame.machines.lifecycle-fx.registration :as rf.machines.lifecycle-fx.registration]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- error-id [f]
  (try (f) nil (catch ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- capturing
  "A machine whose :go action declares `requires` and records the `fact-id` it reads into `seen`."
  [requires fact-id seen]
  {:initial :idle
   :actions {:capture {:rf.cofx/requires requires
                       :fn (fn [{cofx :rf.cofx}] (reset! seen (fact-id cofx)) nil)}}
   :states  {:idle {:on {:go {:target :done :action :capture}}}
             :done {}}})

(deftest event-arity-rejects-sub-source
  (is (= :rf.error/cofx-request-invalid
         (error-id #(rf.cofx/parse-requires :some/evt [{:rf/sub [:a/x] :as :app/x}])))))

(deftest malformed-sub-source-rejected
  (doseq [[label source]
          [["no :as fact-id"                 {:rf/sub [:a/x]}]
           [":rf/sub not a query vector"     {:rf/sub :not-a-vector :as :app/x}]
           ["an empty query vector"          {:rf/sub [] :as :app/x}]
           ["a bare (unqualified) :as"       {:rf/sub [:a/x] :as :bare}]
           ["a key other than :rf/sub / :as" {:rf/sub [:a/x] :as :app/x :bogus 1}]]]
    (is (= :rf.error/cofx-request-invalid
           (error-id #(rf.machines.lifecycle-fx.registration/make-machine-handler (capturing [source] :app/x (atom nil)))))
        label)))

(deftest sub-fact-resolves-live-and-replays-verbatim
  ;; live, the query is evaluated against the committed app-db (true); under
  ;; :strict a recorded false wins and the sub is not re-evaluated
  (rf/reg-event :seed (fn [_ _] {:db {:editor/dirty? true}}))
  (rf/reg-sub :editor/can-submit? (fn [db _] (boolean (:editor/dirty? db))))
  (rf/dispatch-sync [:seed])
  (doseq [[id opts expected]
          [[:sub/live   {} true]
           [:sub/replay {:rf.cofx/mint-policy :strict
                         :rf.cofx {:rf/time-ms 1 :editor/can-submit false}} false]]]
    (let [seen (atom ::unset)]
      (rf/reg-machine id (capturing [{:rf/sub [:editor/can-submit?] :as :editor/can-submit}]
                                    :editor/can-submit seen))
      (rf/dispatch-sync [id [:go]] opts)
      (is (= expected @seen) (str id)))))

(deftest strict-absent-is-missing-required
  (let [m (rf.machines.cofx-attach/index-ensure-sets
            (capturing [{:rf/sub [:editor/gone?] :as :editor/gone}] :editor/gone (atom nil)))]
    (is (= :rf.error/missing-required-cofx
           (error-id #(rf.machines.cofx-attach/ensure-cofx m {:state :idle} [:go] {} nil :sub/strict :strict))))))
