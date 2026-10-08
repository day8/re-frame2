(ns re-frame.resources-mutation-classification-cljs-test
  "A mutation's `:sensitive` declaration governs the egress of its instance,
  its continuation reply and its execute-event payload, while the causal write
  and the durable instance keep the raw value."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.classification :as rf.classification]
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.elision :as rf.elision]
   [re-frame.privacy :as rf.privacy]
   [re-frame.resources.classification :as rf.resources.classification]
   [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
   [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
   [re-frame.resources]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture [f]
  (reset! last-managed-args nil)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-transport-fixture)

;; A unique sentinel, so a leak anywhere in a projected value is unambiguous.
(def ^:private PW "PW-SENTINEL-7f3a91")

(def ^:private redacted-params {:slug "w" :password rf.privacy/redacted-sentinel})

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- reg-secret-mutation! []
  (rf/clear :mutation :m/secret)
  (rf/reg-mutation :m/secret
    {:params-schema [:map [:slug :string] [:password {:optional true} [:maybe :string]]]
     :sensitive     [[:params :password]]}
    (fn [{:keys [slug password]} _]
      {:request {:method :put :url (str "/x/" slug) :body {:slug slug :password password}}})))

(deftest reconcile-mutation-preserves-foreign-owner
  ;; The registry is a multi-owner union: an :effect-sourced declaration must
  ;; survive the mutation reconcile.
  (reg-secret-mutation!)
  (let [rdb (-> {rf.resources.mutation-runtime/mutations-key
                 {(rf.resources.mutation-runtime/instance-key-id :i1)
                  (rf.resources.mutation-runtime/empty-instance :m/secret :i1 {:params {:slug "w" :password PW}})}}
                (assoc-in [:rf.runtime/elision :sensitive-declarations [:app :token]]
                          #{{:source :effect}}))
        out (rf.resources.classification/reconcile-mutation-registry rdb rf.resources.mutation-registry/mutation-meta)]
    (is (= #{{:source :effect}}
           (get-in out [:rf.runtime/elision :sensitive-declarations [:app :token]])))))

(deftest execute-lowers-instance-and-redacts-egress-and-continuation
  (reg-secret-mutation!)
  (let [replied (atom nil)
        traces  (atom [])]
    (rf/reg-event :m/replied (fn [_ [_ reply]] (reset! replied reply) {}))
    (rf.trace.tooling/register-listener! ::rec (fn [ev] (swap! traces conj ev)))
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/secret
                        :params   {:slug "w" :password PW}
                        :instance :i1
                        :reply-to [:m/replied]}])
    (is (= PW (get-in @last-managed-args [:request :body :password])) "the causal write reads the raw value")
    (rf/dispatch-sync (conj (:on-success @last-managed-args) {:status :ok :value {:ok true}}))
    (rf.trace.tooling/unregister-listener! ::rec)
    (let [k-id (rf.resources.mutation-runtime/instance-key-id :i1)
          inst (get-in (runtime-db) (rf.resources.mutation-runtime/instance-path :i1))]
      (is (= PW (get-in inst [:params :password])) "the durable instance keeps the raw value")
      (is (= #{{:source :mutation}}
             (get (rf.elision/sensitive-declarations :rf/default)
                  [:rf.runtime/mutations k-id :params :password])))
      (testing "the off-box egress walk redacts the instance"
        (let [proj (rf.elision/elide-wire-value inst {:frame :rf/default
                                                      :path [:rf.runtime/mutations k-id]
                                                      :rf.egress/include-digests? true})]
          (is (= redacted-params (:params proj)))
          (is (not (str/includes? (pr-str proj) PW))))))
    (testing "the continuation reply redacts the param and keeps the result"
      (is (= redacted-params (:params @replied)))
      (is (= {:ok true} (:value @replied)))
      (is (not (str/includes? (pr-str @replied) PW))))
    (testing "no :rf.mutation/* trace row carries the raw value"
      (doseq [ev @traces
              :when (and (keyword? (:operation ev))
                         (= "rf.mutation" (namespace (:operation ev))))]
        (is (not (str/includes? (pr-str (:tags ev)) PW)) (str (:operation ev)))))
    (testing "the live :rf.event/v slot of the execute event is projected"
      (let [vs (->> @traces
                    (keep #(get-in % [:tags :rf.event/v]))
                    (filter #(and (vector? %) (= :rf.mutation/execute (first %)))))]
        (is (seq vs))
        (doseq [v vs]
          (is (= redacted-params (get-in v [1 :params]))))))
    (testing "the failure continuation redacts too"
      (reset! replied nil)
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :m/secret
                          :params   {:slug "w" :password PW}
                          :instance :i2
                          :reply-to [:m/replied]}])
      (rf/dispatch-sync (conj (:on-failure @last-managed-args)
                              {:status :error :error {:status 422 :body "nope"}}))
      (is (= redacted-params (:params @replied)))
      (is (not (str/includes? (pr-str @replied) PW))))
    (testing "clear drops the lowered instance declaration"
      (rf/dispatch-sync [:rf.mutation/clear {:instance :i1}])
      (is (empty? (get (rf.elision/sensitive-declarations :rf/default)
                       [:rf.runtime/mutations (rf.resources.mutation-runtime/instance-key-id :i1) :params :password]))))))

