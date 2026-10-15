(ns re-frame.routing-reply-test
  "A nil carried navigation token is stale under a live navigation."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.routing.reply :as rf.routing.reply]))

(deftest suppress?-suppresses-a-nil-carried-token-under-a-live-navigation
  ;; A nil captured token still forms a gate `{:route/nav-token nil}`, so it
  ;; never matches a live token. The shared `stale?` reads a nil GATE as
  ;; "nothing to supersede", so the wrapping is what suppresses it.
  (is (true? (rf.routing.reply/suppress? nil "nav-2"))))
