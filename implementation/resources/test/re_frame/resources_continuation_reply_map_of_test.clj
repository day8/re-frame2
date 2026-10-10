(ns re-frame.resources-continuation-reply-map-of-test
  "A continuation reply's `:data` declaration reaches a slot under every key of
  a map keyed by id. `redact-continuation-reply` re-roots `[:data :by-id
  :email]` onto the reply's `:value` and matches it index-free, so the map key
  consumes no declared segment, as it does on the resource's durable entry."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.resources.classification :as rf.resources.classification]))

(deftest a-data-declaration-redacts-under-every-key-of-a-keyed-reply-value
  (is (= {:value {:by-id {"u1" {:name "Ada" :email :rf/redacted}
                          "u2" {:name "Bo" :email :rf/redacted}}}}
         (rf.resources.classification/redact-continuation-reply
           {:value {:by-id {"u1" {:name "Ada" :email "SECRET-ADA@x"}
                            "u2" {:name "Bo" :email "SECRET-BO@x"}}}}
           {:sensitive [[:data :by-id :email]]}))))
