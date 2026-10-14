(ns re-frame.routing-reply-test
  "The nil-carried-token edge of the route family's stale-suppression gate,
  `re-frame.routing.reply/suppress?`. The stale and live verdicts, the
  end-to-end suppression, the stale trace's reply-envelope facts and the live
  reply are driven through the production fx by `re-frame.routing-nav-token-test`."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.routing.reply :as rf.routing.reply]))

(deftest suppress?-suppresses-a-nil-carried-token-under-a-live-navigation
  ;; A nil captured token still forms a gate `{:route/nav-token nil}`, so it
  ;; never matches a live token. The shared `stale?` reads a nil GATE as
  ;; "nothing to supersede", so the wrapping is what suppresses it.
  (is (true? (rf.routing.reply/suppress? nil "nav-2"))))
