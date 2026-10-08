(ns re-frame.resources-skeleton-cljs-test
  "Registration-time validation for `reg-resource` (Spec 016 §Resource
  registration spec), the published feature probe, the passive
  `:rf.resource/*` subs, and the framework-write authority of the
  `:rf.resource/*` event family."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.features :as rf.features]
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources :as rf.resources]
            [re-frame.resources.route]))

(defn- valid-spec []
  {:doc           "test resource"
   :scope         :rf.scope/global
   :params-schema [:map [:slug :string]]})

(def ^:private valid-request
  (fn [_params _ctx]
    {:request {:method :get :url "/api/x"}}))

(use-fixtures :each
  {:before (fn [] (rf.registrar/clear-kind! :resource))
   :after  (fn [] (rf.registrar/clear-kind! :resource))})

(defn- stored [id]
  (:rf/resource (rf/handler-meta {:source :store :kind :resource :id id})))

(deftest reg-resource-registers-under-resource-kind
  (rf.resources/reg-resource :test/article (valid-spec) valid-request)
  (is (= :rf.scope/global (:scope (stored :test/article))))
  (rf/clear :resource :test/article)
  (is (nil? (stored :test/article))))

(deftest gc-after-ms-normalizes-at-registration
  ;; Normalized at registration, so every read sees the finite default, the
  ;; auditable :never opt-out, a positive number, or a loud error.
  (doseq [[extra expected] [[{} 300000]
                            [{:gc-after-ms :never} :never]
                            [{:gc-after-ms 45000} 45000]]]
    (rf.resources/reg-resource :test/gc (merge (valid-spec) extra) valid-request)
    (is (= expected (:gc-after-ms (stored :test/gc))) (pr-str extra)))
  (doseq [bad [0 :neverr nil]]
    (is (thrown-with-msg?
          js/Error #"resource-bad-spec"
          (rf.resources/reg-resource :test/gc-bad
                                     (assoc (valid-spec) :gc-after-ms bad)
                                     valid-request))
        (pr-str bad))))

(deftest scope-policy-is-required-fail-closed
  (is (thrown-with-msg?
        js/Error #"resource-missing-scope-policy"
        (rf.resources/reg-resource :test/no-scope (dissoc (valid-spec) :scope) valid-request)))
  (is (thrown-with-msg?
        js/Error #"resource-bad-spec"
        (rf.resources/reg-resource :test/no-params (dissoc (valid-spec) :params-schema) valid-request)))
  (testing "a :request inside the metadata map is a mislocated key"
    (is (thrown-with-msg?
          js/Error #"resource-bad-spec"
          (rf.resources/reg-resource :test/no-request
                                     (assoc (valid-spec) :request valid-request)
                                     valid-request)))))

(deftest reg-resource-rejects-non-map-metadata
  (let [ex (try (rf.resources/reg-resource :test/bad-vec [] valid-request)
                nil
                (catch :default e e))]
    (is (= {:rf.error/id :rf.error/resource-bad-spec :value []}
           (select-keys (ex-data ex) [:rf.error/id :value])))))

(defn- defn-request
  [_params _ctx]
  {:request {:method :get :url "/api/defn"}})

(deftest reg-resource-rejects-non-callable-request
  ;; A keyword or map is ifn? and would be invoked to a silent nil request,
  ;; so the gate is fn?/var?; 42 is the non-ifn class.
  (doseq [bad [42 :kw {:a 1}]]
    (let [ex (try (rf.resources/reg-resource :test/nonfn-request (valid-spec) bad)
                  nil
                  (catch :default e e))]
      (is (= {:rf.error/id :rf.error/resource-bad-spec :recovery :fix-registration
              :resource-id :test/nonfn-request :value bad}
             (select-keys (ex-data ex) [:rf.error/id :recovery :resource-id :value]))
          (pr-str bad))
      (is (nil? (stored :test/nonfn-request)))))
  (doseq [[label good] [["inline fn" (fn [_p _c] {:request {:url "/i"}})]
                        ["Var of a defn" #'defn-request]]]
    (is (= :test/good-request (rf.resources/reg-resource :test/good-request (valid-spec) good)) label)
    (rf/clear :resource :test/good-request)))

(deftest scope-policy-is-exactly-two-shapes-fail-closed
  ;; The policy is :rf.scope/global or {:from-db <id>}; a reserved-namespace
  ;; typo or a literal data value is refused at registration.
  (doseq [bad [:rf.scope/glabal [:rf.scope/session {:user-id "u-1"}]]]
    (is (thrown-with-msg?
          js/Error #"resource-missing-scope-policy"
          (rf.resources/reg-resource :test/bad-scope (assoc (valid-spec) :scope bad) valid-request))
        (pr-str bad))))

(deftest feature-probe-published
  (is (true? (get-in (rf.features/features) [:resources :loaded?]))))

(deftest resource-subs-registered
  (is (= [] (remove #(rf.registrar/lookup :sub %)
                    [:rf/resource :rf.resource/data :rf.resource/status
                     :rf.resource/loading? :rf.resource/fetching?
                     :rf.resource/stale? :rf.resource/error
                     :rf.resource/refresh-error :rf.resource/has-data?
                     :rf.resource/previous-data]))))

(deftest resource-events-registered
  ;; Every :rf.resource/* event carries framework-write authority.
  (is (= [] (remove #(true? (:rf/framework-authority? (rf.registrar/lookup :event %)))
                    [:rf.resource/ensure
                     :rf.resource/refetch
                     :rf.resource/invalidate-tags
                     :rf.resource/release-owner
                     :rf.resource/clear-scope
                     :rf.resource/remove
                     :rf.resource/window-focused
                     :rf.resource/network-reconnected
                     :rf.resource.internal/succeeded
                     :rf.resource.internal/failed
                     :rf.resource.internal/stale-fired
                     :rf.resource.internal/gc-fired
                     :rf.resource.internal/stale-suppressed]))))
