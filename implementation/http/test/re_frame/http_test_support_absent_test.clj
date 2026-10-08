(ns re-frame.http-test-support-absent-test
  "With `re-frame.http.test-support` ABSENT from the require closure, the two
  canned-stub fxs (`:rf.http/managed-canned-success`,
  `:rf.http/managed-canned-failure`) are not registered — the JVM/SSR
  companion to the CLJS production-bundle elision sentinels in
  `scripts/check-elision.cjs`, where the gate must come from the require
  closure alone because DCE does not apply.

  The assertion is load-order sensitive, so it lives in its own file and this
  namespace must NOT require `re-frame.http.test-support`: the test clears the
  registrar and reloads only `re-frame.http.managed`, whose load-time
  registrations are the control that the reload ran. The presence half is
  `re-frame.http-test-support-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.registrar :as rf.registrar]))

(deftest canned-stub-fxs-absent-without-test-support-require
  (testing "after a clean reload of re-frame.http.managed alone, the production
            fxs are registered and neither canned-stub fx is"
    (rf.registrar/clear-all!)
    (require 're-frame.http.managed :reload)
    (is (= [true true false false]
           (mapv #(some? (rf.registrar/lookup :fx %))
                 [:rf.http/managed :rf.http/managed-abort
                  :rf.http/managed-canned-success :rf.http/managed-canned-failure])))))
