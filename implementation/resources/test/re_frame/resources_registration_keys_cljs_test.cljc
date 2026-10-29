(ns re-frame.resources-registration-keys-cljs-test
  "`reg-resource` and `reg-mutation` classify their metadata keys through the
  shared registration-key classifier (Conventions §No silent swallow): a bare
  key outside the kind's vocabulary, such as the typo `:stale-after`, warns
  `:rf.warning/unknown-registration-key` and still registers; every key Spec
  016 documents registers silently; a namespaced key passes; the retired
  `:spec` throws. The warning is a dev-trace emit, so its assertions sit in
  `(when rf.interop/debug-enabled? ...)` arms."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
            [re-frame.resources.registry :as rf.resources.registry]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (fn [test-fn]
    (rf.registrar/clear-kind! :resource)
    (rf.registrar/clear-kind! :mutation)
    (try
      (test-fn)
      (finally
        (rf.registrar/clear-kind! :resource)
        (rf.registrar/clear-kind! :mutation)))))

(def ^:private request-fn
  (fn [_params _ctx] {:request {:method :get :url "/api/x"}}))

(def ^:private every-resource-key
  "One valid value for every key Spec 016 documents on `reg-resource`."
  {:doc                "every documented key"
   :scope              :rf.scope/global
   :params-schema      [:map [:slug :string]]
   :data-schema        [:map]
   :tags               (fn [_params _data] #{})
   :stale-after-ms     60000
   :gc-after-ms        300000
   :poll-interval-ms   5000
   :timeout-ms         10000
   :sensitive          [[:data :ssn]]
   :large              [[:data :avatar]]
   :sensitive?         false
   :large?             false
   :infinite           true
   :next-page-param    (fn [_last-page _pages] nil)
   :initial-page-param 0
   :page->items        :items
   :refetch            {:refetch-all-pages? false :refetch-window 2}})

(def ^:private every-mutation-key
  "One valid value for every key Spec 016 documents on `reg-mutation`."
  {:doc               "every documented key"
   :params-schema     [:map [:slug :string]]
   :scope             :rf.scope/global
   :invalidates       (fn [_params _result] #{})
   :patches           (fn [_params _result] {})
   :populates         (fn [_params _result] {})
   :removes           (fn [_params _result] [])
   :invalidate-timing :after-success
   :optimistic        (fn [_params] {})
   :optimistic-tags   (fn [_params] [])
   :on-conflict       :invalidate
   :sensitive         [[:params :token]]
   :large             [[:data :avatar]]
   :sensitive?        false
   :large?            false})

(def ^:private minimal-resource
  {:scope :rf.scope/global :params-schema [:map [:slug :string]]})

(def ^:private minimal-mutation
  {:params-schema [:map [:slug :string]]})

(defn- reg-resource! [id metadata]
  (rf.resources.registry/reg-resource id metadata request-fn))

(defn- reg-mutation! [id metadata]
  (rf.resources.mutation-registry/reg-mutation id metadata request-fn))

(defn- unknown-key-warnings
  "Run `f`; return the `[kind id unknown-keys]` of each
  `:rf.warning/unknown-registration-key` it emitted."
  [f]
  (let [acc (atom [])
        lid (keyword "resources-registration-keys-test" (str (gensym "listen")))]
    (rf.trace.tooling/register-listener! lid (fn [ev] (swap! acc conj ev)))
    (try
      (f)
      (into []
            (comp (filter #(= :rf.warning/unknown-registration-key (:operation %)))
                  (map (comp (juxt :kind :id :unknown-keys) :tags)))
            @acc)
      (finally
        (rf.trace.tooling/unregister-listener! lid)))))

(deftest a-typo-key-warns-and-still-registers
  (let [warns (unknown-key-warnings
                #(do (reg-resource! :test/typo-resource (assoc minimal-resource :stale-after 60000))
                     (reg-mutation! :test/typo-mutation
                                    (assoc minimal-mutation :invalidate (fn [_ _] #{})))))]
    (is (= [true true]
           [(some? (rf.resources.registry/resource-meta :test/typo-resource))
            (some? (rf.resources.mutation-registry/mutation-meta :test/typo-mutation))])
        "a nudge, never a rejection")
    (when rf.interop/debug-enabled?
      (is (= [[:resource :test/typo-resource [:stale-after]]
              [:mutation :test/typo-mutation [:invalidate]]]
             warns)))))

(deftest transport-and-prev-page-param-warn-and-still-register
  ;; There is one transport and no prepend event, so neither is a
  ;; registration key.
  (let [warns (unknown-key-warnings
                #(do (reg-resource! :test/transport-resource
                                    (assoc minimal-resource
                                           :transport :rf.http/managed
                                           :prev-page-param (fn [_first-page _pages] nil)))
                     (reg-mutation! :test/transport-mutation
                                    (assoc minimal-mutation :transport :rf.http/managed))))]
    (is (= [true true]
           [(some? (rf.resources.registry/resource-meta :test/transport-resource))
            (some? (rf.resources.mutation-registry/mutation-meta :test/transport-mutation))]))
    (when rf.interop/debug-enabled?
      (is (= [[:resource :test/transport-resource #{:transport :prev-page-param}]
              [:mutation :test/transport-mutation #{:transport}]]
             (mapv (fn [[kind id ks]] [kind id (set ks)]) warns))))))

(deftest every-documented-key-registers-silently
  (let [warns (unknown-key-warnings
                #(do (reg-resource! :test/every-resource-key every-resource-key)
                     (reg-mutation! :test/every-mutation-key every-mutation-key)))]
    (is (= [true true]
           [(some? (rf.resources.registry/resource-meta :test/every-resource-key))
            (some? (rf.resources.mutation-registry/mutation-meta :test/every-mutation-key))]))
    (when rf.interop/debug-enabled?
      (is (= [] warns)))))

(deftest a-namespaced-key-passes
  (let [warns (unknown-key-warnings
                #(do (reg-resource! :test/ns-resource (assoc minimal-resource :myapp/owner-team "core"))
                     (reg-mutation! :test/ns-mutation (assoc minimal-mutation :myapp/owner-team "core"))))]
    (when rf.interop/debug-enabled?
      (is (= [] warns)))))

(deftest the-retired-spec-key-throws
  (doseq [[kind register!] [[:resource #(reg-resource! :test/retired (assoc minimal-resource :spec [:map]))]
                            [:mutation #(reg-mutation! :test/retired (assoc minimal-mutation :spec [:map]))]]]
    (is (= {:rf.error/id :rf.error/retired-registration-key :retired-key :spec :replacement :schema :kind kind}
           (select-keys (try (register!) nil
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))
                        [:rf.error/id :retired-key :replacement :kind]))
        (str kind))))
