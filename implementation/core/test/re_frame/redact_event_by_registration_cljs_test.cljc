(ns re-frame.redact-event-by-registration-cljs-test
  "The PAYLOAD-ROOTED addressing contract of
  `re-frame.classification/redact-event-by-registration` (Spec 015
  §Registration-owned transient classification): a `reg-event` `:sensitive`
  path indexes into the event PAYLOAD, the second element, never the outer
  event vector, and trailing positional args ride raw (the documented
  positional fail-open)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.classification :as rf.classification]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private eid :rf.i5su5y/login)

(deftest payload-field-path-redacts
  (testing "[[:password]] redacts the payload's :password; the event id, an
            undeclared payload field and a trailing positional arg ride raw"
    (rf.registrar/register! :event eid {:sensitive [[:password]]})
    (is (= [eid {:password :rf/redacted :user "ann"} "positional-secret"]
           (rf.classification/redact-event-by-registration
             [eid {:password "hunter2" :user "ann"} "positional-secret"])))))

(deftest whole-payload-mark-preserves-event-id
  (testing "the [[]] whole-shape mark redacts the ENTIRE payload while the
            event id is preserved — index 0 is never addressable"
    (rf.registrar/register! :event eid {:sensitive [[]]})
    (is (= [eid :rf/redacted]
           (rf.classification/redact-event-by-registration
             [eid {:password "hunter2" :user "ann"}])))))
