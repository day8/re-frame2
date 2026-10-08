(ns re-frame.frame-classification-cljs-test
  "What `re-frame.frame-classification` owns at `make-frame`: the retired
  `:sensitive` / `:large` frame keys fail loud with
  `:rf.error/bad-frame-classification` (durable app-db classification rides the
  commit-plane effects, HTTP carriers the `:rf.http/managed` registration), and
  the `:observability` sink policy validates shape-only, failing loud before any
  state mutates, and otherwise rides the frame's config verbatim."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- bad-classification-ex
  "Run thunk and return the caught ex-data when it throws
  `:rf.error/bad-frame-classification`, else nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
         (ex-data e))))

(deftest retired-frame-keys-fail-loud
  (doseq [[k v] [[:sensitive {:app-db [[:auth :token]]}]
                 [:large {:app-db [[:documents :csv-upload]]}]]]
    (is (= {:rf.error/id :rf.error/bad-frame-classification :bad-key k}
           (select-keys (bad-classification-ex #(rf/make-frame {:id :app/retired k v}))
                        [:rf.error/id :bad-key]))))
  (is (nil? (rf.frame/frame :app/retired)) "the throw precedes any frame state"))

(deftest fail-loud-on-unknown-classification-key
  (is (= {:rf.error/id :rf.error/bad-frame-classification
          :bad-key     [:observability :bogus-stream]}
         (select-keys (bad-classification-ex
                        #(rf/make-frame {:id :app/bad2b :observability {:bogus-stream []}}))
                      [:rf.error/id :bad-key]))))

(deftest fail-loud-on-bad-observability-entry
  (is (= {:rf.error/id :rf.error/bad-frame-classification
          :bad-key     [:observability :handled-events :sink]}
         (select-keys (bad-classification-ex
                        #(rf/make-frame {:id :app/bad4 :observability {:handled-events [{:service "x"}]}}))
                      [:rf.error/id :bad-key]))))

(deftest fail-loud-on-unknown-observability-profile
  (let [data (bad-classification-ex
               #(rf/make-frame {:id :app/bad-profile :observability
                                {:handled-events [{:sink :my-app.sinks/datadog
                                                   :rf.egress/profile :rf.egress/bogus-profile}]}}))]
    (is (= {:rf.error/id :rf.error/bad-frame-classification
            :bad-key     [:observability :handled-events :rf.egress/profile]
            :bad-value   :rf.egress/bogus-profile}
           (select-keys data [:rf.error/id :bad-key :bad-value])))
    (is (contains? (:valid data) :rf.egress/off-box-observability)
        "the error carries the closed profile enum"))
  (rf/make-frame {:id :app/good-profile :observability {:handled-events [{:sink :my-app.sinks/datadog
                                                                          :rf.egress/profile :rf.egress/off-box-observability}]}})
  (is (= :my-app.sinks/datadog
         (get-in (rf/frame-meta :app/good-profile) [:observability :handled-events 0 :sink]))))

(deftest fail-loud-on-unknown-sink-entry-key
  ;; the entry is closed (:sink plus optional :rf.egress/profile): route-stream!
  ;; reads only those, so any other key would be accept-and-drop
  (is (= {:rf.error/id :rf.error/bad-frame-classification
          :bad-key     [:observability :handled-events :opts]
          :valid       #{:sink :rf.egress/profile}}
         (select-keys (bad-classification-ex
                        #(rf/make-frame {:id :app/bad-opts :observability
                                         {:handled-events [{:sink :my-app.sinks/datadog :opts {}}]}}))
                      [:rf.error/id :bad-key :valid])))
  (rf/make-frame {:id :app/closed-entry
                  :observability {:handled-events [{:sink :my-app.sinks/datadog
                                                    :rf.egress/profile :rf.egress/public-error}]
                                  :errors         [{:sink :my-app.sinks/sentry}]}})
  (is (= :my-app.sinks/datadog
         (get-in (rf/frame-meta :app/closed-entry) [:observability :handled-events 0 :sink]))))
