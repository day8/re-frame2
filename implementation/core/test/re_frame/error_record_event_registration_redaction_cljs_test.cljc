(ns re-frame.error-record-event-registration-redaction-cljs-test
  "The always-on error record `error-emit/dispatch-on-error!` hands corpus
  listeners applies the event REGISTRATION's own `:sensitive` marks to its
  `:event` slot (EP-0015: event args are registration-owned), as the frame's
  `:observability :errors` sink route does. A real throwing handler under a
  real sink route, so the sink record is the control proving the declaration
  is live for this failure. Both sides survive `-Dre-frame.debug=false`, so no
  posture tag."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.observability :as rf.observability]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            #?(:clj [re-frame.test-support :as rf.test-support
                     :refer [with-emit-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support]))
  #?(:cljs (:require-macros [re-frame.test-support :refer [with-emit-recorder!]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.error-emit/clear-error-listeners!)
                (rf.observability/clear-observability-sinks!))}))

(def ^:private secret "ERROR-RECORD-SENTINEL-3x7nj45")

(deftest corpus-error-record-event-applies-the-registration-marks
  (testing "a handler registered {:sensitive [[:password]]} throws: the corpus
            record's :event redacts the password exactly as the sink record
            does, and leaves the unclassified sibling raw"
    (let [sunk (atom [])]
      (rf/register-observability-sink! :test.sinks/errors
                                       (fn [record] (swap! sunk conj record)))
      (rf/make-frame {:id            :err-record/app
                      :observability {:errors [{:sink :test.sinks/errors}]}})
      (rf/reg-event :err-record/login {:sensitive [[:password]]}
        (fn [_ _] (throw (ex-info "login failed" {}))))
      (with-emit-recorder! [recs]
        (rf/dispatch-sync [:err-record/login {:user "bob" :password secret}]
                          {:frame :err-record/app})
        (let [redacted [:err-record/login {:user "bob" :password rf.privacy/redacted-sentinel}]
              event-of (fn [records]
                         (:event (first (filter #(= :rf.error/handler-exception (:error %))
                                                records))))]
          (is (= redacted (event-of @sunk))
              "control: the sink record's :event is redacted by the registration")
          (is (= redacted (event-of @recs))
              "the corpus record's :event takes the same registration marks"))))))
