(ns re-frame.routing-reply-test
  "The route family's stale-suppression gate, `re-frame.routing.reply/suppress?`.
  The end-to-end suppression, the stale trace's reply-envelope facts and the
  live reply are driven through the production fx by
  `re-frame.routing-nav-token-test`."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.routing.reply :as rf.routing.reply]))

(deftest suppress?-is-true-exactly-when-the-carried-token-is-not-current
  (doseq [[carried current expected]
          [["nav-1" "nav-2" true]
           ["nav-2" "nav-2" false]
           ;; A nil captured token still forms a gate `{:route/nav-token nil}`,
           ;; so it never matches a live token. The shared `stale?` reads a nil
           ;; GATE as "nothing to supersede", so the wrapping is what suppresses it.
           [nil     "nav-2" true]]]
    (is (= expected (rf.routing.reply/suppress? carried current))
        (pr-str [carried current]))))