(deftest project-execute-event-args-scope-rooted-decl
  (rf/clear :mutation :m/scoped)
  (rf/reg-mutation :m/scoped
    {:params-schema [:map [:slug :string]]
     :sensitive     [[:scope :tenant]]}
    (fn [_ _] {:request {:method :get :url "/x"}}))
  (is (= {:tenant rf.privacy/redacted-sentinel :region "r"}
         (:scope (rf.resources.classification/project-execute-event-args
                   {:mutation :m/scoped :params {:slug "w"}
                    :scope    {:tenant PW :region "r"}}
                   rf.resources.mutation-registry/mutation-meta)))))

(deftest project-execute-event-args-fail-open
  ;; No registration to read a declaration off: the payload rides unchanged.
  (rf/clear :mutation :m/ghost)
  (let [args {:mutation :m/ghost :params {:password PW}}]
    (is (identical? args (rf.resources.classification/project-execute-event-args
                           args rf.resources.mutation-registry/mutation-meta))))
  (is (= :not-a-map (rf.resources.classification/project-execute-event-args
                      :not-a-map rf.resources.mutation-registry/mutation-meta))))

(deftest project-execute-event-args-reply-to-rides-target-classification
  (reg-secret-mutation!)
  (rf/reg-event :m/reply-target
    {:sensitive [[:cb-secret]]}
    (fn [{:keys [db]} _] {:db db}))
  (let [out (rf.resources.classification/project-execute-event-args
              {:mutation :m/secret
               :params   {:slug "w" :password PW}
               :reply-to [:m/reply-target {:cb-secret PW :tag "t"}]}
              rf.resources.mutation-registry/mutation-meta)]
    (is (= {:cb-secret rf.privacy/redacted-sentinel :tag "t"} (get-in out [:reply-to 1])))
    (is (not (str/includes? (pr-str out) PW)))))

(deftest core-trace-slots-project-execute-payload
  ;; The :rf.event/v slot and a nested [:dispatch [:rf.mutation/execute …]] fx
  ;; entry go through the same core chokepoint.
  (reg-secret-mutation!)
  (let [payload {:mutation :m/secret :params {:slug "w" :password PW}}
        disp    (rf.classification/project-trace-event
                  {:operation :rf.event/dispatched
                   :tags {:frame       :rf/default
                          :rf.event/v [:rf.mutation/execute payload]}})
        agg     (rf.classification/project-trace-event
                  {:operation :rf.fx/do-fx
                   :tags {:frame        :rf/default
                          :rf.event/fx [[:dispatch [:rf.mutation/execute payload]]]}})]
    (is (= {:mutation :m/secret :params redacted-params} (get-in disp [:tags :rf.event/v 1])))
    (is (= redacted-params (get-in agg [:tags :rf.event/fx 0 1 1 :params])))
    (is (not (str/includes? (pr-str [disp agg]) PW)))))
