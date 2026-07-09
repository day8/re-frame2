(ns re-frame.resources-reply-lowering-cljs-test
  "Host-symmetric conformance for the PURE core of the resource + mutation
  lowering onto the uniform reply envelope (rf2-zqefg3.3): the canonical
  reply-map builders in `re-frame.resources.reply`. Runs on the `npm run
  test:cljs` node gate (its ns matches the `cljs-test$` regexp).

  Resources + mutations lower THROUGH managed HTTP, so the transport
  delivers its PUBLIC `{:kind …}` payload appended to the internal reply
  event; the resource family re-lifts that into the ONE canonical reply map
  every managed-async family produces — `re-frame.resources.reply` builds it
  with `:work/kind :resource` / `:mutation` and the decoded result under
  `:value` (EP-0007 / kh9jz6). The end-to-end entry / instance settlement
  through these replies is covered by `resources_managed_http_cljs_test` /
  `resources_runtime_cljs_test` / `resources_mutation_cljs_test`.

  Canonical contract: `spec/Managed-Effects.md` §The uniform reply
  envelope; EP-0011 §Resource Reply And Work Ledger / §Mutation Reply."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.reply :as reply]
            [re-frame.resources.reply :as rreply]))

(def ^:private resource-vp
  "A resource verification payload — what resource lowering stamps into the
  internal reply target (`:work/id` is the EP-0007 qualified work identity)."
  {:work/id      [:rf.work/resource [:rf.scope/global :article/by-slug {:slug "w"}] 4]
   :resource/key [:rf.scope/global :article/by-slug {:slug "w"}]
   :scope        :rf.scope/global
   :generation   4
   :rf.frame/id  :app/main})

(def ^:private mutation-vp
  "A mutation verification payload — `:work/id` reuses the resource head with
  a mutation-instance key; the carried correlation distinguishes the writer
  via `:mutation-id` / `:instance-id`."
  {:work/id     [:rf.work/resource [:rf.mutation :form/save-1] 2]
   :instance-id :form/save-1
   :mutation-id :article/save
   :scope       :rf.scope/global
   :generation  2
   :rf.frame/id :app/main})

(deftest resource-success-reply-is-canonical
  (testing "a resource :ok reply carries the decoded result under :value
            (EP-0007: :value everywhere, never :data on the reply map)"
    (let [r (rreply/success-reply resource-vp {:title "Welcome"}
                                  {:work-kind rreply/work-kind-resource :completed-at 1781078400456})]
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))
      (is (= :ok (:status r)))
      (is (= :completed (:rf.reply/work-status r)))
      (is (= :resource (:rf.reply/work-kind r)))
      (is (= {:title "Welcome"} (:value r)))
      (is (not (contains? r :data)) "the reply-map spelling is :value, not :data")
      (is (= [:rf.work/resource [:rf.scope/global :article/by-slug {:slug "w"}] 4] (:rf.reply/work-id r)))
      (is (= :app/main (:rf.frame/id r)))
      (is (= 1781078400456 (:completed-at r)))
      (testing "scope / generation / resource-key ride as :correlation, never a second work key"
        (is (= {:scope :rf.scope/global
                :generation 4
                :rf.reply/resource-key [:rf.scope/global :article/by-slug {:slug "w"}]}
               (:correlation r)))))))

(deftest resource-failure-reply-is-canonical
  (testing "a resource :error reply carries the closed :rf.http/* envelope AND
            the causal :completed-at (rf2-rl27r2 — a failed completion is still
            a managed-async completion with a reply token, so its causal
            completion time rides the reply, symmetric with success)"
    (let [r (rreply/failure-reply resource-vp {:kind :rf.http/http-5xx :status 503}
                                  {:work-kind rreply/work-kind-resource
                                   :completed-at 1781078400456})]
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))
      (is (= :error (:status r)))
      (is (= :failed (:rf.reply/work-status r)))
      (is (= :resource (:rf.reply/work-kind r)))
      (is (= {:kind :rf.http/http-5xx :status 503} (:error r)))
      (is (= 1781078400456 (:completed-at r))
          "the failure reply carries the causal :completed-at (rf2-rl27r2)")
      (is (nil? (:value r)))))
  (testing "an :rf.http/aborted envelope lowers to :status :cancelled, not
            :error, and carries the causal :completed-at (rf2-rl27r2)"
    (let [r (rreply/failure-reply resource-vp {:kind :rf.http/aborted :reason :actor-destroyed}
                                  {:work-kind rreply/work-kind-resource
                                   :completed-at 1781078400456})]
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))
      (is (= :cancelled (:status r)))
      (is (= :cancelled (:rf.reply/work-status r)))
      (is (true? (:cancelled? r)))
      (is (= :actor-destroyed (:rf.reply/cancel-reason r)))
      (is (= 1781078400456 (:completed-at r))
          "the cancellation reply carries the causal :completed-at (rf2-rl27r2)")
      (is (= {:kind :rf.http/aborted :reason :actor-destroyed} (:error r))))))

(deftest mutation-success-reply-is-canonical
  (testing "a mutation :ok reply carries :work/kind :mutation and the result
            under :value (the instance layer stores it as :result — kh9jz6)"
    (let [r (rreply/success-reply mutation-vp {:slug "w" :title "Welcome"}
                                  {:work-kind rreply/work-kind-mutation :completed-at 1781078400456})]
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))
      (is (= :ok (:status r)))
      (is (= :mutation (:rf.reply/work-kind r)))
      (is (= {:slug "w" :title "Welcome"} (:value r)))
      (is (not (contains? r :result)) "the reply-map spelling is :value, not :result")
      (is (= [:rf.work/resource [:rf.mutation :form/save-1] 2] (:rf.reply/work-id r)))
      (testing "mutation + instance ids ride as :correlation"
        (is (= {:mutation/id :article/save
                :instance/id :form/save-1
                :scope       :rf.scope/global
                :generation  2}
               (:correlation r)))))))

(deftest stale-reply-suppresses
  (testing "a superseded completion produces :status :stale and DOES NOT deliver"
    (let [carried {:work/id [:rf.work/resource [:rf.scope/global :r {}] 4] :generation 4}
          current {:work/id [:rf.work/resource [:rf.scope/global :r {}] 5] :generation 5}
          out     (rreply/stale-reply
                    {:carried carried :current current
                     :extra   {:rf.reply/work-id (:work/id carried)
                               :work/kind :resource
                               :rf.frame/id :app/main
                               :rf.reply/stale-reason :resource/generation-mismatch}})]
      (is (false? (:deliver? out)) "a stale app reply MUST NOT be delivered")
      (is (= :suppressed (:rf.reply/work-status out)))
      (let [r (:reply out)]
        (is (= :stale (:status r)))
        (is (true? (:stale? r)))
        (is (= :resource/generation-mismatch (:rf.reply/stale-reason r)))
        (is (not (contains? r :value)) "a stale reply carries no value (no app mutation)")
        (is (reply/valid-reply? r) (str (reply/validate-reply r))))
      (testing "the suppression trace carries the carried + current correlation"
        (is (= carried (get-in out [:trace :rf.reply/carried])))
        (is (= current (get-in out [:trace :rf.reply/current])))))))
