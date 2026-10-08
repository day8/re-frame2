(ns re-frame.http-test-support-test
  "Requiring `re-frame.http.test-support` registers the two canned-stub fxs
  (`:rf.http/managed-canned-success`, `:rf.http/managed-canned-failure`).
  This is the presence half of the require gate; the absence half is
  `re-frame.http-test-support-absent-test`, and their reply behaviour is
  exercised end to end by `re-frame.http-managed-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.registrar :as rf.registrar]
            [re-frame.http.test-support]))

;; Start from an empty registrar, so only the reload below can register them.
(use-fixtures :each
  (fn [t]
    (rf.registrar/clear-all!)
    (require 're-frame.http.test-support :reload)
    (t)))

(deftest canned-stub-fxs-register-on-test-support-load
  (testing "loading re-frame.http.test-support registers both canned-stub fx ids"
    (is (= [true true]
           (mapv #(some? (rf.registrar/lookup :fx %))
                 [:rf.http/managed-canned-success :rf.http/managed-canned-failure])))))
