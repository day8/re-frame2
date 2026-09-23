(ns re-frame.error-record-event-registration-redaction-cljs-test
  "rf2-3x7nj.4.5 — the always-on error record `error-emit/dispatch-on-error!`
  builds must apply the event REGISTRATION's own `:sensitive` marks to its
  `:event` slot (EP-0015: event args are registration-owned). It ran only the
  router's path-overlap / `redact-interceptor` scrub and the app-db-rooted
  `elide-wire-value` walk, so `reg-event :login {:sensitive [[:password]]}`
  throwing handed every corpus listener the password RAW — while the two
  sibling channels for the same failure, the dev trace (`project-event-tags`)
  and the frame's `:observability :errors` sink (`project-event-slot`), both
  redacted it.

  Producer-derived: a REAL throwing handler under a REAL sink route. The sink
  record is the control that proves the declaration is live for this failure,
  so the corpus record's redaction is a fact about the same event.

  Always-on on both sides — the corpus record and the sink route survive
  `-Dre-frame.debug=false` — so no posture tag. Dual-runtime `.cljc`."
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
        (let [corpus (filterv #(= :rf.error/handler-exception (:error %)) @recs)
              sink   (filterv #(= :rf.error/handler-exception (:error %)) @sunk)]
          (is (= 1 (count corpus)) "the failure reached the corpus registry once")
          ;; Control: the declaration is live for THIS failure — the sink
          ;; route's registration pass redacts it.
          (is (= [:err-record/login {:user "bob" :password rf.privacy/redacted-sentinel}]
                 (:event (first sink)))
              "control: the sink record's :event is redacted by the registration")
          (is (= [:err-record/login {:user "bob" :password rf.privacy/redacted-sentinel}]
                 (:event (first corpus)))
              "the corpus record's :event takes the same registration marks"))))))
