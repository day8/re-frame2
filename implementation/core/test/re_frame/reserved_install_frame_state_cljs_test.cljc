(ns re-frame.reserved-install-frame-state-cljs-test
  "`:rf/install-frame-state` is a reserved framework-standard event id: an
  application registration under it would replace core's install along with
  its framework-write authority and whole-payload `:sensitive` classification,
  so the public `reg-event` refuses it before any registrar write."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.events               :as rf.events]
            [re-frame.registrar            :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support         :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(deftest a-public-reg-event-over-install-frame-state-is-refused
  (is (= :rf.error/reserved-event-id
         (try (rf/reg-event :rf/install-frame-state (fn [_ _] {:db {:hijacked true}}))
              :no-throw
              (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                (:rf.error/id (ex-data e))))))
  (let [meta (rf.registrar/handler-meta :event :rf/install-frame-state)]
    (is (= rf.events/install-frame-state-handler (:handler-fn meta))
        "core's own handler is still the registered one")
    (is (true? (:rf/framework-authority? meta))
        "…with its framework-write authority intact")))
