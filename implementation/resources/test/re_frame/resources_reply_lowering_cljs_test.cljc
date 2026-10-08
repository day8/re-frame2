(ns re-frame.resources-reply-lowering-cljs-test
  "The pure reply-map builders in `re-frame.resources.reply`, which lift the
  managed-HTTP payload into the canonical uniform reply envelope
  (`spec/Managed-Effects.md` §The uniform reply envelope)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.reply :as rf.reply]
            [re-frame.resources.reply :as rf.resources.reply]))

(def ^:private resource-vp
  {:work/id      [:rf.work/resource [:rf.scope/global :article/by-slug {:slug "w"}] 4]
   :resource/key [:rf.scope/global :article/by-slug {:slug "w"}]
   :scope        :rf.scope/global
   :generation   4
   :rf.frame/id  :app/main})

(def ^:private mutation-vp
  {:work/id     [:rf.work/resource [:rf.mutation :form/save-1] 2]
   :instance-id :form/save-1
   :mutation-id :article/save
   :scope       :rf.scope/global
   :generation  2
   :rf.frame/id :app/main})

(def ^:private resource-opts
  {:work-kind rf.resources.reply/work-kind-resource :completed-at 1781078400456})

(deftest resource-success-reply-is-canonical
  (let [r (rf.resources.reply/success-reply resource-vp {:title "Welcome"} resource-opts)]
    (is (rf.reply/valid-reply? r) (str (rf.reply/validate-reply r)))
    (is (= {:status :ok :rf.reply/work-status :completed :rf.reply/work-kind :resource
            :value {:title "Welcome"}
            :rf.reply/work-id [:rf.work/resource [:rf.scope/global :article/by-slug {:slug "w"}] 4]
            :rf.frame/id :app/main :completed-at 1781078400456
            :correlation {:scope :rf.scope/global
                          :generation 4
                          :rf.reply/resource-key [:rf.scope/global :article/by-slug {:slug "w"}]}}
           (select-keys r [:status :rf.reply/work-status :rf.reply/work-kind :value
                           :rf.reply/work-id :rf.frame/id :completed-at :correlation])))))

(deftest resource-failure-reply-is-canonical
  (testing "a failure carries the :rf.http/* envelope and the causal :completed-at"
    (let [r (rf.resources.reply/failure-reply resource-vp {:kind :rf.http/http-5xx :status 503}
                                              resource-opts)]
      (is (rf.reply/valid-reply? r) (str (rf.reply/validate-reply r)))
      (is (= {:status :error :rf.reply/work-status :failed :rf.reply/work-kind :resource
              :error {:kind :rf.http/http-5xx :status 503} :completed-at 1781078400456}
             (select-keys r [:status :rf.reply/work-status :rf.reply/work-kind :error :completed-at])))
      (is (nil? (:value r)))))
  (testing "an :rf.http/aborted envelope lowers to :cancelled, not :error"
    (let [r (rf.resources.reply/failure-reply resource-vp {:kind :rf.http/aborted :reason :actor-destroyed}
                                              resource-opts)]
      (is (rf.reply/valid-reply? r) (str (rf.reply/validate-reply r)))
      (is (= {:status :cancelled :rf.reply/work-status :cancelled :cancelled? true
              :rf.reply/cancel-reason :actor-destroyed :completed-at 1781078400456
              :error {:kind :rf.http/aborted :reason :actor-destroyed}}
             (select-keys r [:status :rf.reply/work-status :cancelled? :rf.reply/cancel-reason
                             :completed-at :error]))))))

(deftest mutation-success-reply-is-canonical
  (let [r (rf.resources.reply/success-reply mutation-vp {:slug "w" :title "Welcome"}
                                            {:work-kind rf.resources.reply/work-kind-mutation
                                             :completed-at 1781078400456})]
    (is (rf.reply/valid-reply? r) (str (rf.reply/validate-reply r)))
    (is (= {:status :ok :rf.reply/work-kind :mutation :value {:slug "w" :title "Welcome"}
            :rf.reply/work-id [:rf.work/resource [:rf.mutation :form/save-1] 2]
            :correlation {:mutation/id :article/save
                          :instance/id :form/save-1
                          :scope       :rf.scope/global
                          :generation  2}}
           (select-keys r [:status :rf.reply/work-kind :value :rf.reply/work-id :correlation])))))

(deftest stale-reply-suppresses
  (let [carried {:work/id [:rf.work/resource [:rf.scope/global :r {}] 4] :generation 4}
        current {:work/id [:rf.work/resource [:rf.scope/global :r {}] 5] :generation 5}
        out     (rf.resources.reply/stale-reply
                  {:carried carried :current current
                   :extra   {:rf.reply/work-id (:work/id carried)
                             :work/kind :resource
                             :rf.frame/id :app/main
                             :rf.reply/stale-reason :resource/generation-mismatch}})
        r       (:reply out)]
    (is (= {:deliver? false :rf.reply/work-status :suppressed}
           (select-keys out [:deliver? :rf.reply/work-status])))
    (is (rf.reply/valid-reply? r) (str (rf.reply/validate-reply r)))
    (is (= {:status :stale :stale? true :rf.reply/stale-reason :resource/generation-mismatch}
           (select-keys r [:status :stale? :rf.reply/stale-reason])))
    (is (not (contains? r :value)) "a stale reply carries no value")
    (is (= {:rf.reply/carried carried :rf.reply/current current}
           (select-keys (:trace out) [:rf.reply/carried :rf.reply/current])))))
