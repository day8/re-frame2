(ns re-frame.resources-stale-after-ms-registration-cljs-test
  "Registration-time validation of a resource's `:stale-after-ms` (Spec 016
  §Freshness clock contract) and `:timeout-ms`: absent or a non-negative
  number of milliseconds registers; anything else is refused with
  `:rf.error/resource-bad-spec` before it can reach attempt-time arithmetic.
  Every fn slot of a resource or scope registration takes a fn or a Var on
  both hosts, and a non-callable `:tags` is refused (Spec 016 §Resource
  registration spec)."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources.registry :as rf.resources.registry]
            [re-frame.resources.scope-registry :as rf.resources.scope-registry]))

(defn- spec-with [extra]
  (merge {:doc           "test resource"
          :scope         :rf.scope/global
          :params-schema [:map [:slug :string]]}
         extra))

(def ^:private request-fn
  (fn [_params _ctx] {:request {:method :get :url "/api/x"}}))

(use-fixtures :each
  (fn [test-fn]
    (rf.registrar/clear-kind! :resource)
    (rf.registrar/clear-kind! :resource-scope)
    (try
      (test-fn)
      (finally
        (rf.registrar/clear-kind! :resource)
        (rf.registrar/clear-kind! :resource-scope)))))

(defn- bad-spec-data
  "The `ex-data` of registering `id` with `extra` merged into the spec, or nil
  when it registers."
  [id extra]
  (try (rf.resources.registry/reg-resource id (spec-with extra) request-fn)
       nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e))))

(deftest any-other-stale-after-ms-is-a-bad-spec
  (doseq [bad [nil -1 "60000"]]
    (let [thrown (try (rf.resources.registry/reg-resource
                        :test/stale-bad (spec-with {:stale-after-ms bad}) request-fn)
                      nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
      (is (= {:rf.error/id :rf.error/resource-bad-spec :resource-id :test/stale-bad :stale-after-ms bad}
             (select-keys (ex-data thrown) [:rf.error/id :resource-id :stale-after-ms]))
          (pr-str bad))
      (is (nil? (rf.resources.registry/resource-meta :test/stale-bad))))))

(deftest any-other-timeout-ms-is-a-bad-spec
  (doseq [bad [nil -1 "2000"]]
    (is (= {:rf.error/id :rf.error/resource-bad-spec :resource-id :test/timeout-bad :timeout-ms bad}
           (select-keys (bad-spec-data :test/timeout-bad {:timeout-ms bad})
                        [:rf.error/id :resource-id :timeout-ms]))
        (pr-str bad)))
  (is (nil? (bad-spec-data :test/timeout-ok {:timeout-ms 2000}))))

(deftest a-non-callable-tags-is-a-bad-spec
  ;; A static set is the natural mistake. Registered, it would throw at the
  ;; first reply on the JVM, and on CLJS answer the data as the tags.
  (doseq [bad [#{[:article]} :article nil]]
    (is (= {:rf.error/id :rf.error/resource-bad-spec :resource-id :test/tags-bad :tags bad}
           (select-keys (bad-spec-data :test/tags-bad {:tags bad})
                        [:rf.error/id :resource-id :tags]))
        (pr-str bad))))

(defn- tags-of [_params _data] #{[:article]})
(defn- next-of [_last-page _pages] nil)
(defn- items-of [page] (:items page))
(defn- fetch [_params _ctx] {:request {:method :get :url "/api/x"}})
(defn- resolve-session [{:keys [user]} _ctx] (when user [:test/session {:user user}]))

(deftest every-fn-slot-takes-a-var-on-both-hosts
  (rf.resources.registry/reg-resource
    :test/var-slots
    (spec-with {:tags #'tags-of :infinite true :next-page-param #'next-of :page->items #'items-of})
    #'fetch)
  (is (some? (rf.resources.registry/resource-meta :test/var-slots)))
  (is (= :test/var-scope
         (rf.resources.scope-registry/reg-resource-scope
           :test/var-scope {:inputs {:user [:db [:user]]}} #'resolve-session))))
